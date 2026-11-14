package dev.lyra.music.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online lyrics lookup across LRCLIB, NetEase Cloud Music and QQ Music.
 *
 * Safety rules (docs/REQUIREMENTS.md §9.5): HTTPS only, fixed hosts, no cookies, no account,
 * no redirects to other hosts, response size capped, and the only thing sent out is the search
 * text (title / artist) – never device identifiers or the library. Responses are parsed as
 * plain data by [OnlineParsers]; nothing downloaded is ever executed or rendered as markup.
 * NetEase and QQ are unofficial interfaces: any failure simply yields no results from them.
 */
@Singleton
class LyricsSearchService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: SafeHttp,
) {
    fun queryFor(song: SongEntity): LyricsQuery {
        val guess = FileNameGuess.guess(song.fileName)
        val tagged = song.tagsFullyScanned || song.artist.isNotBlank()
        return LyricsQuery(
            title = if (tagged && song.title.isNotBlank()) song.title else guess.first,
            artist = song.artist.ifBlank { guess.second },
            album = song.album,
            durationMs = song.durationMs,
        )
    }

    /** Searches the chosen sources in parallel and returns candidates, best match first. */
    suspend fun search(query: LyricsQuery, sources: Set<OnlineSource> = OnlineSource.LYRICS.toSet()): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            if (query.title.isBlank()) return@withContext emptyList()
            val all = supervisorScope {
                sources.map { src ->
                    async {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            runCatching { searchOne(src, query) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }.awaitAll().flatten()
            }
            MatchScorer.rank(query, all)
        }

    /** Downloads a candidate's lyrics and returns LRC / plain text ready to store, or null. */
    suspend fun fetch(candidate: LyricsCandidate): String? = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = when (candidate.source) {
                OnlineSource.LRCLIB -> candidate.inlineLyrics?.let { FetchedLyrics(it) }
                OnlineSource.NETEASE -> get(
                    "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
                        .addQueryParameter("id", candidate.id)
                        .addQueryParameter("lv", "1").addQueryParameter("tv", "-1").build(),
                    referer = "https://music.163.com/",
                )?.let(OnlineParsers::neteaseLyric)
                OnlineSource.QQ -> get(
                    "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder()
                        .addQueryParameter("songmid", candidate.id)
                        .addQueryParameter("format", "json").addQueryParameter("nobase64", "1").build(),
                    // The lyric endpoint answers -1310 unless the request looks like it comes from the web player.
                    referer = "https://y.qq.com/portal/player.html",
                )?.let(OnlineParsers::qqLyric)
                OnlineSource.KUGOU -> {
                    val (id, key) = candidate.id.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                    if (id.isEmpty() || key.isEmpty()) null else get(
                        "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
                            .addQueryParameter("ver", "1").addQueryParameter("client", "pc")
                            .addQueryParameter("id", id).addQueryParameter("accesskey", key)
                            .addQueryParameter("fmt", "lrc").addQueryParameter("charset", "utf8").build(),
                    )?.let(OnlineParsers::kugouLyric)
                }
                OnlineSource.MUSICBRAINZ -> null
            } ?: return@runCatching null
            normalise(fetched)
        }.getOrNull()
    }

    /**
     * Fully automatic lookup used when a song has no lyrics: returns text only when the best
     * candidate is a confident match ([MatchScorer.AUTO_APPLY]) and is time-synced.
     */
    suspend fun autoFind(song: SongEntity): String? {
        val candidates = search(queryFor(song))
        for (c in candidates.take(3)) {
            if (c.score < MatchScorer.AUTO_APPLY) break
            if (c.synced == false) continue
            val text = fetch(c) ?: continue
            if (LrcParser.parse(text).synced) return text
        }
        return null
    }

    /** mode: 0 = off, 1 = unmetered (Wi-Fi) only, 2 = any connection. */
    fun autoAllowed(mode: Int): Boolean {
        if (mode == 0) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return mode == 2 || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun searchOne(source: OnlineSource, q: LyricsQuery): List<LyricsCandidate> = when (source) {
        OnlineSource.LRCLIB -> {
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", q.title)
                .apply { if (q.artist.isNotBlank()) addQueryParameter("artist_name", q.artist) }
                .build()
            get(url)?.let(OnlineParsers::lrclibSearch).orEmpty()
        }
        OnlineSource.NETEASE -> {
            val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
                .addQueryParameter("s", keywords(q)).addQueryParameter("type", "1")
                .addQueryParameter("limit", "15").addQueryParameter("offset", "0").build()
            get(url, referer = "https://music.163.com/")?.let(OnlineParsers::neteaseSearch).orEmpty()
        }
        OnlineSource.QQ -> {
            val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
                .addQueryParameter("w", keywords(q)).addQueryParameter("format", "json")
                .addQueryParameter("p", "1").addQueryParameter("n", "15").build()
            get(url, referer = "https://y.qq.com/")?.let(OnlineParsers::qqSearch).orEmpty()
        }
        OnlineSource.KUGOU -> {
            val keyword = if (q.artist.isNotBlank()) "${q.artist} - ${q.title}" else q.title
            val url = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
                .addQueryParameter("keyword", keyword.take(120))
                .addQueryParameter("duration", if (q.durationMs > 0) q.durationMs.toString() else "")
                .addQueryParameter("hash", "").build()
            get(url)?.let(OnlineParsers::kugouSearch).orEmpty()
        }
        OnlineSource.MUSICBRAINZ -> emptyList() // no lyrics there
    }

    private fun keywords(q: LyricsQuery) = listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" ").take(120)

    /** Merges a separate translation into the original and re-serialises through our own parser. */
    private fun normalise(f: FetchedLyrics): String? {
        val original = LrcParser.parse(f.original)
        if (original.isEmpty) return null
        val merged = f.translation?.let { LrcParser.mergeTranslation(original, LrcParser.parse(it)) } ?: original
        // Provider credit lines / tags are dropped; only timed text survives the round trip.
        return merged.copy(meta = emptyMap()).toLrc().take(OnlineParsers.MAX_LYRICS_CHARS)
    }

    private fun get(url: HttpUrl, referer: String? = null): String? = http.getText(url, referer)

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 25_000L
    }
}
