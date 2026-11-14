package dev.lyra.music.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

enum class OnlineSource(val label: String) {
    LRCLIB("LRCLIB"),
    NETEASE("网易云"),
    QQ("QQ音乐"),
    KUGOU("酷狗"),
    MUSICBRAINZ("MusicBrainz");

    companion object {
        /** Sources that can provide lyrics. */
        val LYRICS: List<OnlineSource> = listOf(LRCLIB, NETEASE, QQ, KUGOU)
        /** Sources that can provide song information and cover art. */
        val METADATA: List<OnlineSource> = listOf(NETEASE, QQ, MUSICBRAINZ)
    }
}

/** What we know about the song we want lyrics for. */
data class LyricsQuery(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
)

data class LyricsCandidate(
    val source: OnlineSource,
    /** Provider-side id used to fetch the lyrics (song id / songmid). */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** true = time-synced, false = plain text only, null = unknown until fetched. */
    val synced: Boolean?,
    /** Lyrics text when the search response already carried it (LRCLIB). */
    val inlineLyrics: String? = null,
    /** 0..1 – how well this candidate matches the query. */
    val score: Float = 0f,
)

/** Lyrics as returned by a provider: the original plus an optional separate translation. */
data class FetchedLyrics(val original: String, val translation: String? = null)

/**
 * Parsers for the three providers' JSON. Pure functions over text so they can be unit-tested
 * with canned responses. Everything read from the network is treated as untrusted data:
 * only known fields are read, strings are length-capped and stripped of control characters,
 * and anything of the wrong shape is skipped rather than trusted.
 */
object OnlineParsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_RESULTS = 30
    private const val MAX_FIELD = 300
    const val MAX_LYRICS_CHARS = 64 * 1024

    // ---- LRCLIB ---------------------------------------------------------------------------

    fun lrclibSearch(body: String): List<LyricsCandidate> {
        val arr = parse(body) as? JsonArray ?: return emptyList()
        return arr.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val synced = o.str("syncedLyrics")?.takeIf { it.isNotBlank() }
            val plain = o.str("plainLyrics")?.takeIf { it.isNotBlank() }
            val text = synced ?: plain ?: return@mapNotNull null // instrumental / empty entries
            LyricsCandidate(
                source = OnlineSource.LRCLIB,
                id = id,
                title = o.field("trackName") ?: o.field("name") ?: return@mapNotNull null,
                artist = o.field("artistName").orEmpty(),
                album = o.field("albumName").orEmpty(),
                durationMs = ((o.double("duration") ?: 0.0) * 1000).toLong(),
                synced = synced != null,
                inlineLyrics = text.take(MAX_LYRICS_CHARS),
            )
        }
    }

    // ---- NetEase Cloud Music -----------------------------------------------------------------

    /** `/api/cloudsearch/pc` response. */
    fun neteaseSearch(body: String): List<LyricsCandidate> {
        val root = parse(body) as? JsonObject ?: return emptyList()
        val songs = (root["result"] as? JsonObject)?.get("songs") as? JsonArray ?: return emptyList()
        return songs.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            LyricsCandidate(
                source = OnlineSource.NETEASE,
                id = id,
                title = o.field("name") ?: return@mapNotNull null,
                artist = names(o["ar"] ?: o["artists"]),
                album = ((o["al"] ?: o["album"]) as? JsonObject)?.field("name").orEmpty(),
                durationMs = o.long("dt") ?: o.long("duration") ?: 0L,
                synced = null,
            )
        }
    }

    /** `/api/song/lyric` response. */
    fun neteaseLyric(body: String): FetchedLyrics? {
        val root = parse(body) as? JsonObject ?: return null
        val original = (root["lrc"] as? JsonObject)?.str("lyric")?.takeIf { it.isNotBlank() } ?: return null
        val translation = (root["tlyric"] as? JsonObject)?.str("lyric")?.takeIf { it.isNotBlank() }
        return FetchedLyrics(original.take(MAX_LYRICS_CHARS), translation?.take(MAX_LYRICS_CHARS))
    }

    // ---- QQ Music -----------------------------------------------------------------------------

    /** `client_search_cp` response. */
    fun qqSearch(body: String): List<LyricsCandidate> {
        val root = parse(stripJsonp(body)) as? JsonObject ?: return emptyList()
        val list = ((root["data"] as? JsonObject)?.get("song") as? JsonObject)?.get("list") as? JsonArray
            ?: return emptyList()
        return list.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val mid = o.str("songmid")?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all(Char::isLetterOrDigit) }
                ?: return@mapNotNull null
            LyricsCandidate(
                source = OnlineSource.QQ,
                id = mid,
                title = o.field("songname") ?: return@mapNotNull null,
                artist = names(o["singer"]),
                album = o.field("albumname").orEmpty(),
                durationMs = (o.long("interval") ?: 0L) * 1000,
                synced = null,
            )
        }
    }

    /** `fcg_query_lyric_new.fcg` response; fields are plain text or base64 depending on the server. */
    fun qqLyric(body: String): FetchedLyrics? {
        val root = parse(stripJsonp(body)) as? JsonObject ?: return null
        val original = root.str("lyric")?.let(::maybeBase64)?.let(::unescapeEntities)?.takeIf { it.isNotBlank() }
            ?: return null
        val translation = root.str("trans")?.let(::maybeBase64)?.let(::unescapeEntities)?.takeIf { it.isNotBlank() }
        return FetchedLyrics(original.take(MAX_LYRICS_CHARS), translation?.take(MAX_LYRICS_CHARS))
    }

    // ---- Kugou ---------------------------------------------------------------------------------

    /**
     * `lyrics.kugou.com/search` response. The id handed back is "id|accesskey" because both are
     * needed to download. Kugou's lyric search is a lyrics index of its own, so it often finds
     * songs the other services miss.
     */
    fun kugouSearch(body: String): List<LyricsCandidate> {
        val root = parse(body) as? JsonObject ?: return emptyList()
        val list = root["candidates"] as? JsonArray ?: return emptyList()
        return list.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all(Char::isLetterOrDigit) } ?: return@mapNotNull null
            val key = o.str("accesskey")?.takeIf { it.isNotEmpty() && it.length <= 64 && it.all(Char::isLetterOrDigit) } ?: return@mapNotNull null
            LyricsCandidate(
                source = OnlineSource.KUGOU,
                id = "$id|$key",
                title = o.field("song") ?: return@mapNotNull null,
                artist = o.field("singer").orEmpty(),
                album = "",
                durationMs = o.long("duration") ?: 0L,
                synced = true,
            )
        }
    }

    /** `lyrics.kugou.com/download?fmt=lrc` response: base64 LRC in `content`. */
    fun kugouLyric(body: String): FetchedLyrics? {
        val root = parse(body) as? JsonObject ?: return null
        val content = root.str("content")?.takeIf { it.isNotBlank() } ?: return null
        val text = runCatching { String(java.util.Base64.getDecoder().decode(content.trim()), Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF")?.takeIf { it.isNotBlank() } ?: return null
        return FetchedLyrics(text.take(MAX_LYRICS_CHARS))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    /** `callback({...})` → `{...}`. */
    private fun stripJsonp(body: String): String {
        val t = body.trim()
        if (t.startsWith("{") || t.startsWith("[")) return t
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        return if (open in 0 until close) t.substring(open + 1, close) else t
    }

    private fun maybeBase64(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || t.contains('[') || t.contains(' ') || t.contains('\n')) return s
        if (t.length % 4 != 0 || !t.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return s
        return runCatching { String(java.util.Base64.getDecoder().decode(t), Charsets.UTF_8) }.getOrDefault(s)
    }

    private fun unescapeEntities(s: String): String {
        if (!s.contains('&')) return s
        var r = s.replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ")
        r = Regex("""&#(\d{1,7});""").replace(r) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp) && cp >= 0x20) String(Character.toChars(cp))
            else if (cp == 10) "\n" else ""
        }
        return r.replace("&amp;", "&")
    }

    private fun names(e: JsonElement?): String =
        (e as? JsonArray)?.mapNotNull { (it as? JsonObject)?.field("name") }?.joinToString(" / ").orEmpty()

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull || v !is JsonPrimitive) return null
        return v.content
    }

    /** Short display field: single line, control characters removed, length-capped. */
    private fun JsonObject.field(key: String): String? =
        str(key)?.filter { it.code >= 0x20 }?.trim()?.take(MAX_FIELD)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
    private fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()
}

/** Scores how well a candidate matches what we know about the local song. */
object MatchScorer {

    /** Candidates at or above this are applied automatically; below it the user decides. */
    const val AUTO_APPLY = 0.85f

    fun score(q: LyricsQuery, c: LyricsCandidate): Float = score(q, c.title, c.artist, c.durationMs)

