package dev.lyra.music.util

/** Pure helpers for turning a Storage Access Framework tree document id into a file path. */
object StoragePaths {
    /**
     * `primary:Music/Hi-Res` → `/storage/emulated/0/Music/Hi-Res`,
     * `1A2B-3C4D:Music` → `/storage/1A2B-3C4D/Music`. Returns null for ids that are not plain
     * volume paths (cloud providers, downloads provider…) or that try to escape the volume.
     */
    fun documentIdToPath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/')
        if (relative.split('/').any { it == ".." }) return null
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    private val VOLUME_ID = Regex("""[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}""")

    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "ape", "wv", "tta",
        "aiff", "aif", "alac", "dsf", "dff", "mpc", "mka", "amr", "mp4",
    )

    fun isAudioFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}
