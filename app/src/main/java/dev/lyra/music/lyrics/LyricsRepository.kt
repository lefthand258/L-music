package dev.lyra.music.lyrics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.scanner.TagReader
import dev.lyra.music.util.TextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class LyricsSource { SIDECAR, EMBEDDED, CACHE, NONE }

data class LoadedLyrics(val lyrics: Lyrics, val source: LyricsSource)

/**
 * Resolves lyrics for a song. Priority (docs/REQUIREMENTS.md §5):
 *  1. lyrics the user saved / downloaded for this song (app-private cache, wins because it is
 *     the user's most recent explicit choice),
 *  2. embedded tag (USLT / LYRICS / ©lyr),
 *  3. sidecar `.lrc` next to the audio file (same base name),
 *  4. nothing – the online lookup (Sprint 6) plugs in here.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagReader: TagReader,
    private val library: LibraryRepository,
) {
    private val cacheDir: File get() = File(context.filesDir, "lyrics").apply { mkdirs() }

    suspend fun load(song: SongEntity): LoadedLyrics = withContext(Dispatchers.IO) {
        val result = readCache(song)?.let { LoadedLyrics(it, LyricsSource.CACHE) }
            ?: readEmbedded(song)?.let { LoadedLyrics(it, LyricsSource.EMBEDDED) }
            ?: readSidecar(song)?.let { LoadedLyrics(it, LyricsSource.SIDECAR) }
            ?: LoadedLyrics(Lyrics.EMPTY, LyricsSource.NONE)
        val has = !result.lyrics.isEmpty
        if (has != song.hasLyrics) runCatching { library.setHasLyrics(song.id, has) }
        result
    }

    /** Stores lyrics text (LRC or plain) for a song in app-private storage. */
    suspend fun save(song: SongEntity, text: String) = withContext(Dispatchers.IO) {
        val capped = text.take(MAX_CHARS)
        cacheFile(song).writeText(capped, Charsets.UTF_8)
        library.setHasLyrics(song.id, capped.isNotBlank())
    }

    suspend fun clearSaved(song: SongEntity) = withContext(Dispatchers.IO) {
        cacheFile(song).delete()
        Unit
    }

    private fun cacheFile(song: SongEntity) = File(cacheDir, "${song.id}.lrc")

    private fun readCache(song: SongEntity): Lyrics? {
        val f = cacheFile(song)
        if (!f.isFile || f.length() !in 1..MAX_BYTES) return null
        return LrcParser.parse(f.readText(Charsets.UTF_8)).takeUnless { it.isEmpty }
    }

    private suspend fun readEmbedded(song: SongEntity): Lyrics? {
        // A CUE track shares its file with the whole album; embedded lyrics would be wrong.
        if (song.cueIndex > 0) return null
        val text = tagReader.read(song.path)?.lyrics ?: return null
        return LrcParser.parse(text).takeUnless { it.isEmpty }
    }

    private fun readSidecar(song: SongEntity): Lyrics? {
        val audio = File(song.path)
        val dir = audio.parentFile ?: return null
        val base = audio.name.substringBeforeLast('.')
        val candidates = buildList {
            if (song.cueIndex > 0) {
                // "03 - Title.lrc" / "Title.lrc" beside a CUE-split album file.
                add(File(dir, "%02d - %s.lrc".format(song.cueIndex, song.title)))
                add(File(dir, "${song.title}.lrc"))
            } else {
                add(File(dir, "$base.lrc"))
                add(File(dir, "$base.LRC"))
                add(File(dir, "$base.txt"))
            }
        }
        for (f in candidates) {
            if (!f.isFile || f.length() !in 1..MAX_BYTES) continue
            val text = runCatching { TextDecoder.decode(f.readBytes()) }.getOrNull() ?: continue
            LrcParser.parse(text).takeUnless { it.isEmpty }?.let { return it }
        }
        return null
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024
        const val MAX_CHARS = 64 * 1024
    }
}
