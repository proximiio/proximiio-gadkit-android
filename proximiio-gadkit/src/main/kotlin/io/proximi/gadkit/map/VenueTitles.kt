package io.proximi.gadkit.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioAmenity
import java.util.Locale

/**
 * The names of the amenity kinds from the venue data (operator, 2026-11-22, Metis T-1924: the PM
 * edits every shortcut name in the portal). An amenity kind ([AmenityKind]) is named with the title
 * of its amenity in the venue data, in the app language. The library's own text is shown when the
 * data has no title in that language, when two amenities of the kind have a place (the women's and
 * the men's restroom), or when `GadSettings.usesVenueAmenityTitles` is `false`.
 *
 * The title of a language is the amenity's `title_i18n[language]`, then
 * `metadata.title_i18n[language]`: the amenities are edited by hand through the REST API, and
 * `metadata` is the field that takes any key (operator, 2026-11-22; iOS 1.71.0). The untagged `title`
 * counts only for `GadSettings.venueDataLanguage` ("en"): in Arabic an English `title` does not
 * replace the library's Arabic text. iOS `VenueTitles` (1.70.0).
 */
internal object VenueTitles {
    /**
     * Per kind: the titles of its amenity by language code, lower case. Compose state: a screen that
     * shows a kind's name is drawn again when the titles change.
     */
    private var stored by mutableStateOf<Map<AmenityKind, Map<String, String>>>(emptyMap())

    /** The data title of [kind] in [language], or `null` for the library's text. */
    fun title(kind: AmenityKind, language: String): String? = stored[kind]?.let { title(it, language) }

    /**
     * The title of [language] in [titles] (keys in lower case): the exact code, then the base
     * language (`ar` for `ar-AE`). Pure; covered by tests.
     */
    fun title(titles: Map<String, String>, language: String): String? {
        val code = code(language)
        titles[code]?.let { return it }
        val base = code.substringBefore('-')
        return if (base == code) null else titles[base]
    }

    /**
     * The translated titles of [amenity] by language code, lower case: `title_i18n`, then the object
     * `metadata.title_i18n` for the languages `title_i18n` does not have. Blank titles and values
     * that are no text are left out. Pure; covered by tests.
     */
    fun translations(amenity: ProximiioAmenity): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        fun take(language: String, title: String) {
            val code = code(language)
            val trimmed = title.trim()
            if (trimmed.isNotEmpty() && code !in titles) titles[code] = trimmed
        }
        for ((language, title) in amenity.titleTranslations) take(language, title)
        val metadata = (amenity.metadata as? JsonValue.Object)?.objectValue
        val translated = (metadata?.get("title_i18n") as? JsonValue.Object)?.objectValue
        translated?.forEach { (language, value) -> (value as? JsonValue.String)?.let { take(language, it.value) } }
        return titles
    }

    /**
     * The titles of [amenity] by language code, lower case: [translations], and the untagged `title`
     * under [untaggedLanguage] when they have no title for it. Blank titles are left out. Pure;
     * covered by tests.
     */
    fun titles(amenity: ProximiioAmenity, untaggedLanguage: String): Map<String, String> {
        val titles = translations(amenity).toMutableMap()
        val untagged = code(untaggedLanguage)
        val title = amenity.title?.trim().orEmpty()
        if (untagged.isNotEmpty() && title(titles, untagged) == null && title.isNotEmpty()) titles[untagged] = title
        return titles
    }

    /**
     * The titles each kind takes: those of the one amenity of the kind that is in [used]. A kind
     * with two such amenities has none. Pure; covered by tests.
     */
    fun titles(amenities: List<ProximiioAmenity>, used: Set<String>, untaggedLanguage: String): Map<AmenityKind, Map<String, String>> {
        val byId = amenities.associateBy { it.id }
        val result = mutableMapOf<AmenityKind, Map<String, String>>()
        for ((kind, id) in VenueGlyphs.amenityIds(amenities, used, withIcon = used)) {
            val titles = byId[id]?.let { titles(it, untaggedLanguage) }.orEmpty()
            if (titles.isNotEmpty()) result[kind] = titles
        }
        return result
    }

    /**
     * Takes the kind titles from [amenities] for the amenities that places or level changers use
     * ([used]). [enabled] is `GadSettings.usesVenueAmenityTitles`, [untaggedLanguage] is
     * `GadSettings.venueDataLanguage`.
     */
    fun update(amenities: List<ProximiioAmenity>, used: Set<String>, enabled: Boolean, untaggedLanguage: String) {
        val titles = if (enabled) titles(amenities, used, untaggedLanguage) else emptyMap()
        if (titles != stored) stored = titles
    }

    private fun code(language: String): String = language.lowercase(Locale.ROOT).replace('_', '-')
}
