package dev.lyra.music.data.scanner

import dev.lyra.music.cue.CueParser
import dev.lyra.music.cue.CueSheet
import dev.lyra.music.cue.CueTrack
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.util.TextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces whole-album audio files that have a CUE sheet next to them with one row per track.
 *
 * Reading `.cue` files needs "All files access" on Android 11+ (they are not media files); when
 * the permission is missing the folder listing simply returns nothing and songs stay unsplit.
 */
@Singleton
class CueScanner @Inject constructor() {

    suspend fun expand(songs: List<SongEntity>): List<SongEntity> = withContext(Dispatchers.IO) {
        val byFolder = songs.groupBy { it.folderPath }
        val replaced = HashMap<String, List<SongEntity>>() // audio path -> track rows

        for ((folder, folderSongs) in byFolder) {
            val cues = runCatching {
                File(folder).listFiles { f -> f.isFile && f.name.endsWith(".cue", ignoreCase = true) }
            }.getOrNull() ?: continue
            for (cueFile in cues) {
                if (cueFile.length() !in 1..MAX_CUE_BYTES) continue
                val sheet = runCatching { CueParser.parse(TextDecoder.decode(cueFile.readBytes())) }.getOrNull()
                    ?: continue
                for ((fileName, tracks) in sheet.files) {
                    val song = resolve(fileName, cueFile, folderSongs) ?: continue
                    if (song.path in replaced) continue
                    // A sheet describing a single track starting at 0 adds nothing.
                    if (tracks.size == 1 && tracks[0].startMs == 0L) continue
                    replaced[song.path] = buildTracks(song, sheet, tracks)
                }
            }
        }
        if (replaced.isEmpty()) return@withContext songs
        songs.flatMap { replaced[it.path] ?: listOf(it) }
    }

    /** Pure function, unit-tested: turns one audio file + its CUE tracks into library rows. */
    fun buildTracks(song: SongEntity, sheet: CueSheet, tracks: List<CueTrack>): List<SongEntity> {
        val total = tracks.size
        return tracks.mapIndexedNotNull { i, t ->
            val end = t.endMs ?: song.durationMs
            val duration = end - t.startMs
            if (duration <= 0) return@mapIndexedNotNull null
            val number = if (t.number > 0) t.number else i + 1
            song.copy(
                id = 0,
                cueIndex = number,
                cueStartMs = t.startMs,
                cueEndMs = t.endMs,
                title = t.title ?: "Track %02d".format(number),
                artist = t.performer ?: song.artist,
                album = sheet.title ?: song.album,
                albumArtist = sheet.performer ?: song.albumArtist,
                composer = t.songwriter ?: song.composer,
                genre = sheet.genre ?: song.genre,
                year = sheet.year ?: song.year,
                trackNumber = number,
                trackTotal = total,
                durationMs = duration,
                needsMetadata = t.title == null,
            )
        }
    }

    private fun resolve(fileName: String, cueFile: File, folderSongs: List<SongEntity>): SongEntity? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        // 1. exact name, 2. same base name with another extension (sheet says .wav, file is .flac),
        // 3. audio file named like the sheet itself.
        folderSongs.firstOrNull { it.fileName.equals(name, ignoreCase = true) }?.let { return it }
        val base = name.substringBeforeLast('.')
        folderSongs.firstOrNull { it.fileName.substringBeforeLast('.').equals(base, ignoreCase = true) }?.let { return it }
        val cueBase = cueFile.name.substringBeforeLast('.')
        return folderSongs.firstOrNull { it.fileName.substringBeforeLast('.').equals(cueBase, ignoreCase = true) }
    }

    private companion object {
        const val MAX_CUE_BYTES = 1024L * 1024
    }
}