    fun score(q: LyricsQuery, cTitle: String, cArtist: String, cDurationMs: Long): Float {
        val title = similarity(q.title, cTitle)
        val artistKnown = q.artist.isNotBlank() && cArtist.isNotBlank()
        val artist = if (artistKnown) artistSimilarity(q.artist, cArtist) else 0f
        val durationKnown = q.durationMs > 0 && cDurationMs > 0
        val duration = if (durationKnown) durationScore(q.durationMs, cDurationMs) else 0f

        // Weighted over the evidence we actually have; missing evidence caps the confidence
        // so that a title-only match can never reach the auto-apply threshold.
        var s = when {
            artistKnown && durationKnown -> 0.5f * title + 0.3f * artist + 0.2f * duration
            artistKnown -> (0.6f * title + 0.4f * artist) * 0.9f
            durationKnown -> (0.7f * title + 0.3f * duration) * 0.8f
            else -> title * 0.6f
        }
        // A clearly different length means a different recording (live / remix / cover).
        if (durationKnown && abs(q.durationMs - cDurationMs) > 15_000) s *= 0.6f
        if (isVariant(cTitle) && !isVariant(q.title)) s *= 0.85f
        return s.coerceIn(0f, 1f)
    }

    fun rank(q: LyricsQuery, candidates: List<LyricsCandidate>): List<LyricsCandidate> =
        candidates.map { it.copy(score = score(q, it)) }
            .sortedWith(compareByDescending<LyricsCandidate> { it.score }.thenByDescending { it.synced == true })

    internal fun durationScore(a: Long, b: Long): Float {
        val d = abs(a - b)
        return when {
            d <= 2_000 -> 1f
            d <= 5_000 -> 0.8f
            d <= 10_000 -> 0.5f
            else -> 0f
        }
    }

    internal fun artistSimilarity(a: String, b: String): Float {
        val full = similarity(a, b)
        if (full >= 0.99f) return 1f
        // "A / B", "A, B", "A feat. B", "A、B": any shared name counts as a strong match.
        val pa = splitArtists(a)
        val pb = splitArtists(b)
        if (pa.any { x -> pb.any { y -> x == y } }) return 0.95f
        val na = norm(a); val nb = norm(b)
        if (na.isNotEmpty() && nb.isNotEmpty() && (na.contains(nb) || nb.contains(na))) return 0.85f
        return full
    }

    private val ARTIST_SEP = Regex("""\s*(?:/|,|、|&|;|\bfeat\.?\b|\bft\.?\b|\bx\b|\bvs\.?\b)\s*""", RegexOption.IGNORE_CASE)
    private fun splitArtists(s: String): List<String> = s.split(ARTIST_SEP).map(::norm).filter { it.isNotEmpty() }

    private val VARIANT = Regex("""live|remix|伴奏|翻唱|cover|instrumental|demo|dj|现场|原唱|纯音乐|karaoke""", RegexOption.IGNORE_CASE)
    private fun isVariant(title: String) = VARIANT.containsMatchIn(title)

    /** 1.0 for equal after normalisation; otherwise a Levenshtein ratio (base titles compared too). */
    internal fun similarity(a: String, b: String): Float {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        val bx = norm(stripBrackets(a)); val by = norm(stripBrackets(b))
        if (bx.isNotEmpty() && bx == by) return 0.9f
        return ratio(x, y)
    }

    private val BRACKETS = Regex("""[(（\[【].*?[)）\]】]""")
    private fun stripBrackets(s: String) = BRACKETS.replace(s, "")

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = if (ch in '！'..'～') (ch.code - 0xFEE0).toChar() else ch
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun ratio(a: String, b: String): Float {
        if (a.length > 200 || b.length > 200) return 0f
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1f - prev[b.length].toFloat() / maxOf(a.length, b.length)
    }
}

/** Guesses title / artist from a file name when the tags are empty. */
object FileNameGuess {
    private val LEADING_TRACK = Regex("""^\s*\d{1,3}\s*[.\-_、)]\s*""")

    /** Returns (title, artist); artist is "" when the name carries none. */
    fun guess(fileName: String): Pair<String, String> {
        var base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        base = LEADING_TRACK.replace(base, "")
        val sep = base.indexOf(" - ")
        if (sep > 0) {
            val left = base.substring(0, sep).trim()
            val right = base.substring(sep + 3).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return right to left
        }
        return base to ""
    }
}
