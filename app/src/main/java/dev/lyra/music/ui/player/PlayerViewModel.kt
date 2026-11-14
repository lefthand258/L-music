package dev.lyra.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.lyrics.LoadedLyrics
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.lyrics.LyricsRepository
import dev.lyra.music.lyrics.LyricsSaveOutcome
import dev.lyra.music.lyrics.LyricsSaver
import dev.lyra.music.lyrics.LyricsSource
import dev.lyra.music.online.LyricsCandidate
import dev.lyra.music.online.LyricsQuery
import dev.lyra.music.online.LyricsSearchService
import dev.lyra.music.online.OnlineSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import dev.lyra.music.playback.PlayerConnection
import dev.lyra.music.playback.PlayerState
import dev.lyra.music.playback.SleepTimer
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
    private val lyricsSearch: LyricsSearchService,
    private val lyricsSaver: LyricsSaver,
    private val prefs: UserPrefs,
) : ViewModel() {

    val state: StateFlow<PlayerState> = player.state

    /** Full DB row of the current song (for cover, favourite flag, technical info). */
    val currentSong: StateFlow<SongEntity?> = player.state
        .map { it.songId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeSong(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val lyricsReload = MutableStateFlow(0)
    private val autoTried = HashSet<Long>()

    /**
     * Lyrics of the current song; null while loading. Reloads when the file changes or new
     * lyrics were saved. A song without any lyrics is looked up online once per session when
     * the "online identification" setting allows it, and only a confident match is kept.
     */
    val lyrics: StateFlow<LoadedLyrics?> = currentSong
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.dateModified == b?.dateModified }
        .combine(lyricsReload) { song, _ -> song }
        .flatMapLatest { song ->
            flow {
                emit(null)
                if (song == null) return@flow
                val local = lyricsRepository.load(song)
                emit(local)
                if (local.source == LyricsSource.NONE && autoTried.add(song.id) &&
                    lyricsSearch.autoAllowed(prefs.onlineMetadataMode.first())
                ) {
                    val text = lyricsSearch.autoFind(song) ?: return@flow
                    saveLyrics(song, text)
                    emit(lyricsRepository.load(song))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun saveLyrics(song: SongEntity, text: String): LyricsSaveOutcome = lyricsSaver.save(song, text)

    /** Result of the last manual "use these lyrics", shown to the user once. */
    private val _lyricsSaved = MutableStateFlow<LyricsSaveOutcome?>(null)
    val lyricsSaved: StateFlow<LyricsSaveOutcome?> = _lyricsSaved.asStateFlow()
    fun consumeLyricsSaved() { _lyricsSaved.value = null }

    // ---- Manual online search ---------------------------------------------------------------

    data class LyricsSearchUi(
        val searching: Boolean = false,
        val searched: Boolean = false,
        val results: List<LyricsCandidate> = emptyList(),
        val previewOf: LyricsCandidate? = null,
        val previewText: String? = null,
        val loadingPreview: Boolean = false,
        val previewFailed: Boolean = false,
    )

    private val _lyricsSearchUi = MutableStateFlow(LyricsSearchUi())
    val lyricsSearchUi: StateFlow<LyricsSearchUi> = _lyricsSearchUi.asStateFlow()
    private var searchJob: Job? = null

    fun defaultLyricsQuery(): LyricsQuery? = currentSong.value?.let(lyricsSearch::queryFor)

    fun resetLyricsSearch() {
        searchJob?.cancel()
        _lyricsSearchUi.value = LyricsSearchUi()
    }

    fun searchLyrics(title: String, artist: String, sources: Set<OnlineSource>) {
        val song = currentSong.value ?: return
        searchJob?.cancel()
        _lyricsSearchUi.value = LyricsSearchUi(searching = true)
        searchJob = viewModelScope.launch {
            val q = LyricsQuery(title.trim(), artist.trim(), song.album, song.durationMs)
            val results = lyricsSearch.search(q, sources)
            _lyricsSearchUi.value = LyricsSearchUi(searched = true, results = results)
        }
    }

    fun previewLyrics(candidate: LyricsCandidate) {
        searchJob?.cancel()
        _lyricsSearchUi.update { it.copy(previewOf = candidate, previewText = null, loadingPreview = true, previewFailed = false) }
        searchJob = viewModelScope.launch {
            val text = lyricsSearch.fetch(candidate)
            _lyricsSearchUi.update { it.copy(previewText = text, loadingPreview = false, previewFailed = text == null) }
        }
    }

    fun closeLyricsPreview() = _lyricsSearchUi.update { it.copy(previewOf = null, previewText = null, loadingPreview = false, previewFailed = false) }

    /** Saves the previewed lyrics for the current song. */
    fun applyLyricsPreview() {
        val song = currentSong.value ?: return
        val text = _lyricsSearchUi.value.previewText ?: return
        viewModelScope.launch {
            _lyricsSaved.value = saveLyrics(song, text)
            library.setLyricsOffset(song.id, 0)
            lyricsReload.update { it + 1 }
        }
    }

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

    val sleepTimer: StateFlow<SleepTimer> = player.sleepTimer
    fun setSleepTimer(t: SleepTimer) = player.setSleepTimer(t)

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
