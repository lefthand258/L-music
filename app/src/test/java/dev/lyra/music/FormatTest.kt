package dev.lyra.music

import dev.lyra.music.util.audioSummary
import dev.lyra.music.util.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun duration_basic() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("3:05", formatDuration(185_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test fun summary_hiRes() {
        assertEquals("FLAC · 24-bit · 96 kHz · Stereo", audioSummary("FLAC", 24, 96_000, 2800, 2))
        assertEquals("MP3 · 44.1 kHz · 320 kbps · Stereo", audioSummary("mp3", null, 44_100, 320, 2))
    }
}
