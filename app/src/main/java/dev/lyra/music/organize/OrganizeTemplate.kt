package dev.lyra.music.organize

/**
 * Builds the destination path of a song from a template such as
 * `{AlbumArtist}/{Album}/{Track:02} - {Title}`. Pure; unit-tested.
 *
 * Tokens: {Artist} {AlbumArtist} {Album} {Title} {Track} {Track:02} {Disc} {Year} {Genre} {FileName}.
 * Unknown tokens are left as text. Each path segment is made file-system safe.
 */
object OrganizeTemplate {

    const val DEFAULT = "{AlbumArtist}/{Album}/{Track:02} - {Title}"

    data class Fields(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
        val genre: String?,
        val fileName: String,
    )

    private val TOKEN = Regex("""\{(\w+)(?::(\d+))?}""")
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** Relative path (no leading slash, no extension) or null when the template yields nothing. */
    fun relativePath(template: String, f: Fields): String? {
        val raw = TOKEN.replace(template) { m ->
            val pad = m.groupValues[2].toIntOrNull()
            // Values are cleaned before insertion so a "/" inside a title cannot create a folder.
            ILLEGAL.replace(when (m.groupValues[1].lowercase()) {
                "artist" -> f.artist.ifBlank { f.albumArtist }.ifBlank { UNKNOWN_ARTIST }
                "albumartist" -> f.albumArtist.ifBlank { f.artist }.ifBlank { UNKNOWN_ARTIST }
                "album" -> f.album.ifBlank { UNKNOWN_ALBUM }
                "title" -> f.title.ifBlank { f.fileName.substringBeforeLast('.') }
                "track" -> f.trackNumber?.let { n -> if (pad != null) n.toString().padStart(pad, '0') else n.toString() } ?: ""
                "disc" -> f.discNumber?.let { n -> if (pad != null) n.toString().padStart(pad, '0') else n.toString() } ?: ""
                "year" -> f.year?.toString() ?: ""
                "genre" -> f.genre.orEmpty().substringBefore(';').trim()
                "filename" -> f.fileName.substringBeforeLast('.')
                else -> m.value
            }, "_")
        }
        val segments = raw.split('/').map { safeSegment(it) }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /** Removes characters FAT/ext4/MTP choke on, collapses blanks, trims dots and spaces, caps length. */
    fun safeSegment(s: String): String {
        var t = ILLEGAL.replace(s, "_").replace(Regex("""\s+"""), " ").trim().trim('.').trim()
        // A segment like " - " left over from an empty track number.
        t = t.removePrefix("-").trim()
        if (t.length > MAX_SEGMENT) t = t.substring(0, MAX_SEGMENT).trim()
        return t
    }

    private const val MAX_SEGMENT = 120
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
}
