package dev.lyra.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.QueueStateEntity
import dev.lyra.music.data.repo.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves the playback queue + position to Room so playback can resume after process death,
 * and restores it on demand. Writes are debounced; the position is refreshed every few seconds
 * while playing.
 */
@Singleton
class QueuePersistence @Inject constructor(
    private val db: LyraDatabase,
    private val library: LibraryRepository,
) {
    data class Restored(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private var ticker: Job? = null

    fun listener(player: Player): Player.Listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = schedule(player, 500)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = schedule(player, 300)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = schedule(player, 300)
        override fun onRepeatModeChanged(repeatMode: Int) = schedule(player, 300)
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker?.cancel()
            if (isPlaying) {
                ticker = scope.launch {
                    while (true) {
                        delay(5_000)
                        saveNow(player)
                    }
                }
            } else {
                schedule(player, 0)
            }
        }
    }

    private fun schedule(player: Player, delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            saveNow(player)
        }
    }

    /** Snapshot must be taken on the player's application thread, then persisted on IO. */
    suspend fun saveNow(player: Player) {
        val snapshot = withContext(Dispatchers.Main.immediate) {
            if (player.mediaItemCount == 0) return@withContext null
            val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }
            Triple(
                ids,
                player.currentMediaItemIndex,
                QueueStateEntity(
                    currentIndex = player.currentMediaItemIndex,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = player.repeatMode,
                )
            )
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching { db.queueDao().replaceQueue(snapshot.first, snapshot.third) }
        }
    }

    suspend fun restore(): Restored? = withContext(Dispatchers.IO) {
        val state = db.queueDao().state() ?: return@withContext null
        val ids = db.queueDao().items().map { it.songId }
        if (ids.isEmpty()) return@withContext null
        val songs = library.getSongs(ids)
        if (songs.isEmpty()) return@withContext null
        val items = songs.map { it.toMediaItem() }
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        Restored(items, index, state.positionMs)
    }

    suspend fun restoredState(): QueueStateEntity? = withContext(Dispatchers.IO) { db.queueDao().state() }
}
