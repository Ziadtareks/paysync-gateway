package com.paysync.gateway.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language switcher (instant, no app restart — activities recreate).
 * MainActivity extends AppCompatActivity so locales apply below Android 13 too.
 */
object LocaleHelper {

    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_AR = "ar"

    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return LANG_SYSTEM
        return when (locales.get(0)?.language) {
            LANG_AR -> LANG_AR
            LANG_EN -> LANG_EN
            else -> LANG_SYSTEM
        }
    }

    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            when (tag) {
                LANG_AR -> LocaleListCompat.forLanguageTags("ar")
                LANG_EN -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
        )
    }
}
