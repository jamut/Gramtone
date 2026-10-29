/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)

package com.metrolist.music.telegram

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.queues.ListQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

internal fun fmt(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)

internal fun buildAlbums(tracks: List<TgTrack>): List<TgAlbum> =
    tracks.filter { it.album.isNotBlank() }.groupBy { it.albumKey }.map { (key, list) ->
        val sorted = list.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase() }))
        TgAlbum(
            key = key,
            title = sorted.first().displayAlbum,
            artist = sorted.first().albumArtist.ifBlank { sorted.first().displayArtist },
            year = sorted.maxOf { it.year },
            coverPath = sorted.firstNotNullOfOrNull { it.coverPath },
            tracks = sorted,
        )
    }.sortedBy { it.title.lowercase() }

internal fun artistOf(t: TgTrack): String = t.albumArtist.ifBlank { t.artist }.ifBlank { "Unknown artist" }

private fun buildArtists(tracks: List<TgTrack>): List<TgArtist> =
    tracks.groupBy { artistOf(it) }.map { (name, list) ->
        TgArtist(
            name = name,
            albums = list.filter { it.album.isNotBlank() }.map { it.albumKey }.distinct().size,
            tracks = list.size,
            coverPath = list.firstNotNullOfOrNull { it.coverPath },
        )
    }.sortedBy { it.name.lowercase() }

@Composable
fun TelegramLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tg_poc", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        TelegramCatalog.init(context)
        TelegramClient.autoStart(context)
    }
    val auth by TelegramClient.auth.collectAsState()
    var chatId by remember { mutableStateOf(prefs.getLong("chat_id", 0L)) }

    when {
        auth != TelegramClient.AuthState.READY -> TgLoginPanel(prefs, onBack)
        chatId == 0L -> TgChannelPicker(onBack) { id, title ->
            prefs.edit().putLong("chat_id", id).putString("chat_title", title).apply()
            TelegramCatalog.clear()
            chatId = id
        }
        else -> TgLibrary(
            chatId = chatId,
            onBack = onBack,
            onChangeChannel = {
                TelegramSync.stop()
                prefs.edit().remove("chat_id").apply()
                chatId = 0L
            },
        )
    }
}

@Composable
private fun TgLoginPanel(prefs: android.content.SharedPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    val auth by TelegramClient.auth.collectAsState()
    var apiId by remember { mutableStateOf(prefs.getString("id", "") ?: "") }
    var apiHash by remember { mutableStateOf(prefs.getString("hash", "") ?: "") }
    var input by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("< Back to library") }
        Text("Connect Telegram", style = MaterialTheme.typography.titleLarge)
        when (auth) {
            TelegramClient.AuthState.NOT_STARTED, TelegramClient.AuthState.ERROR -> {
                Text("Get api_id and api_hash at my.telegram.org", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(apiId, { apiId = it }, label = { Text("api_id") }, singleLine = true)
                OutlinedTextField(apiHash, { apiHash = it }, label = { Text("api_hash") }, singleLine = true)
                Button(onClick = {
                    val idNum = apiId.trim().toIntOrNull()
                    if (idNum != null && apiHash.isNotBlank()) {
                        prefs.edit().putString("id", apiId.trim()).putString("hash", apiHash.trim()).apply()
                        TelegramClient.start(context.applicationContext, idNum, apiHash.trim())
                    }
                }) { Text("Start") }
            }
            TelegramClient.AuthState.WAIT_PHONE -> {
                OutlinedTextField(input, { input = it }, label = { Text("Phone, e.g. +639...") }, singleLine = true)
                Button(onClick = { TelegramClient.submitPhone(input.trim()); input = "" }) { Text("Send code") }
            }
            TelegramClient.AuthState.WAIT_CODE -> {
                OutlinedTextField(input, { input = it }, label = { Text("Login code") }, singleLine = true)
                Button(onClick = { TelegramClient.submitCode(input.trim()); input = "" }) { Text("Submit") }
            }
            TelegramClient.AuthState.WAIT_PASSWORD -> {
                OutlinedTextField(input, { input = it }, label = { Text("2FA password") }, singleLine = true)
                Button(onClick = { TelegramClient.submitPassword(input); input = "" }) { Text("Submit") }
            }
            TelegramClient.AuthState.READY -> Text("Connected")
        }
    }
}

