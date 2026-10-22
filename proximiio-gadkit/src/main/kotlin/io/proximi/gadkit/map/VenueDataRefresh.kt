package io.proximi.gadkit.map

import io.proximi.gadkit.FieldLog
import io.proximi.sdk.Proximiio
import io.proximi.sdk.VenueDataKind
import io.proximi.sdk.core.model.ProximiioFeature
import io.proximi.sdk.features
import io.proximi.sdk.venueDataChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows changes of the venue data while the app runs, as iOS `VenueDataRefresh`. The screens read
 * the data when they open and again when [version] changes.
 *
 * The SDK's audit sync applies the changes of features, geofences, floors and places every
 * `ProximiioConfiguration.syncInterval` (300 s by default), and `Proximiio.venueDataChanges()` names
 * the kinds whose stored data changed (SDK 6.0.0-beta.27). After a sync pass that changed features
 * the SDK has already rebuilt the route network. Nothing is downloaded here.
 *
 * The open map redraws the changed features, floors, places and geofences itself. A running
 * `JourneyNavigator` routes its legs again only on `routeNetworkChanged()`, which the visit screen
 * calls on a change of [VenueDataKind.features]. Main thread only.
 */
internal class VenueDataRefresh(private val sdk: Proximiio, private val scope: CoroutineScope) {
    private val mutableVersion = MutableStateFlow(0)

    /** Changes when the features, geofences, floors or places changed. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    /** The kinds of the last change, set before [version] changes. */
    var changedKinds: Set<VenueDataKind> = emptySet()
        private set

    /** Runs after [version] changed. Set by `ProximiMap`. */
    var onChange: (() -> Unit)? = null

    /** The stored features, sorted by id. */
    private var features: List<ProximiioFeature> = emptyList()
    private var follow: Job? = null

    /**
     * Call after the first `loadRouteNetwork()` or `loadCachedRouteNetwork()`. A second call while the
     * changes are followed does nothing.
     */
    suspend fun start(): Unit = withContext(Dispatchers.Main.immediate) {
        if (follow != null) return@withContext
        features = withContext(Dispatchers.Default) { sorted(sdk.features()) }
        follow = scope.launch { sdk.venueDataChanges().collect { apply(it.kinds) } }
    }

    /** Ends the follow-up. `ProximiMapView` calls it when it closes. */
    fun stop() {
        follow?.cancel()
        follow = null
    }

    private suspend fun apply(kinds: Set<VenueDataKind>) {
        val watched = kinds.intersect(WATCHED_KINDS)
        if (watched.isEmpty()) return
        var description = watched.map { it.rawValue }.sorted().joinToString()
        if (VenueDataKind.features in watched) {
            val features = withContext(Dispatchers.Default) { sorted(sdk.features()) }
            description += " (${changes(this.features, features)})"
            this.features = features
        }
        changedKinds = watched
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
        /** The kinds the screens read. The open map redraws a changed style and amenity catalogue itself. */
        val WATCHED_KINDS: Set<VenueDataKind> =
            setOf(VenueDataKind.features, VenueDataKind.geofences, VenueDataKind.floors, VenueDataKind.places)

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
