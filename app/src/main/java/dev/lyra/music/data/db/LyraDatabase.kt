package dev.lyra.music.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        GenreEntity::class,
        SongGenreEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LyraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun metadataOverrideDao(): MetadataOverrideDao
    abstract fun queueDao(): QueueDao
    abstract fun settingsDao(): SettingsDao
    abstract fun genreDao(): GenreDao

    companion object {
        const val NAME = "lyra.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `genres` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_genres_name` ON `genres` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_genres` (`songId` INTEGER NOT NULL, `genreId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`songId`, `genreId`), " +
                        "FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`genreId`) REFERENCES `genres`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_genres_genreId` ON `song_genres` (`genreId`)")
            }
        }
    }
}
