package dev.lyra.music.util

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import dev.lyra.music.data.db.SongEntity
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File

/**
 * Coil model describing where to find a song's cover. Resolution order:
 * 1. embedded picture inside the audio file,
 * 2. sidecar image in the folder (cover.jpg…),
 * 3. MediaStore album thumbnail.
 */
data class CoverArt(
    val path: String,
    val externalArtPath: String?,
    val mediaStoreAlbumId: Long,
    val version: Long,
) {
    companion object {
        fun of(song: SongEntity) = CoverArt(
            path = song.path,
            externalArtPath = song.externalArtPath,
            mediaStoreAlbumId = song.albumId,
            version = song.dateModified,
        )
    }
}

class CoverArtKeyer : Keyer<CoverArt> {
    override fun key(data: CoverArt, options: Options): String =
        "cover:${data.path}:${data.version}:${data.externalArtPath ?: ""}"
}

class CoverArtFetcher(
    private val data: CoverArt,
    private val options: Options,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        embedded()?.let { return it }
        external()?.let { return it }
        return mediaStore()
    }

    private fun embedded(): FetchResult? {
        val file = File(data.path)
        if (!file.isFile) return null
        val bytes = runCatching {
            MediaMetadataRetriever().useRetriever { r ->
                r.setDataSource(data.path)
                r.embeddedPicture
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val buffer = Buffer().write(bytes)
        return SourceResult(
            source = ImageSource(buffer, context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun external(): FetchResult? {
        val p = data.externalArtPath ?: return null
        val f = File(p)
        if (!f.isFile || f.length() !in 1..MAX_BYTES) return null
        return SourceResult(
            source = ImageSource(f.source().buffer(), context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun mediaStore(): FetchResult? {
        if (data.mediaStoreAlbumId <= 0) return null
        val uri: Uri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"), data.mediaStoreAlbumId
        )
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return SourceResult(
            source = ImageSource(stream.source().buffer(), context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<CoverArt> {
        override fun create(data: CoverArt, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverArtFetcher(data, options, context)
    }

    private companion object {
        const val MAX_BYTES = 15 * 1024 * 1024
    }
}

private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        runCatching { release() }
    }
}
