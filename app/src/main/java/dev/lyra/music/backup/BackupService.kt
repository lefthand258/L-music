package dev.lyra.music.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.BuildConfig
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.MetadataOverrideEntity
import dev.lyra.music.data.db.PlayHistoryEntity
import dev.lyra.music.data.db.PlaylistEntity
import dev.lyra.music.data.db.SongGenreEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.GenreRepository
import dev.lyra.music.playlistio.LibraryMatcher
import dev.lyra.music.playlistio.SongKey
import dev.lyra.music.playlistio.TrackRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class BackupTrack(val path: String, val title: String, val artist: String, val album: String, val durationMs: Long)
@Serializable data class BackupPlaylist(val name: String, val description: String? = null, val tracks: List<BackupTrack>)
@Serializable data class BackupHistory(val track: BackupTrack, val playedAt: Long, val listenedMs: Long)
@Serializable data class BackupOverride(val track: BackupTrack, val field: String, val originalValue: String?, val newValue: String?, val source: String)
@Serializable data class BackupSongGenres(val track: BackupTrack, val genres: List<String>)
@Serializable data class BackupSongState(val track: BackupTrack, val favourite: Boolean, val playCount: Int, val lastPlayed: Long?, val lyricsOffsetMs: Long)
@Serializable data class BackupSettings(
    val writeTagsToFile: Boolean, val onlineMetadataMode: Int, val autoOrganise: Boolean, val organiseTemplate: String,
    val dynamicColor: Boolean, val themeMode: Int, val floatingLyrics: Boolean, val minDurationMs: Long,
)
@Serializable data class BackupLyrics(val track: BackupTrack, val text: String)
@Serializable data class BackupManifest(val format: Int = 1, val app: String, val createdAt: Long, val songs: Int)

data class RestoreReport(
    val playlists: Int, val playlistTracksMatched: Int, val playlistTracksMissing: Int,
    val favourites: Int, val history: Int, val genres: Int, val settings: Boolean, val lyrics: Int = 0,
)

