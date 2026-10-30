/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Small extras that make Telegram songs behave like every other song in the player. */
object TelegramExtras {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Gives Metrolist the lyrics stored inside the song's tags (current song and the next two),
     * unless it already has lyrics for that song, so nothing is overwritten.
     */
    fun onTransition(player: Player, database: MusicDatabase) {
        val count = player.mediaItemCount
        if (count == 0) return
        val cur = player.currentMediaItemIndex
        val ids = (cur..minOf(cur + 2, count - 1))
            .map { player.getMediaItemAt(it).mediaId }
            .filter { TelegramUri.isTelegramId(it) }
        if (ids.isEmpty()) return
        scope.launch {
            for (id in ids) {
                try {
                    val lyrics = TelegramCatalog.tracks.value.firstOrNull { it.id == id }?.lyrics
                    if (lyrics.isNullOrBlank()) continue
                    if (database.lyrics(id).first() != null) continue
                    database.query { upsert(LyricsEntity(id = id, lyrics = lyrics, provider = "Tags")) }
                } catch (e: Exception) {
                    // lyrics are optional
                }
            }
        }
    }

    /**
     * When the player knows the real length of a Telegram song, remember it (catalog and Metrolist's own
     * database), so lists, the Downloaded page and Last.fm show the right time instead of 0:00.
     */
    fun onReady(player: Player, database: MusicDatabase) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (!TelegramUri.isTelegramId(id)) return
        val ms = player.duration
        if (ms <= 0L || ms == C.TIME_UNSET) return
        val seconds = (ms / 1000L).toInt()
        scope.launch {
            try {
                TelegramCatalog.setDurationIfMissing(id, seconds)
                database.query { updateDuration(id, seconds) }
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    /** Shares the audio file itself (not a link, since a private channel link only works for its members). */
    fun share(context: Context, songId: String, title: String) {
        val app = context.applicationContext
        Toast.makeText(app, "Preparing the file...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                TelegramClient.awaitReady()
                val uri = TelegramUri.uriForSongId(songId)
                val msg = TelegramClient.call(TdApi.GetMessage(uri.host!!.toLong(), uri.pathSegments[0].toLong()))
                val file = telegramFileOf(msg) ?: error("This message is not an audio file")
                val originalName = when (val c = msg.content) {
                    is TdApi.MessageAudio -> c.audio.fileName
                    is TdApi.MessageDocument -> c.document.fileName
                    else -> ""
                } ?: ""

                // make sure the whole file is on the phone (a song being streamed only has parts of it)
                var local = TelegramClient.call(TdApi.DownloadFile(file.id, 32, 0, 0, true), 600).local
                var tries = 0
                while (!local.isDownloadingCompleted && tries < 4) {
                    local = TelegramClient.call(TdApi.DownloadFile(file.id, 32, 0, 0, true), 600).local
                    tries++
                }
                if (!local.isDownloadingCompleted) error("The file did not finish downloading")

                val dir = File(app.cacheDir, "tg_share").apply { deleteRecursively(); mkdirs() }
                val ext = originalName.substringAfterLast('.', "mp3")
                val safe = title.ifBlank { "song" }.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").take(60)
                val out = File(dir, "$safe.$ext")
                File(local.path).copyTo(out, overwrite = true)

                val content = FileProvider.getUriForFile(app, "${app.packageName}.FileProvider", out)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, content)
                    putExtra(Intent.EXTRA_TITLE, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) { app.startActivity(chooser) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Could not share: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
