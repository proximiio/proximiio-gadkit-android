package io.proximi.gadkit.map

import io.proximi.gadkit.FieldLog
import io.proximi.sdk.Proximiio
import io.proximi.sdk.VenueDataKind
import io.proximi.sdk.amenities
import io.proximi.sdk.core.model.ProximiioAmenity
import io.proximi.sdk.core.model.ProximiioFeature
import io.proximi.sdk.core.model.ProximiioGeofence
import io.proximi.sdk.features
import io.proximi.sdk.geofences
import io.proximi.sdk.venueDataChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the venue's features, geofences and amenities in memory and follows their changes while the
 * app runs, as iOS `VenueDataRefresh` (1.23.0). The screens read the data when they open and again
 * when [version] changes.
 *
 * The SDK decodes every stored row on each `features()` call and serves one call at a time. With the
 * GAD venue's 995 features a call took about 0.5 s on the iOS simulator (2026-10-29), and the map's
 * own read of the features waited behind the screens' reads (Nagarro: the map starts in 2 to 2.5 s).
 * Each kind is read once here, and again only after `venueDataChanges()` named it.
 *
 * The SDK's audit sync applies the changes of features, geofences, floors and places every
 * `ProximiioConfiguration.syncInterval` (300 s by default), and `Proximiio.venueDataChanges()` names
 * the kinds whose stored data changed (SDK 6.0.0-beta.27). After a sync pass that changed features
 * the SDK has already rebuilt the route network. Nothing is downloaded here, except the amenity
 * catalogue when the SDK has none stored (`Proximiio.amenities()`).
 *
 * The open map redraws the changed features, floors, places and geofences itself. A running
 * `JourneyNavigator` routes its legs again only on `routeNetworkChanged()`, which the visit screen
 * calls on a change of [VenueDataKind.features]. Main thread only.
 */
internal class VenueDataRefresh(internal val sdk: Proximiio, private val scope: CoroutineScope) {
    private val mutableVersion = MutableStateFlow(0)

    /** Changes when the features, geofences, floors, places or amenities changed. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    /** The kinds of the last change, set before [version] changes. */
    var changedKinds: Set<VenueDataKind> = emptySet()
        private set

    /** Runs after [version] changed. Set by `ProximiMap`. */
    var onChange: (() -> Unit)? = null

    /**
     * The reads every caller of [features], [geofences] and [amenities] waits for. `null` until the
     * first call, and after a change of the kind.
     */
    private var featuresRead: Deferred<List<ProximiioFeature>>? = null
    private var venueFeaturesRead: Deferred<List<VenueFeature>>? = null
    private var geofencesRead: Deferred<List<ProximiioGeofence>>? = null
    private var amenitiesRead: Deferred<List<ProximiioAmenity>>? = null
    private var follow: Job? = null

    /** Whether the changes are followed: after [start], until [stop] or until the SDK stopped. */
    val isFollowing: Boolean get() = follow != null

    /**
     * Call after the first `loadRouteNetwork()` or `loadCachedRouteNetwork()`. Starts the reads of the
     * features, geofences and amenities and returns without waiting for them. A second call while the
     * changes are followed does nothing.
     */
    suspend fun start(): Unit = withContext(Dispatchers.Main.immediate) {
        if (follow != null) return@withContext
        // Subscribed before the reads, so a change during a read reads the kind again.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sdk.venueDataChanges().collect { apply(it.kinds) }
            // The stream ends when the SDK stops. The SDK may have deleted the venue data.
            follow = null
            dropReads()
        }
        // A stream that ended at once: the SDK has stopped.
        follow = job.takeIf { it.isActive }
        featuresTask()
        geofencesTask()
        amenitiesTask()
    }

    /** Ends the follow-up and drops the data read, since the changes made from now on are not followed. */
    fun stop() {
        follow?.cancel()
        follow = null
        dropReads()
    }

    private fun dropReads() {
        featuresRead = null
        venueFeaturesRead = null
        geofencesRead = null
        amenitiesRead = null
    }

    /** The stored features, in the SDK's order. */
    suspend fun features(): List<ProximiioFeature> = withContext(Dispatchers.Main.immediate) { featuresTask() }.await()

    /** The stored features as core reads them ([toVenueFeature]). */
    suspend fun venueFeatures(): List<VenueFeature> = withContext(Dispatchers.Main.immediate) {
        venueFeaturesRead ?: featuresTask().let { read ->
            scope.async(Dispatchers.Default) { read.await().map { it.toVenueFeature() } }.also { venueFeaturesRead = it }
        }
    }.await()

    /** The stored geofences. */
    suspend fun geofences(): List<ProximiioGeofence> = withContext(Dispatchers.Main.immediate) { geofencesTask() }.await()

    /**
     * The amenity catalogue, or none when the SDK has none stored and cannot download it. A failed read
     * is not kept, so the next call asks the SDK again.
     */
    suspend fun amenities(): List<ProximiioAmenity> = withContext(Dispatchers.Main.immediate) {
        val read = amenitiesTask()
        try {
            read.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (amenitiesRead === read) amenitiesRead = null
            emptyList()
        }
    }

    private fun featuresTask(): Deferred<List<ProximiioFeature>> = featuresRead ?: read("features") { sdk.features() }.also { featuresRead = it }

    private fun geofencesTask(): Deferred<List<ProximiioGeofence>> = geofencesRead ?: read("geofences") { sdk.geofences() }.also { geofencesRead = it }

    private fun amenitiesTask(): Deferred<List<ProximiioAmenity>> =
        amenitiesRead ?: read("amenities") { sdk.amenities().distinctBy { it.id } }.also { amenitiesRead = it }

    /** The time in the field log includes the wait for the SDK's earlier calls: the SDK serves one call at a time. */
    private fun <T> read(kind: String, block: suspend () -> List<T>): Deferred<List<T>> = scope.async(Dispatchers.Default) {
        val started = System.nanoTime()
        val values = block()
        FieldLog.log("venue data: ${values.size} $kind read in ${MapDiagnostics.Timing.text(MapDiagnostics.seconds(started))}")
        values
    }

    private suspend fun apply(kinds: Set<VenueDataKind>) {
        val watched = kinds.intersect(WATCHED_KINDS)
        if (watched.isEmpty()) return
        var description = watched.map { it.rawValue }.sorted().joinToString()
        if (VenueDataKind.geofences in watched) geofencesRead = null
        if (VenueDataKind.amenities in watched) amenitiesRead = null
        if (VenueDataKind.features in watched) {
            val old = features()
            featuresRead = null
            venueFeaturesRead = null
            description += " (${changes(old, features())})"
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
        /**
         * The kinds the screens read. The open map redraws a changed style itself; the screens read
         * the amenity catalogue for the icons and lists, so a change of it reads it again.
         */
        val WATCHED_KINDS: Set<VenueDataKind> =
            setOf(VenueDataKind.features, VenueDataKind.geofences, VenueDataKind.floors, VenueDataKind.places, VenueDataKind.amenities)

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
