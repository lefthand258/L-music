package dev.lyra.music.ui.nav

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lyra.music.R
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.DeleteOutcome
import dev.lyra.music.ui.library.LibraryHome
import dev.lyra.music.ui.library.LibraryViewModel
import dev.lyra.music.ui.library.SongActions
import dev.lyra.music.ui.library.SongCollectionScreen
import dev.lyra.music.ui.metadata.MetadataSheet
import dev.lyra.music.ui.permissions.OnboardingScreen
import dev.lyra.music.ui.permissions.Permissions
import dev.lyra.music.ui.player.MiniPlayer
import dev.lyra.music.ui.player.NowPlayingScreen
import dev.lyra.music.ui.player.PlayerViewModel
import dev.lyra.music.ui.playlist.AddToPlaylistDialog
import dev.lyra.music.ui.playlist.ConfirmDialog
import dev.lyra.music.ui.playlist.DeleteFileDialog
import dev.lyra.music.ui.playlist.NameDialog
import dev.lyra.music.ui.playlist.PlaylistMenu
import dev.lyra.music.ui.playlist.PlaylistViewModel
import dev.lyra.music.ui.playlist.PlaylistsTab
import dev.lyra.music.ui.playlist.SmartList
import dev.lyra.music.ui.settings.SettingsScreen
import dev.lyra.music.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val FOLDER = "folder/{path}"
    const val PLAYLIST = "playlist/{id}"
    const val SMART = "smart/{kind}"
    const val SETTINGS = "settings"
    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
    fun playlist(id: Long) = "playlist/$id"
    fun smart(kind: SmartList) = "smart/${kind.name}"
}

@Composable
fun LyraRoot(openPlayerSignal: Int) {
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = hiltViewModel()
    val onboarded by settingsVm.onboarded.collectAsStateWithLifecycle()
    var audioGranted by rememberSaveable { mutableStateOf(Permissions.hasAudio(context)) }
    val scope = rememberCoroutineScope()

    if (onboarded == null) return // prefs not loaded yet (splash still visible)

    if (!audioGranted || onboarded == false) {
        OnboardingScreen(onDone = {
            audioGranted = Permissions.hasAudio(context)
            scope.launch { settingsVm.setOnboarded(true) }
        })
        return
    }

    MainScaffold(openPlayerSignal)
}

/** What the "name" dialog is currently being used for. */
private sealed interface NameRequest {
    /** Create a playlist, optionally adding a song straight away. */
    data class Create(val thenAddSongId: Long?) : NameRequest
    data class Rename(val playlistId: Long, val current: String) : NameRequest
}

