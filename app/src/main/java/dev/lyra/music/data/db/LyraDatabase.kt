package dev.lyra.music.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        PlayHistoryEntity::class,
        MetadataOverrideEntity::class,
        QueueItemEntity::class,
        QueueStateEntity::class,
        BlacklistedFolderEntity::class,
        LibraryFolderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LyraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun metadataOverrideDao(): MetadataOverrideDao
    abstract fun queueDao(): QueueDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val NAME = "lyra.db"
    }
}
