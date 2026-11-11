package dev.lyra.music.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.AlbumRow
import dev.lyra.music.data.db.ArtistRow
import dev.lyra.music.data.db.FolderRow
import dev.lyra.music.data.db.GenreRow
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.scanner.MediaStoreScanner
import dev.lyra.music.data.scanner.TagReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class SongSort { TITLE, DATE_ADDED, ARTIST, DURATION, PLAY_COUNT }

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val phase: String, val done: Int, val total: Int) : ScanState
    data class Finished(val songCount: Int, val at: Long) : ScanState
    data class Failed(val message: String) : ScanState
}

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LyraDatabase,
    private val mediaStoreScanner: MediaStoreScanner,
    private val tagReader: TagReader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val songDao get() = db.songDao()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    fun observeSongs(sort: SongSort): Flow<List<SongEntity>> = when (sort) {
        SongSort.TITLE -> songDao.observeAllByTitle()
        SongSort.DATE_ADDED -> songDao.observeAllByDateAdded()
        SongSort.ARTIST -> songDao.observeAllByArtist()
        SongSort.DURATION -> songDao.observeAllByDuration()
        SongSort.PLAY_COUNT -> songDao.observeAllByPlayCount()
    }

    fun observeCount(): Flow<Int> = songDao.observeCount()
    fun observeSong(id: Long): Flow<SongEntity?> = songDao.observeById(id)
    fun observeAlbums(): Flow<List<AlbumRow>> = songDao.observeAlbums()
    fun observeArtists(): Flow<List<ArtistRow>> = songDao.observeArtists()
    fun observeFolders(): Flow<List<FolderRow>> = songDao.observeFolders()
    fun observeGenres(): Flow<List<GenreRow>> = songDao.observeGenres()
    fun observeAlbumSongs(albumId: Long) = songDao.observeByAlbum(albumId)
    fun observeArtistSongs(artistId: Long) = songDao.observeByArtist(artistId)
    fun observeFolderSongs(folder: String) = songDao.observeByFolder(folder)
    fun observeGenreSongs(genre: String) = songDao.observeByGenre(genre)
    fun observeFavourites() = songDao.observeFavourites()
    fun observeRecentlyPlayed(limit: Int = 100) = songDao.observeRecentlyPlayed(limit)
    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query.trim())

    suspend fun getSong(id: Long): SongEntity? = songDao.getById(id)
    suspend fun getSongs(ids: List<Long>): List<SongEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = songDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
    suspend fun getAllSongs(): List<SongEntity> = songDao.getAll()

    suspend fun setFavourite(id: Long, fav: Boolean) = songDao.setFavourite(id, fav)
    suspend fun recordPlayed(id: Long) = songDao.incrementPlayCount(id, System.currentTimeMillis())

    /** "Remove from Lyra": hide only. */
    suspend fun hideSongs(ids: List<Long>) = songDao.setHidden(ids, true)

    suspend fun setGenre(ids: List<Long>, genre: String?) = songDao.setGenre(ids, genre)

    /** Kick off a scan in the background. Safe to call repeatedly. */
    fun requestScan(fullTags: Boolean = true) {
        scope.launch { scan(fullTags) }
    }

    suspend fun scan(fullTags: Boolean = true) {
        if (!scanMutex.tryLock()) return
        try {
            _scanState.value = ScanState.Scanning("mediastore", 0, 0)
            val blacklist = db.settingsDao().blacklist()
            val scanned = mediaStoreScanner.scan(MediaStoreScanner.Options(blacklistedFolders = blacklist))

            // Remove rows whose files vanished.
            val knownPaths = songDao.getAllScanKeys()
            val scannedPaths = scanned.mapTo(HashSet()) { it.path }
            val gone = knownPaths.filter { it.path !in scannedPaths && !File(it.path).exists() }.map { it.path }
            if (gone.isNotEmpty()) songDao.deleteByPaths(gone)

            // Attach sidecar covers (folder.jpg etc.) cheaply by folder.
            val coverByFolder = HashMap<String, String?>()
            val withCovers = scanned.map { s ->
                val cover = coverByFolder.getOrPut(s.folderPath) { findFolderCover(s.folderPath) }
                if (cover != null) s.copy(externalArtPath = cover) else s
            }
            songDao.mergeScanned(withCovers)

            if (fullTags) {
                val toRead = songDao.getAll().filter { !it.tagsFullyScanned }
                val total = toRead.size
                var done = 0
                val batch = ArrayList<SongEntity>(64)
                for (song in toRead) {
                    tagReader.read(song.path)?.let { batch += tagReader.merge(song, it) }
                    done++
                    if (batch.size >= 64) {
                        songDao.updateAll(batch); batch.clear()
                        _scanState.value = ScanState.Scanning("tags", done, total)
                    }
                }
                if (batch.isNotEmpty()) songDao.updateAll(batch)
            }
            val count = songDao.getAll().size
            _scanState.value = ScanState.Finished(count, System.currentTimeMillis())
        } catch (t: Throwable) {
            _scanState.value = ScanState.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            scanMutex.unlock()
        }
    }

    private fun findFolderCover(folder: String): String? {
        val dir = File(folder)
        if (!dir.isDirectory) return null
        for (name in COVER_NAMES) {
            val f = File(dir, name)
            if (f.isFile && f.length() in 1..MAX_COVER_BYTES) return f.absolutePath
        }
        return null
    }

    companion object {
        private const val MAX_COVER_BYTES = 15L * 1024 * 1024
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png",
            "album.jpg", "album.png", "Cover.jpg", "Folder.jpg", "AlbumArt.jpg", "AlbumArtSmall.jpg",
        )
    }
}
