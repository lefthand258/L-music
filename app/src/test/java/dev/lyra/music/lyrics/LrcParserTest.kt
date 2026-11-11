package dev.lyra.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test fun empty_and_null() {
        assertTrue(LrcParser.parse(null).isEmpty)
        assertTrue(LrcParser.parse("   \n ").isEmpty)
    }

    @Test fun standard_lines_sorted_with_meta() {
        val l = LrcParser.parse(
            """
            [ti:晴天]
            [ar:周杰伦]
            [00:12.50]第二句
            [00:01.00]第一句
            [01:02.345]第三句
            """.trimIndent()
        )
        assertTrue(l.synced)
        assertEquals(listOf(1_000L, 12_500L, 62_345L), l.lines.map { it.timeMs })
        assertEquals("第一句", l.lines[0].text)
        assertEquals("晴天", l.meta["ti"])
        assertEquals("周杰伦", l.meta["ar"])
    }

    @Test fun fraction_digit_widths() {
        val l = LrcParser.parse("[00:01.5]a\n[00:02.05]b\n[00:03.005]c\n[00:04]d\n[00:05:50]e")
        assertEquals(listOf(1_500L, 2_050L, 3_005L, 4_000L, 5_500L), l.lines.map { it.timeMs })
    }

    @Test fun multiple_timestamps_on_one_line() {
        val l = LrcParser.parse("[00:10.00][00:30.00][00:50.00]副歌\n[00:20.00]主歌")
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 50_000L), l.lines.map { it.timeMs })
        assertEquals(listOf("副歌", "主歌", "副歌", "副歌"), l.lines.map { it.text })
    }

    @Test fun offset_positive_shows_earlier() {
        val l = LrcParser.parse("[offset:500]\n[00:10.00]a")
        assertEquals(9_500L, l.lines[0].timeMs)
        assertEquals(500L, l.fileOffsetMs)
        val n = LrcParser.parse("[offset:-500]\n[00:10.00]a")
        assertEquals(10_500L, n.lines[0].timeMs)
        val p = LrcParser.parse("[offset:+250]\n[00:00.10]a")
        assertEquals(0L, p.lines[0].timeMs) // clamped
    }

    @Test fun same_timestamp_pairs_as_translation() {
        val l = LrcParser.parse("[00:05.00]Hello world\n[00:05.00]你好世界\n[00:09.00]Bye")
        assertEquals(2, l.lines.size)
        assertEquals("Hello world", l.lines[0].text)
        assertEquals("你好世界", l.lines[0].translation)
        assertNull(l.lines[1].translation)
    }

    @Test fun enhanced_word_tags() {
        val l = LrcParser.parse("[00:10.00]<00:10.00>你 <00:10.50>好 <00:11.20>吗")
        val line = l.lines.single()
        assertEquals("你 好 吗", line.text)
        assertEquals(listOf(10_000L, 10_500L, 11_200L), line.words.map { it.startMs })
        assertEquals("好 ", line.words[1].text)
    }

    @Test fun plain_text_is_unsynced() {
        val l = LrcParser.parse("first line\n\nsecond line")
        assertFalse(l.synced)
        assertEquals(listOf("first line", "second line"), l.lines.map { it.text })
        assertEquals(-1, l.indexAt(5_000))
    }

    @Test fun index_at_binary_search_and_user_offset() {
        val l = LrcParser.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, l.indexAt(9_999))
        assertEquals(0, l.indexAt(10_000))
        assertEquals(0, l.indexAt(19_999))
        assertEquals(1, l.indexAt(20_000))
        assertEquals(2, l.indexAt(999_999))
        assertEquals(1, l.indexAt(19_600, userOffsetMs = 500))
        assertEquals(-1, l.indexAt(10_200, userOffsetMs = -500))
    }

    @Test fun bom_crlf_and_control_chars() {
        val l = LrcParser.parse("﻿[00:01.00]a\u0007b\r\n[00:02.00]c\r\n")
        assertEquals(listOf("ab", "c"), l.lines.map { it.text })
    }

    @Test fun empty_timed_lines_are_kept_as_gaps() {
        val l = LrcParser.parse("[00:01.00]a\n[00:05.00]\n[00:09.00]b")
        assertEquals(3, l.lines.size)
        assertEquals("", l.lines[1].text)
    }

    @Test fun merge_separate_translation() {
        val o = LrcParser.parse("[00:01.00]one\n[00:05.00]two\n[00:09.00]three")
        val t = LrcParser.parse("[00:01.10]一\n[00:09.00]三")
        val m = LrcParser.mergeTranslation(o, t)
        assertEquals(listOf("一", null, "三"), m.lines.map { it.translation })
    }

    @Test fun roundtrip_to_lrc() {
        val src = "[00:05.00]Hello\n[00:05.00]你好\n[01:02.30]Bye"
        val again = LrcParser.parse(LrcParser.parse(src).toLrc())
        assertEquals(LrcParser.parse(src).lines, again.lines)
    }

    @Test fun oversized_input_is_capped() {
        val big = buildString { repeat(20_000) { append("[00:01.00]x\n") } }
        assertTrue(LrcParser.parse(big).lines.size <= 5_000)
    }
}
