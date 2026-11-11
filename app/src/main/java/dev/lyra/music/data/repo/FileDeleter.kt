package dev.lyra.music.data.repo

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.ui.permissions.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeleteOutcome {
    /** Files are gone and the library rows were removed. */
    data class Done(val deleted: Int, val failed: Int) : DeleteOutcome
    /** The system must ask the user; launch [sender], then call [FileDeleter.onUserConfirmed]. */
    data class NeedsUserConsent(val sender: IntentSender, val paths: List<String>) : DeleteOutcome
}

/**
 * "Delete file from phone". This permanently deletes audio files, so callers must show their
 * own confirmation first. Uses direct deletion with All-files access, otherwise the system
 * consent dialog (Android 11+) or the recoverable-security flow (Android 10).
 */
@Singleton
class FileDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
) {
    suspend fun delete(songs: List<SongEntity>): DeleteOutcome = withContext(Dispatchers.IO) {
        // CUE tracks share a file: deleting one deletes the whole album file, so work per path.
        val byPath = songs.associateBy { it.path }.values.toList()
        val resolver = context.contentResolver

        if (Permissions.hasAllFiles()) {
            var ok = 0
            var failed = 0
            val gone = ArrayList<String>()
            for (s in byPath) {
                val f = File(s.path)
                val deleted = runCatching { !f.exists() || f.delete() }.getOrDefault(false)
                if (deleted) {
                    ok++; gone += s.path
                    s.mediaStoreUri()?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
                } else failed++
            }
            library.forgetPaths(gone)
            return@withContext DeleteOutcome.Done(ok, failed)
        }

        val uris = byPath.mapNotNull { it.mediaStoreUri() }
        if (uris.isEmpty()) return@withContext DeleteOutcome.Done(0, byPath.size)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(resolver, uris)
            return@withContext DeleteOutcome.NeedsUserConsent(pi.intentSender, byPath.map { it.path })
        }

        // Android 10: delete one by one; the first refusal carries the consent intent.
        var ok = 0
        val gone = ArrayList<String>()
        for (s in byPath) {
            val uri = s.mediaStoreUri() ?: continue
            try {
                if (resolver.delete(uri, null, null) > 0) { ok++; gone += s.path }
            } catch (e: RecoverableSecurityException) {
                library.forgetPaths(gone)
                return@withContext DeleteOutcome.NeedsUserConsent(
                    e.userAction.actionIntent.intentSender, byPath.map { it.path } - gone.toSet()
                )
            } catch (_: SecurityException) {
            }
        }
        library.forgetPaths(gone)
        DeleteOutcome.Done(ok, byPath.size - ok)
    }

    /** Call after the system consent dialog returned RESULT_OK. */
    suspend fun onUserConfirmed(paths: List<String>): DeleteOutcome = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The system already deleted the files it was allowed to.
            val gone = paths.filter { !File(it).exists() }
            library.forgetPaths(gone)
            DeleteOutcome.Done(gone.size, paths.size - gone.size)
        } else {
            // Android 10 grants access for the one refused item; retry the rest.
            val songs = paths.flatMap { library.getSongsByPath(it) }
            delete(songs)
        }
    }

    private fun SongEntity.mediaStoreUri(): Uri? =
        if (mediaStoreId > 0) ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), mediaStoreId) else null
}
