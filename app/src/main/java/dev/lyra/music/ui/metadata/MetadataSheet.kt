package dev.lyra.music.ui.metadata

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.R
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.scanner.TagWriter
import dev.lyra.music.online.ApplyResult
import dev.lyra.music.online.LyricsQuery
import dev.lyra.music.online.MetaField
import dev.lyra.music.online.MetadataCandidate
import dev.lyra.music.online.MetadataService
import dev.lyra.music.online.OnlineSource
import dev.lyra.music.ui.library.CoverImage
import dev.lyra.music.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MetadataUi(
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<MetadataCandidate> = emptyList(),
    val applying: Boolean = false,
)

@HiltViewModel
class MetadataViewModel @Inject constructor(
    private val service: MetadataService,
) : ViewModel() {
    private val _ui = MutableStateFlow(MetadataUi())
    val ui: StateFlow<MetadataUi> = _ui.asStateFlow()
    private var job: Job? = null

    fun defaultQuery(song: SongEntity): LyricsQuery = service.queryFor(song)

    fun search(song: SongEntity, title: String, artist: String, sources: Set<OnlineSource>) {
        job?.cancel()
        _ui.value = MetadataUi(searching = true)
        job = viewModelScope.launch {
            val q = LyricsQuery(title.trim(), artist.trim(), song.album, song.durationMs)
            _ui.value = MetadataUi(searched = true, results = service.search(q, sources))
        }
    }

    fun apply(song: SongEntity, c: MetadataCandidate, fields: Set<MetaField>, onDone: (ApplyResult) -> Unit) {
        if (_ui.value.applying) return
        _ui.value = _ui.value.copy(applying = true)
        viewModelScope.launch {
            val r = service.apply(song, c, fields)
            _ui.value = _ui.value.copy(applying = false)
            onDone(r)
        }
    }
}

/**
 * "Identify online": search with editable title / artist, pick a result, then choose field by
 * field what to take from it (current value → new value) before anything is changed.
 */
@Composable
fun MetadataSheet(song: SongEntity, onDismiss: () -> Unit, vm: MetadataViewModel = hiltViewModel(key = "meta-${song.id}")) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val initial = remember(song.id) { vm.defaultQuery(song) }
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var source by remember { mutableStateOf<OnlineSource?>(null) }
    var picked by remember { mutableStateOf<MetadataCandidate?>(null) }

    val msgFile = stringResource(R.string.meta_applied_file)
    val msgDb = stringResource(R.string.meta_applied_db)
    val msgFileFailed = stringResource(R.string.meta_applied_file_failed)
    val msgCoverFailed = stringResource(R.string.meta_cover_failed)

    fun runSearch() {
        if (title.isNotBlank()) vm.search(song, title, artist, source?.let { setOf(it) } ?: OnlineSource.METADATA.toSet())
    }
    LaunchedEffect(Unit) { runSearch() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val c = picked
            if (c != null) {
                ComparePane(
                    song = song, c = c, applying = ui.applying,
                    onBack = { picked = null },
                    onApply = { fields ->
                        vm.apply(song, c, fields) { r ->
                            val msg = buildString {
                                append(
                                    when (r.fileWrite) {
                                        TagWriter.Result.WRITTEN -> msgFile
                                        TagWriter.Result.FAILED -> msgFileFailed
                                        else -> msgDb
                                    }
                                )
                                if (!r.coverOk) append("\n").append(msgCoverFailed)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    },
                )
                return@Column
            }

            Text(stringResource(R.string.meta_title), style = MaterialTheme.typography.titleMedium)
            Text(song.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                OnlineSource.METADATA.forEach { s ->
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
                        stringResource(R.string.meta_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 48.dp)
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(ui.results, key = { "${it.source}:${it.id}" }) { cand ->
                            CandidateRow(cand) { picked = cand }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteCover(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun CandidateRow(c: MetadataCandidate, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteCover(c.coverUrl, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val details = buildList {
                add(c.source.label)
                if (c.durationMs > 0) add(formatDuration(c.durationMs))
                c.year?.let { add(it.toString()) }
                add(stringResource(R.string.lyrics_match, (c.score * 100).toInt()))
            }
            Text(
                details.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = if (c.score >= 0.85f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ComparePane(song: SongEntity, c: MetadataCandidate, applying: Boolean, onBack: () -> Unit, onApply: (Set<MetaField>) -> Unit) {
    data class RowData(val field: MetaField, val label: Int, val old: String, val new: String)
    val rows = remember(song, c) {
        listOf(
            RowData(MetaField.TITLE, R.string.field_title, song.title, c.title),
            RowData(MetaField.ARTIST, R.string.field_artist, song.artist, c.artist),
            RowData(MetaField.ALBUM, R.string.field_album, song.album, c.album),
            RowData(MetaField.YEAR, R.string.field_year, song.year?.toString().orEmpty(), c.year?.toString().orEmpty()),
            RowData(MetaField.GENRE, R.string.field_genre, song.genre.orEmpty(), c.genre.orEmpty()),
        ).filter { it.new.isNotBlank() }
    }
    // Pre-tick what would actually change; the user can untick anything.
    var selected by remember(song, c) {
        mutableStateOf(
            rows.filter { it.old != it.new }.map { it.field }.toSet() + if (c.coverUrl != null) setOf(MetaField.COVER) else emptySet()
        )
    }
    fun toggle(f: MetaField) { selected = if (f in selected) selected - f else selected + f }

    Text(c.source.label + " · " + stringResource(R.string.lyrics_match, (c.score * 100).toInt()), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { toggle(r.field) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.field in selected, onCheckedChange = { toggle(r.field) })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(r.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.meta_current) + ": " + r.old.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.meta_new) + ": " + r.new,
                        color = if (r.old != r.new) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (c.coverUrl != null) {
            Row(Modifier.fillMaxWidth().clickable { toggle(MetaField.COVER) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = MetaField.COVER in selected, onCheckedChange = { toggle(MetaField.COVER) })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_cover), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(song, Modifier.size(84.dp))
                        Text("  →  ")
                        RemoteCover(c.coverUrl, Modifier.size(84.dp))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = !applying) { Text(stringResource(R.string.back)) }
        if (applying) CircularProgressIndicator(Modifier.size(28.dp))
        else Button(onClick = { onApply(selected) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.meta_apply)) }
    }
}