/**
 * Backup = one zip of JSON files: playlists, favourites / play counts, history, metadata
 * corrections, genre tags, settings. Songs are referenced by path and by title/artist/album so a
 * backup restores onto a re-scanned or re-arranged library. The archive is read strictly: only
 * the expected entry names, each size-capped, parsed with unknown fields ignored.
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LyraDatabase,
    private val prefs: UserPrefs,
    private val genres: GenreRepository,
    private val lyricsRepo: dev.lyra.music.lyrics.LyricsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    suspend fun export(target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val songs = db.songDao().getAll()
            val byId = songs.associateBy { it.id }
            fun ref(id: Long): BackupTrack? = byId[id]?.let { BackupTrack(it.path, it.title, it.artist, it.album, it.durationMs) }

            val playlists = db.playlistDao().getAll().map { p ->
                BackupPlaylist(p.name, p.description, db.playlistDao().getSongs(p.id).map { BackupTrack(it.path, it.title, it.artist, it.album, it.durationMs) })
            }
            val states = songs.filter { it.isFavourite || it.playCount > 0 || it.lyricsOffsetMs != 0L }
                .map { BackupSongState(BackupTrack(it.path, it.title, it.artist, it.album, it.durationMs), it.isFavourite, it.playCount, it.lastPlayed, it.lyricsOffsetMs) }
            val history = db.historyDao().all().mapNotNull { h -> ref(h.songId)?.let { BackupHistory(it, h.playedAt, h.listenedMs) } }
            val overrides = db.metadataOverrideDao().all().mapNotNull { o -> ref(o.songId)?.let { BackupOverride(it, o.field, o.originalValue, o.newValue, o.source) } }
            val genreList = db.genreDao().getAll().map { it.name }
            val songGenres = songs.mapNotNull { s ->
                val g = db.genreDao().forSong(s.id)
                if (g.isEmpty()) null else BackupSongGenres(BackupTrack(s.path, s.title, s.artist, s.album, s.durationMs), g.map { it.name })
            }
            val lyrics = lyricsRepo.savedSongIds().mapNotNull { id ->
                val s = byId[id] ?: return@mapNotNull null
                lyricsRepo.savedText(s)?.let { BackupLyrics(BackupTrack(s.path, s.title, s.artist, s.album, s.durationMs), it) }
            }
            val settings = BackupSettings(
                prefs.writeTagsToFile.first(), prefs.onlineMetadataMode.first(), prefs.autoOrganise.first(), prefs.organiseTemplate.first(),
                prefs.dynamicColor.first(), prefs.themeMode.first(), prefs.floatingLyrics.first(), prefs.minDurationMs.first(),
            )

            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                ZipOutputStream(out).use { zip ->
                    fun put(name: String, text: String) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                    put("manifest.json", json.encodeToString(BackupManifest(app = "Lyra ${BuildConfig.VERSION_NAME}", createdAt = System.currentTimeMillis(), songs = songs.size)))
                    put("playlists.json", json.encodeToString(playlists))
                    put("song_state.json", json.encodeToString(states))
                    put("history.json", json.encodeToString(history))
                    put("overrides.json", json.encodeToString(overrides))
                    put("genres.json", json.encodeToString(genreList))
                    put("song_genres.json", json.encodeToString(songGenres))
                    put("settings.json", json.encodeToString(settings))
                    put("lyrics.json", json.encodeToString(lyrics))
                    // Playlists again as plain M3U8 so any other player can read them without Lyra.
                    for (p in playlists) {
                        put("m3u/${dev.lyra.music.organize.OrganizeTemplate.safeSegment(p.name).ifBlank { "playlist" }}.m3u8",
                            dev.lyra.music.playlistio.PlaylistIO.writeM3u(p.name, p.tracks.map { TrackRef(it.title, it.artist, it.album, it.durationMs, it.path) }))
                    }
                }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    suspend fun restore(source: Uri): RestoreReport? = withContext(Dispatchers.IO) {
        runCatching {
            val entries = HashMap<String, String>()
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var e: ZipEntry? = zip.nextEntry
                    var total = 0L
                    while (e != null) {
                        val name = e.name
                        if (name in WANTED) {
                            val bytes = readCapped(zip, MAX_ENTRY_BYTES) ?: return@runCatching null
                            total += bytes.size
                            if (total > MAX_TOTAL_BYTES) return@runCatching null
                            entries[name] = String(bytes, Charsets.UTF_8)
                        }
                        zip.closeEntry(); e = zip.nextEntry
                    }
                }
            } ?: return@runCatching null
            if ("manifest.json" !in entries) return@runCatching null

            val songs = db.songDao().getAll()
            val matcher = LibraryMatcher(songs.map { SongKey(it.id, it.path, it.title, it.artist, it.album) })
            fun find(t: BackupTrack): Long? = matcher.match(TrackRef(t.title, t.artist, t.album, t.durationMs, t.path))?.songId

            var playlistsMade = 0; var matched = 0; var missing = 0
            entries["playlists.json"]?.let { txt ->
                for (p in json.decodeFromString<List<BackupPlaylist>>(txt)) {
                    val ids = p.tracks.mapNotNull { t -> find(t).also { if (it == null) missing++ else matched++ } }
                    if (ids.isEmpty()) continue
                    val existing = db.playlistDao().getByName(p.name)
                    val id = existing?.id ?: db.playlistDao().insert(PlaylistEntity(name = p.name.take(120), description = p.description)).also { playlistsMade++ }
                    db.playlistDao().addSongs(id, ids)
                }
            }
            var favs = 0
            entries["song_state.json"]?.let { txt ->
                for (st in json.decodeFromString<List<BackupSongState>>(txt)) {
                    val id = find(st.track) ?: continue
                    val s = db.songDao().getById(id) ?: continue
                    db.songDao().update(
                        s.copy(
                            isFavourite = s.isFavourite || st.favourite,
                            playCount = maxOf(s.playCount, st.playCount),
                            lastPlayed = listOfNotNull(s.lastPlayed, st.lastPlayed).maxOrNull(),
                            lyricsOffsetMs = if (s.lyricsOffsetMs == 0L) st.lyricsOffsetMs else s.lyricsOffsetMs,
                        )
                    )
                    if (st.favourite) favs++
                }
            }
            var hist = 0
            entries["history.json"]?.let { txt ->
                val existing = db.historyDao().all().mapTo(HashSet()) { it.songId to it.playedAt }
                for (h in json.decodeFromString<List<BackupHistory>>(txt).take(20_000)) {
                    val id = find(h.track) ?: continue
                    if ((id to h.playedAt) in existing) continue
                    db.historyDao().insert(PlayHistoryEntity(songId = id, playedAt = h.playedAt, listenedMs = h.listenedMs)); hist++
                }
            }
            entries["overrides.json"]?.let { txt ->
                for (o in json.decodeFromString<List<BackupOverride>>(txt).take(50_000)) {
                    val id = find(o.track) ?: continue
                    if (db.metadataOverrideDao().get(id, o.field) != null) continue
                    db.metadataOverrideDao().upsert(MetadataOverrideEntity(songId = id, field = o.field, originalValue = o.originalValue, newValue = o.newValue, source = o.source))
                }
            }
            var genreLinks = 0
            entries["genres.json"]?.let { txt -> json.decodeFromString<List<String>>(txt).take(500).forEach { genres.create(it) } }
            entries["song_genres.json"]?.let { txt ->
                for (sg in json.decodeFromString<List<BackupSongGenres>>(txt)) {
                    val id = find(sg.track) ?: continue
                    val gids = sg.genres.take(20).mapNotNull { genres.create(it)?.id }.toSet()
                    if (gids.isNotEmpty()) { genres.addToSongs(listOf(id), gids); genreLinks++ }
                }
            }
            var lyricsRestored = 0
            entries["lyrics.json"]?.let { txt ->
                for (l in json.decodeFromString<List<BackupLyrics>>(txt).take(50_000)) {
                    val id = find(l.track) ?: continue
                    val s = db.songDao().getById(id) ?: continue
                    if (lyricsRepo.savedText(s) != null) continue
                    lyricsRepo.save(s, l.text.take(64 * 1024)); lyricsRestored++
                }
            }
            val settingsOk = entries["settings.json"]?.let { txt ->
                val s = json.decodeFromString<BackupSettings>(txt)
                prefs.setWriteTagsToFile(s.writeTagsToFile); prefs.setOnlineMetadataMode(s.onlineMetadataMode.coerceIn(0, 2))
                prefs.setAutoOrganise(s.autoOrganise); prefs.setOrganiseTemplate(s.organiseTemplate.take(300))
                prefs.setDynamicColor(s.dynamicColor); prefs.setThemeMode(s.themeMode.coerceIn(0, 2))
                prefs.setFloatingLyrics(s.floatingLyrics); prefs.setMinDurationMs(s.minDurationMs.coerceIn(0, 600_000))
                true
            } ?: false
            RestoreReport(playlistsMade, matched, missing, favs, hist, genreLinks, settingsOk, lyricsRestored)
        }.getOrNull()
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
        val WANTED = setOf("lyrics.json", "manifest.json", "playlists.json", "song_state.json", "history.json", "overrides.json", "genres.json", "song_genres.json", "settings.json")
    }
}
