package dev.lyra.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.data.db.AlbumRow
import dev.lyra.music.data.db.ArtistRow
import dev.lyra.music.data.db.FolderRow
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.repo.ScanState
import dev.lyra.music.data.repo.SongSort
import dev.lyra.music.playback.PlayerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val prefs: UserPrefs,
    private val player: PlayerConnection,
) : ViewModel() {

    val sort: StateFlow<SongSort> = prefs.songSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongSort.TITLE)

    val query = MutableStateFlow("")

    val songs: StateFlow<List<SongEntity>> =
        combine(sort, query.debounce(150)) { s, q -> s to q }
            .flatMapLatest { (s, q) -> if (q.isBlank()) library.observeSongs(s) else library.search(q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songCount: StateFlow<Int> = library.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val albums: StateFlow<List<AlbumRow>> = library.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistRow>> = library.observeArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderRow>> = library.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scanState: StateFlow<ScanState> = library.scanState

    fun albumSongs(albumId: Long): Flow<List<SongEntity>> = library.observeAlbumSongs(albumId)
    fun artistSongs(artistId: Long): Flow<List<SongEntity>> = library.observeArtistSongs(artistId)
    fun folderSongs(folder: String): Flow<List<SongEntity>> =
        if (folder.isEmpty()) flowOf(emptyList()) else library.observeFolderSongs(folder)

    fun setSort(s: SongSort) = viewModelScope.launch { prefs.setSongSort(s) }
    fun rescan() = library.requestScan()

    fun play(list: List<SongEntity>, index: Int) = player.playSongs(list, index)
    fun playAll(list: List<SongEntity>) = player.playSongs(list, 0, shuffle = false)
    fun shuffleAll(list: List<SongEntity>) {
        if (list.isEmpty()) return
        player.playSongs(list, (list.indices).random(), shuffle = true)
    }
    fun playNext(song: SongEntity) = player.playNext(listOf(song))
    fun addToQueue(song: SongEntity) = player.addToQueue(listOf(song))
    fun toggleFavourite(song: SongEntity) = viewModelScope.launch { library.setFavourite(song.id, !song.isFavourite) }
    fun hide(song: SongEntity) = viewModelScope.launch { library.hideSongs(listOf(song.id)) }
}
