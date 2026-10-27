/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

/** Scans a Telegram chat for music and fills [TelegramCatalog] (tags, albums, covers). */
object TelegramSync {
    sealed class State {
        object Idle : State()
        data class Scanning(val found: Int) : State()
        data class Indexing(val done: Int, val total: Int) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val audioExt = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "alac", "wma", "aiff", "ape", "wv")

    private class Info(val file: TdApi.File, val fileName: String, val title: String, val performer: String, val duration: Int)

    private fun info(m: TdApi.Message): Info? =
        when (val c = m.content) {
            is TdApi.MessageAudio -> {
                val a = c.audio
                Info(a.audio, a.fileName ?: "", a.title ?: "", a.performer ?: "", a.duration)
            }
            is TdApi.MessageDocument -> {
                val d = c.document
                val ext = (d.fileName ?: "").substringAfterLast('.', "").lowercase()
                if (ext in audioExt || (d.mimeType ?: "").startsWith("audio/")) {
                    Info(d.document, d.fileName ?: "", "", "", 0)
                } else null
            }
            else -> null
        }

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, chatId: Long) {
        if (isRunning) return
        val app = context.applicationContext
        TelegramCatalog.init(app)
        job = scope.launch {
            try {
                TelegramClient.awaitReady()
                scan(chatId)
                enrich(app)
                TelegramCatalog.refresh()
                _state.value = State.Idle
            } catch (e: Exception) {
                TelegramCatalog.refresh()
                _state.value = State.Failed(e.message ?: "Sync failed")
            }
        }
    }

    private fun scan(chatId: Long) {
        val known = TelegramCatalog.knownIds()
        var from = 0L
        var found = 0
        var emptyPages = 0
        while (true) {
            val msgs = TelegramClient.call(TdApi.GetChatHistory(chatId, from, 0, 100, false)).messages
            if (msgs.isEmpty()) {
                if (++emptyPages >= 2) break else continue
            }
            emptyPages = 0
            for (m in msgs) {
                val i = info(m) ?: continue
                found++
                val id = TelegramUri.songId(chatId, m.id)
                if (id in known) continue
                val baseName = i.fileName.substringBeforeLast('.').ifBlank { "Track ${m.id}" }
                TelegramCatalog.insertBasic(
                    TgTrack(
                        id = id,
                        chatId = chatId,
                        messageId = m.id,
                        title = i.title.ifBlank { baseName },
                        artist = i.performer,
                        albumArtist = "",
                        album = "",
                        year = 0,
                        trackNo = 0,
                        discNo = 0,
                        durationSec = i.duration,
                        fileName = i.fileName,
                        coverPath = null,
                        indexed = false,
                    ),
                )
            }
            from = msgs.last().id
            _state.value = State.Scanning(found)
            TelegramCatalog.refresh()
        }
    }

    private fun enrich(context: Context) {
        val todo = TelegramCatalog.unindexed()
        val coverDir = TelegramCatalog.coverDir(context)
        todo.forEachIndexed { index, t ->
            _state.value = State.Indexing(index, todo.size)
            var updated = t.copy(indexed = true)
            var fileId = 0
            try {
                val msg = TelegramClient.call(TdApi.GetMessage(t.chatId, t.messageId))
                val i = info(msg)
                if (i != null) {
                    fileId = i.file.id
                    val reader = TelegramFileReader(i.file.id, i.file.size)
                    val tags = try { TelegramTagReader.read(reader) } finally { reader.close() }
                    if (tags != null) {
                        val album = tags.album ?: ""
                        val artist = tags.artist ?: t.artist
                        val albumArtist = tags.albumArtist ?: ""
                        var cover: String? = null
                        if (tags.cover != null) {
                            val key = if (album.isBlank()) "t_${t.messageId}" else TelegramCatalog.albumKey(albumArtist.ifBlank { artist }, album)
                            val f = File(coverDir, "$key.jpg")
                            cover = if (f.exists()) f.absolutePath else TelegramTagReader.saveCover(tags.cover, f)
                        }
                        updated = t.copy(
                            title = tags.title ?: t.title,
                            artist = artist,
                            albumArtist = albumArtist,
                            album = album,
                            year = tags.year,
                            trackNo = tags.trackNo,
                            discNo = tags.discNo,
                            durationSec = if (tags.durationSec > 0) tags.durationSec else t.durationSec,
                            coverPath = cover,
                            indexed = true,
                        )
                    }
                }
            } catch (e: Exception) {
                // keep the basic info from Telegram; don't retry forever
            }
            TelegramCatalog.update(updated)
            if (fileId != 0) TelegramClient.deleteLocalFile(fileId) // don't let tag-reading fill the phone
            if (index % 5 == 4) TelegramCatalog.refresh()
        }
    }
}
