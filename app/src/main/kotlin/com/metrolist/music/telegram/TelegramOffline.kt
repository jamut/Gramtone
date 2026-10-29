/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/** The downloadable file behind a Telegram message (audio message or plain document). */
internal fun telegramFileOf(msg: TdApi.Message): TdApi.File? =
    when (val c = msg.content) {
        is TdApi.MessageAudio -> c.audio.audio
        is TdApi.MessageDocument -> c.document.document
        else -> null
    }

/** File ids that are playing right now, so background cleanup never deletes them mid-song. */
object TelegramActiveFiles {
    val ids: MutableSet<Int> = ConcurrentHashMap.newKeySet()
}

/** Keeps the next songs of the queue on the phone so playback never waits on the network. */
object TelegramPrefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileIds = ConcurrentHashMap<String, Int>()
    private const val AHEAD = 2

    /** Call when the player moves to a new song. [isDownloaded] tells which songs are already saved offline. */
    fun onTransition(player: Player, isDownloaded: (String) -> Boolean) {
        val count = player.mediaItemCount
        if (count == 0) return
        val cur = player.currentMediaItemIndex
        val ids = (cur + 1..minOf(cur + AHEAD, count - 1))
            .map { player.getMediaItemAt(it).mediaId }
            .filter { TelegramUri.isTelegramId(it) }
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEachIndexed { n, id ->
                if (!runCatching { isDownloaded(id) }.getOrDefault(false)) prefetch(id, if (n == 0) 20 else 10)
            }
            TelegramClient.trimCache()
        }
    }

    private fun prefetch(id: String, priority: Int) {
        if (TelegramClient.auth.value != TelegramClient.AuthState.READY) return
        try {
            val uri = TelegramUri.uriForSongId(id)
            val chatId = uri.host!!.toLong()
            val messageId = uri.pathSegments[0].toLong()
            val fileId = fileIds.getOrPut(id) {
                telegramFileOf(TelegramClient.call(TdApi.GetMessage(chatId, messageId)))!!.id
            }
            TelegramClient.call(TdApi.DownloadFile(fileId, priority, 0, 0, false))
        } catch (e: Exception) {
            // prefetch is best effort
        }
    }
}
