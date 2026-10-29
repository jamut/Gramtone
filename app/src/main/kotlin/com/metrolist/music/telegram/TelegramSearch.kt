/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.queues.ListQueue

/**
 * Matches from the user's Telegram music, shown at the top of Metrolist's own search
 * (both the YouTube Music search and the library search). Shows nothing for an empty query
 * or when nothing matches, so YouTube results look exactly as before.
 */
@Composable
fun TelegramSearchResults(query: String) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TelegramCatalog.init(context) }
    val tracks by TelegramCatalog.tracks.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val q = query.trim().lowercase()
    val matches = remember(tracks, q) {
        if (q.isEmpty()) emptyList()
        else tracks.filter {
            it.title.lowercase().contains(q) ||
                it.displayArtist.lowercase().contains(q) ||
                it.displayAlbum.lowercase().contains(q)
        }.sortedBy { it.title.lowercase() }
    }
    if (matches.isEmpty()) return

    val shown = matches.take(6)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            "From your Telegram music",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        shown.forEachIndexed { index, t ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        playerConnection?.playQueue(
                            ListQueue(title = "Search", items = matches.map { it.toMediaItem() }, startIndex = index),
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TgCover(t.coverPath, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${t.displayArtist} \u2022 ${t.displayAlbum}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(fmt(t.durationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (matches.size > shown.size) {
            Text(
                "+${matches.size - shown.size} more in your Telegram library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
