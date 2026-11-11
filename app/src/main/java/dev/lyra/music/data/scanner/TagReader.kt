package dev.lyra.music.data.scanner

import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.db.withLibraryIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second-pass, accurate tag reading with jaudiotagger. Fills in bit depth, sample rate, channels,
 * codec, album artist, disc numbers, lyrics presence and embedded-art presence that MediaStore
 * does not expose reliably.
 */
@Singleton
class TagReader @Inject constructor() {

    init {
        // jaudiotagger is very chatty about malformed tags; keep logcat clean.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    data class Result(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val composer: String?,
        val genre: String?,
        val year: Int?,
        val trackNumber: Int?,
        val trackTotal: Int?,
        val discNumber: Int?,
        val discTotal: Int?,
        val comment: String?,
        val lyrics: String?,
        val durationMs: Long?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val channels: Int?,
        val codec: String?,
        val hasEmbeddedArt: Boolean,
    )

    suspend fun read(path: String): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return@runCatching null
            val audio = AudioFileIO.read(file)
            val header = audio.audioHeader
            val tag: Tag? = audio.tag

            fun str(key: FieldKey): String? = runCatching { tag?.getFirst(key) }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }?.sanitize()

            fun int(key: FieldKey): Int? = str(key)?.let { parseLeadingInt(it) }

            Result(
                title = str(FieldKey.TITLE),
                artist = str(FieldKey.ARTIST),
                album = str(FieldKey.ALBUM),
                albumArtist = str(FieldKey.ALBUM_ARTIST),
                composer = str(FieldKey.COMPOSER),
                genre = str(FieldKey.GENRE)?.let(::mapId3v1Genre),
                year = int(FieldKey.YEAR)?.takeIf { it in 1000..2999 },
                trackNumber = int(FieldKey.TRACK),
                trackTotal = int(FieldKey.TRACK_TOTAL),
                discNumber = int(FieldKey.DISC_NO),
                discTotal = int(FieldKey.DISC_TOTAL),
                comment = str(FieldKey.COMMENT),
                lyrics = str(FieldKey.LYRICS),
                durationMs = runCatching { (header.preciseTrackLength * 1000).toLong() }.getOrNull()
                    ?.takeIf { it > 0 } ?: (header.trackLength * 1000L).takeIf { it > 0 },
                bitrateKbps = runCatching { header.bitRateAsNumber.toInt() }.getOrNull()?.takeIf { it > 0 },
                sampleRateHz = runCatching { header.sampleRateAsNumber }.getOrNull()?.takeIf { it > 0 },
                bitDepth = runCatching { header.bitsPerSample }.getOrNull()?.takeIf { it > 0 },
                channels = runCatching { header.channels?.toIntOrNull() }.getOrNull()
                    ?: runCatching { parseChannels(header.channels) }.getOrNull(),
                codec = runCatching { header.format }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { header.encodingType }.getOrNull(),
                hasEmbeddedArt = runCatching { tag?.firstArtwork != null }.getOrDefault(false),
            )
        }.getOrNull()
    }

    /** Applies a successful [Result] on top of a [SongEntity], preferring file tags over MediaStore. */
    fun merge(song: SongEntity, r: Result): SongEntity = mergeTags(song, r).withLibraryIds()

    private fun mergeTags(song: SongEntity, r: Result): SongEntity {
        if (song.cueIndex > 0) {
            // CUE track: descriptive tags come from the sheet; the file only contributes
            // technical facts, and the length of the last track (which runs to end of file).
            val duration = if (song.cueEndMs == null && r.durationMs != null && song.cueStartMs != null) {
                (r.durationMs - song.cueStartMs).coerceAtLeast(0)
            } else song.durationMs
            return song.copy(
                durationMs = duration,
                bitrateKbps = r.bitrateKbps ?: song.bitrateKbps,
                sampleRateHz = r.sampleRateHz ?: song.sampleRateHz,
                bitDepth = r.bitDepth ?: song.bitDepth,
                channels = r.channels ?: song.channels,
                codec = r.codec ?: song.codec,
                hasEmbeddedArt = r.hasEmbeddedArt,
                genre = song.genre ?: r.genre,
                year = song.year ?: r.year,
                tagsFullyScanned = true,
            )
        }
        val title = r.title ?: song.title
        val artist = r.artist ?: song.artist
        val album = r.album ?: song.album
        val albumArtist = r.albumArtist ?: song.albumArtist.ifBlank { artist }
        return song.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            composer = r.composer ?: song.composer,
            genre = r.genre ?: song.genre,
            year = r.year ?: song.year,
            trackNumber = r.trackNumber ?: song.trackNumber,
            trackTotal = r.trackTotal ?: song.trackTotal,
            discNumber = r.discNumber ?: song.discNumber,
            discTotal = r.discTotal ?: song.discTotal,
            comment = r.comment ?: song.comment,
            durationMs = r.durationMs ?: song.durationMs,
            bitrateKbps = r.bitrateKbps ?: song.bitrateKbps,
            sampleRateHz = r.sampleRateHz ?: song.sampleRateHz,
            bitDepth = r.bitDepth ?: song.bitDepth,
            channels = r.channels ?: song.channels,
            codec = r.codec ?: song.codec,
            hasEmbeddedArt = r.hasEmbeddedArt,
            hasLyrics = r.lyrics != null || song.hasLyrics,
            tagsFullyScanned = true,
            needsMetadata = title.isBlank() || artist.isBlank() || album.isBlank() ||
                (!r.hasEmbeddedArt && song.externalArtPath == null),
        )
    }

    private fun parseLeadingInt(s: String): Int? {
        val digits = s.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun parseChannels(s: String?): Int? = when {
        s == null -> null
        s.contains("mono", true) -> 1
        s.contains("stereo", true) -> 2
        s.contains("joint", true) -> 2
        s.contains("dual", true) -> 2
        else -> s.filter { it.isDigit() }.toIntOrNull()
    }

    /** "(17)" / "17" style ID3v1 numeric genres → names. */
    private fun mapId3v1Genre(g: String): String {
        val n = g.trim().removePrefix("(").removeSuffix(")").toIntOrNull() ?: return g
        return Genres.ID3V1.getOrNull(n) ?: g
    }

    /** Strip control characters that can break UI or file writes. */
    private fun String.sanitize(): String =
        filter { it == '\n' || it == '\t' || it.code >= 0x20 }.take(4096)
}
