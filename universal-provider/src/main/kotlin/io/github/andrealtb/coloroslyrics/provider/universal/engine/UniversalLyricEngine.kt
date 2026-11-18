package io.github.andrealtb.coloroslyrics.provider.universal.engine

import android.content.Context
import android.content.Intent
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppNetwork
import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricPayloadPolicy
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSettingsConstants
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery
import io.github.andrealtb.coloroslyrics.provider.universal.api.UnisonTranslationSource
import io.github.andrealtb.coloroslyrics.provider.universal.cache.UniversalLyricCache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class UniversalLyricEngine(
    private val context: Context,
    private val aggregator: LyricsSourceAggregator = LyricsSourceAggregator()
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var lastQueryKey: String? = null

    fun onTrackChanged(
        ownerPackage: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        generation: Long
    ) {
        if (title.isBlank()) return
        val queryKey = "${ownerPackage}|${title}|${artist}|$generation"
        if (queryKey == lastQueryKey) return
        lastQueryKey = queryKey

        val cachedJson = UniversalLyricCache.get(context, title, artist)
        if (!cachedJson.isNullOrBlank() && !io.github.andrealtb.coloroslyrics.provider.universal.NoLyricPolicy.isRetryableMiss(cachedJson)) {
            val updatedJson = updateGeneration(cachedJson, generation)
            syncToSystem(ownerPackage, generation, updatedJson)
            return
        }

        executor.execute {
            UniversalAppNetwork.initialize(context)
            val query = TrackQuery(title, artist, album, durationMs)
            val priority = LyricsSourceConfigStore.read(context).mapNotNull { id ->
                runCatching { LyricsSourceAggregator.SourceId.valueOf(id) }.getOrNull()
            }.distinct()
            val preferWordTiming = context
                .getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(UniversalSettingsConstants.KEY_WORD_TIMING, false)
            val report = aggregator.fetchFirst(
                query.copy(generation = generation),
                priority.ifEmpty { LyricsSourceAggregator.SourceId.values().toList() },
                preferWordTiming,
                preferTtml = io.github.andrealtb.coloroslyrics.provider.universal.UniversalFeaturePrefs.ttmlFirst(context)
            )
            val result = report.result
            if (result != null && result.lyric.isNotBlank() &&
                !io.github.andrealtb.coloroslyrics.provider.universal.api.SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)
            ) {
                val translationOn = context.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(UniversalSettingsConstants.KEY_TRANSLATION, true)
                val targetLang = UniversalSettingsConstants.resolveTargetLanguage(context)

                var finalTranslation = result.translationLyric
                if (translationOn && isTranslationMissingOrWrongLang(finalTranslation, targetLang)) {
                    if (needsTranslation(result.lyric, targetLang)) {
                        val httpClient = OkHttpClient()
                        finalTranslation = UnisonTranslationSource(httpClient).translate(result.lyric, targetLang)
                            ?.takeIf { !isTranslationMissingOrWrongLang(it, targetLang) }
                            ?: translateLrcViaGoogle(httpClient, result.lyric, targetLang)
                            ?: finalTranslation
                    }
                }

                val json = JSONObject().apply {
                    put("songName", result.title)
                    put("artist", result.artist)
                    put("album", album.orEmpty())
                    put("songId", "universal-$generation")
                    put("lyricType", 0)
                    put("id", "")
                    put("noLyric", false)
                    put("lyric", result.lyric)
                    put("rawLyric", result.rawLyric ?: result.lyric)
                    UniversalLyricPayloadPolicy.putTtmlAndFormat(this, result)
                    if (translationOn && !finalTranslation.isNullOrBlank()) {
                        put("transLyric", finalTranslation)
                        put("translationLyric", finalTranslation)
                        put("translationLanguageTag", Locale.forLanguageTag(targetLang).toLanguageTag())
                    }
                    put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
                    put("source", result.source)
                    put("sessionGeneration", generation)
                    put("remark", "[CLLUniversal]")
                }.toString()

                UniversalLyricCache.put(context, title, artist, json)
                syncToSystem(ownerPackage, generation, json)
            }
        }
    }

    private fun translateLrcViaGoogle(client: OkHttpClient, lrc: String, targetLang: String): String? = runCatching {
        val timeRegex = Regex("""^(\[\d{1,2}:\d{2}(?:\.\d{1,3})?\])+""")
        val lines = lrc.lines()
        val prefixes = mutableListOf<String>()
        val textsToTranslate = mutableListOf<String>()

        for (line in lines) {
            val match = timeRegex.find(line.trim())
            val tag = match?.value.orEmpty()
            val body = line.trim().removePrefix(tag).trim()
            if (body.isNotBlank() && !body.startsWith("[")) {
                prefixes.add(tag)
                textsToTranslate.add(body)
            }
        }
        if (textsToTranslate.isEmpty()) return null

        val joined = textsToTranslate.joinToString("\n")
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLang)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", joined)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONArray(resp.body?.string().orEmpty())
            val sentences = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                sb.append(sentences.optJSONArray(i)?.optString(0).orEmpty())
            }
            val translatedLines = sb.toString().lines()
            if (translatedLines.isEmpty()) return null

            buildString {
                for (i in prefixes.indices) {
                    val tr = translatedLines.getOrNull(i)?.trim().orEmpty()
                    if (tr.isNotBlank()) {
                        append(prefixes[i]).append(tr).append('\n')
                    }
                }
            }.trim().takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun isTranslationMissingOrWrongLang(trans: String?, targetLang: String): Boolean {
        if (trans.isNullOrBlank()) return true
        if (targetLang == "ru" || targetLang == "uk") {
            return !trans.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' || it == 'І' || it == 'і' || it == 'Ї' || it == 'ї' || it == 'Є' || it == 'є' }
        }
        return false
    }

    private fun needsTranslation(text: String, targetLang: String): Boolean {
        if (targetLang == "ru" || targetLang == "uk") {
            val cyr = text.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val lat = text.count { it in 'A'..'Z' || it in 'a'..'z' }
            return lat > cyr * 2
        }
        return true
    }

    private fun updateGeneration(jsonStr: String, generation: Long): String {
        return runCatching {
            val json = JSONObject(jsonStr)
            json.put("sessionGeneration", generation)
            json.put("songId", "universal-$generation")
            json.toString()
        }.getOrDefault(jsonStr)
    }

    private fun syncToSystem(ownerPackage: String, generation: Long, lyricInfo: String) {
        val intent = Intent(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC).apply {
            putExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE, ownerPackage)
            putExtra(UniversalSnapshotStore.EXTRA_GENERATION, generation)
            putExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO, lyricInfo)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(0x01000000)
        }
        context.sendBroadcast(intent)
    }
}
