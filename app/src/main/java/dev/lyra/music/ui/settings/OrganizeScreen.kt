package dev.lyra.music.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.R
import dev.lyra.music.data.db.MoveLogEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.organize.FileOrganizer
import dev.lyra.music.organize.OrganizePlan
import dev.lyra.music.organize.OrganizeState
import dev.lyra.music.organize.OrganizeTemplate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrganizeViewModel @Inject constructor(
    private val organizer: FileOrganizer,
    private val prefs: UserPrefs,
    private val library: LibraryRepository,
) : ViewModel() {
    val template = prefs.organiseTemplate.stateIn(viewModelScope, SharingStarted.Eagerly, OrganizeTemplate.DEFAULT)
    val auto = prefs.autoOrganise.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val state = organizer.state
    val recent = organizer.observeRecentMoves().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTemplate(t: String) = viewModelScope.launch { prefs.setOrganiseTemplate(t.take(300)) }
    fun setAuto(v: Boolean) = viewModelScope.launch { prefs.setAutoOrganise(v) }
    suspend fun plan(template: String): OrganizePlan = organizer.plan(library.getAllSongs(), template)
    fun runAll() = organizer.startAll()
    fun cancel() = organizer.cancel()
    suspend fun undo(e: MoveLogEntity): Boolean = organizer.undo(e)
}

@Composable
fun OrganizeScreen(bottomPadding: PaddingValues, onBack: () -> Unit, vm: OrganizeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by vm.template.collectAsStateWithLifecycle()
    val auto by vm.auto.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    var template by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (template.isBlank()) template = saved }
    var plan by remember { mutableStateOf<OrganizePlan?>(null) }
    var planning by remember { mutableStateOf(false) }
    val undoFailed = stringResource(R.string.organize_undo_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.organize_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = bottomPadding.calculateBottomPadding() + 16.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = template, onValueChange = { template = it.take(300); plan = null }, singleLine = true,
                        label = { Text(stringResource(R.string.organize_template)) }, modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.organize_template_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(enabled = template.isNotBlank() && !planning, onClick = {
                            planning = true
                            vm.setTemplate(template)
                            scope.launch { plan = vm.plan(template); planning = false }
                        }) { Text(stringResource(R.string.organize_preview)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { template = OrganizeTemplate.DEFAULT; plan = null }) { Text("↺") }
                    }
                    Row(Modifier.fillMaxWidth().clickable { vm.setAuto(!auto) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.organize_auto), Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { vm.setAuto(it) })
                    }

                    plan?.let { p ->
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.organize_plan, p.moves.size, p.alreadyInPlace, p.skipped), style = MaterialTheme.typography.bodyMedium)
                        if (p.needsPermission) Text(stringResource(R.string.organize_need_permission), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        p.moves.take(8).forEach { m ->
                            Text(m.from.absolutePath.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("→ " + m.to.absolutePath.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (p.moves.size > 8) Text("…", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        when (val s = state) {
                            is OrganizeState.Running -> {
                                LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.organize_running, s.done, s.total, s.failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) }
                                }
                            }
                            is OrganizeState.Finished -> Text(stringResource(R.string.organize_done, s.moved, s.failed), style = MaterialTheme.typography.bodySmall)
                            OrganizeState.Idle -> Unit
                        }
                        if (state !is OrganizeState.Running) {
                            Button(enabled = p.moves.isNotEmpty() && !p.needsPermission, onClick = { vm.setTemplate(template); vm.runAll(); plan = null }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.organize_run, p.moves.size))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.organize_recent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            items(recent, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.toPath.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.fromPath.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ " + e.toPath.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { scope.launch { if (!vm.undo(e)) Toast.makeText(context, undoFailed, Toast.LENGTH_SHORT).show() } }) {
                        Text(stringResource(R.string.organize_undo))
                    }
                }
            }
        }
    }
}
