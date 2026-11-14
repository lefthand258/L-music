package dev.lyra.music.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.repo.SongSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyra_prefs")

@Singleton
class UserPrefs @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val SONG_SORT = stringPreferencesKey("song_sort")
        val MIN_DURATION_MS = longPreferencesKey("min_duration_ms")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LAST_SCAN = longPreferencesKey("last_scan")
        val ONLINE_METADATA_MODE = intPreferencesKey("online_metadata_mode") // 0 off, 1 wifi only, 2 always
        val WRITE_TAGS_TO_FILE = booleanPreferencesKey("write_tags_to_file")
        val AUTO_ORGANISE = booleanPreferencesKey("auto_organise")
        val ORGANISE_TEMPLATE = stringPreferencesKey("organise_template")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = intPreferencesKey("theme_mode") // 0 system, 1 light, 2 dark
        val GENRES_SEEDED = booleanPreferencesKey("genres_seeded")
        val FLOATING_LYRICS = booleanPreferencesKey("floating_lyrics")
        val LYRICS_SIDECAR = booleanPreferencesKey("lyrics_sidecar")
    }

    val songSort: Flow<SongSort> = context.dataStore.data.map { p ->
        runCatching { SongSort.valueOf(p[Keys.SONG_SORT] ?: "") }.getOrDefault(SongSort.TITLE)
    }
    val minDurationMs: Flow<Long> = context.dataStore.data.map { it[Keys.MIN_DURATION_MS] ?: 30_000L }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }
    val lastScan: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SCAN] ?: 0L }
    val onlineMetadataMode: Flow<Int> = context.dataStore.data.map { it[Keys.ONLINE_METADATA_MODE] ?: 1 }
    val writeTagsToFile: Flow<Boolean> = context.dataStore.data.map { it[Keys.WRITE_TAGS_TO_FILE] ?: true }
    val autoOrganise: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_ORGANISE] ?: false }
    val organiseTemplate: Flow<String> = context.dataStore.data.map {
        it[Keys.ORGANISE_TEMPLATE] ?: "{AlbumArtist}/{Album}/{Track:02} - {Title}"
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }
    val genresSeeded: Flow<Boolean> = context.dataStore.data.map { it[Keys.GENRES_SEEDED] ?: false }
    val lyricsSidecar: Flow<Boolean> = context.dataStore.data.map { it[Keys.LYRICS_SIDECAR] ?: true }
    val floatingLyrics: Flow<Boolean> = context.dataStore.data.map { it[Keys.FLOATING_LYRICS] ?: false }
    val themeMode: Flow<Int> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: 0 }

    suspend fun setSongSort(sort: SongSort) = context.dataStore.edit { it[Keys.SONG_SORT] = sort.name }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }
    suspend fun setLastScan(v: Long) = context.dataStore.edit { it[Keys.LAST_SCAN] = v }
    suspend fun setOnlineMetadataMode(v: Int) = context.dataStore.edit { it[Keys.ONLINE_METADATA_MODE] = v }
    suspend fun setWriteTagsToFile(v: Boolean) = context.dataStore.edit { it[Keys.WRITE_TAGS_TO_FILE] = v }
    suspend fun setAutoOrganise(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_ORGANISE] = v }
    suspend fun setOrganiseTemplate(v: String) = context.dataStore.edit { it[Keys.ORGANISE_TEMPLATE] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = v }
    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
    suspend fun setGenresSeeded(v: Boolean) = context.dataStore.edit { it[Keys.GENRES_SEEDED] = v }
    suspend fun setLyricsSidecar(v: Boolean) = context.dataStore.edit { it[Keys.LYRICS_SIDECAR] = v }
    suspend fun setFloatingLyrics(v: Boolean) = context.dataStore.edit { it[Keys.FLOATING_LYRICS] = v }
    suspend fun setMinDurationMs(v: Long) = context.dataStore.edit { it[Keys.MIN_DURATION_MS] = v }
}
