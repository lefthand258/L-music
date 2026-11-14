package dev.lyra.music.data.scanner

/** Splits a genre tag's text into separate genre names. */
object GenreText {
    private val SEPARATORS = Regex("""\s*[;/|,、\u0000]\s*""")

    /** "Pop; Rock / 古风" → ["Pop", "Rock", "古风"]. Case-insensitive duplicates are dropped. */
    fun split(text: String?): List<String> =
        text.orEmpty().split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
}
