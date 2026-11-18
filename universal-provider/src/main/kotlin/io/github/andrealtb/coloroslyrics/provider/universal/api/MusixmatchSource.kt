package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MusixmatchSource(baseClient: OkHttpClient) : LyricsSource {
    override val name: String = "musixmatch"

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    private val mxmClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) {
                    val existing = cookieStore[url.host].orEmpty().associateBy { it.name }.toMutableMap()
                    cookies.forEach { existing[it.name] = it }
                    cookieStore[url.host] = existing.values.toList()
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host].orEmpty()
            }
        })
        .build()

    @Volatile
    private var cachedToken: String? = null
    private var tokenFetchedAt: Long = 0L

    private companion object {
        const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1/"
        const val APP_ID = "web-desktop-app-v1.0"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Musixmatch/0.19.4 Chrome/58.0.3029.110 Electron/1.7.6 Safari/537.36"
        const val TOKEN_TTL_MS = 15 * 60 * 1000L
    }

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val mxmResult = runCatching { fetchViaMacro(query) }.getOrNull()
        if (mxmResult != null) return mxmResult
        return runCatching { fetchFallbackLrclib(query) }.getOrNull()
    }

    private fun fetchViaMacro(query: TrackQuery): LyricResult? {
        val token = getToken() ?: return null
        val targetLang = Locale.getDefault().language.ifBlank { "ru" }

        val url = (BASE_URL + "macro.subtitles.get").toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("namespace", "lyrics_richsynched")
            .addQueryParameter("subtitle_format", "lrc")
            .addQueryParameter("app_id", APP_ID)
            .addQueryParameter("usertoken", token)
            .addQueryParameter("q_track", query.title.trim())
            .addQueryParameter("q_artist", query.artist.trim())
            .addQueryParameter("selected_language", targetLang)
            .apply {
                if (!query.album.isNullOrBlank()) {
                    addQueryParameter("q_album", query.album.trim())
                }
                if (query.durationMs != null && query.durationMs > 0L) {
                    addQueryParameter("q_duration", (query.durationMs / 1000L).toString())
                }
            }
            .build()

        val root = getJson(url.toString()) ?: return null
        val message = root.optJSONObject("message") ?: return null
        val statusCode = message.optJSONObject("header")?.optInt("status_code") ?: 0
        if (statusCode == 401 || statusCode == 403) {
            cachedToken = null
            cookieStore.clear()
            return null
        }

        val macroCalls = message.optJSONObject("body")?.optJSONObject("macro_calls") ?: return null

        val trackObj = macroCalls.optJSONObject("matcher.track.get")
            ?.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONObject("track")

        val trackName = trackObj?.optString("track_name")?.ifBlank { null } ?: query.title
        val artistName = trackObj?.optString("artist_name")?.ifBlank { null } ?: query.artist
        val commonTrackId = trackObj?.optLong("commontrack_id", 0L)?.takeIf { it > 0L }?.toString()
        val durationSec = trackObj?.optInt("track_length", 0) ?: 0
        val durationMs = if (durationSec > 0) durationSec * 1000L else null

        val score = SongMatchScorer.calculate(
            query.title, query.artist, query.durationMs,
            trackName, artistName, durationMs
        )
        if (score < 0.50) return null

        val originalLrc = macroCalls.optJSONObject("track.subtitles.get")
            ?.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONArray("subtitle_list")
            ?.optJSONObject(0)
            ?.optJSONObject("subtitle")
            ?.optString("subtitle_body")
            ?.trim()
            .orEmpty()

        if (originalLrc.isBlank()) return null

        var translationLrc: String? = null
        if (needsTranslation(originalLrc, targetLang) && commonTrackId != null) {
            translationLrc = fetchHumanTranslation(token, commonTrackId, targetLang, originalLrc)
        }

        return LyricResult(
            title = trackName,
            artist = artistName,
            lyric = originalLrc,
            rawLyric = originalLrc,
            translationLyric = translationLrc,
            source = "universal/musixmatch",
            sourceTrackId = commonTrackId,
            format = LyricFormats.LRC
        )
    }

    private fun fetchHumanTranslation(
        token: String,
        commonTrackId: String,
        lang: String,
        originalLrc: String
    ): String? = runCatching {
        val transUrl = (BASE_URL + "track.subtitle.translation.get").toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP_ID)
            .addQueryParameter("usertoken", token)
            .addQueryParameter("commontrack_id", commonTrackId)
            .addQueryParameter("selected_language", lang)
            .build()

        val root = getJson(transUrl.toString()) ?: return null
        val body = root.optJSONObject("message")?.optJSONObject("body") ?: return null

        val rawTranslated = body.optJSONObject("subtitle_translated")?.optString("subtitle_body")
            ?.ifBlank { null }
            ?: body.optJSONObject("subtitle")?.optString("subtitle_body")
            ?.ifBlank { null }
            ?: return null

        LrcTranslationAligner.align(originalLrc, rawTranslated) ?: rawTranslated
    }.getOrNull()

    private fun getToken(): String? {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (!existing.isNullOrBlank() && (now - tokenFetchedAt) < TOKEN_TTL_MS) {
            return existing
        }
        val url = (BASE_URL + "token.get").toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP_ID)
            .build()
        val root = getJson(url.toString()) ?: return null
        val token = root.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optString("user_token")
            ?.takeIf { it.isNotBlank() && !it.contains("UpgradeOnly", ignoreCase = true) }
        if (token != null) {
            cachedToken = token
            tokenFetchedAt = now
        }
        return token
    }

    private fun getJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", "https://www.musixmatch.com")
            .header("Referer", "https://www.musixmatch.com/")
            .build()
        mxmClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return JSONObject(body)
        }
    }

    override fun searchCandidates(query: String): List<SearchCandidate> = runCatching {
        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        val req = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "UniversalLyricsProvider/1.1.5")
            .build()
        mxmClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics").trim()
                    if (synced.isBlank()) continue
                    val durSec = item.optDouble("duration", 0.0)
                    add(
                        SearchCandidate(
                            id = item.optLong("id").toString(),
                            title = item.optString("trackName"),
                            artist = item.optString("artistName"),
                            album = item.optString("albumName"),
                            source = LyricsSourceAggregator.SourceId.MUSIXMATCH.label,
                            durationMs = if (durSec > 0) (durSec * 1000).toLong() else 0L,
                            formatHint = LyricFormats.LRC
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? =
        fetchLyrics(TrackQuery(candidate.title, candidate.artist, candidate.album, candidate.durationMs))

    private fun fetchFallbackLrclib(query: TrackQuery): LyricResult? {
        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title.trim())
            .addQueryParameter("artist_name", query.artist.trim())
            .build()
        val req = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "UniversalLyricsProvider/1.1.5")
            .build()
        mxmClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val array = JSONArray(response.body?.string().orEmpty())
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
            val lrc = bestSynced?.takeIf { bestScore >= 0.50 } ?: return null
            return LyricResult(
                title = bestTitle,
                artist = bestArtist,
                lyric = lrc,
                rawLyric = lrc,
                translationLyric = null,
                source = "universal/musixmatch-lrclib",
                sourceTrackId = bestId,
                format = LyricFormats.LRC
            )
        }
    }

    private fun needsTranslation(lrc: String, targetLang: String): Boolean {
        if (targetLang == "ru") {
            val cyrillicCount = lrc.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val latinCount = lrc.count { it in 'A'..'Z' || it in 'a'..'z' }
            return latinCount > cyrillicCount * 2
        }
        return true
    }
}
