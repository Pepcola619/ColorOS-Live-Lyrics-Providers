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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                var statusSuffix = ""

                if (translationOn && needsTranslation(result.lyric, targetLang)) {
                    if (isTranslationMissingOrWrongLang(finalTranslation, targetLang)) {
                        val httpClient = OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build()

                        val googleRes = translateLrcViaGooglePost(httpClient, result.lyric, targetLang)
                        if (googleRes.first != null) {
                            finalTranslation = googleRes.first
                            statusSuffix = "+$targetLang"
                        } else {
                            val unisonTr = runCatching {
                                UnisonTranslationSource(httpClient).translate(result.lyric, targetLang)
                            }.getOrNull()
                            if (!isTranslationMissingOrWrongLang(unisonTr, targetLang)) {
                                finalTranslation = unisonTr
                                statusSuffix = "+$targetLang(unison)"
                            } else {
                                statusSuffix = "[trErr:${googleRes.second}]"
                            }
                        }
                    } else {
                        statusSuffix = "+$targetLang(mxm)"
                    }
                }

                val mergedLyric = if (translationOn && !finalTranslation.isNullOrBlank()) {
                    mergeOriginalAndTranslation(result.lyric, finalTranslation)
                } else {
                    result.lyric
                }

                val json = JSONObject().apply {
                    put("songName", result.title)
                    put("artist", result.artist)
                    put("album", album.orEmpty())
                    put("songId", "universal-$generation")
                    put("lyricType", 0)
                    put("id", "")
                    put("noLyric", false)
                    put("lyric", mergedLyric)
                    put("rawLyric", mergedLyric)
                    if (!translationOn || finalTranslation.isNullOrBlank()) {
                        UniversalLyricPayloadPolicy.putTtmlAndFormat(this, result)
                    } else {
                        put("transLyric", finalTranslation)
                        put("translationLyric", finalTranslation)
                        put("translationLanguageTag", Locale.forLanguageTag(targetLang).toLanguageTag())
                    }
                    put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
                    put("source", result.source + statusSuffix)
                    put("sessionGeneration", generation)
                    put("remark", "[CLLUniversal]")
                }.toString()

                UniversalLyricCache.put(context, title, artist, json)
                syncToSystem(ownerPackage, generation, json)
            }
        }
    }

    private fun mergeOriginalAndTranslation(originalLrc: String, translationLrc: String): String {
        val timeRegex = Regex("""^(\[\d{1,2}:\d{2}(?:\.\d{1,3})?\])+""")
        val transMap = LinkedHashMap<String, String>()
        val transList = mutableListOf<String>()

        for (line in translationLrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val match = timeRegex.find(trimmed)
            val tag = match?.value.orEmpty()
            val body = trimmed.removePrefix(tag).trim()
            if (body.isNotBlank() && !body.startsWith("[")) {
                if (tag.isNotBlank()) {
                    transMap[tag] = body
                }
                transList.add(body)
            }
        }

        if (transMap.isEmpty() && transList.isEmpty()) return originalLrc

        var lineIdx = 0
        return originalLrc.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            val match = timeRegex.find(trimmed)
            if (match == null) {
                line
            } else {
                val tag = match.value
                val origBody = trimmed.removePrefix(tag).trim()
                if (origBody.isBlank() || origBody.startsWith("[")) {
                    line
                } else {
                    val tr = transMap[tag] ?: transList.getOrNull(lineIdx)
                    lineIdx++
                    if (!tr.isNullOrBlank() && !tr.equals(origBody, ignoreCase = true)) {
                        "$tag $origBody\n$tr"
                    } else {
                        line
                    }
                }
            }
        }
    }

    private fun translateLrcViaGooglePost(
        client: OkHttpClient,
        lrc: String,
        targetLang: String
    ): Pair<String?, String> {
        return try {
            val timeRegex = Regex("""^(\[\d{1,2}:\d{2}(?:\.\d{1,3})?\])+""")
            val prefixes = mutableListOf<String>()
            val textsToTranslate = mutableListOf<String>()

            for (line in lrc.lines()) {
                val trimmed = line.trim()
                val match = timeRegex.find(trimmed)
                val tag = match?.value.orEmpty()
                val body = trimmed.removePrefix(tag).trim()
                if (tag.isNotBlank() && body.isNotBlank() && !body.startsWith("[")) {
                    prefixes.add(tag)
                    textsToTranslate.add(body)
                }
            }
            if (textsToTranslate.isEmpty()) return Pair(null, "emptyLines")

            val formBody = FormBody.Builder()
                .add("client", "gtx")
                .add("sl", "auto")
                .add("tl", targetLang)
                .add("dt", "t")
                .add("q", textsToTranslate.joinToString("\n"))
                .build()

            val req = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair(null, "http${resp.code}")
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return Pair(null, "emptyBody")

                val root = JSONArray(raw)
                val sentences = root.optJSONArray(0) ?: return Pair(null, "noArray")
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.optJSONArray(i)?.optString(0).orEmpty())
                }
                val translatedLines = sb.toString().lines()
                if (translatedLines.isEmpty()) return Pair(null, "noLines")

                val resultLrc = buildString {
                    for (i in prefixes.indices) {
                        val tr = translatedLines.getOrNull(i)?.trim().orEmpty()
                        if (tr.isNotBlank()) {
                            append(prefixes[i]).append(" ").append(tr).append('\n')
                        }
                    }
                }.trim()

                if (resultLrc.isBlank()) Pair(null, "blankOut") else Pair(resultLrc, "ok")
            }
        } catch (e: Exception) {
            Pair(null, e.javaClass.simpleName)
        }
    }

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