@Composable
private fun MainScaffold(openPlayerSignal: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val libraryVm: LibraryViewModel = hiltViewModel()
    val playerVm: PlayerViewModel = hiltViewModel()
    val playlistVm: PlaylistViewModel = hiltViewModel()
    val playerState by playerVm.state.collectAsStateWithLifecycle()
    val playlists by playlistVm.all.collectAsStateWithLifecycle()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }

    // ---- Dialog / picker state ---------------------------------------------------------------
    var addToPlaylistSong by remember { mutableStateOf<SongEntity?>(null) }
    var nameRequest by remember { mutableStateOf<NameRequest?>(null) }
    var deleteFileSong by remember { mutableStateOf<SongEntity?>(null) }
    var identifySong by remember { mutableStateOf<SongEntity?>(null) }
    var deletePlaylist by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var pendingSystemDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportM3uPlaylistId by remember { mutableStateOf<Long?>(null) }
    var exportCsvPlaylistIds by remember { mutableStateOf<List<Long>?>(null) }

    val msgDeleted = stringResource(R.string.delete_file_done)
    val msgDeleteFailed = stringResource(R.string.delete_file_failed)
    val msgExported = stringResource(R.string.playlist_exported)
    val msgExportFailed = stringResource(R.string.playlist_export_failed)
    val msgImportFailed = stringResource(R.string.playlist_import_failed)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun report(outcome: DeleteOutcome.Done) = toast(if (outcome.deleted > 0 && outcome.failed == 0) msgDeleted else msgDeleteFailed)

    val systemDeleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val paths = pendingSystemDeletePaths
        pendingSystemDeletePaths = emptyList()
        if (result.resultCode == Activity.RESULT_OK && paths.isNotEmpty()) {
            scope.launch {
                when (val o = playlistVm.onDeleteConfirmedBySystem(paths)) {
                    is DeleteOutcome.Done -> report(o)
                    is DeleteOutcome.NeedsUserConsent -> toast(msgDeleteFailed)
                }
            }
        }
    }

    val m3uSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val id = exportM3uPlaylistId
        exportM3uPlaylistId = null
        if (uri != null && id != null) scope.launch {
            toast(if (playlistVm.exportM3uTo(id, uri)) msgExported else msgExportFailed)
        }
    }
    var csvExportArmed by remember { mutableStateOf(false) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val ids = exportCsvPlaylistIds
        val armed = csvExportArmed
        exportCsvPlaylistIds = null; csvExportArmed = false
        if (uri != null && armed) scope.launch {
            toast(if (playlistVm.exportCsvTo(ids, uri)) msgExported else msgExportFailed)
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = playlistVm.importFrom(uri)
            if (r == null) toast(msgImportFailed)
            else Toast.makeText(
                context, context.getString(R.string.playlist_import_result, r.matched, r.unmatched.size), Toast.LENGTH_LONG
            ).show()
        }
    }

    val actions = remember(libraryVm) {
        SongActions(
            onPlay = libraryVm::play,
            onPlayAll = libraryVm::playAll,
            onShuffleAll = libraryVm::shuffleAll,
            onPlayNext = libraryVm::playNext,
            onAddToQueue = libraryVm::addToQueue,
            onToggleFavourite = libraryVm::toggleFavourite,
            onAddToPlaylist = { addToPlaylistSong = it },
            onIdentify = { identifySong = it },
            onHide = libraryVm::hide,
            onDeleteFile = { deleteFileSong = it },
        )
    }

    LaunchedEffect(openPlayerSignal) { if (openPlayerSignal > 0 && playerState.hasItem) playerExpanded = true }
    BackHandler(enabled = playerExpanded) { playerExpanded = false }

    val miniHeight = if (playerState.hasItem) 72.dp else 0.dp
    val bottomPadding = PaddingValues(bottom = miniHeight)

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                LibraryHome(
                    vm = libraryVm,
                    actions = actions,
                    currentSongId = playerState.songId,
                    bottomPadding = bottomPadding,
                    playlistsTab = { padding ->
                        PlaylistsTab(
                            playlists = playlists,
                            padding = padding,
                            onOpenSmart = { nav.navigate(Routes.smart(it)) },
                            onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                            onCreate = { nameRequest = NameRequest.Create(null) },
                            onImport = { importPicker.launch(arrayOf("*/*")) },
                            onExportAll = {
                                exportCsvPlaylistIds = null; csvExportArmed = true
                                csvSaver.launch("lyra-playlists.csv")
                            },
                        )
                    },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                    onOpenArtist = { nav.navigate(Routes.artist(it)) },
                    onOpenFolder = { nav.navigate(Routes.folder(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val songs by remember(id) { libraryVm.albumSongs(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                val first = songs.firstOrNull()
                SongCollectionScreen(
                    title = first?.album?.ifBlank { stringResource(R.string.unknown_album) } ?: "",
                    subtitle = first?.albumArtist?.ifBlank { first.artist },
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    actions = actions, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val songs by remember(id) { libraryVm.artistSongs(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                SongCollectionScreen(
                    title = songs.firstOrNull()?.artist?.ifBlank { stringResource(R.string.unknown_artist) } ?: "",
                    subtitle = null,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    actions = actions, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.FOLDER, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                val path = entry.arguments?.getString("path") ?: ""
                val songs by remember(path) { libraryVm.folderSongs(path) }.collectAsStateWithLifecycle(initialValue = emptyList())
                SongCollectionScreen(
                    title = path.substringAfterLast('/'), subtitle = path,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    actions = actions, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val playlist by remember(id) { playlistVm.playlist(id) }.collectAsStateWithLifecycle(initialValue = null)
                val songs by remember(id) { playlistVm.songs(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                val name = playlist?.name ?: ""
                SongCollectionScreen(
                    title = name, subtitle = null,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    actions = actions, onBack = { nav.popBackStack() },
                    onRemoveFromPlaylist = { playlistVm.removeSong(id, it.id) },
                    topActions = {
                        PlaylistMenu(
                            onRename = { nameRequest = NameRequest.Rename(id, name) },
                            onExportM3u = { exportM3uPlaylistId = id; m3uSaver.launch("$name.m3u8") },
                            onExportCsv = {
                                exportCsvPlaylistIds = listOf(id); csvExportArmed = true
                                csvSaver.launch("$name.csv")
                            },
                            onDelete = { deletePlaylist = id to name },
                        )
                    },
                )
            }
            composable(Routes.SMART, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                val kind = runCatching { SmartList.valueOf(entry.arguments?.getString("kind") ?: "") }
                    .getOrDefault(SmartList.FAVOURITES)
                val songs by remember(kind) { playlistVm.smart(kind) }.collectAsStateWithLifecycle(initialValue = emptyList())
                SongCollectionScreen(
                    title = stringResource(
                        when (kind) {
                            SmartList.FAVOURITES -> R.string.smart_favourites
                            SmartList.RECENT -> R.string.smart_recent
                            SmartList.MOST_PLAYED -> R.string.smart_most_played
                        }
                    ),
                    subtitle = null,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    actions = actions, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() }, bottomPadding = bottomPadding)
            }
        }

        AnimatedVisibility(
            visible = playerState.hasItem && !playerExpanded,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MiniPlayer(playerVm, onExpand = { playerExpanded = true }, modifier = Modifier.navigationBarsPadding())
        }

        AnimatedVisibility(
            visible = playerExpanded,
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            NowPlayingScreen(playerVm, onCollapse = { playerExpanded = false }, onIdentify = { identifySong = it })
        }
    }

    // ---- Dialogs -------------------------------------------------------------------------------

    identifySong?.let { song ->
        MetadataSheet(song = song, onDismiss = { identifySong = null })
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addToPlaylistSong = null },
            onPick = { pid -> playlistVm.addSongs(pid, listOf(song.id)); addToPlaylistSong = null },
            onCreateNew = { addToPlaylistSong = null; nameRequest = NameRequest.Create(song.id) },
        )
    }

    nameRequest?.let { req ->
        NameDialog(
            title = stringResource(if (req is NameRequest.Rename) R.string.playlist_rename else R.string.playlist_new),
            initial = (req as? NameRequest.Rename)?.current ?: "",
            onDismiss = { nameRequest = null },
            onConfirm = { name ->
                when (req) {
                    is NameRequest.Create -> playlistVm.create(name, listOfNotNull(req.thenAddSongId))
                    is NameRequest.Rename -> playlistVm.rename(req.playlistId, name)
                }
                nameRequest = null
            },
        )
    }

    deletePlaylist?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.playlist_delete),
            message = stringResource(R.string.playlist_delete_confirm, name),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onDismiss = { deletePlaylist = null },
            onConfirm = {
                deletePlaylist = null
                playlistVm.delete(id)
                nav.popBackStack()
            },
        )
    }

    deleteFileSong?.let { song ->
        DeleteFileDialog(
            song = song,
            onDismiss = { deleteFileSong = null },
            onConfirm = {
                deleteFileSong = null
                scope.launch {
                    when (val o = playlistVm.deleteFiles(listOf(song))) {
                        is DeleteOutcome.Done -> report(o)
                        is DeleteOutcome.NeedsUserConsent -> {
                            pendingSystemDeletePaths = o.paths
                            systemDeleteLauncher.launch(IntentSenderRequest.Builder(o.sender).build())
                        }
                    }
                }
            },
        )
    }
}
