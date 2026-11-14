package dev.lyra.music.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizeTemplateTest {
    private val f = OrganizeTemplate.Fields("晴天", "周杰伦", "叶惠美", "周杰伦", 3, 1, 2003, "流行 Pop; 摇滚", "track03.flac")

    @Test fun default_template() {
        assertEquals("周杰伦/叶惠美/03 - 晴天", OrganizeTemplate.relativePath(OrganizeTemplate.DEFAULT, f))
    }

    @Test fun tokens_and_padding() {
        assertEquals("2003/流行 Pop/1-003 晴天", OrganizeTemplate.relativePath("{Year}/{Genre}/{Disc}-{Track:03} {Title}", f))
        assertEquals("track03", OrganizeTemplate.relativePath("{FileName}", f))
        assertEquals("{Nope} x", OrganizeTemplate.relativePath("{Nope} x", f))
    }

    @Test fun missing_values_fall_back() {
        val g = f.copy(title = "", artist = "", albumArtist = "", album = "", trackNumber = null, year = null)
        assertEquals("Unknown Artist/Unknown Album/track03", OrganizeTemplate.relativePath(OrganizeTemplate.DEFAULT, g))
        assertEquals("Unknown Artist", OrganizeTemplate.relativePath("{Artist}", g))
        assertNull(OrganizeTemplate.relativePath("{Year}/{Track}", g))
    }

    @Test fun illegal_characters_are_replaced() {
        val g = f.copy(title = "AC/DC: Live? \"Yes\" <1>", album = "  ..hidden..  ")
        assertEquals("周杰伦/hidden/03 - AC_DC_ Live_ _Yes_ _1_", OrganizeTemplate.relativePath(OrganizeTemplate.DEFAULT, g))
    }

    @Test fun long_segments_are_capped() {
        val g = f.copy(title = "x".repeat(300))
        assertEquals(120, OrganizeTemplate.relativePath("{Title}", g)!!.length)
    }
}
