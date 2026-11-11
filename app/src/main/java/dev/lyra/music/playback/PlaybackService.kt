package dev.lyra.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.lyra.music.MainActivity
import dev.lyra.music.data.repo.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground media service. Owns the single [ExoPlayer] instance and exposes it through a
 * Media3 [MediaSession], which gives us notification controls, lock-screen controls, Bluetooth
 * AVRCP / headset buttons and audio focus handling for free.
 *
 * Audio-quality policy (see docs/REQUIREMENTS.md §0):
 *  - float PCM output so 24-bit sources are never truncated to 16-bit inside the app;
 *  - no AudioProcessors are installed by default – the DSP chain is empty unless the user
 *    explicitly enables EQ / pitch / channel mixing, which is wired in a later sprint;
 *  - the player never applies software volume scaling (system volume only);
 *  - no app-side resampling: the PCM leaves the app at the source sample rate.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var queuePersistence: QueuePersistence

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var playTracker: PlayCountTracker

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer()
        playTracker = PlayCountTracker(player) { songId ->
            scope.launch(Dispatchers.IO) { library.recordPlayed(songId) }
        }
        player.addListener(playTracker)
        player.addListener(queuePersistence.listener(player))

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_PLAYER
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setId("lyra_session")
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
            .setEnableDecoderFallback(true)
            // ON: platform decoders first; an FFmpeg extension renderer (added in a later sprint)
            // picks up the formats the platform cannot decode (APE, DSD, WavPack, TTA, WMA…).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSkipSilenceEnabled(false)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.volume = 1f }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the task is swiped away; stop only when paused.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.launch { queuePersistence.saveNow(player) }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        /**
         * Controllers may hand us items that carry only a mediaId (e.g. Bluetooth / system UI
         * restoring a queue). Resolve them against the library so every item has a playable URI.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(mediaItems)
            }
            return scope.future(Dispatchers.IO) {
                val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
                val songs = library.getSongs(ids).associateBy { it.id }
                mediaItems.map { item ->
                    if (item.localConfiguration != null) item
                    else songs[item.mediaId.toLongOrNull()]?.toMediaItem() ?: item
                }.toMutableList()
            }
        }

        /** Called when the system wants to resume playback (e.g. headset button after process death). */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return scope.future(Dispatchers.IO) {
                val restored = queuePersistence.restore()
                if (restored == null) {
                    MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L)
                } else {
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(restored.items), restored.startIndex, restored.startPositionMs
                    )
                }
            }
        }
    }
}

/** Counts a play once a track has been heard for ≥ 30 s or ≥ 50 % of its length. */
private class PlayCountTracker(
    private val player: Player,
    private val onPlayed: (songId: Long) -> Unit,
) : Player.Listener {
    private var currentId: Long? = null
    private var maxPositionMs = 0L
    private var counted = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        currentId = mediaItem?.mediaId?.toLongOrNull()
        maxPositionMs = 0
        counted = false
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (counted) return
        val id = currentId ?: return
        val pos = player.currentPosition
        if (pos > maxPositionMs) maxPositionMs = pos
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        if (maxPositionMs >= 30_000 || maxPositionMs >= duration / 2) {
            counted = true
            onPlayed(id)
        }
    }
}
