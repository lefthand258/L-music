package dev.lyra.music.data.repo

import dev.lyra.music.data.db.GenreEntity
import dev.lyra.music.data.db.GenreRow
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.db.SongGenreEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.scanner.GenreText
import dev.lyra.music.data.scanner.Genres
import dev.lyra.music.data.scanner.TagWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genre tags. Each song may carry several; the list of genres itself belongs to the user
 * (rename / delete / add). `songs.genre` keeps the joined text ("流行 Pop; 古风") so that the
 * value written back into files and shown in other players stays in sync.
 */
@Singleton
class GenreRepository @Inject constructor(
    private val db: LyraDatabase,
    private val prefs: UserPrefs,
    private val tagWriter: TagWriter,
) {
    private val dao get() = db.genreDao()

    fun observeAll(): Flow<List<GenreEntity>> = dao.observeAll()
    fun observeWithCounts(): Flow<List<GenreRow>> = dao.observeWithCounts()
    fun observeGenre(id: Long): Flow<GenreEntity?> = dao.observeById(id)
    fun observeSongs(genreId: Long): Flow<List<SongEntity>> = dao.observeSongs(genreId)
    fun observeForSong(songId: Long): Flow<List<GenreEntity>> = dao.observeForSong(songId)

    /** First run: fill the list with the built-in suggestions. Runs once; deletions are respected afterwards. */
    suspend fun seedIfNeeded() {
        if (prefs.genresSeeded.first()) return
        if (dao.count() == 0) {
            Genres.PICKER.forEachIndexed { i, name -> dao.insert(GenreEntity(name = name, sortOrder = i)) }
        }
        prefs.setGenresSeeded(true)
    }

    /** Links songs whose `genre` text came from file tags / online lookups but has no tag rows yet. */
    suspend fun linkUnlinked() {
        for (row in db.songDao().songsWithUnlinkedGenre()) {
            val names = split(row.genre)
            if (names.isEmpty()) continue
            val ids = names.map { getOrCreate(it).id }
            dao.link(ids.map { SongGenreEntity(row.id, it) })
        }
    }

    suspend fun getOrCreate(name: String): GenreEntity {
        val clean = name.trim().take(MAX_NAME)
        dao.getByName(clean)?.let { return it }
        val id = dao.insert(GenreEntity(name = clean, sortOrder = dao.maxSortOrder() + 1))
        return if (id > 0) GenreEntity(id, clean) else dao.getByName(clean)!!
    }

    suspend fun create(name: String): GenreEntity? {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return null
        return getOrCreate(clean)
    }

    /** Renames a genre everywhere, including the joined text (and files) of every song carrying it. */
    suspend fun rename(id: Long, newName: String) {
        val clean = newName.trim().take(MAX_NAME)
        if (clean.isEmpty()) return
        val clash = dao.getByName(clean)
        if (clash != null && clash.id != id) {
            // Merge into the existing genre.
            val songs = dao.songIdsFor(id)
            dao.link(songs.map { SongGenreEntity(it, clash.id) })
            dao.delete(id)
            songs.forEach { refreshSong(it) }
            return
        }
        dao.rename(id, clean)
        dao.songIdsFor(id).forEach { refreshSong(it) }
    }

    /** Deletes a genre; songs simply lose that tag. */
    suspend fun delete(id: Long) {
        val songs = dao.songIdsFor(id)
        dao.delete(id)
        songs.forEach { refreshSong(it) }
    }

    /** Sets the full tag set of one song. */
    suspend fun setSongGenres(songId: Long, genreIds: Set<Long>) {
        dao.clearSong(songId)
        if (genreIds.isNotEmpty()) dao.link(genreIds.map { SongGenreEntity(songId, it) })
        refreshSong(songId)
    }

    /** Adds tags to several songs at once (keeps their existing tags). */
    suspend fun addToSongs(songIds: List<Long>, genreIds: Set<Long>) {
        for (s in songIds) {
            dao.link(genreIds.map { SongGenreEntity(s, it) })
            refreshSong(s)
        }
    }

    /** Recomputes the joined text and writes it to the file when enabled. */
    private suspend fun refreshSong(songId: Long) {
        val joined = dao.forSong(songId).joinToString("; ") { it.name }.ifBlank { null }
        db.songDao().setGenreString(songId, joined)
        val song = db.songDao().getById(songId) ?: return
        if (song.cueIndex == 0 && prefs.writeTagsToFile.first()) {
            tagWriter.writeGenre(song.path, joined.orEmpty())
        }
    }

    companion object {
        const val MAX_NAME = 40
        fun split(text: String?): List<String> = GenreText.split(text)
    }
}
