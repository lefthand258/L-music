package dev.lyra.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A song in the local library. One row per audio file (or per CUE track, see [cueIndex]).
 *
 * Tag fields hold the *effective* values shown to the user: original file tags, overridden by
 * any user / online corrections recorded in [MetadataOverrideEntity]. The raw file tag values are
 * kept in the override table so the user can always restore them.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index("mediaStoreId", unique = false),
        Index(value = ["path", "cueIndex"], unique = true),
        Index("albumId"),
        Index("artistId"),
        Index("title"),
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore _ID, or -1 for files discovered via SAF folders only. */
    val mediaStoreId: Long = -1,
    /** Absolute file path, or a content:// URI string for SAF-only files. */
    val path: String,
    val uri: String,
    val fileName: String,
    val folderPath: String,
    val fileSize: Long,
    val dateAdded: Long,
    val dateModified: Long,

    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    /** Lyra grouping ids (see LibraryIds) – derived from tag text, not from MediaStore. */
    val albumId: Long = 0,
    val artistId: Long = 0,
    /** MediaStore ALBUM_ID, kept only as a last-resort source of cover art. */
    val mediaStoreAlbumId: Long = 0,
    val composer: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val comment: String? = null,

    val durationMs: Long,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val mimeType: String? = null,
    val codec: String? = null,

    /** Whether the file has embedded cover art; the resolved cover URI is computed at display time. */
    val hasEmbeddedArt: Boolean = false,
    /** Path to an external cover image (folder.jpg / cover.jpg / downloaded), if any. */
    val externalArtPath: String? = null,
    /** Whether embedded or sidecar lyrics are known to exist. */
    val hasLyrics: Boolean = false,

    /**
     * CUE sheet support. 0 = an ordinary one-song file; > 0 = track number inside a CUE sheet,
     * in which case several rows share the same [path] and are told apart by this value.
     */
    val cueIndex: Int = 0,
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,

    val isFavourite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    /** Set when the user chose "Remove from Lyra" – the file still exists but is hidden. */
    val isHidden: Boolean = false,
    /** True when tags were fully read with jaudiotagger (vs. MediaStore-only quick scan). */
    val tagsFullyScanned: Boolean = false,
    /** True when some essential metadata (title/artist/album/cover) is missing. */
    val needsMetadata: Boolean = false,
    /** User lyric timing correction in ms (positive = lyrics earlier). */
    val lyricsOffsetMs: Long = 0,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index("songId"), Index(value = ["playlistId", "position"])]
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "play_history",
    indices = [Index("songId"), Index("playedAt")]
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
    /** How much of the track was actually listened to, in ms. */
    val listenedMs: Long,
)

/**
 * Records every metadata change made by the user or by the online identifier so it can be
 * reviewed, exported and undone. [originalValue] is the value read from the file before the
 * first change; [source] is e.g. "user", "musicbrainz", "netease", "qq", "lrclib".
 */
@Entity(
    tableName = "metadata_overrides",
    indices = [Index(value = ["songId", "field"], unique = true)]
)
data class MetadataOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val field: String,
    val originalValue: String?,
    val newValue: String?,
    val source: String,
    val confidence: Float? = null,
    val appliedAt: Long = System.currentTimeMillis(),
    val writtenToFile: Boolean = false,
)

/** Persisted playback queue so playback resumes exactly where it stopped. */
@Entity(tableName = "queue_items", indices = [Index("position")])
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val songId: Long,
)

@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 0,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    @ColumnInfo(name = "repeatMode") val repeatMode: Int,
)

/** Folders the user excluded from the library. */
@Entity(tableName = "blacklisted_folders")
data class BlacklistedFolderEntity(
    @PrimaryKey val path: String,
)

/** Extra folders added via SAF document tree. */
@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A genre tag. The list is the user's: built-in entries can be renamed or deleted like any other. */
@Entity(tableName = "genres", indices = [Index(value = ["name"], unique = true)])
data class GenreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A song can carry any number of genre tags. */
@Entity(
    tableName = "song_genres",
    primaryKeys = ["songId", "genreId"],
    foreignKeys = [
        ForeignKey(entity = SongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GenreEntity::class, parentColumns = ["id"], childColumns = ["genreId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("genreId")]
)
data class SongGenreEntity(val songId: Long, val genreId: Long)

/** One file move made by the organiser, kept so it can be undone. */
@Entity(tableName = "move_log", indices = [Index("movedAt")])
data class MoveLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val fromPath: String,
    val toPath: String,
    val movedAt: Long = System.currentTimeMillis(),
    val undone: Boolean = false,
)
