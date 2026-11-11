package dev.lyra.music.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {
    private val text = "[00:01.00]故事的小黄花\nTITLE \"七里香\""

    @Test fun utf8_plain_and_bom() {
        assertEquals(text, TextDecoder.decode(text.toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, TextDecoder.decode(bom))
    }

    @Test fun gbk_is_detected() {
        assertEquals(text, TextDecoder.decode(text.toByteArray(Charset.forName("GBK"))))
    }

    @Test fun utf16_boms() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, TextDecoder.decode(le))
        assertEquals(text, TextDecoder.decode(be))
    }

    @Test fun empty() = assertEquals("", TextDecoder.decode(ByteArray(0)))
}
