package dev.lyra.music.data.scanner

import android.net.Uri
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.util.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks user-added folders directly on disk to find audio files MediaStore does not index
 * (folders with `.nomedia`, formats the system does not recognise such as APE / DSF…).
 * Needs "All files access"; without it the walk finds nothing and the scan degrades silently.
 */
@Singleton
class DirectoryScanner @Inject constructor() {

    suspend fun scan(
        roots: List<String>,
        alreadyKnownPaths: Set<String>,
        blacklistedFolders: List<String>,
    ): List<SongEntity> = withContext(Dispatchers.IO) {
        val out = ArrayList<SongEntity>()
        val seenDirs = HashSet<String>()
        for (root in roots) walk(File(root), 0, alreadyKnownPaths, blacklistedFolders, seenDirs, out)
        out
    }

    private fun walk(
        dir: File, depth: Int, known: Set<String>, blacklist: List<String>,
        seenDirs: MutableSet<String>, out: MutableList<SongEntity>,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!seenDirs.add(canonical)) return // symlink loops
        val path = dir.absolutePath
        if (blacklist.any { path == it || path.startsWith("$it/") }) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) walk(f, depth + 1, known, blacklist, seenDirs, out)
            } else if (f.isFile && StoragePaths.isAudioFileName(f.name) && f.absolutePath !in known) {
                val size = f.length()
                if (size < MIN_BYTES) continue
                val modified = f.lastModified()
                out += SongEntity(
                    mediaStoreId = -1,
                    path = f.absolutePath,
                    uri = Uri.fromFile(f).toString(),
                    fileName = f.name,
                    folderPath = path,
                    fileSize = size,
                    dateAdded = modified,
                    dateModified = modified,
                    title = f.name.substringBeforeLast('.'),
                    artist = "",
                    album = "",
                    albumArtist = "",
                    durationMs = 0, // filled in by the tag pass
                    needsMetadata = true,
                )
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_FILES = 100_000
        const val MIN_BYTES = 64L * 1024
    }
}
