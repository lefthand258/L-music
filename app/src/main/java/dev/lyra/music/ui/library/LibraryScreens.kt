package dev.lyra.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyra.music.R
import dev.lyra.music.data.db.AlbumRow
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.repo.ScanState
import dev.lyra.music.data.repo.SongSort
import dev.lyra.music.util.formatDuration
import kotlinx.coroutines.launch

@Composable
fun LibraryHome(
    vm: LibraryViewModel,
    actions: SongActions,
    currentSongId: Long?,
    bottomPadding: PaddingValues,
    playlistsTab: @Composable (PaddingValues) -> Unit,
    genresTab: @Composable (PaddingValues) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tabs = listOf(R.string.nav_songs, R.string.nav_playlists, R.string.nav_albums, R.string.nav_artists, R.string.nav_genres, R.string.nav_folders)
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    val query by vm.query.collectAsState()
    var sortMenu by remember { mutableStateOf(false) }
    val sort by vm.sort.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (vm.songCount.value == 0) vm.rescan() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searching) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { vm.query.value = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.search_hint)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else Text(stringResource(R.string.app_name))
                    },
                    actions = {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) vm.query.value = ""
                        }) { Icon(Icons.Default.Search, null) }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, stringResource(R.string.sort)) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SongSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text((if (s == sort) "✓ " else "") + s.label()) },
                                        onClick = { sortMenu = false; vm.setSort(s) })
                                }
                            }
                        }
                        IconButton(onClick = { vm.rescan() }) { Icon(Icons.Default.Refresh, stringResource(R.string.library_scan)) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) }
                    }
                )
                when (val s = scanState) {
                    is ScanState.Scanning -> {
                        if (s.total > 0) LinearProgressIndicator(
                            progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth()
                        ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> Unit
                }
                ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, label ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(stringResource(label)) }
                        )
                    }
                }
            }
        }
    ) { inner ->
        val padding = PaddingValues(
            top = inner.calculateTopPadding(),
            bottom = bottomPadding.calculateBottomPadding()
        )
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> {
                    val songs by vm.songs.collectAsStateWithLifecycle()
                    SongList(songs = songs, currentSongId = currentSongId, contentPadding = padding, actions = actions)
                }
                1 -> playlistsTab(padding)
                2 -> {
                    val albums by vm.albums.collectAsStateWithLifecycle()
                    AlbumGrid(albums, padding, onOpenAlbum)
                }
                3 -> {
                    val artists by vm.artists.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                        items(artists, key = { it.artistId }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenArtist(a.artistId) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.artist.ifBlank { stringResource(R.string.unknown_artist) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${a.albumCount} · ${stringResource(R.string.songs_count, a.songCount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                4 -> genresTab(padding)
                else -> {
                    val folders by vm.folders.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                        items(folders, key = { it.folderPath }) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenFolder(f.folderPath) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.folderPath.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        f.folderPath, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    stringResource(R.string.songs_count, f.songCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box {
                                    var menu by remember { mutableStateOf(false) }
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.folder_exclude)) },
                                            onClick = { menu = false; vm.excludeFolder(f.folderPath) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongSort.label(): String = when (this) {
    SongSort.TITLE -> "A-Z"
    SongSort.DATE_ADDED -> "Date added"
    SongSort.ARTIST -> "Artist"
    SongSort.DURATION -> "Duration"
    SongSort.PLAY_COUNT -> "Most played"
}

@Composable
private fun AlbumGrid(albums: List<AlbumRow>, padding: PaddingValues, onOpen: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 8.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.albumId }) { a ->
            Column(Modifier.clickable { onOpen(a.albumId) }) {
                CoverImage(
                    song = SongEntity(
                        id = a.sampleSongId, path = a.sampleSongPath, uri = "", fileName = "", folderPath = "",
                        fileSize = 0, dateAdded = 0, dateModified = 0, title = "", artist = "", album = "",
                        albumArtist = "", albumId = a.albumId, mediaStoreAlbumId = a.sampleMediaStoreAlbumId, durationMs = 0,
                        hasEmbeddedArt = a.sampleHasEmbeddedArt, externalArtPath = a.sampleExternalArtPath,
                    ),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 12
                )
                Spacer(Modifier.height(6.dp))
                Text(a.album.ifBlank { stringResource(R.string.unknown_album) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    a.albumArtist.ifBlank { stringResource(R.string.unknown_artist) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Generic "list of songs with a title" screen used for album / artist / folder detail. */
@Composable
fun SongCollectionScreen(
    title: String,
    subtitle: String?,
    songs: List<SongEntity>,
    currentSongId: Long?,
    bottomPadding: PaddingValues,
    actions: SongActions,
    onBack: () -> Unit,
    onRemoveFromPlaylist: ((SongEntity) -> Unit)? = null,
    /** Extra buttons in the app bar (playlist rename / export / delete…). */
    topActions: (@Composable () -> Unit)? = null,
    onReorder: ((List<Long>) -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { topActions?.invoke() }
            )
        }
    ) { inner ->
        SongList(
            songs = songs, currentSongId = currentSongId,
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = bottomPadding.calculateBottomPadding()),
            actions = actions,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onReorder = onReorder,
            header = if (songs.isNotEmpty()) ({
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(songs.first(), Modifier.size(120.dp), corner = 16)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${stringResource(R.string.songs_count, songs.size)} · ${formatDuration(songs.sumOf { it.durationMs })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }) else null
        )
    }
}
