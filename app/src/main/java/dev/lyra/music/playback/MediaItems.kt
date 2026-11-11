package dev.lyra.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.lyra.music.data.db.SongEntity

object Extras {
    const val PATH = "lyra.path"
    const val EXTERNAL_ART = "lyra.externalArt"
    const val DATE_MODIFIED = "lyra.dateModified"
    const val SAMPLE_RATE = "lyra.sampleRate"
    const val BIT_DEPTH = "lyra.bitDepth"
    const val CHANNELS = "lyra.channels"
    const val CODEC = "lyra.codec"
    const val BITRATE = "lyra.bitrate"
    const val HAS_EMBEDDED_ART = "lyra.hasEmbeddedArt"
    const val CUE_START = "lyra.cueStart"
    const val CUE_END = "lyra.cueEnd"
}

fun SongEntity.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(Extras.PATH, path)
        putString(Extras.EXTERNAL_ART, externalArtPath)
        putLong(Extras.DATE_MODIFIED, dateModified)
        sampleRateHz?.let { putInt(Extras.SAMPLE_RATE, it) }
        bitDepth?.let { putInt(Extras.BIT_DEPTH, it) }
        channels?.let { putInt(Extras.CHANNELS, it) }
        bitrateKbps?.let { putInt(Extras.BITRATE, it) }
        codec?.let { putString(Extras.CODEC, it) }
        putBoolean(Extras.HAS_EMBEDDED_ART, hasEmbeddedArt)
        cueStartMs?.let { putLong(Extras.CUE_START, it) }
        cueEndMs?.let { putLong(Extras.CUE_END, it) }
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.ifBlank { null })
        .setAlbumTitle(album.ifBlank { null })
        .setAlbumArtist(albumArtist.ifBlank { null })
        .setComposer(composer)
        .setGenre(genre)
        .setTrackNumber(trackNumber)
        .setTotalTrackCount(trackTotal)
        .setDiscNumber(discNumber)
        .setTotalDiscCount(discTotal)
        .setRecordingYear(year)
        .setDurationMs(durationMs)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        // Album art for notification / Bluetooth: external file if present, else the MediaStore
        // album art URI (embedded art is resolved by the session bitmap loader in a later sprint).
        .setArtworkUri(
            externalArtPath?.let { Uri.fromFile(java.io.File(it)) }
                ?: if (albumId > 0) Uri.parse("content://media/external/audio/albumart/$albumId") else null
        )
        .setExtras(extras)
        .build()

    val builder = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri.ifBlank { Uri.fromFile(java.io.File(path)).toString() })
        .setMimeType(mimeType)
        .setMediaMetadata(metadata)

    if (cueStartMs != null) {
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(cueStartMs)
                .apply { cueEndMs?.let { setEndPositionMs(it) } }
                .build()
        )
    }
    return builder.build()
}

val MediaItem.songId: Long? get() = mediaId.toLongOrNull()
