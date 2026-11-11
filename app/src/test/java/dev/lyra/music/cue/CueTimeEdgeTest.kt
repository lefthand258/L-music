package dev.lyra.music.cue

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTimeEdgeTest {
    @Test fun index00_only_is_used_as_start() {
        val cue = CueParser.parse(
            """
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                INDEX 00 00:00:00
              TRACK 02 AUDIO
                INDEX 00 02:00:00
            """.trimIndent()
        )!!
        assertEquals(listOf(0L, 120_000L), cue.tracks.map { it.startMs })
        assertEquals(120_000L, cue.tracks[0].endMs)
    }
}
