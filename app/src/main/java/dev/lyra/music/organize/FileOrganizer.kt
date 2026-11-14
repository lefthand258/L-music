package dev.lyra.music.organize

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.MoveLogEntity
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.playback.PlayerConnection
import dev.lyra.music.ui.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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

data class PlannedMove(val song: SongEntity, val from: File, val to: File)

data class OrganizePlan(
    val moves: List<PlannedMove>,
    /** Songs already in the right place. */
    val alreadyInPlace: Int,
    /** Skipped: CUE tracks, files not on a writable volume, target exists, template empty. */
    val skipped: Int,
    val needsPermission: Boolean,
)

sealed interface OrganizeState {
    data object Idle : OrganizeState
    data class Running(val done: Int, val total: Int, val moved: Int, val failed: Int) : OrganizeState
    data class Finished(val moved: Int, val failed: Int) : OrganizeState
}

/**
 * Moves audio files into `Music/<template>` on the same storage volume. Every move is logged
 * and can be undone for 30 days. Sidecar files (.lrc / .cue / cover images with the same base
 * name) travel with the song. Nothing is overwritten: when the target exists the song is skipped.
 */
@Singleton
class FileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LyraDatabase,
    private val prefs: UserPrefs,
    private val player: PlayerConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<OrganizeState>(OrganizeState.Idle)
    val state: StateFlow<OrganizeState> = _state.asStateFlow()

    fun observeRecentMoves(limit: Int = 200): Flow<List<MoveLogEntity>> = db.moveLogDao().observeRecent(limit)

    fun targetFor(song: SongEntity, template: String): File? {
        if (song.cueIndex > 0) return null
        val src = File(song.path)
        val root = musicRootFor(src) ?: return null
        val rel = OrganizeTemplate.relativePath(template, song.fields()) ?: return null
        return File(root, "$rel.${src.extension.lowercase()}")
    }

    suspend fun plan(songs: List<SongEntity>, template: String): OrganizePlan = withContext(Dispatchers.IO) {
        var inPlace = 0
        var skipped = 0
        val moves = ArrayList<PlannedMove>()
        val claimed = HashSet<String>()
        for (s in songs) {
            val src = File(s.path)
            val to = targetFor(s, template)
            if (to == null || !src.isFile) { skipped++; continue }
            if (to.absolutePath == src.absolutePath) { inPlace++; continue }
            if (to.exists() || !claimed.add(to.absolutePath)) { skipped++; continue }
            moves += PlannedMove(s, src, to)
        }
        OrganizePlan(moves, inPlace, skipped, needsPermission = !Permissions.hasAllFiles())
    }

    /** Organises every song in the library with the saved template. */
    fun startAll() {
        if (job?.isActive == true) return
        job = scope.launch {
            val template = prefs.organiseTemplate.first()
            val plan = plan(db.songDao().getAll(), template)
            run(plan)
        }
    }

    fun cancel() { job?.cancel(); _state.value = OrganizeState.Idle }

    /** Used by "auto-organise" after tags change: moves one song, silently. */
    suspend fun organizeOne(song: SongEntity) {
        if (!prefs.autoOrganise.first() || !Permissions.hasAllFiles()) return
        val plan = plan(listOf(song), prefs.organiseTemplate.first())
        plan.moves.firstOrNull()?.let { move(it) }
    }

    private suspend fun run(plan: OrganizePlan) {
        if (plan.needsPermission) { _state.value = OrganizeState.Finished(0, plan.moves.size); return }
        var moved = 0; var failed = 0
        _state.value = OrganizeState.Running(0, plan.moves.size, 0, 0)
        for ((i, m) in plan.moves.withIndex()) {
            if (!scope.isActive) break
            if (move(m)) moved++ else failed++
            _state.value = OrganizeState.Running(i + 1, plan.moves.size, moved, failed)
        }
        db.moveLogDao().purgeOlderThan(System.currentTimeMillis() - UNDO_WINDOW_MS)
        _state.value = OrganizeState.Finished(moved, failed)
    }

    private suspend fun move(m: PlannedMove): Boolean {
        val ok = moveFile(m.from, m.to)
        if (!ok) return false
        for (side in sidecars(m.from)) {
            val target = File(m.to.parentFile, m.to.nameWithoutExtension + "." + side.extension)
            if (!target.exists()) moveFile(side, target)
        }
        relocate(m.song, m.to)
        db.moveLogDao().insert(MoveLogEntity(songId = m.song.id, fromPath = m.from.absolutePath, toPath = m.to.absolutePath))
        return true
    }

    /** Puts a logged move back; the song row follows. */
    suspend fun undo(entry: MoveLogEntity): Boolean = withContext(Dispatchers.IO) {
        val from = File(entry.toPath); val back = File(entry.fromPath)
        if (!from.isFile || back.exists()) return@withContext false
        if (!moveFile(from, back)) return@withContext false
        for (side in sidecars(from)) {
            val target = File(back.parentFile, back.nameWithoutExtension + "." + side.extension)
            if (!target.exists()) moveFile(side, target)
        }
        db.songDao().getById(entry.songId)?.let { relocate(it, back) }
        db.moveLogDao().markUndone(entry.id)
        true
    }

    private suspend fun relocate(song: SongEntity, to: File) {
        db.songDao().updateLocation(song.id, to.absolutePath, Uri.fromFile(to).toString(), to.name, to.parent ?: "")
        val updated = db.songDao().getById(song.id) ?: return
        withContext(Dispatchers.Main) { player.refreshSong(updated) }
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (!from.isFile || to.exists()) return false
        to.parentFile?.mkdirs()
        val ok = runCatching { from.renameTo(to) }.getOrDefault(false) || runCatching {
            // Different mount point: copy then delete, verifying the size first.
            from.copyTo(to, overwrite = false)
            if (to.length() == from.length()) from.delete() else { to.delete(); false }
        }.getOrDefault(false)
        if (ok) runCatching { MediaScannerConnection.scanFile(context, arrayOf(from.absolutePath, to.absolutePath), null, null) }
        return ok
    }

    private fun sidecars(audio: File): List<File> {
        val dir = audio.parentFile ?: return emptyList()
        val base = audio.nameWithoutExtension
        return SIDECAR_EXT.map { File(dir, "$base.$it") }.filter { it.isFile }
    }

    /** `/storage/emulated/0/...` → `/storage/emulated/0/Music`; `/storage/XXXX-XXXX/...` → that card's Music. */
    private fun musicRootFor(file: File): File? {
        val p = file.absolutePath
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (p.startsWith("$primary/")) return File(primary, Environment.DIRECTORY_MUSIC)
        val m = Regex("""^(/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/""").find(p) ?: return null
        return File(m.groupValues[1], Environment.DIRECTORY_MUSIC)
    }

    private fun SongEntity.fields() = OrganizeTemplate.Fields(title, artist, album, albumArtist, trackNumber, discNumber, year, genre, fileName)

    private companion object {
        const val UNDO_WINDOW_MS = 30L * 24 * 3600 * 1000
        val SIDECAR_EXT = listOf("lrc", "LRC", "cue", "txt")
    }
}
