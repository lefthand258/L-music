package dev.lyra.music.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.AlbumRow
import dev.lyra.music.data.db.ArtistRow
import dev.lyra.music.data.db.FolderRow
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.db.BlacklistedFolderEntity
import dev.lyra.music.data.db.LibraryFolderEntity
import dev.lyra.music.data.db.PlayHistoryEntity
import dev.lyra.music.data.scanner.CueScanner
import dev.lyra.music.data.scanner.DirectoryScanner
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
    private val cueScanner: CueScanner,
    private val directoryScanner: DirectoryScanner,
    private val genreRepository: dagger.Lazy<GenreRepository>,
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
    fun observeAlbumSongs(albumId: Long) = songDao.observeByAlbum(albumId)
    fun observeArtistSongs(artistId: Long) = songDao.observeByArtist(artistId)
    fun observeFolderSongs(folder: String) = songDao.observeByFolder(folder)
    fun observeFavourites() = songDao.observeFavourites()
    fun observeRecentlyPlayed(limit: Int = 100) = songDao.observeRecentlyPlayed(limit)
    fun observeMostPlayed(limit: Int = 100) = songDao.observeMostPlayed(limit)
    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query.trim())

    suspend fun getSong(id: Long): SongEntity? = songDao.getById(id)
    suspend fun getSongs(ids: List<Long>): List<SongEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, SongEntity>(ids.size)
        ids.distinct().chunked(SQL_CHUNK).forEach { chunk -> songDao.getByIds(chunk).forEach { byId[it.id] = it } }
        return ids.mapNotNull { byId[it] }
    }
    suspend fun getSongsByPath(path: String): List<SongEntity> = songDao.getByPath(path)
    suspend fun updateSong(song: SongEntity) = songDao.update(song)
    suspend fun getSongsNeedingMetadata(): List<SongEntity> = songDao.getNeedingMetadata()
    fun observeNeedingMetadataCount(): Flow<Int> = songDao.observeNeedingMetadataCount()
    suspend fun getAllSongs(): List<SongEntity> = songDao.getAll()

    suspend fun setFavourite(id: Long, fav: Boolean) = songDao.setFavourite(id, fav)
    suspend fun recordPlayed(id: Long) {
        val now = System.currentTimeMillis()
        songDao.incrementPlayCount(id, now)
        val d = songDao.getById(id)?.durationMs ?: 0L
        runCatching { db.historyDao().insert(PlayHistoryEntity(songId = id, playedAt = now, listenedMs = d)) }
    }

    /** Drops library rows for files that were deleted from the phone. */
    suspend fun forgetPaths(paths: List<String>) = paths.distinct().chunked(SQL_CHUNK).forEach { songDao.deleteByPaths(it) }

    /** "Remove from Lyra": hide only. */
    suspend fun hideSongs(ids: List<Long>) = ids.chunked(SQL_CHUNK).forEach { songDao.setHidden(it, true) }

    suspend fun setLyricsOffset(id: Long, offsetMs: Long) = songDao.setLyricsOffset(id, offsetMs)
    suspend fun setHasLyrics(id: Long, has: Boolean) = songDao.setHasLyrics(id, has)


    // ---- Folders -------------------------------------------------------------------------

    fun observeBlacklist(): Flow<List<String>> = db.settingsDao().observeBlacklist()
    fun observeLibraryFolders(): Flow<List<LibraryFolderEntity>> = db.settingsDao().observeLibraryFolders()

    /** Exclude a folder (and its sub-folders) from the library; files are untouched. */
    suspend fun excludeFolder(path: String) {
        db.settingsDao().addBlacklist(BlacklistedFolderEntity(path.trimEnd('/')))
        scan(fullTags = false)
    }

    suspend fun includeFolder(path: String) {
        db.settingsDao().removeBlacklist(path)
        scan()
    }

    /** [path] is the resolved file-system path of the picked tree; [treeUri] is kept as the key. */
    suspend fun addLibraryFolder(treeUri: String, path: String) {
        db.settingsDao().addLibraryFolder(LibraryFolderEntity(treeUri = treeUri, displayName = path))
        scan()
    }

    suspend fun removeLibraryFolder(treeUri: String) {
        db.settingsDao().removeLibraryFolder(treeUri)
        scan(fullTags = false)
    }

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

            // User-added folders: pick up files MediaStore does not know about.
            val extraRoots = db.settingsDao().libraryFolders().map { it.displayName }
            val extra = if (extraRoots.isEmpty()) emptyList() else
                directoryScanner.scan(extraRoots, scanned.mapTo(HashSet()) { it.path }, blacklist)

            // Attach sidecar covers (folder.jpg etc.) cheaply by folder.
            val coverByFolder = HashMap<String, String?>()
            val withCovers = (scanned + extra).map { s ->
                val cover = coverByFolder.getOrPut(s.folderPath) { findFolderCover(s.folderPath) }
                if (cover != null) s.copy(externalArtPath = cover) else s
            }
            // Split whole-album files that have a CUE sheet into per-track rows.
            val expanded = cueScanner.expand(withCovers)

            // Remove rows that no longer correspond to anything on disk: vanished files, and
            // rows whose (path, cueIndex) is obsolete because a CUE sheet appeared or went away.
            val wanted = expanded.mapTo(HashSet()) { it.path to it.cueIndex }
            val scannedPaths = expanded.mapTo(HashSet()) { it.path }
            val stale = songDao.getAllScanKeys().filter { k ->
                if (k.path in scannedPaths) (k.path to k.cueIndex) !in wanted
                else !File(k.path).exists() || blacklist.any { b -> k.path.startsWith("$b/") }
            }.map { it.id }
            stale.chunked(SQL_CHUNK).forEach { songDao.deleteByIds(it) }

            expanded.chunked(SQL_CHUNK).forEach { songDao.mergeScanned(it) }

            if (fullTags) {
                val toRead = songDao.getAll().filter { !it.tagsFullyScanned }
                val total = toRead.size
                var done = 0
                val batch = ArrayList<SongEntity>(64)
                // CUE tracks share one file: read each file's tags only once.
                var lastPath: String? = null
                var lastResult: TagReader.Result? = null
                for (song in toRead.sortedBy { it.path }) {
                    if (song.path != lastPath) {
                        lastPath = song.path
                        lastResult = tagReader.read(song.path)
                    }
                    lastResult?.let { batch += tagReader.merge(song, it) }
                    done++
                    if (batch.size >= 64) {
                        songDao.updateAll(batch); batch.clear()
                        _scanState.value = ScanState.Scanning("tags", done, total)
                    }
                }
                if (batch.isNotEmpty()) songDao.updateAll(batch)
            }
            runCatching { genreRepository.get().seedIfNeeded(); genreRepository.get().linkUnlinked() }
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
        /** SQLite allows at most 999 bound variables per statement on Android 10. */
        private const val SQL_CHUNK = 500
        private const val MAX_COVER_BYTES = 15L * 1024 * 1024
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png",
            "album.jpg", "album.png", "Cover.jpg", "Folder.jpg", "AlbumArt.jpg", "AlbumArtSmall.jpg",
        )
    }
}
