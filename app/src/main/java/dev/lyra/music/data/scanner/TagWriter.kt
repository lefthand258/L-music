package dev.lyra.music.data.scanner

import dev.lyra.music.online.SongTags
import dev.lyra.music.ui.permissions.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Writes metadata back into audio files so other players see it too. Only the tag area is
 * rewritten; after writing, the file is re-read and its audio length compared with the value
 * from before, so a write that damaged the file is at least reported.
 */
@Singleton
class TagWriter @Inject constructor() {

    init {
        // Use jaudiotagger's Android artwork implementation (no java.awt / ImageIO).
        TagOptionSingleton.getInstance().isAndroid = true
    }

    enum class Result { WRITTEN, SKIPPED_NO_PERMISSION, SKIPPED_UNSUPPORTED, FAILED }

    suspend fun writeLyrics(path: String, lyrics: String): Result = withContext(Dispatchers.IO) {
        if (!Permissions.hasAllFiles()) return@withContext Result.SKIPPED_NO_PERMISSION
        val file = File(path)
        if (file.extension.lowercase() !in WRITABLE || !file.canWrite()) return@withContext Result.SKIPPED_UNSUPPORTED
        runCatching {
            val audio = AudioFileIO.read(file)
            val before = audio.audioHeader.trackLength
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.LYRICS, lyrics)
            audio.commit()
            val after = AudioFileIO.read(file).audioHeader.trackLength
            if (abs(after - before) <= 1) Result.WRITTEN else Result.FAILED
        }.getOrDefault(Result.FAILED)
    }

    /**
     * Writes text tags and, when [coverJpeg] is given, replaces the embedded front cover.
     * [coverJpeg] must already be a clean JPEG produced by Lyra's own re-encoding.
     */
    suspend fun writeTags(path: String, tags: SongTags, coverJpeg: ByteArray?, coverWidth: Int = 0, coverHeight: Int = 0): Result =
        withContext(Dispatchers.IO) {
            if (!Permissions.hasAllFiles()) return@withContext Result.SKIPPED_NO_PERMISSION
            val file = File(path)
            if (file.extension.lowercase() !in WRITABLE || !file.canWrite()) return@withContext Result.SKIPPED_UNSUPPORTED
            runCatching {
                val audio = AudioFileIO.read(file)
                val before = audio.audioHeader.trackLength
                val tag = audio.tagOrCreateAndSetDefault
                fun set(key: FieldKey, value: String?) {
                    if (!value.isNullOrBlank()) runCatching { tag.setField(key, value) }
                }
                set(FieldKey.TITLE, tags.title)
                set(FieldKey.ARTIST, tags.artist)
                set(FieldKey.ALBUM, tags.album)
                set(FieldKey.ALBUM_ARTIST, tags.albumArtist)
                set(FieldKey.YEAR, tags.year?.toString())
                set(FieldKey.GENRE, tags.genre)
                if (coverJpeg != null) {
                    val art = ArtworkFactory.getNew()
                    art.binaryData = coverJpeg
                    art.mimeType = "image/jpeg"
                    art.description = ""
                    art.pictureType = 3 // front cover
                    art.width = coverWidth
                    art.height = coverHeight
                    runCatching { tag.deleteArtworkField() }
                    tag.setField(art)
                }
                audio.commit()
                val after = AudioFileIO.read(file).audioHeader.trackLength
                if (abs(after - before) <= 1) Result.WRITTEN else Result.FAILED
            }.getOrDefault(Result.FAILED)
        }

    private companion object {
        /** Formats whose tag writing in jaudiotagger is mature. */
        val WRITABLE = setOf("mp3", "flac", "m4a", "ogg")
    }
}
