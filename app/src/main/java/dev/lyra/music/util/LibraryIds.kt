package dev.lyra.music.util

/**
 * Lyra's own album / artist grouping ids, derived from the (corrected) tag text rather than
 * from MediaStore, so that CUE tracks, directly scanned files and songs whose metadata was
 * fixed online all group correctly. Ids are stable 64-bit FNV-1a hashes of the normalised names.
 */
object LibraryIds {

    fun artistId(artist: String): Long = hash("artist\u0000" + norm(artist))

    /**
     * Albums are keyed by album artist + album title. Songs without an album title are grouped
     * per folder instead of being lumped into one giant "Unknown album".
     */
    fun albumId(album: String, albumArtist: String, folderPath: String): Long =
        if (album.isBlank()) hash("folder\u0000$folderPath")
        else hash("album\u0000" + norm(albumArtist) + "\u0000" + norm(album))

    private fun norm(s: String): String = s.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("""\s+""")

    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        for (ch in s) {
            h = h xor (ch.code and 0xFF).toLong()
            h *= 0x100000001b3L
            h = h xor (ch.code ushr 8).toLong()
            h *= 0x100000001b3L
        }
        // Keep ids positive and non-zero (0 means "unset" elsewhere).
        return (h and Long.MAX_VALUE).coerceAtLeast(1)
    }
}
