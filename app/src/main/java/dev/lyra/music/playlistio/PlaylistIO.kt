package dev.lyra.music.playlistio

/** A track as described by a playlist file – whatever subset of fields the format carries. */
data class TrackRef(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long? = null,
    val path: String? = null,
    val isrc: String? = null,
)

data class ImportedPlaylist(val name: String, val tracks: List<TrackRef>)

/** Minimal view of a library song used for matching (keeps this file free of Android types). */
data class SongKey(val id: Long, val path: String, val title: String, val artist: String, val album: String)

/**
 * Playlist import / export in the formats other players and transfer services understand:
 *  - M3U / M3U8 (extended, with `#EXTINF` and `#PLAYLIST`),
 *  - CSV in the column layout TuneMyMusic exports and imports
 *    (`Track name, Artist name, Album, Playlist name, Type, ISRC`).
 */
object PlaylistIO {

    private const val MAX_TRACKS = 50_000

    // ---- M3U ------------------------------------------------------------------------------

    fun parseM3u(text: String, fallbackName: String, baseDir: String? = null): ImportedPlaylist {
        var name = fallbackName
        var pendingTitle: String? = null
        var pendingArtist = ""
        var pendingDuration: Long? = null
        val tracks = ArrayList<TrackRef>()

        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (tracks.size >= MAX_TRACKS) break
            when {
                line.startsWith("#PLAYLIST:", ignoreCase = true) ->
                    name = line.substringAfter(':').trim().ifBlank { fallbackName }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    val secs = info.substringBefore(',').trim().substringBefore(' ').toDoubleOrNull()
                    pendingDuration = secs?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
                    val display = info.substringAfter(',', "").trim()
                    val sep = display.indexOf(" - ")
                    if (sep > 0) {
                        pendingArtist = display.substring(0, sep).trim()
                        pendingTitle = display.substring(sep + 3).trim()
                    } else {
                        pendingArtist = ""
                        pendingTitle = display.ifBlank { null }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val path = resolvePath(line, baseDir)
                    val fileTitle = line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                    tracks += TrackRef(
                        title = pendingTitle ?: fileTitle,
                        artist = pendingArtist,
                        durationMs = pendingDuration,
                        path = path,
                    )
                    pendingTitle = null; pendingArtist = ""; pendingDuration = null
                }
            }
        }
        return ImportedPlaylist(name, tracks)
    }

    fun writeM3u(name: String, tracks: List<TrackRef>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.oneLine()).append('\n')
        for (t in tracks) {
            val secs = t.durationMs?.let { (it + 500) / 1000 } ?: -1
            append("#EXTINF:").append(secs).append(',')
            if (t.artist.isNotBlank()) append(t.artist.oneLine()).append(" - ")
            append(t.title.oneLine()).append('\n')
            append(t.path ?: t.title.oneLine()).append('\n')
        }
    }

    private fun resolvePath(entry: String, baseDir: String?): String {
        if (entry.startsWith("/") || entry.contains("://") || WINDOWS_ABS.containsMatchIn(entry)) return entry
        val rel = entry.replace('\\', '/')
        return if (baseDir == null) rel else baseDir.trimEnd('/') + "/" + rel.removePrefix("./")
    }

    private val WINDOWS_ABS = Regex("""^[A-Za-z]:[\\/]""")

    // ---- CSV (TuneMyMusic layout) ---------------------------------------------------------

    private val CSV_HEADER = listOf("Track name", "Artist name", "Album", "Playlist name", "Type", "ISRC")

    fun writeCsv(playlists: List<ImportedPlaylist>): String = buildString {
        append(CSV_HEADER.joinToString(",")).append("\r\n")
        for (p in playlists) for (t in p.tracks) {
            append(listOf(t.title, t.artist, t.album, p.name, "Playlist", t.isrc ?: "").joinToString(",") { it.csvEscape() })
            append("\r\n")
        }
    }

    /**
     * Reads a CSV with a header row. Columns are located by name, so exports from TuneMyMusic,
     * Spotify (Exportify) and similar tools all work. Rows are grouped by playlist name; files
     * without a playlist column become one playlist called [fallbackName].
     */
    fun parseCsv(text: String, fallbackName: String): List<ImportedPlaylist> {
        val rows = parseCsvRows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOfFirst { it == n }.takeIf { it >= 0 } }
                ?: names.firstNotNullOfOrNull { n -> header.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
                ?: -1

        val iTitle = col("track name", "title", "track", "song", "name")
        if (iTitle < 0) return emptyList()
        val iArtist = col("artist name", "artist name(s)", "artist")
        val iAlbum = col("album name", "album")
        val iPlaylist = col("playlist name", "playlist")
        val iIsrc = col("isrc")
        val iDuration = col("duration (ms)", "track duration (ms)", "duration_ms")

        val grouped = LinkedHashMap<String, MutableList<TrackRef>>()
        var count = 0
        for (row in rows.drop(1)) {
            if (count >= MAX_TRACKS) break
            val title = row.getOrNull(iTitle)?.trim().orEmpty()
            if (title.isEmpty()) continue
            val name = row.getOrNull(iPlaylist)?.trim()?.ifBlank { null } ?: fallbackName
            grouped.getOrPut(name) { ArrayList() } += TrackRef(
                title = title,
                artist = row.getOrNull(iArtist)?.trim().orEmpty(),
                album = row.getOrNull(iAlbum)?.trim().orEmpty(),
                durationMs = row.getOrNull(iDuration)?.trim()?.toLongOrNull(),
                isrc = row.getOrNull(iIsrc)?.trim()?.ifBlank { null },
            )
            count++
        }
        return grouped.map { (n, t) -> ImportedPlaylist(n, t) }
    }

    /** RFC 4180 CSV reader: quoted fields, doubled quotes, embedded commas and newlines. */
    internal fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> { inQuotes = true; fieldStarted = true }
                ',' -> { row.add(field.toString()); field.setLength(0); fieldStarted = true }
                '\r' -> Unit
                '\n' -> {
                    if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) {
                        row.add(field.toString()); rows.add(row)
                    }
                    row = ArrayList(); field.setLength(0); fieldStarted = false
                }
                else -> field.append(c)
            }
            i++
        }
        if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row)
        }
        return rows
    }

    private fun String.csvEscape(): String =
        if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + replace("\"", "\"\"") + "\"" else this

    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ').trim()
}

