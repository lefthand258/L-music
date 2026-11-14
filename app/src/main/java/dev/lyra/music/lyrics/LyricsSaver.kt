package dev.lyra.music.lyrics

import android.content.Context
import android.media.MediaScannerConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.scanner.TagWriter
import dev.lyra.music.ui.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where a saved set of lyrics ended up. */
data class LyricsSaveOutcome(
    /** null = embedding was not attempted (write-back off, or a CUE track). */
    val embedded: TagWriter.Result?,
    /** true = a same-name .lrc file was written next to the audio file. */
    val sidecar: Boolean,
    /** Why the sidecar was not written, when it was wanted. */
    val sidecarSkippedNoPermission: Boolean,
) {
    /** True when another player opening the file (or its folder) will see the lyrics. */
    val travelsWithFile: Boolean get() = embedded == TagWriter.Result.WRITTEN || sidecar
}

sealed interface WriteAllState {
    data object Idle : WriteAllState
    data class Running(val done: Int, val total: Int) : WriteAllState
    data class Finished(val embedded: Int, val sidecars: Int, val notWritten: Int) : WriteAllState
}

/**
 * Saves lyrics so they travel with the song: into Lyra, into the file's own lyrics tag
 * (mp3 / flac / m4a / ogg) and, when enabled, as a same-name `.lrc` next to the file – the one
 * format practically every player and car head unit reads.
 */
@Singleton
class LyricsSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: LyricsRepository,
    private val tagWriter: TagWriter,
    private val prefs: UserPrefs,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _writeAll = MutableStateFlow<WriteAllState>(WriteAllState.Idle)
    val writeAll: StateFlow<WriteAllState> = _writeAll.asStateFlow()

    suspend fun save(song: SongEntity, text: String): LyricsSaveOutcome = withContext(Dispatchers.IO) {
        repo.save(song, text)
        toFiles(song, text)
    }

    private suspend fun toFiles(song: SongEntity, text: String): LyricsSaveOutcome {
        val writeBack = prefs.writeTagsToFile.first()
        val embedded = if (song.cueIndex == 0 && writeBack) tagWriter.writeLyrics(song.path, text) else null
        var sidecar = false
        var noPerm = false
        if (prefs.lyricsSidecar.first()) {
            if (!Permissions.hasAllFiles()) noPerm = true
            else sidecar = writeSidecar(song, text)
        }
        if (embedded == TagWriter.Result.WRITTEN || sidecar) {
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null) }
        }
        return LyricsSaveOutcome(embedded, sidecar, noPerm)
    }

    /** Writes `<same name>.lrc` (or `NN - Title.lrc` for a CUE track) as UTF-8 with BOM. */
    private fun writeSidecar(song: SongEntity, text: String): Boolean = runCatching {
        val audio = File(song.path)
        val dir = audio.parentFile ?: return false
        val name = if (song.cueIndex > 0) {
            "%02d - %s.lrc".format(song.cueIndex, song.title.replace(Regex("""[\\/:*?"<>|]"""), "_"))
        } else audio.nameWithoutExtension + ".lrc"
        val target = File(dir, name)
        val tmp = File(dir, ".$name.tmp")
        // BOM so Windows players do not mistake UTF-8 Chinese for GBK.
        tmp.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        if (target.exists()) target.delete()
        tmp.renameTo(target) || run { tmp.delete(); false }
    }.getOrDefault(false)

    /** Writes every lyrics text saved inside Lyra out to the songs' files. */
    fun startWriteAll() {
        if (job?.isActive == true) return
        job = scope.launch {
            val saved = repo.savedSongIds()
            var embedded = 0; var sidecars = 0; var none = 0
            _writeAll.value = WriteAllState.Running(0, saved.size)
            for ((i, id) in saved.withIndex()) {
                if (!isActive) break
                val song = library.getSong(id)
                val text = song?.let { repo.savedText(it) }
                if (song == null || text == null) { none++ } else {
                    val o = toFiles(song, text)
                    if (o.embedded == TagWriter.Result.WRITTEN) embedded++
                    if (o.sidecar) sidecars++
                    if (!o.travelsWithFile) none++
                }
                _writeAll.value = WriteAllState.Running(i + 1, saved.size)
            }
            _writeAll.value = WriteAllState.Finished(embedded, sidecars, none)
        }
    }
}
