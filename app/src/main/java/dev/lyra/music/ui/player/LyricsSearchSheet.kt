package dev.lyra.music.ui.player

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyra.music.R
import dev.lyra.music.lyrics.LrcParser
import dev.lyra.music.online.LyricsCandidate
import dev.lyra.music.online.OnlineSource
import dev.lyra.music.util.formatDuration

/**
 * "Search lyrics online": editable title / artist, source filter, ranked candidates, and a
 * preview step before anything is saved.
 */
@Composable
fun LyricsSearchSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ui by vm.lyricsSearchUi.collectAsStateWithLifecycle()
    val initial = remember { vm.defaultLyricsQuery() }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var artist by remember { mutableStateOf(initial?.artist.orEmpty()) }
    var source by remember { mutableStateOf<OnlineSource?>(null) }
    val savedMsg = stringResource(R.string.lyrics_saved)

    fun runSearch() {
        if (title.isNotBlank()) vm.searchLyrics(title, artist, source?.let { setOf(it) } ?: OnlineSource.entries.toSet())
    }

    // Search straight away with what the tags (or file name) say.
    LaunchedEffect(Unit) { runSearch() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val preview = ui.previewOf
            if (preview != null) {
                PreviewPane(
                    candidate = preview,
                    text = ui.previewText,
                    loading = ui.loadingPreview,
                    failed = ui.previewFailed,
                    onBack = vm::closeLyricsPreview,
                    onUse = {
                        vm.applyLyricsPreview()
                        Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                )
                return@Column
            }

            Text(stringResource(R.string.lyrics_search_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.field_title)) }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = artist, onValueChange = { artist = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.field_artist)) }, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = source == null, onClick = { source = null }, label = { Text(stringResource(R.string.source_all)) })
                OnlineSource.entries.forEach { s ->
                    FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
                }
            }
            Button(onClick = { runSearch() }, enabled = title.isNotBlank() && !ui.searching, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.search))
            }
            Spacer(Modifier.height(8.dp))

            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp), contentAlignment = Alignment.TopCenter) {
                when {
                    ui.searching -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
                    ui.searched && ui.results.isEmpty() -> Text(
                        stringResource(R.string.lyrics_search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 48.dp)
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(ui.results, key = { "${it.source}:${it.id}" }) { c ->
                            CandidateRow(c) { vm.previewLyrics(c) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(c: LyricsCandidate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        val details = buildList {
            add(c.source.label)
            if (c.durationMs > 0) add(formatDuration(c.durationMs))
            when (c.synced) {
                true -> add(stringResource(R.string.lyrics_synced))
                false -> add(stringResource(R.string.lyrics_plain))
                null -> Unit
            }
            add(stringResource(R.string.lyrics_match, (c.score * 100).toInt()))
        }
        Text(
            details.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = if (c.score >= 0.85f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreviewPane(
    candidate: LyricsCandidate,
    text: String?,
    loading: Boolean,
    failed: Boolean,
    onBack: () -> Unit,
    onUse: () -> Unit,
) {
    Text(candidate.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        listOf(candidate.artist, candidate.source.label).filter { it.isNotBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp), contentAlignment = Alignment.TopCenter) {
        when {
            loading -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
            failed || text == null -> Text(
                stringResource(R.string.lyrics_preview_failed),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 48.dp)
            )
            else -> {
                // Show the words without timestamps so the user can check it is the right song.
                val shown = remember(text) {
                    LrcParser.parse(text).lines.joinToString("\n") { l ->
                        if (l.translation != null) l.text + "\n" + l.translation else l.text
                    }
                }
                Text(shown, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Button(onClick = onUse, enabled = text != null && !loading) { Text(stringResource(R.string.lyrics_use)) }
    }
}
