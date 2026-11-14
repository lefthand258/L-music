package dev.lyra.music.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.LyraDatabase
import dev.lyra.music.data.db.MetadataOverrideEntity
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.db.withLibraryIds
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.scanner.TagWriter
import dev.lyra.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ApplyResult(
    val song: SongEntity,
    /** null when writing to the file was not attempted (setting off / CUE track). */
    val fileWrite: TagWriter.Result?,
    /** false when a cover was requested but could not be downloaded or decoded. */
    val coverOk: Boolean,
)

sealed interface BatchState {
    data object Idle : BatchState
    data class Running(val done: Int, val total: Int, val updated: Int) : BatchState
    data class Finished(val checked: Int, val updated: Int) : BatchState
}

/**
 * Finds song information and cover art online and applies it: always to Lyra's database, and
 * to the audio file's tags when "write tags back" is on and the format is supported.
 */
@Singleton
class MetadataService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: SafeHttp,
    private val db: LyraDatabase,
    private val library: LibraryRepository,
    private val tagWriter: TagWriter,
    private val prefs: UserPrefs,
    private val player: PlayerConnection,
    private val lyricsSearch: LyricsSearchService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: Job? = null
    private val _batch = MutableStateFlow<BatchState>(BatchState.Idle)
    val batch: StateFlow<BatchState> = _batch.asStateFlow()

    fun queryFor(song: SongEntity): LyricsQuery = lyricsSearch.queryFor(song)

    suspend fun search(query: LyricsQuery, sources: Set<OnlineSource> = OnlineSource.METADATA.toSet()): List<MetadataCandidate> =
        withContext(Dispatchers.IO) {
            if (query.title.isBlank()) return@withContext emptyList()
            val all = supervisorScope {
                sources.map { src ->
                    async {
                        withTimeoutOrNull(25_000) { runCatching { searchOne(src, query) }.getOrDefault(emptyList()) } ?: emptyList()
                    }
                }.awaitAll().flatten()
            }
            all.map { it.copy(score = MatchScorer.score(query, it.title, it.artist, it.durationMs)) }
                .sortedByDescending { it.score }
        }

    private fun searchOne(source: OnlineSource, q: LyricsQuery): List<MetadataCandidate> {
        val keywords = listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" ").take(120)
        return when (source) {
            OnlineSource.NETEASE -> http.getText(
                "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
                    .addQueryParameter("s", keywords).addQueryParameter("type", "1")
                    .addQueryParameter("limit", "10").addQueryParameter("offset", "0").build(),
                referer = "https://music.163.com/",
            )?.let(MetadataParsers::netease).orEmpty()
            OnlineSource.QQ -> http.getText(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
                    .addQueryParameter("w", keywords).addQueryParameter("format", "json")
                    .addQueryParameter("p", "1").addQueryParameter("n", "10").build(),
                referer = "https://y.qq.com/",
            )?.let(MetadataParsers::qq).orEmpty()
            OnlineSource.MUSICBRAINZ -> http.getText(
                "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
                    .addQueryParameter("query", MetadataParsers.musicBrainzQuery(q.title, q.artist))
                    .addQueryParameter("fmt", "json").addQueryParameter("limit", "8").build(),
            )?.let(MetadataParsers::musicBrainz).orEmpty()
            OnlineSource.LRCLIB -> emptyList()
        }
    }

    /** Applies the chosen [fields] of [candidate] to [song]. */
    suspend fun apply(song: SongEntity, candidate: MetadataCandidate, fields: Set<MetaField>, source: String = candidate.source.name): ApplyResult =
        withContext(Dispatchers.IO) {
            val before = song.tags()
            val after = MetadataMerge.merge(before, candidate, fields)

            // Cover: download → decode in the platform decoder → re-encode as a clean JPEG.
            var cover: CleanCover? = null
            var coverOk = true
            if (MetaField.COVER in fields && candidate.coverUrl != null) {
                cover = http.getImage(candidate.coverUrl)?.let(::sanitizeCover)
                coverOk = cover != null
            }
            var coverPath = song.externalArtPath
            if (cover != null) {
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val target = File(dir, "${song.id}_${System.currentTimeMillis()}.jpg")
                target.writeBytes(cover.jpeg)
                song.externalArtPath?.takeIf { it.startsWith(dir.absolutePath) }?.let { runCatching { File(it).delete() } }
                coverPath = target.absolutePath
            }

            // Remember what the file said before the first change, so it can be reviewed / undone.
            recordOverrides(song.id, before, after, source, candidate.score)

            var write: TagWriter.Result? = null
            var updated = song.copy(
                title = after.title, artist = after.artist, album = after.album, albumArtist = after.albumArtist,
                year = after.year, genre = after.genre, externalArtPath = coverPath,
            )
            if (song.cueIndex == 0 && prefs.writeTagsToFile.first() && (after != before || cover != null)) {
                write = tagWriter.writeTags(song.path, after, cover?.jpeg, cover?.width ?: 0, cover?.height ?: 0)
                if (write == TagWriter.Result.WRITTEN) {
                    val f = File(song.path)
                    updated = updated.copy(
                        dateModified = f.lastModified() / 1000 * 1000,
                        fileSize = f.length(),
                        hasEmbeddedArt = song.hasEmbeddedArt || cover != null,
                    )
                    // Let Android's own media index (and other players) see the new tags.
                    runCatching { MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null) }
                }
            }
            updated = updated.copy(
                needsMetadata = updated.title.isBlank() || updated.artist.isBlank() || updated.album.isBlank() ||
                    (!updated.hasEmbeddedArt && updated.externalArtPath == null),
            ).withLibraryIds()
            library.updateSong(updated)
            withContext(Dispatchers.Main) { player.refreshSong(updated) }
            ApplyResult(updated, write, coverOk)
        }

    // ---- Automatic completion of missing information ------------------------------------------

    /**
     * Goes through songs that lack artist / album / cover and fills in only what is missing,
     * and only from a confident match. Existing information is never overwritten here.
     */
    fun startBatch() {
        if (batchJob?.isActive == true) return
        batchJob = scope.launch {
            val songs = library.getSongsNeedingMetadata()
            var updated = 0
            _batch.value = BatchState.Running(0, songs.size, 0)
            for ((i, song) in songs.withIndex()) {
                if (!isActive) break
                runCatching { if (autoFill(song)) updated++ }
                _batch.value = BatchState.Running(i + 1, songs.size, updated)
                delay(1_100) // MusicBrainz asks for at most one request per second
            }
            _batch.value = BatchState.Finished(songs.size, updated)
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _batch.value = BatchState.Idle
    }

    private suspend fun autoFill(song: SongEntity): Boolean {
        val best = search(queryFor(song)).firstOrNull() ?: return false
        if (best.score < MatchScorer.AUTO_APPLY) return false
        val hasCover = song.hasEmbeddedArt || song.externalArtPath != null
        val fields = MetadataMerge.missingFields(song.tags(), hasCover, best)
        if (fields.isEmpty()) return false
        val r = apply(song, best, fields, source = "auto:" + best.source.name)
        return r.song != song
    }

    // ---- helpers ------------------------------------------------------------------------------

    private suspend fun recordOverrides(songId: Long, before: SongTags, after: SongTags, source: String, score: Float) {
        val dao = db.metadataOverrideDao()
        suspend fun rec(field: String, old: String?, new: String?) {
            if (old == new) return
            val existing = dao.get(songId, field)
            dao.upsert(
                MetadataOverrideEntity(
                    id = existing?.id ?: 0, songId = songId, field = field,
                    originalValue = if (existing != null) existing.originalValue else old,
                    newValue = new, source = source, confidence = score,
                )
            )
        }
        rec("title", before.title, after.title)
        rec("artist", before.artist, after.artist)
        rec("album", before.album, after.album)
        rec("albumArtist", before.albumArtist, after.albumArtist)
        rec("year", before.year?.toString(), after.year?.toString())
        rec("genre", before.genre, after.genre)
    }

    private class CleanCover(val jpeg: ByteArray, val width: Int, val height: Int)

    private fun sanitizeCover(bytes: ByteArray): CleanCover? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > 8000 || h > 8000) return null
        var sample = 1
        while (maxOf(w, h) / sample > 2400) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_COVER_PX) {
            val scale = MAX_COVER_PX.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val out = ByteArrayOutputStream()
        // Re-encoding keeps pixels only: whatever else was in the downloaded file is discarded.
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) return null
        CleanCover(out.toByteArray(), bmp.width, bmp.height).also { bmp.recycle() }
    }.getOrNull()

    private companion object {
        const val MAX_COVER_PX = 1200
    }
}

fun SongEntity.tags() = SongTags(title, artist, album, albumArtist, year, genre)
