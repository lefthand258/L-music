package dev.lyra.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsTest {

    @Test fun lrclib_search_parses_and_skips_empty() {
        val body = """
        [{"id":1,"name":"Song","trackName":"Song","artistName":"Band","albumName":"Alb","duration":200.5,
          "instrumental":false,"hasWordSync":false,"plainLyrics":"line one\nline two","syncedLyrics":"[00:01.00]line one\n[00:05.00]line two","extra":1},
         {"id":2,"trackName":"Plain","artistName":"B","albumName":"","duration":100,"plainLyrics":"just text","syncedLyrics":null},
         {"id":3,"trackName":"Inst","artistName":"B","duration":100,"instrumental":true,"plainLyrics":null,"syncedLyrics":null},
         "garbage", {"trackName":"no id"}]
        """.trimIndent()
        val r = OnlineParsers.lrclibSearch(body)
        assertEquals(2, r.size)
        assertEquals(OnlineSource.LRCLIB, r[0].source)
        assertEquals("1", r[0].id); assertEquals("Band", r[0].artist); assertEquals(200_500L, r[0].durationMs)
        assertEquals(true, r[0].synced); assertTrue(r[0].inlineLyrics!!.startsWith("[00:01.00]"))
        assertEquals(false, r[1].synced); assertEquals("just text", r[1].inlineLyrics)
    }

    @Test fun netease_search_and_lyric() {
        val body = """{"result":{"songs":[
            {"id":186016,"name":"晴天","dt":269000,"ar":[{"id":1,"name":"周杰伦"},{"name":"某某"}],"al":{"name":"叶惠美","picUrl":"http://x/y.jpg"}},
            {"id":"bad id","name":"x"},
            {"id":5,"name":"旧格式","duration":1000,"artists":[{"name":"A"}],"album":{"name":"B"}}
        ],"songCount":2},"code":200}"""
        val r = OnlineParsers.neteaseSearch(body)
        assertEquals(2, r.size)
        assertEquals("186016", r[0].id); assertEquals("周杰伦 / 某某", r[0].artist)
        assertEquals("叶惠美", r[0].album); assertEquals(269_000L, r[0].durationMs)
        assertEquals("A", r[1].artist); assertEquals("B", r[1].album)

        val l = OnlineParsers.neteaseLyric("""{"lrc":{"version":1,"lyric":"[00:01.000]甲\n"},"tlyric":{"version":0,"lyric":""},"code":200}""")!!
        assertEquals("[00:01.000]甲\n", l.original); assertNull(l.translation)
        assertNull(OnlineParsers.neteaseLyric("""{"code":200,"nolyric":true}"""))
        // Encrypted "abroad" payloads and garbage are rejected, not trusted.
        assertTrue(OnlineParsers.neteaseSearch("""{"result":"a1b2c3","abroad":true,"code":200}""").isEmpty())
        assertTrue(OnlineParsers.neteaseSearch("<html>").isEmpty())
    }

    @Test fun qq_search_and_lyric_variants() {
        val body = """callback({"code":0,"data":{"song":{"list":[
            {"songmid":"0039MnYb0qxYhV","songname":"晴天","singer":[{"name":"周杰伦"}],"albumname":"叶惠美","albummid":"000MkMni19ClKG","interval":269},
            {"songmid":"../evil","songname":"x"}
        ]}}})"""
        val r = OnlineParsers.qqSearch(body)
        assertEquals(1, r.size)
        assertEquals("0039MnYb0qxYhV", r[0].id); assertEquals(269_000L, r[0].durationMs)

        val plain = OnlineParsers.qqLyric("""{"retcode":0,"lyric":"[00:01.00]It&apos;s &#38; ok","trans":""}""")!!
        assertEquals("[00:01.00]It's & ok", plain.original); assertNull(plain.translation)
        val b64 = java.util.Base64.getEncoder().encodeToString("[00:01.00]甲乙\n[00:02.00]丙".toByteArray())
        assertEquals("[00:01.00]甲乙\n[00:02.00]丙", OnlineParsers.qqLyric("""{"retcode":0,"lyric":"$b64"}""")!!.original)
        assertNull(OnlineParsers.qqLyric("""{"retcode":-1310,"code":-1310,"subcode":-1310}"""))
    }

    private fun cand(title: String, artist: String = "", dur: Long = 0) =
        LyricsCandidate(OnlineSource.LRCLIB, "1", title, artist, "", dur, true)

    @Test fun scoring_exact_match_auto_applies() {
        val q = LyricsQuery("晴天", "周杰伦", "叶惠美", 269_000)
        assertTrue(MatchScorer.score(q, cand("晴天", "周杰伦", 269_500)) >= MatchScorer.AUTO_APPLY)
        assertTrue(MatchScorer.score(q, cand("晴天", "周杰伦 / 某某", 268_000)) >= MatchScorer.AUTO_APPLY)
    }

    @Test fun scoring_rejects_covers_wrong_length_and_weak_evidence() {
        val q = LyricsQuery("晴天", "周杰伦", "", 269_000)
        assertTrue(MatchScorer.score(q, cand("晴天 (原唱 周杰伦)", "RyaVocal", 270_738)) < MatchScorer.AUTO_APPLY)
        assertTrue(MatchScorer.score(q, cand("晴天 (Live)", "周杰伦", 330_000)) < MatchScorer.AUTO_APPLY)
        assertTrue(MatchScorer.score(q, cand("阴天", "周杰伦", 269_000)) < MatchScorer.AUTO_APPLY)
        // Title only – never enough to apply without asking.
        assertTrue(MatchScorer.score(LyricsQuery("晴天"), cand("晴天", "周杰伦", 269_000)) < MatchScorer.AUTO_APPLY)
        assertTrue(MatchScorer.score(LyricsQuery("Hello", durationMs = 200_000), cand("Hello", "X", 200_000)) < MatchScorer.AUTO_APPLY)
    }

    @Test fun ranking_orders_best_first() {
        val q = LyricsQuery("Hello", "Adele", "", 295_000)
        val ranked = MatchScorer.rank(q, listOf(cand("Hello", "Lionel Richie", 250_000), cand("Hello", "Adele", 295_000), cand("Hello (Live)", "Adele", 400_000)))
        assertEquals("Adele", ranked[0].artist); assertEquals(295_000L, ranked[0].durationMs)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test fun similarity_helpers() {
        assertEquals(1f, MatchScorer.similarity("Ｈｅｌｌｏ, World!", "hello world"), 0f)
        assertEquals(0.9f, MatchScorer.similarity("Song (Remastered 2011)", "Song"), 0f)
        assertEquals(0f, MatchScorer.similarity("", "x"), 0f)
        assertEquals(0.95f, MatchScorer.artistSimilarity("A feat. B", "B, C"), 0f)
    }

    @Test fun filename_guess() {
        assertEquals("晴天" to "周杰伦", FileNameGuess.guess("周杰伦 - 晴天.flac"))
        assertEquals("Song Name" to "", FileNameGuess.guess("03. Song_Name.mp3"))
        assertEquals("Title" to "Artist", FileNameGuess.guess("12 - Artist - Title.ape"))
        assertEquals("noext" to "", FileNameGuess.guess("noext"))
    }
}
