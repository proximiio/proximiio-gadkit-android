package io.proximi.gadkit.map

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.proximi.gadkit.FieldLog
import io.proximi.sdk.Proximiio
import io.proximi.sdk.core.model.ProximiioFeature
import io.proximi.sdk.core.sync.SyncEvent
import io.proximi.sdk.features
import io.proximi.sdk.loadCachedRouteNetwork
import io.proximi.sdk.loadRouteNetwork
import io.proximi.sdk.syncEvents
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows changes of the venue data while the app runs, as iOS `VenueDataRefresh`. The screens read
 * the data when they open and again when [version] changes.
 *
 * - Features (POIs, paths, level changers): `loadRouteNetwork()` every
 *   [io.proximi.gadkit.GadSettings.venueDataRefreshInterval] in the foreground and when the app
 *   returns to the foreground. It downloads the features, stores them and rebuilds the route
 *   network. The SDK sync does not bring feature changes: the audit feed of api.proximi.fi
 *   (`/v5/audit/changes`) had no `Feature` record in the 30 days before 2026-10-16, while 500
 *   features were created.
 * - Geofences, floors and places: the SDK sync applies them every 300 s. A `Feature` record of the
 *   sync rebuilds the route network from the store.
 *
 * The open map keeps the POIs it has drawn: the library does not call the session's
 * `reloadVenueData()` of map 6.0.0-beta.22 yet, as the iOS package 1.7.0. Main thread only.
 */
internal class VenueDataRefresh(private val sdk: Proximiio, private val scope: CoroutineScope) {
    private val mutableVersion = MutableStateFlow(0)

    /** Changes when the features, geofences, floors or places changed. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    /** Runs after [version] changed. Set by `ProximiMap`. */
    var onChange: (() -> Unit)? = null

    /** The stored features, sorted by id. */
    private var features: List<ProximiioFeature> = emptyList()
    private var isDownloading = false
    private val jobs = mutableListOf<Job>()
    private var foregroundObserver: LifecycleEventObserver? = null

    /**
     * Call after the first `loadRouteNetwork()`. With [interval] 0 only the SDK sync is followed. Runs
     * on the main thread whatever the caller's thread: the lifecycle takes observers there only.
     */
    suspend fun start(interval: Duration): Unit = withContext(Dispatchers.Main.immediate) {
        features = withContext(Dispatchers.Default) { sorted(sdk.features()) }
        jobs += scope.launch { followSync() }
        if (!interval.isPositive()) return@withContext
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        jobs += scope.launch {
            while (isActive) {
                delay(interval)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) download("interval")
            }
        }
        // Android reports ON_START at once to a new observer of a started app: only a return from
        // the background downloads, as iOS `willEnterForeground`.
        var wasInBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasInBackground = true
                Lifecycle.Event.ON_START -> if (wasInBackground) scope.launch { download("foreground") }
                else -> Unit
            }
        }
        foregroundObserver = observer
        lifecycle.addObserver(observer)
    }

    /** Ends the downloads and the sync follow-up. `ProximiMapView` calls it when it closes. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        foregroundObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        foregroundObserver = null
    }

    private suspend fun download(reason: String) {
        if (isDownloading) return
        isDownloading = true
        try {
            try {
                sdk.loadRouteNetwork()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                FieldLog.log("venue data: download failed ($reason): ${error.message}")
                return
            }
            val features = withContext(Dispatchers.Default) { sorted(sdk.features()) }
            val changes = changes(this.features, features)
            if (changes.isEmpty) return
            this.features = features
            changed("features ($reason): $changes")
        } finally {
            isDownloading = false
        }
    }

    private suspend fun followSync() {
        val entities = mutableSetOf<String>()
        sdk.syncEvents().collect { event ->
            when (event) {
                is SyncEvent.Updated -> if (event.entity in WATCHED_ENTITIES) entities += event.entity
                is SyncEvent.Deleted -> if (event.entity in WATCHED_ENTITIES) entities += event.entity
                is SyncEvent.SyncCompleted -> {
                    if (entities.isEmpty()) return@collect
                    if ("Feature" in entities) {
                        features = withContext(Dispatchers.Default) {
                            sdk.loadCachedRouteNetwork()
                            sorted(sdk.features())
                        }
                    }
                    changed("sync: ${entities.sorted().joinToString()}")
                    entities.clear()
                }
                else -> Unit
            }
        }
    }

    private fun changed(description: String) {
        mutableVersion.value += 1
        FieldLog.log("venue data changed · $description · version ${version.value}")
        onChange?.invoke()
    }

    /** The difference between two feature lists, by id. */
    data class Changes(val added: Int = 0, val removed: Int = 0, val changed: Int = 0) {
        val isEmpty: Boolean get() = added == 0 && removed == 0 && changed == 0

        override fun toString(): String = "$added added, $removed removed, $changed changed"
    }

    companion object {
        /** Entities of the SDK sync that the screens read. */
        val WATCHED_ENTITIES: Set<String> = setOf("Feature", "Geofence", "Floor", "Place")

        fun sorted(features: List<ProximiioFeature>): List<ProximiioFeature> = features.sortedBy { it.id }

        /** The difference between [old] and [new], by id. Pure; covered by tests. */
        fun changes(old: List<ProximiioFeature>, new: List<ProximiioFeature>): Changes {
            val before = LinkedHashMap<String, ProximiioFeature>().apply { old.forEach { putIfAbsent(it.id, it) } }
            val after = LinkedHashMap<String, ProximiioFeature>().apply { new.forEach { putIfAbsent(it.id, it) } }
            return Changes(
                added = after.keys.count { it !in before },
                removed = before.keys.count { it !in after },
                changed = after.count { (id, feature) -> before[id]?.let { it != feature } ?: false },
            )
        }
    }
}
