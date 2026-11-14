package dev.lyra.music.ui.metadata

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.R
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.scanner.TagWriter
import dev.lyra.music.online.ApplyResult
import dev.lyra.music.online.MetadataService
import dev.lyra.music.online.SongTags
import dev.lyra.music.online.tags
import dev.lyra.music.ui.library.CoverImage
import dev.lyra.music.util.audioSummary
import dev.lyra.music.util.formatDuration
import dev.lyra.music.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SongEditViewModel @Inject constructor(private val service: MetadataService) : ViewModel() {
    suspend fun save(song: SongEntity, tags: SongTags, coverUri: Uri?): ApplyResult {
        val bytes = coverUri?.let { withContext(Dispatchers.IO) { service.readPickedImage(it) } }
        return service.applyManual(song, tags, bytes)
    }
}

/** Manual tag editor with a cover picker. Saves to Lyra and, when enabled, into the file. */
@Composable
fun SongEditSheet(song: SongEntity, onDismiss: () -> Unit, vm: SongEditViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember(song.id) { song.tags() }
    var title by remember { mutableStateOf(t.title) }
    var artist by remember { mutableStateOf(t.artist) }
    var album by remember { mutableStateOf(t.album) }
    var albumArtist by remember { mutableStateOf(t.albumArtist) }
    var composer by remember { mutableStateOf(t.composer.orEmpty()) }
    var year by remember { mutableStateOf(t.year?.toString().orEmpty()) }
    var track by remember { mutableStateOf(t.trackNumber?.toString().orEmpty()) }
    var disc by remember { mutableStateOf(t.discNumber?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(t.genre.orEmpty()) }
    var comment by remember { mutableStateOf(t.comment.orEmpty()) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    val msgFile = stringResource(R.string.meta_applied_file)
    val msgDb = stringResource(R.string.meta_applied_db)
    val msgFileFailed = stringResource(R.string.meta_applied_file_failed)
    val msgCoverFailed = stringResource(R.string.meta_cover_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) coverUri = uri }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(song, Modifier.size(72.dp), corner = 10)
                Spacer(Modifier.width(12.dp))
                Column {
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.edit_pick_cover)) }
                    if (coverUri != null) Text(stringResource(R.string.edit_cover_picked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Field(title, { title = it }, R.string.field_title)
            Field(artist, { artist = it }, R.string.field_artist)
            Field(album, { album = it }, R.string.field_album)
            Field(albumArtist, { albumArtist = it }, R.string.field_album_artist)
            Field(composer, { composer = it }, R.string.field_composer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(year, { year = it }, R.string.field_year, Modifier.weight(1f))
                NumField(track, { track = it }, R.string.field_track, Modifier.weight(1f))
                NumField(disc, { disc = it }, R.string.field_disc, Modifier.weight(1f))
            }
            Field(genre, { genre = it }, R.string.field_genre)
            Field(comment, { comment = it }, R.string.field_comment, singleLine = false)

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.edit_technical), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                listOf(
                    audioSummary(song.codec ?: song.mimeType, song.bitDepth, song.sampleRateHz, song.bitrateKbps, song.channels),
                    formatDuration(song.durationMs), formatFileSize(song.fileSize),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(song.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                if (saving) CircularProgressIndicator(Modifier.size(28.dp))
                else Button(enabled = title.isNotBlank(), onClick = {
                    saving = true
                    val tags = SongTags(
                        title = title.trim(), artist = artist.trim(), album = album.trim(),
                        albumArtist = albumArtist.trim().ifBlank { artist.trim() },
                        year = year.toIntOrNull()?.takeIf { it in 1000..2999 }, genre = genre.trim().ifBlank { null },
                        composer = composer.trim().ifBlank { null }, trackNumber = track.toIntOrNull(),
                        discNumber = disc.toIntOrNull(), comment = comment.trim().ifBlank { null },
                    )
                    scope.launch {
                        val r = vm.save(song, tags, coverUri)
                        val msg = buildString {
                            append(when (r.fileWrite) { TagWriter.Result.WRITTEN -> msgFile; TagWriter.Result.FAILED -> msgFileFailed; else -> msgDb })
                            if (!r.coverOk) append("\n").append(msgCoverFailed)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(if (singleLine) 300 else 4000)) }, singleLine = singleLine,
        label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(4)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(stringResource(label)) }, modifier = modifier.padding(vertical = 2.dp)
    )
}
