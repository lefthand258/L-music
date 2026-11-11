package dev.lyra.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable UI snapshot of the player. */
data class PlayerState(
    val isConnected: Boolean = false,
    val currentItem: MediaItem? = null,
    val currentIndex: Int = C.INDEX_UNSET,
    val queue: List<MediaItem> = emptyList(),
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
) {
    val hasItem get() = currentItem != null
    val songId get() = currentItem?.songId
}

/**
 * Process-wide [MediaController] wrapper. The controller lives as long as the app process does;
 * the UI only ever reads [state] and calls the command methods here.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        scope.launch { connect() }
    }

    private suspend fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }
            .getOrElse { e ->
                _state.update { it.copy(error = e.message) }
                return
            }
        controller = c
        c.addListener(listener)
        refreshAll()
        _state.update { it.copy(isConnected = true) }
        // Progress ticker – cheap, UI only redraws when the value changes.
        scope.launch {
            while (isActive) {
                val ctl = controller
                if (ctl != null && ctl.isPlaying) {
                    _state.update {
                        it.copy(positionMs = ctl.currentPosition, bufferedMs = ctl.bufferedPosition)
                    }
                }
                delay(250)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_PLAYER_ERROR,
                )
            ) refreshAll()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.errorCodeName) }
        }
    }

    private fun refreshAll() {
        val c = controller ?: return
        val count = c.mediaItemCount
        val queue = ArrayList<MediaItem>(count)
        for (i in 0 until count) queue += c.getMediaItemAt(i)
        _state.update {
            it.copy(
                currentItem = c.currentMediaItem,
                currentIndex = c.currentMediaItemIndex,
                queue = queue,
                isPlaying = c.isPlaying,
                playWhenReady = c.playWhenReady,
                playbackState = c.playbackState,
                positionMs = c.currentPosition.coerceAtLeast(0),
                bufferedMs = c.bufferedPosition,
                durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L,
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                error = if (c.playerError == null) null else it.error,
            )
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(0, songs.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<SongEntity>) {
        val c = controller ?: return
        val items = songs.map { it.toMediaItem() }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    fun addToQueue(songs: List<SongEntity>) {
        val c = controller ?: return
        val items = songs.map { it.toMediaItem() }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(items)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun play() = controller?.play()
    fun pause() = controller?.pause()
    fun next() = controller?.seekToNextMediaItem()
    fun previous() {
        val c = controller ?: return
        // Standard behaviour: restart if > 3s in, else go to previous track.
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs.coerceAtLeast(0))
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE))
    }

    fun skipToQueueItem(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = controller?.clearMediaItems()

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Restore the last saved queue without starting playback (used on cold start). */
    fun restoreQueue(items: List<MediaItem>, index: Int, positionMs: Long, shuffle: Boolean, repeat: Int) {
        val c = controller ?: return
        if (c.mediaItemCount > 0 || items.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        c.repeatMode = repeat
        c.setMediaItems(items, index.coerceIn(0, items.lastIndex), positionMs)
        c.prepare()
        c.pause()
    }
}
