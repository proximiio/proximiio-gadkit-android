package io.proximi.gadkit.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import io.proximi.gadkit.map.MapLanguage
import java.util.Locale

/**
 * The library's texts in the host app's language while the app runs, as iOS `AppLanguage` (GADKit
 * 1.28.0; Nagarro, 2026-10-30: the language changed wrongly while the app ran). The language is the
 * first locale of the composition's `LocalConfiguration`: the one the host app gives the library's
 * views, or the activity's (the app language, `LocaleManager`). "ar" for Arabic, else "en"
 * ([MapLanguage]). The map labels follow the same language.
 */
internal object AppLanguage {
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
    val language = MapLanguage.forAppLanguage(LocalConfiguration.current.locales[0]?.language)
    val localized = remember(context, language) {
        val resources = AppLanguage.resources(context, language)
        if (resources === context.resources) context else LocalizedContext(context, resources)
    }
    CompositionLocalProvider(
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
