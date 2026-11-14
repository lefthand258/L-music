package dev.lyra.music.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/** Song information offered by an online source. Empty / null means "source does not know". */
data class MetadataCandidate(
    val source: OnlineSource,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long = 0,
    /** HTTPS URL on an allow-listed image host, or null. */
    val coverUrl: String? = null,
    val score: Float = 0f,
)

enum class MetaField { TITLE, ARTIST, ALBUM, YEAR, GENRE, COVER }

/** The text fields of a song that online metadata may change. */
data class SongTags(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: Int?,
    val genre: String?,
)

object MetadataMerge {
    /**
     * Applies the chosen [fields] of [c] on top of [current]. A field the candidate does not
     * know is never applied, so existing information is not replaced by blanks.
     */
    fun merge(current: SongTags, c: MetadataCandidate, fields: Set<MetaField>): SongTags {
        val artist = if (MetaField.ARTIST in fields && c.artist.isNotBlank()) c.artist else current.artist
        val albumChanged = MetaField.ALBUM in fields && c.album.isNotBlank() && c.album != current.album
        return SongTags(
            title = if (MetaField.TITLE in fields && c.title.isNotBlank()) c.title else current.title,
            artist = artist,
            album = if (MetaField.ALBUM in fields && c.album.isNotBlank()) c.album else current.album,
            // A new album or a previously empty album artist follows the track artist.
            albumArtist = if (albumChanged || current.albumArtist.isBlank()) artist else current.albumArtist,
            year = if (MetaField.YEAR in fields && c.year != null) c.year else current.year,
            genre = if (MetaField.GENRE in fields && !c.genre.isNullOrBlank()) c.genre else current.genre,
        )
    }

    /** Fields that are empty locally and that the candidate can fill – used by automatic completion. */
    fun missingFields(current: SongTags, hasCover: Boolean, c: MetadataCandidate): Set<MetaField> = buildSet {
        if (current.artist.isBlank() && c.artist.isNotBlank()) add(MetaField.ARTIST)
        if (current.album.isBlank() && c.album.isNotBlank()) add(MetaField.ALBUM)
        if (current.year == null && c.year != null) add(MetaField.YEAR)
        if (current.genre.isNullOrBlank() && !c.genre.isNullOrBlank()) add(MetaField.GENRE)
        if (!hasCover && c.coverUrl != null) add(MetaField.COVER)
    }
}