@Composable
private fun TgChannelPicker(onBack: () -> Unit, onPicked: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<TdApi.Chat>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        status = "Loading chats..."
        chats = withContext(Dispatchers.IO) {
            runCatching { TelegramClient.listChats() }.getOrElse { status = it.message ?: "Error"; emptyList() }
        }
        if (chats.isNotEmpty()) status = ""
    }

    LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("< Back to library") }
                Text("Pick your music channel", style = MaterialTheme.typography.titleLarge)
                if (status.isNotEmpty()) Text(status)
            }
        }
        items(chats, key = { it.id }) { c ->
            Text(
                c.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { scope.launch { onPicked(c.id, c.title) } }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
internal fun TgCover(path: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.library_music_outlined), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TgLibrary(chatId: Long, onBack: () -> Unit, onChangeChannel: () -> Unit) {
    val context = LocalContext.current
    val allTracks by TelegramCatalog.tracks.collectAsState()
    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsState()
    val offlineIds = remember(downloads) { downloads.filterValues { it.state == Download.STATE_COMPLETED }.keys }
    val usedBytes = remember(downloads) { downloadUtil.downloadCache.cacheSpace }
    var onlyDownloaded by rememberSaveable { mutableStateOf(false) }
    val tracks = remember(allTracks, offlineIds, onlyDownloaded) {
        if (onlyDownloaded) allTracks.filter { it.id in offlineIds } else allTracks
    }
    val sync by TelegramSync.state.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    var tab by rememberSaveable { mutableStateOf(0) } // 0 songs, 1 albums, 2 artists
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var artistFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showEdit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId) { TelegramSync.start(context, chatId) }
    BackHandler(enabled = openAlbum != null) { openAlbum = null }

    if (showEdit) {
        TgEditDialog(
            count = selected.size,
            single = tracks.firstOrNull { it.id in selected }.takeIf { selected.size == 1 },
            onDismiss = { showEdit = false },
            onSave = { title, artist, album, year, trackNo ->
                val ids = selected
                showEdit = false
                selected = emptySet()
                selectMode = false
                scope.launch(Dispatchers.IO) {
                    TelegramCatalog.tracks.value.filter { it.id in ids }.forEach { t ->
                        TelegramCatalog.update(
                            t.copy(
                                title = if (title.isNotBlank()) title else t.title,
                                artist = if (artist.isNotBlank()) artist else t.artist,
                                albumArtist = if (artist.isNotBlank()) artist else t.albumArtist,
                                album = if (album.isNotBlank()) album else t.album,
                                year = year ?: t.year,
                                trackNo = trackNo ?: t.trackNo,
                                indexed = true,
                            ),
                        )
                    }
                    TelegramCatalog.refresh()
                }
            },
        )
    }

    val albums = remember(tracks) { buildAlbums(tracks) }
    val artists = remember(tracks) { buildArtists(tracks) }
    val q = query.trim().lowercase()

    fun play(list: List<TgTrack>, index: Int, title: String) {
        if (list.isEmpty()) return
        playerConnection?.playQueue(ListQueue(title = title, items = list.map { it.toMediaItem() }, startIndex = index))
    }

    val header: @Composable () -> Unit = {
        TgHeader(
            tab = tab,
            onTab = { tab = it; openAlbum = null },
            query = query,
            onQuery = { query = it },
            artistFilter = artistFilter,
            onClearArtist = { artistFilter = null },
            sync = sync,
            songCount = tracks.size,
            onlyDownloaded = onlyDownloaded,
            onToggleDownloaded = { onlyDownloaded = !onlyDownloaded },
            usedBytes = usedBytes,
            selectMode = selectMode,
            selectedCount = selected.size,
            onToggleSelect = { selectMode = !selectMode; if (!selectMode) selected = emptySet() },
            onEditSelected = { if (selected.isNotEmpty()) showEdit = true },
            onSelectAll = { selected = tracks.map { it.id }.toSet() },
            onBack = onBack,
            onSync = { TelegramSync.start(context, chatId) },
            onChangeChannel = onChangeChannel,
            onStop = { TelegramSync.stop() },
        )
    }

    val album = openAlbum?.let { key -> albums.firstOrNull { it.key == key } }
    if (album != null) {
        TgAlbumDetail(album, onClose = { openAlbum = null }, onPlay = { i -> play(album.tracks, i, album.title) }, onShuffle = { play(album.tracks.shuffled(), 0, album.title) })
        return
    }

    when (tab) {
        0 -> {
            val list = tracks.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.displayArtist.lowercase().contains(q) || it.displayAlbum.lowercase().contains(q) }
                .filter { artistFilter == null || artistOf(it) == artistFilter }
                .sortedBy { it.title.lowercase() }
            LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                item { header() }
                items(list, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (selectMode) selected = if (t.id in selected) selected - t.id else selected + t.id
                            else play(list, list.indexOf(t), "Telegram")
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectMode) {
                            Checkbox(checked = t.id in selected, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        TgCover(t.coverPath, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text("${t.displayArtist} • ${t.displayAlbum}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmt(t.durationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TgDownloadButton(t)
                    }
                }
            }
        }
        1 -> {
            val list = albums.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
                .filter { artistFilter == null || it.artist == artistFilter }
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                gridItems(list, key = { it.key }) { a ->
                    Column(Modifier.padding(8.dp).clickable { openAlbum = a.key }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            if (a.coverPath != null) {
                                AsyncImage(model = File(a.coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                            } else {
                                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.library_music_outlined), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString()).joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        else -> {
            val list = artists.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                item { header() }
                items(list, key = { it.name }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { artistFilter = a.name; tab = 0 }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TgCover(a.coverPath, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(if (a.albums > 0) "${a.albums} albums • ${a.tracks} songs" else "${a.tracks} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TgHeader(
    tab: Int,
    onTab: (Int) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    artistFilter: String?,
    onClearArtist: () -> Unit,
    sync: TelegramSync.State,
    songCount: Int,
    onlyDownloaded: Boolean,
    onToggleDownloaded: () -> Unit,
    usedBytes: Long,
    selectMode: Boolean,
    selectedCount: Int,
    onToggleSelect: () -> Unit,
    onEditSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onChangeChannel: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = true,
                onClick = onBack,
                label = { Text("Telegram") },
                shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
            )
            listOf("Songs", "Albums", "Artists").forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { onTab(i) }, label = { Text(label) }, shape = RoundedCornerShape(16.dp))
            }
            FilterChip(selected = onlyDownloaded, onClick = onToggleDownloaded, label = { Text("Downloaded") }, shape = RoundedCornerShape(16.dp))
            FilterChip(selected = selectMode, onClick = onToggleSelect, label = { Text(if (selectMode) "Done" else "Select") }, shape = RoundedCornerShape(16.dp))
            if (selectMode) {
                FilterChip(selected = false, onClick = onSelectAll, label = { Text("All") }, shape = RoundedCornerShape(16.dp))
                FilterChip(selected = selectedCount > 0, onClick = onEditSelected, label = { Text("Edit ($selectedCount)") }, shape = RoundedCornerShape(16.dp))
            }
            if (artistFilter != null) {
                FilterChip(
                    selected = true,
                    onClick = onClearArtist,
                    label = { Text(artistFilter, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search your Telegram music") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (sync) {
            is TelegramSync.State.Scanning ->
                TgSyncRow("Scanning channel... ${sync.found} found", null, onStop, onChangeChannel)
            is TelegramSync.State.Indexing ->
                TgSyncRow(
                    "Reading tags and covers ${sync.done}/${sync.total}",
                    if (sync.total == 0) 0f else sync.done / sync.total.toFloat(),
                    onStop,
                    onChangeChannel,
                )
            is TelegramSync.State.Failed -> {
                Text("Sync failed: ${sync.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = onSync) { Text("Retry") }
                    TextButton(onClick = onChangeChannel) { Text("Change channel") }
                }
            }
            TelegramSync.State.Idle -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (usedBytes > 0) "$songCount songs \u2022 ${fmtBytes(usedBytes)} downloaded" else "$songCount songs", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSync) { Text("Sync") }
                    TextButton(onClick = onChangeChannel) { Text("Change channel") }
                }
            }
        }
    }
}

/** Scanning/indexing status with a Stop button; Change channel stays available the whole time. */
@Composable
private fun TgSyncRow(text: String, progress: Float?, onStop: () -> Unit, onChangeChannel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop") }
            TextButton(onClick = onChangeChannel) { Text("Change channel") }
        }
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun TgAlbumDetail(album: TgAlbum, onClose: () -> Unit, onPlay: (Int) -> Unit, onShuffle: () -> Unit) {
    LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                FilterChip(
                    selected = true,
                    onClick = onClose,
                    label = { Text("Albums") },
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TgCover(album.coverPath, 120.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(album.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(album.year.takeIf { it > 0 }?.toString(), "${album.tracks.size} songs").joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val context = LocalContext.current
                    val downloadUtil = LocalDownloadUtil.current
                    val downloads by downloadUtil.downloads.collectAsState()
                    val allOffline = album.tracks.all { downloads[it.id]?.state == Download.STATE_COMPLETED }
                    Button(onClick = { onPlay(0) }) { Text("Play") }
                    OutlinedButton(onClick = onShuffle) { Text("Shuffle") }
                    OutlinedButton(onClick = {
                        if (allOffline) album.tracks.forEach { removeTgDownload(context, it.id) }
                        else album.tracks.forEach { downloadUtil.download(it.toMediaMetadata()) }
                    }) { Text(if (allOffline) "Remove" else "Download") }
                }
            }
        }
        items(album.tracks.size, key = { album.tracks[it].id }) { i ->
            val t = album.tracks[i]
            Row(
                Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t.trackNo > 0) t.trackNo.toString() else "${i + 1}", modifier = Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(fmt(t.durationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TgDownloadButton(t)
            }
        }
    }
}

internal fun fmtBytes(b: Long): String =
    if (b >= 1_000_000_000L) "%.1f GB".format(b / 1_000_000_000.0) else "%d MB".format(b / 1_000_000L)

private fun removeTgDownload(context: android.content.Context, id: String) {
    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
}

/** Download icon using Metrolist's own downloader: tap to download, ring while downloading, check when saved. */
@Composable
internal fun TgDownloadButton(t: TgTrack) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    val d = downloads[t.id]
    when (d?.state) {
        Download.STATE_COMPLETED -> {
            IconButton(onClick = { confirmRemove = true }) {
                Icon(painterResource(R.drawable.done), contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
            }
            if (confirmRemove) {
                AlertDialog(
                    onDismissRequest = { confirmRemove = false },
                    title = { Text("Remove download?") },
                    text = { Text(t.title) },
                    confirmButton = { TextButton(onClick = { confirmRemove = false; removeTgDownload(context, t.id) }) { Text("Remove") } },
                    dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
                )
            }
        }
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> {
            IconButton(onClick = { removeTgDownload(context, t.id) }) {
                val pct = d.percentDownloaded
                if (pct < 0f) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        else -> IconButton(onClick = { downloadUtil.download(t.toMediaMetadata()) }) {
            Icon(painterResource(R.drawable.download), contentDescription = "Download")
        }
    }
}

@Composable
private fun TgEditDialog(
    count: Int,
    single: TgTrack?,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, album: String, year: Int?, trackNo: Int?) -> Unit,
) {
    var title by remember { mutableStateOf(single?.title ?: "") }
    var artist by remember { mutableStateOf(single?.artist ?: "") }
    var album by remember { mutableStateOf(single?.album ?: "") }
    var year by remember { mutableStateOf(single?.year?.takeIf { it > 0 }?.toString() ?: "") }
    var trackNo by remember { mutableStateOf(single?.trackNo?.takeIf { it > 0 }?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Edit song" else "Edit $count songs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (count != 1) {
                    Text("Fill only what you want to change. Empty fields stay as they are.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(trackNo, { trackNo = it }, label = { Text("Track number") }, singleLine = true)
                }
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim(), album.trim(), year.trim().toIntOrNull(), trackNo.trim().toIntOrNull()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
