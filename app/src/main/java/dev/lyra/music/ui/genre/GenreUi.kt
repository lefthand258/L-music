package dev.lyra.music.ui.genre

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.R
import dev.lyra.music.data.db.GenreEntity
import dev.lyra.music.data.db.GenreRow
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.GenreRepository
import dev.lyra.music.ui.playlist.ConfirmDialog
import dev.lyra.music.ui.playlist.NameDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GenreViewModel @Inject constructor(private val repo: GenreRepository) : ViewModel() {
    val all: StateFlow<List<GenreEntity>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val withCounts: StateFlow<List<GenreRow>> = repo.observeWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { repo.seedIfNeeded() } }

    fun forSong(songId: Long): Flow<List<GenreEntity>> = repo.observeForSong(songId)
    fun songsOf(genreId: Long): Flow<List<SongEntity>> = repo.observeSongs(genreId)
    fun genre(id: Long): Flow<GenreEntity?> = repo.observeGenre(id)

    fun setSongGenres(songId: Long, ids: Set<Long>) = viewModelScope.launch { repo.setSongGenres(songId, ids) }
    fun create(name: String, onCreated: (GenreEntity) -> Unit = {}) = viewModelScope.launch { repo.create(name)?.let(onCreated) }
    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

/** Multi-select genre tags for one song; new genres can be typed in straight away. */
@Composable
fun GenrePickerDialog(song: SongEntity, onDismiss: () -> Unit, onManage: () -> Unit, vm: GenreViewModel = hiltViewModel()) {
    val all by vm.all.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Set<Long>?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(song.id) { selected = vm.forSong(song.id).first().map { it.id }.toSet() }
    val sel = selected ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.genre_set_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    all.forEach { g ->
                        FilterChip(
                            selected = g.id in sel,
                            onClick = { selected = if (g.id in sel) sel - g.id else sel + g.id },
                            label = { Text(g.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(GenreRepository.MAX_NAME) }, singleLine = true,
                        label = { Text(stringResource(R.string.genre_new)) }, modifier = Modifier.weight(1f)
                    )
                    IconButton(enabled = newName.isNotBlank(), onClick = {
                        vm.create(newName) { g -> selected = (selected ?: emptySet()) + g.id }
                        newName = ""
                    }) { Icon(Icons.Default.Add, stringResource(R.string.genre_new)) }
                }
                TextButton(onClick = onManage) { Text(stringResource(R.string.genre_manage)) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setSongGenres(song.id, sel); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Read-only chips shown in the player; tapping opens the picker. */
@Composable
fun GenreChips(songId: Long, onClick: () -> Unit, vm: GenreViewModel = hiltViewModel()) {
    val genres by remember(songId) { vm.forSong(songId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        genres.forEach { g ->
            AssistChip(onClick = onClick, label = { Text(g.name, style = MaterialTheme.typography.labelSmall) })
        }
        AssistChip(
            onClick = onClick,
            label = { Text(if (genres.isEmpty()) stringResource(R.string.genre_add) else "+", style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.width(14.dp)) },
        )
    }
}

/** Library tab: every genre with its song count. */
@Composable
fun GenresTab(padding: PaddingValues, onOpen: (Long) -> Unit, onManage: () -> Unit, vm: GenreViewModel = hiltViewModel()) {
    val rows by vm.withCounts.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        items(rows, key = { it.id }) { g ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(g.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(g.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.songs_count, g.songCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("manage") {
            TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.genre_manage)) }
        }
    }
}

/** Full-screen list to add / rename / delete genres. */
@Composable
fun GenreManageScreen(bottomPadding: PaddingValues, onBack: () -> Unit, vm: GenreViewModel = hiltViewModel()) {
    val rows by vm.withCounts.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<GenreRow?>(null) }
    var deleting by remember { mutableStateOf<GenreRow?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.genre_manage)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.genre_new)) } },
            )
        }
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = bottomPadding.calculateBottomPadding())) {
            items(rows, key = { it.id }) { g ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.songs_count, g.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { renaming = g }) { Icon(Icons.Default.Edit, stringResource(R.string.playlist_rename)) }
                    IconButton(onClick = { deleting = g }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    val label = stringResource(R.string.genre_name)
    if (adding) NameDialog(stringResource(R.string.genre_new), "", onDismiss = { adding = false }, label = label) { vm.create(it); adding = false }
    renaming?.let { g -> NameDialog(stringResource(R.string.playlist_rename), g.name, onDismiss = { renaming = null }, label = label) { vm.rename(g.id, it); renaming = null } }
    deleting?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.genre_delete),
            message = stringResource(R.string.genre_delete_confirm, g.name, g.songCount),
            confirmLabel = stringResource(R.string.delete), destructive = true,
            onDismiss = { deleting = null }, onConfirm = { vm.delete(g.id); deleting = null },
        )
    }
}