/**
 * Matches playlist entries against the local library.
 * Order: exact path → file name → title + artist → title alone (only when unambiguous).
 */
class LibraryMatcher(songs: List<SongKey>) {

    enum class Confidence { EXACT, STRONG, WEAK }
    data class Match(val songId: Long, val confidence: Confidence)

    private val byPath = songs.associateBy { it.path }
    private val byFileName = songs.groupBy { it.path.substringAfterLast('/').lowercase() }
    private val byTitleArtist = songs.groupBy { norm(it.title) + "\u0000" + norm(it.artist) }
    private val byTitle = songs.groupBy { norm(it.title) }

    fun match(ref: TrackRef): Match? {
        ref.path?.let { p ->
            byPath[p]?.let { return Match(it.id, Confidence.EXACT) }
            val name = p.replace('\\', '/').substringAfterLast('/').lowercase()
            byFileName[name]?.singleOrNull()?.let { return Match(it.id, Confidence.STRONG) }
        }
        val title = norm(ref.title)
        if (title.isEmpty()) return null
        if (ref.artist.isNotBlank()) {
            byTitleArtist[title + "\u0000" + norm(ref.artist)]?.let { c ->
                return Match(pickByAlbum(c, ref).id, Confidence.STRONG)
            }
            // Artist given in a different form ("A, B" vs "A / B" vs "A feat. B"): compare by containment.
            val wanted = norm(ref.artist)
            byTitle[title]?.filter { s ->
                val a = norm(s.artist)
                a.isNotEmpty() && (a.contains(wanted) || wanted.contains(a))
            }?.takeIf { it.isNotEmpty() }?.let { c ->
                return Match(pickByAlbum(c, ref).id, Confidence.STRONG)
            }
        }
        byTitle[title]?.singleOrNull()?.let { return Match(it.id, Confidence.WEAK) }
        return null
    }

    private fun pickByAlbum(candidates: List<SongKey>, ref: TrackRef): SongKey {
        if (candidates.size == 1 || ref.album.isBlank()) return candidates.first()
        val album = norm(ref.album)
        return candidates.firstOrNull { norm(it.album) == album } ?: candidates.first()
    }

    companion object {
        /** Lower-case, drop bracketed qualifiers' brackets, punctuation and spaces; keep letters/digits of any script. */
        fun norm(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                // Full-width ASCII → half-width so "ＡＢＣ" matches "ABC".
                val c = if (ch in '！'..'～') (ch.code - 0xFEE0).toChar() else ch
                if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            }
            return sb.toString()
        }
    }
}
