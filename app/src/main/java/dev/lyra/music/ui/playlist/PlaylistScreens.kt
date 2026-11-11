package dev.lyra.music.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyra.music.R
import dev.lyra.music.data.db.PlaylistWithCount
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.util.formatFileSize

/** The "Playlists" tab: smart lists, user playlists, create / import / export-all. */
@Composable
fun PlaylistsTab(
    playlists: List<PlaylistWithCount>,
    padding: PaddingValues,
    onOpenSmart: (SmartList) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item("fav") { EntryRow(Icons.Default.Favorite, stringResource(R.string.smart_favourites), null) { onOpenSmart(SmartList.FAVOURITES) } }
        item("recent") { EntryRow(Icons.Default.History, stringResource(R.string.smart_recent), null) { onOpenSmart(SmartList.RECENT) } }
        item("most") { EntryRow(Icons.Default.TrendingUp, stringResource(R.string.smart_most_played), null) { onOpenSmart(SmartList.MOST_PLAYED) } }
        item("div1") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item("new") { EntryRow(Icons.Default.Add, stringResource(R.string.playlist_new), null, onClick = onCreate) }
        items(playlists, key = { it.id }) { p ->
            EntryRow(
                Icons.AutoMirrored.Filled.PlaylistPlay, p.name,
                stringResource(R.string.songs_count, p.songCount)
            ) { onOpenPlaylist(p.id) }
        }
        item("div2") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item("import") { EntryRow(Icons.Default.FileDownload, stringResource(R.string.playlist_import), null, onClick = onImport) }
        if (playlists.isNotEmpty()) {
            item("exportAll") { EntryRow(Icons.Default.FileUpload, stringResource(R.string.playlist_export_all_csv), null, onClick = onExportAll) }
        }
    }
}

@Composable
private fun EntryRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** App-bar overflow menu for a user playlist. */
@Composable
fun PlaylistMenu(
    onRename: () -> Unit,
    onExportM3u: () -> Unit,
    onExportCsv: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_export_m3u)) }, onClick = { open = false; onExportM3u() })
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_export_csv)) }, onClick = { open = false; onExportCsv() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() })
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(120) }, singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, destructive: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Pick an existing playlist (or create one) for [song]. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistWithCount>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_to_playlist)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                item("new") { EntryRow(Icons.Default.Add, stringResource(R.string.playlist_new), null, onClick = onCreateNew) }
                items(playlists, key = { it.id }) { p ->
                    EntryRow(Icons.AutoMirrored.Filled.PlaylistPlay, p.name, stringResource(R.string.songs_count, p.songCount)) { onPick(p.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Confirmation for permanently deleting a song's file. */
@Composable
fun DeleteFileDialog(song: SongEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val base = stringResource(R.string.delete_file_message, song.title, song.path, formatFileSize(song.fileSize))
    val message = if (song.cueIndex > 0) base + "\n\n" + stringResource(R.string.delete_file_cue_warning) else base
    ConfirmDialog(
        title = stringResource(R.string.delete_file_title),
        message = message,
        confirmLabel = stringResource(R.string.delete),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
