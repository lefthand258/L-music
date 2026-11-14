package dev.lyra.music.ui.playlist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.PlaylistEntity
import dev.lyra.music.data.db.PlaylistWithCount
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.DeleteOutcome
import dev.lyra.music.data.repo.FileDeleter
import dev.lyra.music.data.repo.ImportReport
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.repo.PlaylistRepository
import dev.lyra.music.util.TextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SmartList { FAVOURITES, RECENT, MOST_PLAYED }

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val deleter: FileDeleter,
) : ViewModel() {

    val all: StateFlow<List<PlaylistWithCount>> = playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlists.observePlaylist(id)
    fun songs(id: Long): Flow<List<SongEntity>> = playlists.observeSongs(id)
    fun smart(kind: SmartList): Flow<List<SongEntity>> = when (kind) {
        SmartList.FAVOURITES -> library.observeFavourites()
        SmartList.RECENT -> library.observeRecentlyPlayed(200)
        SmartList.MOST_PLAYED -> library.observeMostPlayed(200)
    }

    fun create(name: String, thenAdd: List<Long> = emptyList()) = viewModelScope.launch {
        val id = playlists.create(name)
        if (thenAdd.isNotEmpty()) playlists.addSongs(id, thenAdd)
    }
    fun addSongs(playlistId: Long, songIds: List<Long>) = viewModelScope.launch { playlists.addSongs(playlistId, songIds) }
    fun removeSong(playlistId: Long, songId: Long) = viewModelScope.launch { playlists.removeSongs(playlistId, listOf(songId)) }
    fun reorder(id: Long, orderedIds: List<Long>) = viewModelScope.launch { playlists.reorder(id, orderedIds) }
    fun rename(id: Long, name: String) = viewModelScope.launch { playlists.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { playlists.delete(id) }

    // ---- File deletion ----------------------------------------------------------------------

    suspend fun deleteFiles(songs: List<SongEntity>): DeleteOutcome = deleter.delete(songs)
    suspend fun onDeleteConfirmedBySystem(paths: List<String>): DeleteOutcome = deleter.onUserConfirmed(paths)

    // ---- Import / export through the system file picker -------------------------------------

    /** Writes the playlist as M3U8 (UTF-8) to the document the user picked. */
    suspend fun exportM3uTo(playlistId: Long, target: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = playlists.exportM3u(playlistId) ?: return@withContext false
        writeText(target, text)
    }

    /** Writes playlists as a TuneMyMusic-compatible CSV. */
    suspend fun exportCsvTo(playlistIds: List<Long>?, target: Uri): Boolean = withContext(Dispatchers.IO) {
        writeText(target, playlists.exportCsv(playlistIds))
    }

    suspend fun importFrom(source: Uri): ImportReport? = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName(source) ?: "Imported"
            val bytes = context.contentResolver.openInputStream(source)?.use { input ->
                // Playlist files are small; refuse anything unreasonably large.
                val buf = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                if (buf.size > MAX_IMPORT_BYTES) null else buf
            } ?: return@runCatching null
            playlists.import(name, TextDecoder.decode(bytes))
        }.getOrNull()
    }

    private fun writeText(target: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }
}
