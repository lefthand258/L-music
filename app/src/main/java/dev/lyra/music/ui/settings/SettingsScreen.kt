package dev.lyra.music.ui.settings

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.BuildConfig
import dev.lyra.music.R
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.data.repo.ScanState
import dev.lyra.music.ui.permissions.Permissions
import dev.lyra.music.ui.permissions.rememberPermissionState
import dev.lyra.music.util.StoragePaths
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPrefs,
    private val library: LibraryRepository,
) : ViewModel() {
    /** null until DataStore has been read once. */
    val onboarded: StateFlow<Boolean?> = prefs.onboarded.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val themeMode = prefs.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val dynamicColor = prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val writeTags = prefs.writeTagsToFile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val onlineMode = prefs.onlineMetadataMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val autoOrganise = prefs.autoOrganise.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val scanState = library.scanState
    val blacklist = library.observeBlacklist().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val libraryFolders = library.observeLibraryFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addLibraryFolder(treeUri: String, path: String) = viewModelScope.launch { library.addLibraryFolder(treeUri, path) }
    fun removeLibraryFolder(treeUri: String) = viewModelScope.launch { library.removeLibraryFolder(treeUri) }
    fun includeFolder(path: String) = viewModelScope.launch { library.includeFolder(path) }

    suspend fun setOnboarded(v: Boolean) = prefs.setOnboarded(v)
    fun setThemeMode(v: Int) = viewModelScope.launch { prefs.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { prefs.setDynamicColor(v) }
    fun setWriteTags(v: Boolean) = viewModelScope.launch { prefs.setWriteTagsToFile(v) }
    fun setOnlineMode(v: Int) = viewModelScope.launch { prefs.setOnlineMetadataMode(v) }
    fun setAutoOrganise(v: Boolean) = viewModelScope.launch { prefs.setAutoOrganise(v) }
    fun rescan() = library.requestScan()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, bottomPadding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val writeTags by vm.writeTags.collectAsStateWithLifecycle()
    val onlineMode by vm.onlineMode.collectAsStateWithLifecycle()
    val autoOrganise by vm.autoOrganise.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val allFiles = rememberPermissionState { Permissions.hasAllFiles() }
    val battery = rememberPermissionState { Permissions.isIgnoringBatteryOptimizations(context) }
    val excluded by vm.blacklist.collectAsStateWithLifecycle()
    val extraFolders by vm.libraryFolders.collectAsStateWithLifecycle()
    val unsupported = stringResource(R.string.folders_unsupported)
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = runCatching { StoragePaths.documentIdToPath(DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
            if (path == null) Toast.makeText(context, unsupported, Toast.LENGTH_LONG).show()
            else vm.addLibraryFolder(uri.toString(), path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = inner.calculateTopPadding(), bottom = bottomPadding.calculateBottomPadding())
        ) {
            Section("Library / 曲库")
            ListItem(
                headlineContent = { Text(stringResource(R.string.library_scan)) },
                supportingContent = {
                    Text(
                        when (val s = scan) {
                            is ScanState.Scanning -> stringResource(R.string.library_scanning) + " ${s.done}/${s.total}"
                            is ScanState.Finished -> stringResource(R.string.library_scan_done, s.songCount)
                            is ScanState.Failed -> s.message
                            ScanState.Idle -> "—"
                        }
                    )
                },
                modifier = Modifier.clickable { vm.rescan() }
            )
            ToggleRow(
                title = stringResource(R.string.perm_all_files_title),
                subtitle = if (allFiles) "Granted / 已授权" else "Not granted / 未授权 – tap to open settings",
                checked = allFiles,
                onToggle = { runCatching { context.startActivity(Permissions.allFilesIntent(context)) } },
            )
            ToggleRow(
                title = stringResource(R.string.perm_battery_title),
                subtitle = if (battery) "Unrestricted / 不受限" else "Restricted / 受限 – tap to open settings",
                checked = battery,
                onToggle = { runCatching { context.startActivity(Permissions.batteryIntent(context)) } },
            )
            Section(stringResource(R.string.folders_extra))
            Text(
                stringResource(R.string.folders_extra_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)
            )
            extraFolders.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.displayName) },
                    trailingContent = { TextButton(onClick = { vm.removeLibraryFolder(f.treeUri) }) { Text(stringResource(R.string.remove)) } }
                )
            }
            TextButton(onClick = { treePicker.launch(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+ " + stringResource(R.string.folders_add))
            }

            Section(stringResource(R.string.folders_excluded))
            if (excluded.isEmpty()) {
                Text(
                    stringResource(R.string.folders_excluded_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            excluded.forEach { path ->
                ListItem(
                    headlineContent = { Text(path) },
                    trailingContent = { TextButton(onClick = { vm.includeFolder(path) }) { Text(stringResource(R.string.remove)) } }
                )
            }
            HorizontalDivider()

            Section("Metadata / 元数据")
            ToggleRow(
                title = "Write tags back to files / 写回文件标签",
                subtitle = "Corrections are also stored in Lyra and can be restored",
                checked = writeTags, onToggle = { vm.setWriteTags(!writeTags) },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Online identification / 在线识别", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Off 关", "Wi-Fi only 仅Wi-Fi", "Always 始终").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = onlineMode == i, onClick = { vm.setOnlineMode(i) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            ToggleRow(
                title = "Auto-organise files / 自动整理文件",
                subtitle = "{AlbumArtist}/{Album}/{Track} - {Title} (preview before moving)",
                checked = autoOrganise, onToggle = { vm.setAutoOrganise(!autoOrganise) },
            )
            HorizontalDivider()

            Section("Appearance / 外观")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("System 跟随系统", "Light 浅色", "Dark 深色").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = themeMode == i, onClick = { vm.setThemeMode(i) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            ToggleRow(
                title = "Dynamic colour / 动态取色",
                subtitle = "Material You",
                checked = dynamic, onToggle = { vm.setDynamicColor(!dynamic) },
            )
            HorizontalDivider()

            Section("About / 关于")
            ListItem(
                headlineContent = { Text("Lyra Music ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Open source (GPL-3.0). No analytics, no tracking.") }
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
