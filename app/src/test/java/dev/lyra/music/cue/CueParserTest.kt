package dev.lyra.music.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CueParserTest {

    private val sheet = """
        REM GENRE "Pop"
        REM DATE 2004
        PERFORMER "周杰伦"
        TITLE "七里香"
        FILE "周杰伦 - 七里香.flac" WAVE
          TRACK 01 AUDIO
            TITLE "我的地盘"
            PERFORMER "周杰伦"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "七里香"
            INDEX 00 03:58:50
            INDEX 01 04:00:37
          TRACK 03 AUDIO
            TITLE "借口"
            INDEX 01 09:00:00
    """.trimIndent()

    @Test fun parses_album_and_tracks() {
        val cue = CueParser.parse(sheet)!!
        assertEquals("七里香", cue.title)
        assertEquals("周杰伦", cue.performer)
        assertEquals("Pop", cue.genre)
        assertEquals(2004, cue.year)
        assertEquals(3, cue.tracks.size)
        assertEquals("周杰伦 - 七里香.flac", cue.tracks[0].fileName)
        assertEquals(listOf("我的地盘", "七里香", "借口"), cue.tracks.map { it.title })
    }

    @Test fun start_uses_index01_and_end_is_next_start() {
        val t = CueParser.parse(sheet)!!.tracks
        assertEquals(0L, t[0].startMs)
        // 04:00:37 → 240 s + 37/75 s = 240_493 ms
        assertEquals(240_493L, t[1].startMs)
        assertEquals(240_493L, t[0].endMs)
        assertEquals(540_000L, t[1].endMs)
        assertNull(t[2].endMs)
    }

    @Test fun track_performer_falls_back_to_album() {
        val t = CueParser.parse(sheet)!!.tracks
        assertEquals("周杰伦", t[1].performer)
    }

    @Test fun multiple_files_do_not_chain_end_times() {
        val cue = CueParser.parse(
            """
            FILE "01.wav" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            FILE "02.wav" WAVE
              TRACK 02 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        )!!
        assertEquals(2, cue.files.size)
        assertNull(cue.tracks[0].endMs)
        assertNull(cue.tracks[1].endMs)
    }

    @Test fun unquoted_file_and_non_audio_tracks() {
        val cue = CueParser.parse(
            """
            FILE album.ape WAVE
              TRACK 01 MODE1/2352
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE Unquoted Title
                INDEX 01 01:00:00
            """.trimIndent()
        )!!
        assertEquals(1, cue.tracks.size)
        assertEquals("album.ape", cue.tracks[0].fileName)
        assertEquals("Unquoted Title", cue.tracks[0].title)
        assertEquals(60_000L, cue.tracks[0].startMs)
    }

    @Test fun time_parsing() {
        assertEquals(0L, CueParser.parseTime("00:00:00"))
        assertEquals(1_000L, CueParser.parseTime("00:01:00"))
        assertEquals(6_000_000L, CueParser.parseTime("100:00:00"))
        assertEquals(987L, CueParser.parseTime("00:00:74"))
        assertNull(CueParser.parseTime("00:00:75"))
        assertNull(CueParser.parseTime("00:61:00"))
        assertNull(CueParser.parseTime("garbage"))
    }

    @Test fun garbage_returns_null() {
        assertNull(CueParser.parse("this is not a cue sheet"))
        assertNull(CueParser.parse(""))
        assertNotNull(CueParser.parse("﻿" + sheet))
    }
}
