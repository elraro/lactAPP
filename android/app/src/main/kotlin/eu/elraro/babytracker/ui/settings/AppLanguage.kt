package eu.elraro.babytracker.ui.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import eu.elraro.babytracker.R

/**
 * Languages offered in Settings. Keep in sync with res/xml/locales_config.xml and the
 * localeFilters in app/build.gradle.kts.
 */
enum class AppLanguage(
    /** BCP 47 tag; null follows the system language. */
    val tag: String?,
    @param:StringRes val label: Int,
) {
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_spanish),
    ;

    companion object {
        /** The language for a stored tag list such as "es" or "es-ES"; SYSTEM when there is none. */
        fun fromTags(languageTags: String): AppLanguage {
            val primary = languageTags.substringBefore(',').substringBefore('-').lowercase()
            return entries.firstOrNull { it.tag == primary } ?: SYSTEM
        }

        fun current(): AppLanguage = fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    /** Applies and persists the choice; the platform recreates the activity. */
    fun apply() {
        val locales = tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
