package dev.lyra.music.lyrics

/** One timed word inside an enhanced-LRC line. */
data class LyricWord(val startMs: Long, val text: String)

/**
 * One lyric line. [timeMs] is -1 for unsynced (plain text) lyrics.
 * [translation] holds the second-language line when the source pairs two lines on one timestamp.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** Offset declared by the file's `[offset:]` tag, already applied to [lines]. */
    val fileOffsetMs: Long = 0,
    val meta: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Index of the line that should be highlighted at [positionMs], or -1 before the first line.
     * [userOffsetMs] > 0 makes lyrics appear earlier (same sign convention as the LRC offset tag).
     */
    fun indexAt(positionMs: Long, userOffsetMs: Long = 0): Int {
        if (!synced || lines.isEmpty()) return -1
        val t = positionMs + userOffsetMs
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    /** Serialises back to standard LRC (or plain text when unsynced). */
    fun toLrc(): String = buildString {
        for ((k, v) in meta) append('[').append(k).append(':').append(v).append("]\n")
        for (l in lines) {
            if (synced) append(formatTag(l.timeMs))
            append(l.text).append('\n')
            if (l.translation != null) {
                if (synced) append(formatTag(l.timeMs))
                append(l.translation).append('\n')
            }
        }
    }

    companion object {
        val EMPTY = Lyrics(emptyList(), synced = false)

        internal fun formatTag(ms: Long): String {
            val t = ms.coerceAtLeast(0)
            val m = t / 60_000
            val s = (t % 60_000) / 1000
            val cs = (t % 1000) / 10
            return "[%02d:%02d.%02d]".format(m, s, cs)
        }
    }
}

/**
 * Tolerant LRC parser.
 *
 * Supports: standard `[mm:ss.xx]` tags (1–3 fraction digits, `.` or `:` separator), several
 * tags on one line, `[offset:±ms]`, ID tags (`ar`, `ti`, `al`, `by`, `length`…), enhanced LRC
 * word tags `<mm:ss.xx>`, two lines sharing a timestamp (original + translation), and plain
 * unsynced text. Input is treated purely as data: control characters are dropped and the size
 * is capped, nothing in it is ever interpreted as markup.
 */
object LrcParser {

    private const val MAX_CHARS = 64 * 1024
    private const val MAX_LINES = 5_000
    private const val MAX_LINE_CHARS = 1_000

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""^\[([A-Za-z#][A-Za-z0-9_]*):(.*)\]$""")

    fun parse(raw: String?): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.EMPTY
        val text = raw.take(MAX_CHARS).removePrefix("﻿")
            .replace("\r\n", "\n").replace('\r', '\n')

        val meta = LinkedHashMap<String, String>()
        var offsetMs = 0L
        data class Raw(val timeMs: Long, val text: String, val words: List<LyricWord>, val order: Int)
        val timed = ArrayList<Raw>()
        val plain = ArrayList<String>()
        var order = 0

        for (rawLine in text.lineSequence().take(MAX_LINES)) {
            val line = rawLine.clean().trim()
            if (line.isEmpty()) continue

            // Leading time tags (possibly several).
            val times = ArrayList<Long>(2)
            var pos = 0
            while (true) {
                val m = TIME_TAG.matchAt(line, pos) ?: break
                times += toMs(m)
                pos = m.range.last + 1
                while (pos < line.length && line[pos] == ' ') pos++
            }

            if (times.isEmpty()) {
                val meta1 = META_TAG.matchEntire(line)
                if (meta1 != null) {
                    val key = meta1.groupValues[1].lowercase()
                    val value = meta1.groupValues[2].trim()
                    if (key == "offset") offsetMs = value.replace("+", "").toLongOrNull() ?: 0L
                    else if (key != "#") meta[key] = value
                } else {
                    plain += line
                }
                continue
            }

            val body = line.substring(pos)
            val (cleanBody, words) = extractWords(body, times.first())
            for (t in times) {
                // Word timings only make sense for the first timestamp of a repeated line.
                timed += Raw(t, cleanBody, if (t == times.first()) words else emptyList(), order++)
            }
        }

        if (timed.isEmpty()) {
            return Lyrics(plain.map { LyricLine(-1, it) }, synced = false, meta = meta)
        }

        // LRC convention: positive offset = lyrics show earlier.
        val sorted = timed.sortedWith(compareBy<Raw> { it.timeMs }.thenBy { it.order })
        val out = ArrayList<LyricLine>(sorted.size)
        for (r in sorted) {
            val t = (r.timeMs - offsetMs).coerceAtLeast(0)
            val last = out.lastOrNull()
            if (last != null && last.timeMs == t && last.translation == null &&
                last.text.isNotEmpty() && r.text.isNotEmpty() && last.text != r.text
            ) {
                out[out.lastIndex] = last.copy(translation = r.text)
            } else {
                out += LyricLine(
                    timeMs = t,
                    text = r.text,
                    words = r.words.map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0)) },
                )
            }
        }
        return Lyrics(out, synced = true, fileOffsetMs = offsetMs, meta = meta)
    }

    /**
     * Merges a separately fetched translation LRC (as NetEase / QQ return it) into [original]
     * by matching timestamps within [toleranceMs].
     */
    fun mergeTranslation(original: Lyrics, translation: Lyrics, toleranceMs: Long = 300): Lyrics {
        if (!original.synced || !translation.synced || translation.isEmpty) return original
        val tl = translation.lines
        var j = 0
        val merged = original.lines.map { line ->
            if (line.translation != null || line.text.isBlank()) return@map line
            while (j < tl.size && tl[j].timeMs < line.timeMs - toleranceMs) j++
            val cand = tl.getOrNull(j)
            if (cand != null && kotlin.math.abs(cand.timeMs - line.timeMs) <= toleranceMs && cand.text.isNotBlank()) {
                line.copy(translation = cand.text)
            } else line
        }
        return original.copy(lines = merged)
    }

    private fun extractWords(body: String, lineStartMs: Long): Pair<String, List<LyricWord>> {
        if (!body.contains('<')) return body.trim() to emptyList()
        val matches = WORD_TAG.findAll(body).toList()
        if (matches.isEmpty()) return body.trim() to emptyList()
        val words = ArrayList<LyricWord>(matches.size + 1)
        val head = body.substring(0, matches.first().range.first)
        if (head.isNotBlank()) words += LyricWord(lineStartMs, head)
        for ((i, m) in matches.withIndex()) {
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val w = body.substring(m.range.last + 1, end)
            if (w.isNotEmpty()) words += LyricWord(toMs(m), w)
        }
        val clean = WORD_TAG.replace(body, "").trim()
        return clean to words
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return min * 60_000 + sec * 1000 + ms
    }

    private fun String.clean(): String {
        val s = if (length > MAX_LINE_CHARS) substring(0, MAX_LINE_CHARS) else this
        return s.filter { it == '\t' || it.code >= 0x20 }
    }
}
