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
import androidx.compose.foundation.lazy.items
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
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavourite: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
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
                DropdownMenuItem(text = { Text(stringResource(R.string.action_play_next)) }, onClick = { menu = false; onPlayNext() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_queue)) }, onClick = { menu = false; onAddToQueue() })
                DropdownMenuItem(
                    text = { Text(if (song.isFavourite) "♥ −" else "♥ +") },
                    onClick = { menu = false; onToggleFavourite() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_library)) }, onClick = { menu = false; onHide() })
            }
        }
    }
}

@Composable
fun SongList(
    songs: List<SongEntity>,
    currentSongId: Long?,
    contentPadding: PaddingValues,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onPlayAll: (List<SongEntity>) -> Unit,
    onShuffleAll: (List<SongEntity>) -> Unit,
    onPlayNext: (SongEntity) -> Unit,
    onAddToQueue: (SongEntity) -> Unit,
    onToggleFavourite: (SongEntity) -> Unit,
    onHide: (SongEntity) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
) {
    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        if (header != null) item("header") { header() }
        item("actions") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilledTonalButton(onClick = { onPlayAll(songs) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play_all))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onShuffleAll(songs) }) {
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
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrent = song.id == currentSongId,
                onClick = { onPlay(songs, songs.indexOf(song)) },
                onPlayNext = { onPlayNext(song) },
                onAddToQueue = { onAddToQueue(song) },
                onToggleFavourite = { onToggleFavourite(song) },
                onHide = { onHide(song) },
            )
        }
        item("bottom") { Spacer(Modifier.height(8.dp)) }
    }
}
