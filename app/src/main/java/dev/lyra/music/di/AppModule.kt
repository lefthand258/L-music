package dev.lyra.music.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lyra.music.data.db.LyraDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LyraDatabase =
        Room.databaseBuilder(context, LyraDatabase::class.java, LyraDatabase.NAME)
            .addMigrations(LyraDatabase.MIGRATION_1_2, LyraDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
}
