package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context
import java.util.Locale

object UniversalSettingsConstants {
    const val PREFS_NAME = "LyricSourceSettingsActivity"
    const val KEY_WORD_TIMING = "word_timing"
    const val KEY_TRANSLATION = "translation_enabled"
    const val KEY_SWAP_TRANSLATION = "swap_translation_enabled"
    const val KEY_TRANSLATION_LANG = "translation_target_lang"
    const val KEY_MUSIXMATCH_TOKEN = "musixmatch_user_token"
    const val KEY_RAW_LYRIC = "raw_lyric_enabled"
    const val KEY_DEBUG = "debug_enabled"
    const val KEY_CLOCK_LINE_FALLBACK = "clock_line_fallback_enabled"
    const val KEY_LANGUAGE = "ui_language"

    fun resolveTargetLanguage(context: Context?): String {
        val saved = runCatching {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_TRANSLATION_LANG, "ru")
        }.getOrNull().orEmpty().trim()
        if (saved.isNotBlank() && saved != "system") return saved
        return Locale.getDefault().language.ifBlank { "ru" }
    }

    fun getMusixmatchToken(context: Context?): String {
        return runCatching {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_MUSIXMATCH_TOKEN, "").orEmpty().trim()
        }.getOrDefault("")
    }

    fun isSwapTranslationEnabled(context: Context?): Boolean {
        return runCatching {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getBoolean(KEY_SWAP_TRANSLATION, false) ?: false
        }.getOrDefault(false)
    }
}
