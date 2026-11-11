package dev.lyra.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.lyrics.LoadedLyrics
import dev.lyra.music.lyrics.LyricsRepository
import dev.lyra.music.playback.PlayerConnection
import dev.lyra.music.playback.PlayerState
import dev.lyra.music.playback.QueuePersistence
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val library: LibraryRepository,
    private val queuePersistence: QueuePersistence,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    val state: StateFlow<PlayerState> = player.state

    /** Full DB row of the current song (for cover, favourite flag, technical info). */
    val currentSong: StateFlow<SongEntity?> = player.state
        .map { it.songId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeSong(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Lyrics of the current song; null while loading. Reloads when the file changes. */
    val lyrics: StateFlow<LoadedLyrics?> = currentSong
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.dateModified == b?.dateModified }
        .flatMapLatest { song ->
            flow {
                emit(null)
                if (song != null) emit(lyricsRepository.load(song))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun adjustLyricsOffset(deltaMs: Long) {
        val s = currentSong.value ?: return
        val next = (s.lyricsOffsetMs + deltaMs).coerceIn(-30_000, 30_000)
        viewModelScope.launch { library.setLyricsOffset(s.id, next) }
    }

    init {
        // Cold start: once connected, restore the last queue paused at the saved position.
        viewModelScope.launch {
            player.state.filter { it.isConnected }.first()
            if (player.state.value.queue.isEmpty()) {
                val restored = queuePersistence.restore() ?: return@launch
                val st = queuePersistence.restoredState()
                player.restoreQueue(
                    restored.items, restored.startIndex, restored.startPositionMs,
                    shuffle = st?.shuffleEnabled ?: false, repeat = st?.repeatMode ?: 0,
                )
            }
        }
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun seekBy(ms: Long) = player.seekBy(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun skipToQueueItem(i: Int) = player.skipToQueueItem(i)
    fun removeFromQueue(i: Int) = player.removeFromQueue(i)
    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)
    fun clearQueue() = player.clearQueue()
    fun toggleFavourite() {
        val s = currentSong.value ?: return
        viewModelScope.launch { library.setFavourite(s.id, !s.isFavourite) }
    }
}
