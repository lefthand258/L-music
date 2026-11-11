package dev.lyra.music.data.repo

import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.PlaylistEntity
import dev.lyra.music.data.db.PlaylistWithCount
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.playlistio.ImportedPlaylist
import dev.lyra.music.playlistio.LibraryMatcher
import dev.lyra.music.playlistio.PlaylistIO
import dev.lyra.music.playlistio.SongKey
import dev.lyra.music.playlistio.TrackRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ImportReport(
    val playlists: Int,
    val matched: Int,
    /** Matched by title only – worth a second look. */
    val weakMatches: Int,
    val unmatched: List<TrackRef>,
)

@Singleton
class PlaylistRepository @Inject constructor(
    private val db: LyraDatabase,
) {
    private val dao get() = db.playlistDao()

    fun observePlaylists(): Flow<List<PlaylistWithCount>> = dao.observeAllWithCounts()
    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = dao.observeById(id)
    fun observeSongs(id: Long): Flow<List<SongEntity>> = dao.observeSongs(id)

    suspend fun create(name: String): Long = dao.insert(PlaylistEntity(name = name.trim().take(MAX_NAME).ifBlank { "Playlist" }))
    suspend fun rename(id: Long, name: String) {
        val n = name.trim().take(MAX_NAME)
        if (n.isNotEmpty()) dao.rename(id, n, System.currentTimeMillis())
    }
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun addSongs(id: Long, songIds: List<Long>) = dao.addSongs(id, songIds)
    suspend fun removeSongs(id: Long, songIds: List<Long>) {
        songIds.chunked(500).forEach { dao.removeEntries(id, it) }
        dao.touch(id, System.currentTimeMillis())
    }
    suspend fun reorder(id: Long, orderedSongIds: List<Long>) = dao.reorder(id, orderedSongIds)

    // ---- Export ---------------------------------------------------------------------------

    suspend fun exportM3u(id: Long): String? = withContext(Dispatchers.IO) {
        val p = dao.getById(id) ?: return@withContext null
        PlaylistIO.writeM3u(p.name, dao.getSongs(id).map { it.toRef() })
    }

    /** One CSV (TuneMyMusic layout) with the given playlists, or all of them when [ids] is null. */
    suspend fun exportCsv(ids: List<Long>? = null): String = withContext(Dispatchers.IO) {
        val playlists = dao.getAll().filter { ids == null || it.id in ids }
        PlaylistIO.writeCsv(playlists.map { p -> ImportedPlaylist(p.name, dao.getSongs(p.id).map { it.toRef() }) })
    }

    // ---- Import ---------------------------------------------------------------------------

    /**
     * Imports an M3U / M3U8 / CSV file. Entries are matched against the local library; tracks
     * that are not on the phone are reported back, never silently dropped.
     */
    suspend fun import(fileName: String, text: String, baseDir: String? = null): ImportReport = withContext(Dispatchers.IO) {
        val fallback = fileName.substringBeforeLast('.').ifBlank { "Imported" }
        val isCsv = fileName.endsWith(".csv", ignoreCase = true) ||
            (!text.trimStart().startsWith("#EXTM3U") && text.lineSequence().firstOrNull()?.count { it == ',' }?.let { it >= 1 } == true &&
                text.lineSequence().firstOrNull()?.contains("track", ignoreCase = true) == true)
        val imported = if (isCsv) PlaylistIO.parseCsv(text, fallback) else listOf(PlaylistIO.parseM3u(text, fallback, baseDir))

        val matcher = LibraryMatcher(db.songDao().getAll().map { SongKey(it.id, it.path, it.title, it.artist, it.album) })
        var matched = 0
        var weak = 0
        var created = 0
        val unmatched = ArrayList<TrackRef>()
        for (pl in imported) {
            if (pl.tracks.isEmpty()) continue
            val ids = ArrayList<Long>(pl.tracks.size)
            for (t in pl.tracks) {
                val m = matcher.match(t)
                if (m == null) unmatched += t else {
                    ids += m.songId; matched++
                    if (m.confidence == LibraryMatcher.Confidence.WEAK) weak++
                }
            }
            if (ids.isEmpty()) continue
            val existing = dao.getByName(pl.name)
            val playlistId = existing?.id ?: dao.insert(PlaylistEntity(name = pl.name.take(MAX_NAME))).also { created++ }
            dao.addSongs(playlistId, ids)
        }
        ImportReport(created, matched, weak, unmatched)
    }

    private fun SongEntity.toRef() = TrackRef(title = title, artist = artist, album = album, durationMs = durationMs, path = path)

    private companion object {
        const val MAX_NAME = 120
    }
}
