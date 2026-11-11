package dev.lyra.music.data.db

import dev.lyra.music.util.LibraryIds

/** Recomputes Lyra's grouping ids from the song's current tag text. */
fun SongEntity.withLibraryIds(): SongEntity = copy(
    albumId = LibraryIds.albumId(album, albumArtist.ifBlank { artist }, folderPath),
    artistId = LibraryIds.artistId(artist),
)
