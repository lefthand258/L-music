package dev.lyra.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class AlbumRow(
    val albumId: Long,
    val album: String,
    val albumArtist: String,
    val year: Int?,
    val songCount: Int,
    val durationMs: Long,
    /** Path of any song in the album, used to load the cover. */
    val sampleSongId: Long,
    val sampleSongPath: String,
    val sampleHasEmbeddedArt: Boolean,
    val sampleExternalArtPath: String?,
)

data class ArtistRow(
    val artistId: Long,
    val artist: String,
    val songCount: Int,
    val albumCount: Int,
    val sampleSongId: Long,
)

data class FolderRow(
    val folderPath: String,
    val songCount: Int,
)

data class GenreRow(
    val genre: String,
    val songCount: Int,
)

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY title COLLATE NOCASE ASC")
    fun observeAllByTitle(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY dateAdded DESC")
    fun observeAllByDateAdded(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY artist COLLATE NOCASE ASC, album COLLATE NOCASE ASC, discNumber ASC, trackNumber ASC")
    fun observeAllByArtist(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY durationMs DESC")
    fun observeAllByDuration(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY playCount DESC, lastPlayed DESC")
    fun observeAllByPlayCount(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND isFavourite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeFavourites(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: Long): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): SongEntity?

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT id, path, dateModified, fileSize FROM songs")
    suspend fun getAllScanKeys(): List<ScanKey>

    @Query("SELECT COUNT(*) FROM songs WHERE isHidden = 0")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM songs WHERE isHidden = 0 AND (
            title LIKE '%' || :q || '%' OR
            artist LIKE '%' || :q || '%' OR
            album LIKE '%' || :q || '%' OR
            fileName LIKE '%' || :q || '%'
        ) ORDER BY title COLLATE NOCASE ASC LIMIT 500
        """
    )
    fun search(q: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC, title COLLATE NOCASE ASC")
    fun observeByAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND artistId = :artistId ORDER BY album COLLATE NOCASE ASC, discNumber ASC, trackNumber ASC")
    fun observeByArtist(artistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND folderPath = :folder ORDER BY fileName COLLATE NOCASE ASC")
    fun observeByFolder(folder: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND genre = :genre ORDER BY title COLLATE NOCASE ASC")
    fun observeByGenre(genre: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT albumId, album, albumArtist, MAX(year) AS year, COUNT(*) AS songCount,
               SUM(durationMs) AS durationMs,
               MIN(id) AS sampleSongId, MIN(path) AS sampleSongPath,
               MAX(hasEmbeddedArt) AS sampleHasEmbeddedArt, MAX(externalArtPath) AS sampleExternalArtPath
        FROM songs WHERE isHidden = 0
        GROUP BY albumId ORDER BY album COLLATE NOCASE ASC
        """
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query(
        """
        SELECT artistId, artist, COUNT(*) AS songCount, COUNT(DISTINCT albumId) AS albumCount,
               MIN(id) AS sampleSongId
        FROM songs WHERE isHidden = 0
        GROUP BY artistId ORDER BY artist COLLATE NOCASE ASC
        """
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query("SELECT folderPath, COUNT(*) AS songCount FROM songs WHERE isHidden = 0 GROUP BY folderPath ORDER BY folderPath COLLATE NOCASE ASC")
    fun observeFolders(): Flow<List<FolderRow>>

    @Query("SELECT IFNULL(genre, '') AS genre, COUNT(*) AS songCount FROM songs WHERE isHidden = 0 GROUP BY genre ORDER BY genre COLLATE NOCASE ASC")
    fun observeGenres(): Flow<List<GenreRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<SongEntity>): List<Long>

    @Upsert
    suspend fun upsert(song: SongEntity): Long

    @Update
    suspend fun update(song: SongEntity)

    @Update
    suspend fun updateAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("UPDATE songs SET isHidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    @Query("UPDATE songs SET isFavourite = :fav WHERE id = :id")
    suspend fun setFavourite(id: Long, fav: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id")
    suspend fun incrementPlayCount(id: Long, now: Long)

    @Query("UPDATE songs SET genre = :genre WHERE id IN (:ids)")
    suspend fun setGenre(ids: List<Long>, genre: String?)

    /**
     * Upsert by [SongEntity.path]: keeps user state (favourites, play counts, overrides) for files
     * that are already known, and inserts the rest.
     */
    @Transaction
    suspend fun mergeScanned(scanned: List<SongEntity>) {
        for (s in scanned) {
            val existing = getByPath(s.path)
            if (existing == null) {
                upsert(s)
            } else {
                update(
                    s.copy(
                        id = existing.id,
                        isFavourite = existing.isFavourite,
                        playCount = existing.playCount,
                        lastPlayed = existing.lastPlayed,
                        isHidden = existing.isHidden,
                        // Keep richer tag data if a full scan already happened and file is unchanged.
                        title = if (existing.tagsFullyScanned && existing.dateModified == s.dateModified) existing.title else s.title,
                        artist = if (existing.tagsFullyScanned && existing.dateModified == s.dateModified) existing.artist else s.artist,
                        album = if (existing.tagsFullyScanned && existing.dateModified == s.dateModified) existing.album else s.album,
                        albumArtist = if (existing.tagsFullyScanned && existing.dateModified == s.dateModified) existing.albumArtist else s.albumArtist,
                        genre = existing.genre ?: s.genre,
                        externalArtPath = existing.externalArtPath ?: s.externalArtPath,
                        tagsFullyScanned = existing.tagsFullyScanned && existing.dateModified == s.dateModified,
                    )
                )
            }
        }
    }
}

data class ScanKey(val id: Long, val path: String, val dateModified: Long, val fileSize: Long)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT s.* FROM songs s INNER JOIN playlist_entries e ON e.songId = s.id
        WHERE e.playlistId = :playlistId AND s.isHidden = 0 ORDER BY e.position ASC
        """
    )
    fun observeSongs(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removeEntries(playlistId: Long, songIds: List<Long>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Transaction
    suspend fun addSongs(playlistId: Long, songIds: List<Long>) {
        var pos = count(playlistId)
        insertEntries(songIds.map { PlaylistEntryEntity(playlistId, it, pos++) })
        touch(playlistId, System.currentTimeMillis())
    }

    @Transaction
    suspend fun reorder(playlistId: Long, orderedSongIds: List<Long>) {
        clearEntries(playlistId)
        insertEntries(orderedSongIds.mapIndexed { i, id -> PlaylistEntryEntity(playlistId, id, i) })
        touch(playlistId, System.currentTimeMillis())
    }

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayHistoryEntity>>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface MetadataOverrideDao {
    @Query("SELECT * FROM metadata_overrides WHERE songId = :songId")
    suspend fun forSong(songId: Long): List<MetadataOverrideEntity>

    @Query("SELECT * FROM metadata_overrides ORDER BY appliedAt DESC")
    fun observeAll(): Flow<List<MetadataOverrideEntity>>

    @Upsert
    suspend fun upsert(override: MetadataOverrideEntity)

    @Query("DELETE FROM metadata_overrides WHERE songId = :songId")
    suspend fun clearForSong(songId: Long)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun items(): List<QueueItemEntity>

    @Query("SELECT * FROM queue_state WHERE id = 0")
    suspend fun state(): QueueStateEntity?

    @Query("DELETE FROM queue_items")
    suspend fun clearItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<QueueItemEntity>)

    @Upsert
    suspend fun saveState(state: QueueStateEntity)

    @Transaction
    suspend fun replaceQueue(songIds: List<Long>, state: QueueStateEntity) {
        clearItems()
        insertItems(songIds.mapIndexed { i, id -> QueueItemEntity(i, id) })
        saveState(state)
    }
}

@Dao
interface SettingsDao {
    @Query("SELECT path FROM blacklisted_folders")
    fun observeBlacklist(): Flow<List<String>>

    @Query("SELECT path FROM blacklisted_folders")
    suspend fun blacklist(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBlacklist(folder: BlacklistedFolderEntity)

    @Query("DELETE FROM blacklisted_folders WHERE path = :path")
    suspend fun removeBlacklist(path: String)

    @Query("SELECT * FROM library_folders ORDER BY addedAt ASC")
    fun observeLibraryFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders")
    suspend fun libraryFolders(): List<LibraryFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addLibraryFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE treeUri = :treeUri")
    suspend fun removeLibraryFolder(treeUri: String)
}
