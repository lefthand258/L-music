package dev.lyra.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.lyra.music.R
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.playback.PlayerState
import dev.lyra.music.ui.library.CoverImage
import dev.lyra.music.util.audioSummary
import dev.lyra.music.util.formatDuration

@Composable
fun MiniPlayer(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val item = state.currentItem ?: return

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onExpand),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(song, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.mediaMetadata.title?.toString() ?: stringResource(R.string.unknown_title),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        item.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = vm::previous) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.skip_previous)) }
                FilledIconButton(onClick = vm::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                    )
                }
                IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, stringResource(R.string.skip_next)) }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(vm: PlayerViewModel, onCollapse: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val song by vm.currentSong.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, null) }
                Text(
                    stringResource(R.string.now_playing), modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge
                )
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, stringResource(R.string.queue)) }
            }

            Spacer(Modifier.weight(1f))
            CoverImage(
                song,
                Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f),
                corner = 24
            )
            Spacer(Modifier.weight(1f))

            val item = state.currentItem
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item?.mediaMetadata?.title?.toString() ?: "—",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(item?.mediaMetadata?.artist?.toString(), item?.mediaMetadata?.albumTitle?.toString())
                            .joinToString(" · ").ifBlank { stringResource(R.string.unknown_artist) },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = vm::toggleFavourite) {
                    Icon(
                        if (song?.isFavourite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                        tint = if (song?.isFavourite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            song?.let { s ->
                val summary = audioSummary(s.codec ?: s.mimeType, s.bitDepth, s.sampleRateHz, s.bitrateKbps, s.channels)
                if (summary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AssistChip(onClick = {}, label = { Text(summary, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SeekBar(state, onSeek = vm::seekTo)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle, null,
                        tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, null) }
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.skip_previous), Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = vm::togglePlayPause, modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null,
                        Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.skip_next), Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.seekBy(10_000) }) { Icon(Icons.Default.Forward10, null) }
                IconButton(onClick = vm::cycleRepeat) {
                    val on = state.repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showQueue) {
        QueueSheet(vm = vm, state = state, onDismiss = { showQueue = false })
    }
}

@Composable
private fun SeekBar(state: PlayerState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((shown * duration).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QueueSheet(vm: PlayerViewModel, state: PlayerState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(R.string.queue)} · ${state.currentIndex + 1}/${state.queue.size}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.clearQueue(); onDismiss() }) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            itemsIndexed(state.queue, key = { i, it -> "$i:${it.mediaId}" }) { index, item ->
                val isCurrent = index == state.currentIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                        .clickable { vm.skipToQueueItem(index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.mediaMetadata.title?.toString() ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(item.mediaMetadata.artist?.toString() ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.removeFromQueue(index) }) { Icon(Icons.Default.Close, null) }
                }
            }
        }
    }
}

@Suppress("unused")
private fun SongEntity.displayArtist() = artist.ifBlank { albumArtist }
