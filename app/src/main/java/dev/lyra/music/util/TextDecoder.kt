package dev.lyra.music.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes text files of unknown encoding (.lrc / .cue files are very often GBK on Chinese
 * systems). Order: BOM → strict UTF-8 → GB18030 (superset of GBK/GB2312) → ISO-8859-1.
 */
object TextDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOMs
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GB18030") }.getOrNull()?.let { gb ->
            strict(bytes, gb)?.let { return it }
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
