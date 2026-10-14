package dev.daniel.jigsaw

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/** The UI language: the system's, or one of the app's translations. [tag] is null for the system language. */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    German("de"),
    ;

    companion object {
        /** The translation serving [tag] (any region, e.g. de-AT), or [System] for none or unknown. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it).language }
            return entries.firstOrNull { it.tag != null && it.tag == language } ?: System
        }
    }
}

/**
 * Reads and applies the app's own language. Android 13+ keeps it as the per-app language (the same value
 * as system Settings → Apps → Jigsaw → Language) and restarts the activity itself; older versions store it
 * here, and [MainActivity] applies it to its context via [wrap].
 */
object AppLocale {
    private const val PREFS = "locale"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        AppLanguage.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
    } else {
        AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))
    }

    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.tag.orEmpty())
        } else {
            prefs(activity).edit { putString(KEY_LANGUAGE, language.tag) }
            activity.recreate()
        }
    }

    /** Before Android 13: [base] with the chosen language applied; unchanged when following the system. */
    fun wrap(base: Context): Context {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else current(base).tag
        return tag?.let {
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(it))
            base.createConfigurationContext(config)
        } ?: base
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
