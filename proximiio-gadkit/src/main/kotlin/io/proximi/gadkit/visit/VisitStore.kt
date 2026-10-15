package io.proximi.gadkit.visit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.VisitMeta
import io.proximi.map.core.JourneyCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.visitDataStore: DataStore<Preferences> by preferencesDataStore(name = "visit")

/**
 * Persists the visit between launches, as iOS `VisitStore` keeps it in `UserDefaults`: the
 * journey's stops and their states (the leg is recomputed), and the title shown on the Hub.
 *
 * The values are the iOS JSON under the iOS keys: the journey as the map library writes it
 * ([JourneyCodec], the same wire format as iOS `Journey: Codable`), the meta as
 * `{"title": …, "summary": …}`.
 *
 * Reads are synchronous from memory; the stored values are read once, by the first [of]. Call it off
 * the main thread the first time. Writes update memory at once and reach the disk in order.
 * `VisitScreen` saves the journey on every change.
 */
public class VisitStore private constructor(context: Context, private val scope: CoroutineScope) {
    private val dataStore = context.applicationContext.visitDataStore

    private val mutableJourney: MutableStateFlow<Journey?>
    private val mutableMeta: MutableStateFlow<VisitMeta?>

    init {
        // One small read at launch, before the Hub reads the visit; iOS reads UserDefaults synchronously too.
        val stored = runBlocking { dataStore.data.first() }
        mutableJourney = MutableStateFlow(stored[JOURNEY_KEY]?.let(VisitStoreCodec::decodeJourney))
        mutableMeta = MutableStateFlow(stored[META_KEY]?.let(VisitStoreCodec::decodeMeta))
    }

    /** The stored journey, `null` when there is none or it has no stops. */
    public val journey: StateFlow<Journey?> = mutableJourney.asStateFlow()

    /** The title and summary of the stored journey, for the host app's screens. */
    public val meta: StateFlow<VisitMeta?> = mutableMeta.asStateFlow()

    public fun load(): Journey? = journey.value

    public fun loadMeta(): VisitMeta? = meta.value

    /** `null` or an empty journey removes the stored visit. */
    public fun save(journey: Journey?) {
        val kept = journey?.takeIf { it.stops.isNotEmpty() }
        if (kept == mutableJourney.value) return
        mutableJourney.value = kept
        val text = kept?.let(VisitStoreCodec::encodeJourney)
        write { if (text == null) it.remove(JOURNEY_KEY) else it[JOURNEY_KEY] = text }
    }

    public fun saveMeta(meta: VisitMeta?) {
        if (meta == mutableMeta.value) return
        mutableMeta.value = meta
        val text = meta?.let(VisitStoreCodec::encodeMeta)
        write { if (text == null) it.remove(META_KEY) else it[META_KEY] = text }
    }

    private fun write(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { dataStore.edit(change) }
    }

    public companion object {
        private val JOURNEY_KEY = stringPreferencesKey("GuggenheimAbuDhabi.visit")
        private val META_KEY = stringPreferencesKey("GuggenheimAbuDhabi.visitMeta")

        @Volatile
        private var instance: VisitStore? = null

        /** The process's store. The first call reads the disk. */
        public fun of(context: Context): VisitStore = instance ?: synchronized(this) {
            instance ?: VisitStore(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { instance = it }
        }
    }
}

/** The stored JSON of [VisitStore]. Pure, so the JVM tests check it. */
internal object VisitStoreCodec {
    fun encodeJourney(journey: Journey): String = JourneyCodec.encode(journey.toMap())

    /** `null` for unreadable JSON or a journey without stops. */
    fun decodeJourney(text: String): Journey? =
        runCatching { JourneyCodec.decode(text).toCore() }.getOrNull()?.takeIf { it.stops.isNotEmpty() }

    fun encodeMeta(meta: VisitMeta): String {
        val fields = buildMap {
            put("title", JsonPrimitive(meta.title))
            meta.summary?.let { put("summary", JsonPrimitive(it)) }
        }
        return JsonObject(fields).toString()
    }

    fun decodeMeta(text: String): VisitMeta? = runCatching {
        val fields = JourneyCodec.json.parseToJsonElement(text).jsonObject
        VisitMeta(fields.getValue("title").jsonPrimitive.content, fields["summary"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()
}
