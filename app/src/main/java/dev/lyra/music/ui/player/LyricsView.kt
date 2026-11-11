package dev.lyra.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lyra.music.R
import dev.lyra.music.lyrics.Lyrics
import java.util.Locale

/**
 * Scrolling lyrics. The current line is highlighted and kept centred; tapping any synced line
 * seeks playback to it. While the user is scrolling by hand, auto-centring pauses and resumes
 * a few seconds after they let go. Tapping empty space calls [onTapBackground] (back to cover).
 */
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    positionMs: Long,
    userOffsetMs: Long,
    onSeek: (Long) -> Unit,
    onAdjustOffset: (deltaMs: Long) -> Unit,
    onTapBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val noRipple = remember { MutableInteractionSource() }
    if (lyrics == null || lyrics.isEmpty) {
        Box(
            modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null, onClick = onTapBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.lyrics_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val listState = rememberLazyListState()
    val current = lyrics.indexAt(positionMs, userOffsetMs)
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }

    // Programmatic centring also reports isScrollInProgress; only hand scrolling should pause it.
    val autoScrolling = remember { booleanArrayOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !autoScrolling[0]) lastUserScrollAt = System.currentTimeMillis()
    }

    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val half = maxHeight / 2

            // Keep the current line centred unless the user scrolled within the last 3 s.
            LaunchedEffect(current, lyrics) {
                if (current < 0 || !lyrics.synced) return@LaunchedEffect
                if (System.currentTimeMillis() - lastUserScrollAt < 3_000) return@LaunchedEffect
                autoScrolling[0] = true
                try {
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (info == null) {
                        listState.animateScrollToItem(current)
                    } else {
                        // contentPadding already puts item 0 at the centre line when offset == 0,
                        // so centring means bringing (offset + size/2) to the viewport start.
                        val delta = info.offset + info.size / 2f
                        if (kotlin.math.abs(delta) > 2f) listState.animateScrollBy(delta)
                    }
                } finally {
                    autoScrolling[0] = false
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = half, bottom = half),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val isCurrent = index == current
                    val color = when {
                        !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                        isCurrent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (lyrics.synced) {
                                    lastUserScrollAt = 0L
                                    onSeek((line.timeMs - userOffsetMs).coerceAtLeast(0))
                                } else onTapBackground()
                            }
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (line.text.isNotEmpty()) {
                            Text(
                                line.text,
                                style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = color, textAlign = TextAlign.Center,
                            )
                        }
                        if (line.translation != null) {
                            Text(
                                line.translation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = color.copy(alpha = color.alpha * 0.8f), textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        if (lyrics.synced) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onAdjustOffset(-500) }) { Text("−0.5s") }
                TextButton(onClick = { onAdjustOffset(-100) }) { Text("−0.1s") }
                Text(
                    String.format(Locale.US, "%+.1fs", userOffsetMs / 1000.0),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (userOffsetMs == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                TextButton(onClick = { onAdjustOffset(100) }) { Text("+0.1s") }
                TextButton(onClick = { onAdjustOffset(500) }) { Text("+0.5s") }
            }
        }
    }
}
