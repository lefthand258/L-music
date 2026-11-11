package dev.lyra.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast first-pass scan using MediaStore. Produces [SongEntity] rows with whatever metadata
 * MediaStore already indexed; the slower [TagReader] pass later enriches them.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Options(
        val minDurationMs: Long = 30_000,
        val blacklistedFolders: List<String> = emptyList(),
    )

    suspend fun scan(options: Options = Options()): List<SongEntity> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val api30 = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        val projection = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            if (api30) MediaStore.Audio.Media.BITRATE else null,
            if (api30) MediaStore.Audio.Media.GENRE else null,
            if (api30) MediaStore.Audio.Media.DISC_NUMBER else null,
            if (api30) MediaStore.Audio.Media.NUM_TRACKS else null,
        ).toTypedArray()
        // IS_MUSIC is unreliable for some vendors; filter by duration and MIME instead.
        val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'"
        val args = arrayOf(options.minDurationMs.toString())

        val result = ArrayList<SongEntity>(2048)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iArtistId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val iComposer = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iBitrate = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            val iGenre = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            val iDisc = c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
            val iNumTracks = c.getColumnIndex(MediaStore.Audio.Media.NUM_TRACKS)

            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val folder = File(path).parent ?: continue
                if (options.blacklistedFolders.any { folder == it || folder.startsWith("$it/") }) continue

                val id = c.getLong(iId)
                val uri: Uri = ContentUris.withAppendedId(collection, id)
                val rawTitle = c.getString(iTitle)
                val fileName = c.getString(iName) ?: File(path).name
                val title = rawTitle?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')
                val artist = c.getString(iArtist).normalizeUnknown()
                val album = c.getString(iAlbum).normalizeUnknown()
                val albumArtist = c.getString(iAlbumArtist).normalizeUnknown().ifBlank { artist }
                val (disc, track) = parseTrack(c.getIntOrNull(iTrack), c.getIntOrNull(iDisc))
                val genre = c.getStringOrNull(iGenre)?.takeIf { it.isNotBlank() }

                result += SongEntity(
                    mediaStoreId = id,
                    path = path,
                    uri = uri.toString(),
                    fileName = fileName,
                    folderPath = folder,
                    fileSize = c.getLong(iSize),
                    dateAdded = c.getLong(iAdded) * 1000,
                    dateModified = c.getLong(iMod) * 1000,
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    albumId = c.getLong(iAlbumId),
                    artistId = c.getLong(iArtistId),
                    composer = c.getString(iComposer)?.takeIf { it.isNotBlank() },
                    genre = genre,
                    year = c.getIntOrNull(iYear)?.takeIf { it > 0 },
                    trackNumber = track,
                    trackTotal = c.getIntOrNull(iNumTracks)?.takeIf { it > 0 },
                    discNumber = disc,
                    durationMs = c.getLong(iDuration),
                    bitrateKbps = c.getIntOrNull(iBitrate)?.let { it / 1000 }?.takeIf { it > 0 },
                    mimeType = c.getString(iMime),
                    needsMetadata = rawTitle.isNullOrBlank() || artist.isBlank() || album.isBlank(),
                )
            }
        }
        result
    }

    private fun String?.normalizeUnknown(): String {
        if (this == null) return ""
        val t = trim()
        return if (t.equals(MediaStore.UNKNOWN_STRING, ignoreCase = true) || t.equals("<unknown>", true)) "" else t
    }

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (index < 0 || isNull(index)) null else getInt(index)

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    /** MediaStore packs disc*1000+track into TRACK on many devices. */
    private fun parseTrack(track: Int?, disc: Int?): Pair<Int?, Int?> {
        if (track == null || track <= 0) return disc to null
        return if (track >= 1000) (disc ?: (track / 1000)) to (track % 1000) else disc to track
    }
}
