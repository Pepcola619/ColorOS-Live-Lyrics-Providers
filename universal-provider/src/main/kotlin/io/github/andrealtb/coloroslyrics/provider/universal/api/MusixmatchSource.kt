package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

class MusixmatchSource(private val client: OkHttpClient) : LyricsSource {
    override val name: String = "musixmatch"
    private val translator = UnisonTranslationSource(client)

    override fun fetchLyrics(query: TrackQuery): LyricResult? = runCatching {
        // 1. Поиск трека через LRCLIB / Musixmatch-совместимый поиск
        val searchUrl = "https://lrclib.net".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title.trim())
            .addQueryParameter("artist_name", query.artist.trim())
            .build()

        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "UniversalLyricsProvider/1.1.5")
            .build()

        val response = client.newCall(searchReq).execute()
        if (!response.isSuccessful) return null
        val bodyStr = response.body?.string().orEmpty()
        if (bodyStr.isBlank()) return null

        val array = org.json.JSONArray(bodyStr)
        var bestSynced: String? = null
        var bestTitle = query.title
        var bestArtist = query.artist
        var bestId: String? = null
        var bestScore = 0.0

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val synced = item.optString("syncedLyrics").trim()
            if (synced.isBlank()) continue

            val tName = item.optString("trackName", query.title)
            val aName = item.optString("artistName", query.artist)
            val durSec = item.optDouble("duration", 0.0)
            val durMs = if (durSec > 0) (durSec * 1000).toLong() else null

            val score = SongMatchScorer.calculate(
                query.title, query.artist, query.durationMs,
                tName, aName, durMs, rankIndex = i
            )
            if (score > bestScore) {
                bestScore = score
                bestSynced = synced
                bestTitle = tName
                bestArtist = aName
                bestId = item.optLong("id").toString()
            }
        }

        val lrc = bestSynced?.takeIf { bestScore >= 0.55 } ?: return null

        // 2. Автоперевод на системный язык (если текст не на русском, а система на русском)
        val targetLang = Locale.getDefault().language.ifBlank { "ru" }
        val translated = if (needsTranslation(lrc, targetLang)) {
            translator.translate(lrc, targetLang)
        } else {
            null
        }

        LyricResult(
            title = bestTitle,
            artist = bestArtist,
            lyric = lrc,
            rawLyric = lrc,
            translationLyric = translated,
            source = "universal/musixmatch",
            sourceTrackId = bestId,
            format = LyricFormats.LRC
        )
    }.getOrNull()

    private fun needsTranslation(lrc: String, targetLang: String): Boolean {
        if (targetLang == "ru") {
            val cyrillicCount = lrc.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val latinCount = lrc.count { it in 'A'..'Z' || it in 'a'..'z' }
            return latinCount > cyrillicCount * 2
        }
        return true
    }
}
