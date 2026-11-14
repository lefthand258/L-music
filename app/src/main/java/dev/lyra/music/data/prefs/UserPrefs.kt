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
        val FLOAT_LOCKED = booleanPreferencesKey("float_locked")
        val FLOAT_COLOR = intPreferencesKey("float_color")
        val FLOAT_CLEAR_BG = booleanPreferencesKey("float_clear_bg")
        val FLOAT_SIZE = intPreferencesKey("float_size")
        val FLOAT_Y = intPreferencesKey("float_y")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val EQ_PREAMP = intPreferencesKey("eq_preamp_tenths")
        val EQ_BALANCE = intPreferencesKey("eq_balance")
        val PLAY_SPEED = intPreferencesKey("play_speed_pct")
        val PLAY_PITCH = intPreferencesKey("play_pitch_halfsteps_x2")
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
    val floatLocked: Flow<Boolean> = context.dataStore.data.map { it[Keys.FLOAT_LOCKED] ?: false }
    /** Index into FloatingLyricsController.COLORS. */
    val floatColor: Flow<Int> = context.dataStore.data.map { it[Keys.FLOAT_COLOR] ?: 0 }
    val floatClearBg: Flow<Boolean> = context.dataStore.data.map { it[Keys.FLOAT_CLEAR_BG] ?: false }
    val floatSizeSp: Flow<Int> = context.dataStore.data.map { it[Keys.FLOAT_SIZE] ?: 18 }
    val floatY: Flow<Int> = context.dataStore.data.map { it[Keys.FLOAT_Y] ?: -1 }

    // ---- Sound ----
    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.EQ_ENABLED] ?: false }
    /** Ten gains in tenths of a dB, comma separated. */
    val eqBands: Flow<List<Int>> = context.dataStore.data.map { p ->
        val v = p[Keys.EQ_BANDS]?.split(',')?.mapNotNull { it.toIntOrNull() }
        if (v != null && v.size == 10) v else List(10) { 0 }
    }
    val eqPreampTenths: Flow<Int> = context.dataStore.data.map { it[Keys.EQ_PREAMP] ?: 0 }
    /** -100 (left only) … 0 … +100 (right only). */
    val eqBalance: Flow<Int> = context.dataStore.data.map { it[Keys.EQ_BALANCE] ?: 0 }
    /** Speed in percent (50–200). */
    val playSpeedPct: Flow<Int> = context.dataStore.data.map { it[Keys.PLAY_SPEED] ?: 100 }
    /** Pitch in half-semitone steps (-24…+24 = ±12 semitones). */
    val playPitchSteps: Flow<Int> = context.dataStore.data.map { it[Keys.PLAY_PITCH] ?: 0 }
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
    suspend fun setFloatLocked(v: Boolean) = context.dataStore.edit { it[Keys.FLOAT_LOCKED] = v }
    suspend fun setFloatColor(v: Int) = context.dataStore.edit { it[Keys.FLOAT_COLOR] = v }
    suspend fun setFloatClearBg(v: Boolean) = context.dataStore.edit { it[Keys.FLOAT_CLEAR_BG] = v }
    suspend fun setFloatSizeSp(v: Int) = context.dataStore.edit { it[Keys.FLOAT_SIZE] = v.coerceIn(12, 32) }
    suspend fun setFloatY(v: Int) = context.dataStore.edit { it[Keys.FLOAT_Y] = v }
    suspend fun setEqEnabled(v: Boolean) = context.dataStore.edit { it[Keys.EQ_ENABLED] = v }
    suspend fun setEqBands(v: List<Int>) = context.dataStore.edit { it[Keys.EQ_BANDS] = v.take(10).joinToString(",") { g -> g.coerceIn(-120, 120).toString() } }
    suspend fun setEqPreampTenths(v: Int) = context.dataStore.edit { it[Keys.EQ_PREAMP] = v.coerceIn(-120, 120) }
    suspend fun setEqBalance(v: Int) = context.dataStore.edit { it[Keys.EQ_BALANCE] = v.coerceIn(-100, 100) }
    suspend fun setPlaySpeedPct(v: Int) = context.dataStore.edit { it[Keys.PLAY_SPEED] = v.coerceIn(50, 200) }
    suspend fun setPlayPitchSteps(v: Int) = context.dataStore.edit { it[Keys.PLAY_PITCH] = v.coerceIn(-24, 24) }
    suspend fun setFloatingLyrics(v: Boolean) = context.dataStore.edit { it[Keys.FLOATING_LYRICS] = v }
    suspend fun setMinDurationMs(v: Long) = context.dataStore.edit { it[Keys.MIN_DURATION_MS] = v }
}
