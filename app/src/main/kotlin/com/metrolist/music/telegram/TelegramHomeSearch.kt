/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.queues.ListQueue

@Composable
private fun rememberPlay(): (List<TgTrack>, Int, String) -> Unit {
    val pc = LocalPlayerConnection.current
    return { list, index, title ->
        if (list.isNotEmpty()) {
            pc?.playQueue(ListQueue(title = title, items = list.map { it.toMediaItem() }, startIndex = index))
        }
    }
}

/** Home page used when the source is Telegram: recent songs, albums, shuffle. */
@Composable
fun TelegramHomeScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        TelegramCatalog.init(context)
        TelegramClient.autoStart(context)
    }
    val tracks by TelegramCatalog.tracks.collectAsState()
    val play = rememberPlay()
    val albums = remember(tracks) { buildAlbums(tracks).sortedByDescending { a -> a.tracks.maxOf { it.messageId } } }
    val recent = remember(tracks) { tracks.sortedByDescending { it.messageId }.take(20) }

    LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Your Telegram music", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${tracks.size} songs \u2022 ${albums.size} albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play(tracks.shuffled(), 0, "Telegram") }) { Text("Shuffle all") }
                        OutlinedButton(onClick = { play(recent, 0, "Recently added") }) { Text("Play recent") }
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Open the Library tab to connect Telegram and pick your music channel.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            item { SectionTitle("Recently added") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { t ->
                        Column(Modifier.width(140.dp).clickable { play(recent, recent.indexOf(t), "Recently added") }) {
                            TgCover(t.coverPath, 140.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Albums") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums.take(30), key = { it.key }) { a ->
                            Column(Modifier.width(140.dp).clickable { play(a.tracks, 0, a.title) }) {
                                TgCover(a.coverPath, 140.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
}

/** Search used when the source is Telegram: searches your catalog only. */
@Composable
fun TelegramSearchScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TelegramCatalog.init(context) }
    val tracks by TelegramCatalog.tracks.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val play = rememberPlay()
    val q = query.trim().lowercase()
    val results = remember(tracks, q) {
        if (q.isEmpty()) emptyList()
        else tracks.filter {
            it.title.lowercase().contains(q) || it.displayArtist.lowercase().contains(q) || it.displayAlbum.lowercase().contains(q)
        }.sortedBy { it.title.lowercase() }
    }

    LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search your Telegram music") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        if (q.isNotEmpty() && results.isEmpty()) {
            item { Text("No matches.", modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        items(results, key = { it.id }) { t ->
            Row(
                Modifier.fillMaxWidth().clickable { play(results, results.indexOf(t), "Search") }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TgCover(t.coverPath, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text("${t.displayArtist} \u2022 ${t.displayAlbum}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(fmt(t.durationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
