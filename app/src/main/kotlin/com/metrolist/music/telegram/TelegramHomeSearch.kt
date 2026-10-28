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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.screens.Screens
import kotlinx.coroutines.flow.MutableStateFlow

/** Lets other screens (like Home) ask the Library tab to open the Telegram library. */
object TelegramNav {
    val openLibrary = MutableStateFlow(false)
}

@Composable
private fun rememberPlay(): (List<TgTrack>, Int, String) -> Unit {
    val pc = LocalPlayerConnection.current
    return { list, index, title ->
        if (list.isNotEmpty()) {
            pc?.playQueue(ListQueue(title = title, items = list.map { it.toMediaItem() }, startIndex = index))
        }
    }
}

/**
 * A compact "Your Telegram music" block shown inside Metrolist's own Home and Library pages,
 * so Telegram and YouTube Music live side by side.
 */
@Composable
fun TelegramHomeSection() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    LaunchedEffect(Unit) {
        TelegramCatalog.init(context)
        TelegramClient.autoStart(context)
    }
    val tracks by TelegramCatalog.tracks.collectAsState()
    val play = rememberPlay()
    val recent = remember(tracks) { tracks.sortedByDescending { it.messageId }.take(20) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your Telegram music", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (tracks.isEmpty()) "Connect your channel to see it here" else "${tracks.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tracks.isNotEmpty()) {
                TextButton(onClick = { play(tracks.shuffled(), 0, "Telegram") }) { Text("Shuffle") }
            }
            TextButton(onClick = {
                TelegramNav.openLibrary.value = true
                navController.navigate(Screens.Library.route) { launchSingleTop = true }
            }) { Text(if (tracks.isEmpty()) "Set up" else "Open") }
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { t ->
                    Column(Modifier.width(130.dp).clickable { play(recent, recent.indexOf(t), "Recently added") }) {
                        TgCover(t.coverPath, 130.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
