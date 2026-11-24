package io.proximi.gadkit.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.map.MapLanguage
import java.util.Locale

/**
 * The library's texts in the host app's language while the app runs, as iOS `AppLanguage` (GADKit
 * 1.28.0; Nagarro, 2026-10-30: the language changed wrongly while the app ran). The language is the
 * first locale of the composition's `LocalConfiguration`: the one the host app gives the library's
 * views, or the activity's (the app language, `LocaleManager`). "ar" for Arabic, else "en"
 * ([MapLanguage]). The map labels follow the same language.
 *
 * [io.proximi.gadkit.GadSettings.language] goes before both when the host app has set it (iOS 1.72.0;
 * Nagarro, 2026-11-23: the Explore map was English in an Arabic app).
 */
internal object AppLanguage {
    /** The language the library's views took last, for the field log. */
    private var taken: String? = null

    /** `GadSettings.language` as the host app set it. */
    @Volatile
    var fixedValue: String? = null
        private set

    /** "ar" or "en" of [fixedValue], or `null`. A composition that reads it runs again when it changes. */
    var fixed: String? by mutableStateOf(null)
        private set

    /** The language the library's views drew last, "ar" or "en"; `null` before the first view. */
    private var hosted: String? by mutableStateOf(null)

    /**
     * The library's language for what is not read from the resources, such as the font (iOS
     * `AppLanguage.current`): `GadSettings.language`, else the language of the library's views, else
     * the language of the process. A composition that reads it runs again when it changes.
     */
    val current: String
        get() = fixed ?: hosted ?: code(Locale.getDefault().language)

    /** The library's views draw in [language]. */
    fun host(language: String) {
        if (hosted != language) hosted = language
    }

    /** "ar" for an Arabic language identifier, else "en". Pure; covered by tests. */
    fun code(identifier: String?): String = if (identifier?.lowercase(Locale.ROOT)?.startsWith("ar") == true) "ar" else "en"

    /** The language from its sources, first the one the host app has set. Pure; covered by tests. */
    fun resolved(fixed: String?, host: String?): String = fixed ?: code(host)

    /** The language tag the library takes: [fixed], else [hostTag]. */
    fun tag(hostTag: String?): String? = fixed ?: hostTag

    /** `GadSettings.language` changed: the host app's own choice, or `null` to follow the app again. */
    fun fix(language: String?) {
        fixedValue = language
        val code = language?.let(::code)
        fixed = code
        if (code != null) take(code, "GadSettings.language $language")
    }

    /** Writes `app language <ar|en> (<reason>)` to the field log when [language] is new (iOS). */
    fun take(language: String, reason: String) {
        if (language == taken) return
        taken = language
        FieldLog.log("app language $language ($reason)")
    }

    /** [context]'s resources in [language] ("ar" or "en"), or [context]'s own when they are in it already. */
    fun resources(context: Context, language: String): Resources {
        val own = context.resources
        if (MapLanguage.forAppLanguage(own.configuration.locales[0]?.language) == language) return own
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(own.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration).resources
    }
}

/**
 * [content] with the library's texts and layout direction (right to left in Arabic) in the language of
 * the composition's `LocalConfiguration` ([AppLanguage]). The root views of the library use it, so a
 * host app that sets the locale of its composition gets the library's texts in that locale without
 * recreating the activity.
 */
@Composable
internal fun HostLanguage(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val hostConfiguration = LocalConfiguration.current
    val locale = hostConfiguration.locales[0]
    val fixed = AppLanguage.fixed
    val language = AppLanguage.resolved(fixed, locale?.language)
    SideEffect {
        AppLanguage.host(language)
        AppLanguage.take(language, if (fixed != null) "GadSettings.language ${AppLanguage.fixedValue}" else "host locale ${locale?.toLanguageTag() ?: "-"}")
    }
    // With `GadSettings.language` the views below read that language from `LocalConfiguration` too.
    val configuration = remember(hostConfiguration, fixed) {
        if (fixed == null) hostConfiguration else Configuration(hostConfiguration).apply { setLocale(Locale.forLanguageTag(fixed)) }
    }
    val localized = remember(context, language) {
        val resources = AppLanguage.resources(context, language)
        if (resources === context.resources) context else LocalizedContext(context, resources)
    }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localized,
        LocalResources provides localized.resources,
        LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

/** [base] with [localized] resources. The activity stays reachable through the wrapper. */
private class LocalizedContext(base: Context, private val localized: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}
