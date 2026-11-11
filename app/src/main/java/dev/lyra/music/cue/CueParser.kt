package dev.lyra.music.cue

data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val songwriter: String?,
    /** Audio file this track lives in, exactly as written in the sheet. */
    val fileName: String,
    val startMs: Long,
    /** Start of the next track in the same file, or null for the last track of a file. */
    val endMs: Long?,
)

data class CueSheet(
    val title: String?,
    val performer: String?,
    val genre: String?,
    val year: Int?,
    val tracks: List<CueTrack>,
) {
    /** Tracks grouped by referenced audio file, preserving order. */
    val files: Map<String, List<CueTrack>> get() = tracks.groupBy { it.fileName }
}

/**
 * CUE sheet parser. Understands FILE / TRACK / INDEX / TITLE / PERFORMER / SONGWRITER and
 * `REM GENRE` / `REM DATE`. Track start is INDEX 01 (falling back to INDEX 00); a track ends
 * where the next track of the same FILE starts. Times are `mm:ss:ff` with 75 frames per second.
 */
object CueParser {

    private const val MAX_LINES = 20_000
    private const val MAX_TRACKS = 999

    private class TrackBuilder(val number: Int, val fileName: String) {
        var title: String? = null
        var performer: String? = null
        var songwriter: String? = null
        var index00: Long? = null
        var index01: Long? = null
        val start: Long? get() = index01 ?: index00
    }

    fun parse(text: String): CueSheet? {
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var genre: String? = null
        var year: Int? = null
        var currentFile: String? = null
        var current: TrackBuilder? = null
        val builders = ArrayList<TrackBuilder>()

        for (raw in text.removePrefix("﻿").lineSequence().take(MAX_LINES)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val keyword = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()

            when (keyword) {
                "REM" -> {
                    val k = rest.substringBefore(' ').uppercase()
                    val v = unquote(rest.substringAfter(' ', ""))
                    when (k) {
                        "GENRE" -> genre = v.ifBlank { null }
                        "DATE" -> year = Regex("""\d{4}""").find(v)?.value?.toIntOrNull()
                    }
                }
                "FILE" -> {
                    currentFile = parseFileName(rest)
                    current = null
                }
                "TRACK" -> {
                    val file = currentFile ?: continue
                    val parts = rest.split(Regex("""\s+"""))
                    val n = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val type = parts.getOrNull(1)?.uppercase() ?: "AUDIO"
                    if (type != "AUDIO") { current = null; continue }
                    if (builders.size >= MAX_TRACKS) break
                    current = TrackBuilder(n, file).also { builders += it }
                }
                "TITLE" -> {
                    val v = unquote(rest).ifBlank { null }
                    val c = current
                    if (c != null) c.title = v else albumTitle = v
                }
                "PERFORMER" -> {
                    val v = unquote(rest).ifBlank { null }
                    val c = current
                    if (c != null) c.performer = v else albumPerformer = v
                }
                "SONGWRITER" -> current?.songwriter = unquote(rest).ifBlank { null }
                "INDEX" -> {
                    val c = current ?: continue
                    val parts = rest.split(Regex("""\s+"""))
                    val idx = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val t = parseTime(parts.getOrNull(1) ?: continue) ?: continue
                    when (idx) {
                        0 -> c.index00 = t
                        1 -> c.index01 = t
                    }
                }
            }
        }

        val valid = builders.filter { it.start != null }
        if (valid.isEmpty()) return null

        val tracks = valid.mapIndexed { i, b ->
            val next = valid.getOrNull(i + 1)?.takeIf { it.fileName == b.fileName }
            val start = b.start!!
            val end = next?.start?.takeIf { it > start }
            CueTrack(
                number = b.number,
                title = b.title,
                performer = b.performer ?: albumPerformer,
                songwriter = b.songwriter,
                fileName = b.fileName,
                startMs = start,
                endMs = end,
            )
        }
        return CueSheet(albumTitle, albumPerformer, genre, year, tracks)
    }

    /** `mm:ss:ff` → milliseconds (75 frames per second). Minutes may exceed 99. */
    fun parseTime(s: String): Long? {
        val p = s.trim().split(':')
        if (p.size != 3) return null
        val m = p[0].toLongOrNull() ?: return null
        val sec = p[1].toLongOrNull() ?: return null
        val f = p[2].toLongOrNull() ?: return null
        if (m < 0 || sec !in 0..59 || f !in 0..74) return null
        return m * 60_000 + sec * 1000 + (f * 1000 + 37) / 75
    }

    /** `"name with spaces.flac" WAVE` or `name.flac WAVE` → name. */
    private fun parseFileName(rest: String): String {
        if (rest.startsWith('"')) {
            val end = rest.indexOf('"', 1)
            if (end > 0) return rest.substring(1, end)
        }
        // Unquoted: last token is the type.
        val lastSpace = rest.lastIndexOf(' ')
        return if (lastSpace > 0) rest.substring(0, lastSpace).trim() else rest
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.first() == '"' && t.last() == '"') t.substring(1, t.length - 1).trim() else t
    }
}