/** Parsers for song-information search responses. Same untrusted-data rules as [OnlineParsers]. */
object MetadataParsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_RESULTS = 20
    private const val MAX_FIELD = 300

    /** Image hosts we are willing to download cover art from (exact host or sub-domain). */
    val IMAGE_HOST_SUFFIXES = listOf("music.126.net", "y.gtimg.cn", "coverartarchive.org", "archive.org")

    fun isAllowedImageHost(host: String): Boolean {
        val h = host.lowercase()
        return IMAGE_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** `/api/cloudsearch/pc` */
    fun netease(body: String): List<MetadataCandidate> {
        val root = parse(body) as? JsonObject ?: return emptyList()
        val songs = (root["result"] as? JsonObject)?.get("songs") as? JsonArray ?: return emptyList()
        return songs.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val album = (o["al"] ?: o["album"]) as? JsonObject
            MetadataCandidate(
                source = OnlineSource.NETEASE,
                id = id,
                title = o.field("name") ?: return@mapNotNull null,
                artist = names(o["ar"] ?: o["artists"]),
                album = album?.field("name").orEmpty(),
                year = yearOfEpochMs(o.long("publishTime")),
                durationMs = o.long("dt") ?: o.long("duration") ?: 0L,
                coverUrl = album?.str("picUrl")?.let { safeImageUrl(it, "?param=800y800") },
            )
        }
    }

    /** `client_search_cp` */
    fun qq(body: String): List<MetadataCandidate> {
        val root = parse(stripJsonp(body)) as? JsonObject ?: return emptyList()
        val list = ((root["data"] as? JsonObject)?.get("song") as? JsonObject)?.get("list") as? JsonArray
            ?: return emptyList()
        return list.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val mid = o.str("songmid")?.takeIf { it.isMid() } ?: return@mapNotNull null
            val albumMid = o.str("albummid")?.takeIf { it.isMid() }
            MetadataCandidate(
                source = OnlineSource.QQ,
                id = mid,
                title = o.field("songname") ?: return@mapNotNull null,
                artist = names(o["singer"]),
                album = o.field("albumname").orEmpty(),
                year = yearOfEpochMs(o.long("pubtime")?.times(1000)),
                durationMs = (o.long("interval") ?: 0L) * 1000,
                coverUrl = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" },
            )
        }
    }

    /** MusicBrainz `/ws/2/recording?query=…&fmt=json` */
    fun musicBrainz(body: String): List<MetadataCandidate> {
        val root = parse(body) as? JsonObject ?: return emptyList()
        val recs = root["recordings"] as? JsonArray ?: return emptyList()
        return recs.take(MAX_RESULTS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isUuid() } ?: return@mapNotNull null
            val releases = (o["releases"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            // Prefer an original album over compilations / singles when several releases exist.
            val release = releases.minByOrNull { r ->
                val group = r["release-group"] as? JsonObject
                val secondary = (group?.get("secondary-types") as? JsonArray)?.isNotEmpty() == true
                when {
                    group?.str("primary-type") == "Album" && !secondary -> 0
                    group?.str("primary-type") == "Album" -> 2
                    else -> 1
                }
            }
            val releaseId = release?.str("id")?.takeIf { it.isUuid() }
            val date = o.str("first-release-date") ?: release?.str("date")
            val genre = (o["tags"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.maxByOrNull { it.long("count") ?: 0L }?.field("name")
            MetadataCandidate(
                source = OnlineSource.MUSICBRAINZ,
                id = id,
                title = o.field("title") ?: return@mapNotNull null,
                artist = names(o["artist-credit"]),
                album = release?.field("title").orEmpty(),
                year = date?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
                genre = genre,
                durationMs = o.long("length") ?: 0L,
                coverUrl = releaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
            )
        }
    }

    /** Lucene-escapes user text for a MusicBrainz query. */
    fun musicBrainzQuery(title: String, artist: String): String {
        fun esc(s: String) = s.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), """\\$1""")
        return buildString {
            append("recording:\"").append(esc(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(esc(artist)).append('"')
        }
    }

    /** Upgrades http→https, checks the host against the allow-list, drops anything else. */
    internal fun safeImageUrl(raw: String, suffix: String = ""): String? {
        val m = Regex("""^https?://([A-Za-z0-9.-]+)(/[^\s?#]*)$""").matchEntire(raw.trim()) ?: return null
        val host = m.groupValues[1]
        if (!isAllowedImageHost(host)) return null
        return "https://$host${m.groupValues[2]}$suffix"
    }

    internal fun yearOfEpochMs(ms: Long?): Int? {
        if (ms == null || ms <= 0) return null
        return runCatching { Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).year }.getOrNull()?.takeIf { it in 1900..2100 }
    }

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private fun stripJsonp(body: String): String {
        val t = body.trim()
        if (t.startsWith("{") || t.startsWith("[")) return t
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        return if (open in 0 until close) t.substring(open + 1, close) else t
    }

    private fun String.isMid() = isNotEmpty() && length <= 32 && all(Char::isLetterOrDigit)
    private fun String.isUuid() = Regex("""[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}""").matches(this)

    private fun names(e: JsonElement?): String =
        (e as? JsonArray)?.mapNotNull { (it as? JsonObject)?.field("name") }?.joinToString(" / ").orEmpty()

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull || v !is JsonPrimitive) return null
        return v.content
    }

    private fun JsonObject.field(key: String): String? =
        str(key)?.filter { it.code >= 0x20 }?.trim()?.take(MAX_FIELD)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
}
