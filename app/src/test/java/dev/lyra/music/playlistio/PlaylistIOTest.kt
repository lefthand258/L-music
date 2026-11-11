package dev.lyra.music.playlistio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIOTest {

    @Test fun m3u_extended_roundtrip() {
        val tracks = listOf(
            TrackRef("晴天", "周杰伦", durationMs = 269_000, path = "/storage/emulated/0/Music/晴天.flac"),
            TrackRef("No Artist", durationMs = null, path = "/storage/emulated/0/Music/x.mp3"),
        )
        val text = PlaylistIO.writeM3u("我的最爱", tracks)
        assertTrue(text.startsWith("#EXTM3U\n#PLAYLIST:我的最爱\n"))
        val back = PlaylistIO.parseM3u(text, "fallback")
        assertEquals("我的最爱", back.name)
        assertEquals(2, back.tracks.size)
        assertEquals("晴天", back.tracks[0].title)
        assertEquals("周杰伦", back.tracks[0].artist)
        assertEquals(269_000L, back.tracks[0].durationMs)
        assertEquals("/storage/emulated/0/Music/晴天.flac", back.tracks[0].path)
        assertEquals("No Artist", back.tracks[1].title)
        assertNull(back.tracks[1].durationMs)
    }

    @Test fun m3u_plain_relative_and_windows_paths() {
        val p = PlaylistIO.parseM3u("a/one.mp3\r\n.\\two.flac\r\nC:\\Music\\three.ape\r\n# comment\r\n", "pl", baseDir = "/sdcard/Music/")
        assertEquals("pl", p.name)
        assertEquals(listOf("/sdcard/Music/a/one.mp3", "/sdcard/Music/two.flac", "C:\\Music\\three.ape"), p.tracks.map { it.path })
        assertEquals(listOf("one", "two", "three"), p.tracks.map { it.title })
    }

    @Test fun csv_rows_handle_quotes_commas_newlines() {
        val rows = PlaylistIO.parseCsvRows("a,\"b,c\",\"d \"\"q\"\"\"\r\n\"multi\nline\",,z\n")
        assertEquals(listOf(listOf("a", "b,c", "d \"q\""), listOf("multi\nline", "", "z")), rows)
    }

    @Test fun csv_tunemymusic_roundtrip_groups_by_playlist() {
        val csv = PlaylistIO.writeCsv(
            listOf(
                ImportedPlaylist("Road, Trip", listOf(TrackRef("Song \"A\"", "X, Y", "Alb", isrc = "USABC1234567"))),
                ImportedPlaylist("睡前", listOf(TrackRef("夜曲", "周杰伦", "十一月的萧邦"), TrackRef("安静", "周杰伦"))),
            )
        )
        assertTrue(csv.startsWith("Track name,Artist name,Album,Playlist name,Type,ISRC\r\n"))
        val back = PlaylistIO.parseCsv(csv, "fallback")
        assertEquals(listOf("Road, Trip", "睡前"), back.map { it.name })
        assertEquals("Song \"A\"", back[0].tracks[0].title)
        assertEquals("X, Y", back[0].tracks[0].artist)
        assertEquals("USABC1234567", back[0].tracks[0].isrc)
        assertEquals(2, back[1].tracks.size)
        assertEquals("十一月的萧邦", back[1].tracks[0].album)
    }

    @Test fun csv_foreign_headers_and_no_playlist_column() {
        val csv = "\uFEFFTrack URI,Track Name,Artist Name(s),Album Name,Track Duration (ms)\n" +
            "spotify:track:1,Hello,Adele,25,295000\n,,,,\n"
        val back = PlaylistIO.parseCsv(csv, "Imported")
        assertEquals(1, back.size)
        assertEquals("Imported", back[0].name)
        val t = back[0].tracks.single()
        assertEquals("Hello", t.title); assertEquals("Adele", t.artist); assertEquals("25", t.album)
        assertEquals(295_000L, t.durationMs)
    }

    @Test fun csv_without_title_column_is_rejected() {
        assertTrue(PlaylistIO.parseCsv("foo,bar\n1,2\n", "x").isEmpty())
        assertTrue(PlaylistIO.parseCsv("", "x").isEmpty())
    }

    private val lib = listOf(
        SongKey(1, "/m/周杰伦 - 晴天.flac", "晴天", "周杰伦", "叶惠美"),
        SongKey(2, "/m/live/晴天.mp3", "晴天", "周杰伦", "2004 无与伦比演唱会"),
        SongKey(3, "/m/Hello.mp3", "Hello", "Adele", "25"),
        SongKey(4, "/m/hello2.mp3", "Hello", "Lionel Richie", "Can't Slow Down"),
        SongKey(5, "/m/unique.mp3", "Only One (Remastered)", "Somebody", ""),
        SongKey(6, "/m/duo.mp3", "Duet", "A / B", ""),
    )
    private val matcher = LibraryMatcher(lib)

    @Test fun match_exact_path_then_filename() {
        assertEquals(LibraryMatcher.Match(3, LibraryMatcher.Confidence.EXACT), matcher.match(TrackRef("zzz", path = "/m/Hello.mp3")))
        assertEquals(LibraryMatcher.Match(5, LibraryMatcher.Confidence.STRONG), matcher.match(TrackRef("zzz", path = "D:\\Music\\UNIQUE.mp3")))
    }

    @Test fun match_title_artist_prefers_album() {
        assertEquals(1L, matcher.match(TrackRef("晴天", "周杰伦", "叶惠美"))!!.songId)
        assertEquals(2L, matcher.match(TrackRef("晴天", "周杰伦", "2004 无与伦比演唱会"))!!.songId)
        assertEquals(4L, matcher.match(TrackRef("HELLO", "lionel richie"))!!.songId)
    }

    @Test fun match_is_punctuation_width_and_case_insensitive() {
        assertEquals(5L, matcher.match(TrackRef("only one remastered", "SOMEBODY"))!!.songId)
        assertEquals(3L, matcher.match(TrackRef("Ｈｅｌｌｏ", "Ａｄｅｌｅ"))!!.songId)
    }

    @Test fun match_artist_containment_and_weak_title_only() {
        assertEquals(LibraryMatcher.Confidence.STRONG, matcher.match(TrackRef("Duet", "A"))!!.confidence)
        assertEquals(LibraryMatcher.Match(5, LibraryMatcher.Confidence.WEAK), matcher.match(TrackRef("Only One (Remastered)")))
        // Ambiguous title without artist → no guess.
        assertNull(matcher.match(TrackRef("Hello")))
        assertNull(matcher.match(TrackRef("Hello", "Nobody Known")))
        assertNull(matcher.match(TrackRef("")))
    }
}
