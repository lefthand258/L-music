package dev.lyra.music.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.ui.library.LibraryHome
import dev.lyra.music.ui.library.LibraryViewModel
import dev.lyra.music.ui.library.SongCollectionScreen
import dev.lyra.music.ui.permissions.OnboardingScreen
import dev.lyra.music.ui.permissions.Permissions
import dev.lyra.music.ui.player.MiniPlayer
import dev.lyra.music.ui.player.NowPlayingScreen
import dev.lyra.music.ui.player.PlayerViewModel
import dev.lyra.music.ui.settings.SettingsScreen
import dev.lyra.music.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val FOLDER = "folder/{path}"
    const val SETTINGS = "settings"
    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
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

@Composable
private fun MainScaffold(openPlayerSignal: Int) {
    val nav = rememberNavController()
    val libraryVm: LibraryViewModel = hiltViewModel()
    val playerVm: PlayerViewModel = hiltViewModel()
    val playerState by playerVm.state.collectAsStateWithLifecycle()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openPlayerSignal) { if (openPlayerSignal > 0 && playerState.hasItem) playerExpanded = true }
    BackHandler(enabled = playerExpanded) { playerExpanded = false }

    val miniHeight = if (playerState.hasItem) 72.dp else 0.dp
    val bottomPadding = PaddingValues(bottom = miniHeight)

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                LibraryHome(
                    vm = libraryVm,
                    currentSongId = playerState.songId,
                    bottomPadding = bottomPadding,
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
                    vm = libraryVm, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val songs by remember(id) { libraryVm.artistSongs(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                SongCollectionScreen(
                    title = songs.firstOrNull()?.artist?.ifBlank { stringResource(R.string.unknown_artist) } ?: "",
                    subtitle = null,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    vm = libraryVm, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.FOLDER, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                val path = entry.arguments?.getString("path") ?: ""
                val songs by remember(path) { libraryVm.folderSongs(path) }.collectAsStateWithLifecycle(initialValue = emptyList())
                SongCollectionScreen(
                    title = path.substringAfterLast('/'), subtitle = path,
                    songs = songs, currentSongId = playerState.songId, bottomPadding = bottomPadding,
                    vm = libraryVm, onBack = { nav.popBackStack() },
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
            NowPlayingScreen(playerVm, onCollapse = { playerExpanded = false })
        }
    }
}
