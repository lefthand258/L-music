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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import dev.lyra.music.data.scanner.TagWriter
import dev.lyra.music.playback.SleepTimer
import dev.lyra.music.ui.common.rememberReorderState
import dev.lyra.music.ui.common.reorderItem
import dev.lyra.music.ui.common.reorderable
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.lyra.music.ui.genre.GenreChips
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
fun NowPlayingScreen(vm: PlayerViewModel, onCollapse: () -> Unit, onIdentify: (SongEntity) -> Unit = {}, onSetGenres: (SongEntity) -> Unit = {}, onEdit: (SongEntity) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val song by vm.currentSong.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showLyricsSearch by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val loadedLyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsSaved by vm.lyricsSaved.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val msgSavedFile = stringResource(R.string.lyrics_saved_file)
    val msgSavedLrc = stringResource(R.string.lyrics_saved_lrc)
    val msgSavedBoth = stringResource(R.string.lyrics_saved_both)
    val msgOff = stringResource(R.string.lyrics_saved_only_app_off)
    val msgNoPerm = stringResource(R.string.lyrics_saved_only_app_perm)
    val msgFormat = stringResource(R.string.lyrics_saved_only_app_format)
    val msgFailed = stringResource(R.string.lyrics_saved_only_app_failed)
    LaunchedEffect(lyricsSaved) {
        val o = lyricsSaved ?: return@LaunchedEffect
        val embedded = o.embedded == TagWriter.Result.WRITTEN
        val msg = when {
            embedded && o.sidecar -> msgSavedBoth
            embedded -> msgSavedFile
            o.sidecar -> msgSavedLrc
            o.embedded == null && !o.sidecarSkippedNoPermission -> msgOff
            o.embedded == TagWriter.Result.SKIPPED_NO_PERMISSION || o.sidecarSkippedNoPermission -> msgNoPerm
            o.embedded == TagWriter.Result.SKIPPED_UNSUPPORTED -> msgFormat
            else -> msgFailed
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        vm.consumeLyricsSaved()
    }

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
                val sleep by vm.sleepTimer.collectAsStateWithLifecycle()
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Default.Bedtime, stringResource(R.string.sleep_timer),
                        tint = if (sleep != SleepTimer.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { song?.let(onEdit) }) {
                    Icon(Icons.Default.Edit, stringResource(R.string.action_song_info))
                }
                IconButton(onClick = { song?.let(onIdentify) }) {
                    Icon(Icons.Default.AutoFixHigh, stringResource(R.string.action_identify))
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        Icons.Default.Lyrics, stringResource(R.string.lyrics),
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, stringResource(R.string.queue)) }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsView(
                        lyrics = loadedLyrics?.lyrics,
                        positionMs = state.positionMs,
                        userOffsetMs = song?.lyricsOffsetMs ?: 0L,
                        onSeek = vm::seekTo,
                        onAdjustOffset = vm::adjustLyricsOffset,
                        onTapBackground = { showLyrics = false },
                        onSearchOnline = { vm.resetLyricsSearch(); showLyricsSearch = true },
                        loading = loadedLyrics == null && song != null,
                        source = loadedLyrics?.source,
                    )
                } else {
                    CoverImage(
                        song,
                        Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f)
                            .clickable { showLyrics = true },
                        corner = 24
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

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
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (summary.isNotBlank()) AssistChip(onClick = {}, label = { Text(summary, style = MaterialTheme.typography.labelSmall) })
                    GenreChips(songId = s.id, onClick = { onSetGenres(s) })
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

    if (showSleep) {
        SleepTimerDialog(vm = vm, onDismiss = { showSleep = false })
    }

    if (showLyricsSearch) {
        LyricsSearchSheet(vm = vm, onDismiss = { showLyricsSearch = false })
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
    val listState = rememberLazyListState()
    // Stable keys even when the same song appears twice: mediaId plus occurrence number.
    val keys = remember(state.queue) {
        val seen = HashMap<String, Int>()
        state.queue.map { it -> val n = seen.getOrDefault(it.mediaId, 0); seen[it.mediaId] = n + 1; "${it.mediaId}#$n" }
    }
    // Drag-and-drop: moves are sent to the player immediately and the queue re-emits.
    val reorder = rememberReorderState(
        listState = listState,
        keyOf = { i -> keys.getOrNull(i) },
        onMove = { from, to -> vm.moveQueueItem(from, to) },
    )
    LaunchedEffect(Unit) { if (state.currentIndex > 2) listState.scrollToItem(state.currentIndex - 1) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(R.string.queue)} · ${state.currentIndex + 1}/${state.queue.size}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.clearQueue(); onDismiss() }) { Text(stringResource(R.string.clear)) }
        }
        Text(
            stringResource(R.string.drag_hint), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn(Modifier.fillMaxWidth().height(480.dp), state = listState) {
            itemsIndexed(state.queue, key = { i, _ -> keys[i] }) { index, item ->
                val isCurrent = index == state.currentIndex
                val key = keys[index]
                Row(
                    Modifier.fillMaxWidth()
                        .reorderItem(reorder, key)
                        .background(
                            when {
                                reorder.draggingKey == key -> MaterialTheme.colorScheme.surfaceVariant
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                        .reorderable(reorder, index)
                        .clickable { vm.skipToQueueItem(index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
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

@Composable
private fun SleepTimerDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val timer by vm.sleepTimer.collectAsStateWithLifecycle()
    var finishSong by remember { mutableStateOf(true) }
    var custom by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf("") }
    // Tick once a second so the remaining time stays accurate.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) { while (timer is SleepTimer.AtTime) { delay(1_000); now = System.currentTimeMillis() } }

    fun start(minutes: Int) {
        vm.setSleepTimer(SleepTimer.AtTime(System.currentTimeMillis() + minutes * 60_000L, finishSong)); onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (val t = timer) {
                    is SleepTimer.AtTime -> Text(
                        stringResource(R.string.sleep_remaining, formatDuration((t.endAtMs - now).coerceAtLeast(0))),
                        color = MaterialTheme.colorScheme.primary
                    )
                    is SleepTimer.AfterSongs -> Text(stringResource(R.string.sleep_remaining_songs, t.remaining), color = MaterialTheme.colorScheme.primary)
                    SleepTimer.Off -> Unit
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { m -> FilledTonalButton(onClick = { start(m) }) { Text("${m}m") } }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.sleep_minutes)) }, modifier = Modifier.weight(1f)
                    )
                    TextButton(enabled = (custom.toIntOrNull() ?: 0) > 0, onClick = { start(custom.toInt()) }) { Text(stringResource(R.string.ok)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = songs, onValueChange = { songs = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.sleep_after_songs)) }, modifier = Modifier.weight(1f)
                    )
                    TextButton(enabled = (songs.toIntOrNull() ?: 0) > 0, onClick = {
                        vm.setSleepTimer(SleepTimer.AfterSongs(songs.toInt())); onDismiss()
                    }) { Text(stringResource(R.string.ok)) }
                }
                Row(Modifier.fillMaxWidth().clickable { finishSong = !finishSong }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = finishSong, onCheckedChange = { finishSong = it })
                    Text(stringResource(R.string.sleep_finish_song), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (timer != SleepTimer.Off) TextButton(onClick = { vm.setSleepTimer(SleepTimer.Off); onDismiss() }) { Text(stringResource(R.string.sleep_off)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Suppress("unused")
private fun SongEntity.displayArtist() = artist.ifBlank { albumArtist }
