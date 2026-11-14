package dev.lyra.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lyra.music.R
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.util.CoverArt
import dev.lyra.music.util.formatDuration

/** Everything a song row can do; built once at the app root and passed down. */
class SongActions(
    val onPlay: (List<SongEntity>, Int) -> Unit,
    val onPlayAll: (List<SongEntity>) -> Unit,
    val onShuffleAll: (List<SongEntity>) -> Unit,
    val onPlayNext: (SongEntity) -> Unit,
    val onAddToQueue: (SongEntity) -> Unit,
    val onToggleFavourite: (SongEntity) -> Unit,
    val onAddToPlaylist: (SongEntity) -> Unit,
    /** Look up song information and cover art online. */
    val onIdentify: (SongEntity) -> Unit,
    /** "Remove from Lyra": hides the song, the file stays on the phone. */
    val onHide: (SongEntity) -> Unit,
    /** "Delete file from phone": asks for confirmation, then deletes the file itself. */
    val onDeleteFile: (SongEntity) -> Unit,
)

@Composable
fun CoverImage(song: SongEntity?, modifier: Modifier = Modifier, corner: Int = 8) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (song != null) {
            AsyncImage(
                model = CoverArt.of(song),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            Icons.Default.MusicNote, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}

@Composable
fun SongRow(
    song: SongEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(song, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title.ifBlank { stringResource(R.string.unknown_title) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(song.artist.ifBlank { stringResource(R.string.unknown_artist) })
                    if (song.album.isNotBlank()) append(" · ").append(song.album)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (song.isFavourite) {
            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_play_next)) }, onClick = { menu = false; actions.onPlayNext(song) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_queue)) }, onClick = { menu = false; actions.onAddToQueue(song) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_playlist)) }, onClick = { menu = false; actions.onAddToPlaylist(song) })
                DropdownMenuItem(
                    text = { Text(stringResource(if (song.isFavourite) R.string.action_unfavourite else R.string.action_favourite)) },
                    onClick = { menu = false; actions.onToggleFavourite(song) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_identify)) }, onClick = { menu = false; actions.onIdentify(song) })
                if (onRemoveFromPlaylist != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_playlist)) }, onClick = { menu = false; onRemoveFromPlaylist() })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_library)) }, onClick = { menu = false; actions.onHide(song) })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_file), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; actions.onDeleteFile(song) })
            }
        }
    }
}

@Composable
fun SongList(
    songs: List<SongEntity>,
    currentSongId: Long?,
    contentPadding: PaddingValues,
    actions: SongActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    onRemoveFromPlaylist: ((SongEntity) -> Unit)? = null,
) {
    if (songs.isEmpty() && header == null) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        if (header != null) item("header") { header() }
        if (songs.isNotEmpty()) item("actions") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilledTonalButton(onClick = { actions.onPlayAll(songs) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play_all))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { actions.onShuffleAll(songs) }) {
                    Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shuffle_all))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.songs_count, songs.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        itemsIndexed(songs, key = { _, it -> it.id }) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.id == currentSongId,
                onClick = { actions.onPlay(songs, index) },
                actions = actions,
                onRemoveFromPlaylist = onRemoveFromPlaylist?.let { cb -> { cb(song) } },
            )
        }
        item("bottom") { Spacer(Modifier.height(8.dp)) }
    }
}
