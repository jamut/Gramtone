/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
            } catch (e: CancellationException) {
                throw e // user pressed Stop
            } catch (e: Exception) {
                TelegramCatalog.refresh()
                _state.value = State.Failed(e.message ?: "Sync failed")
            }
        }
    }

    /** Stops scanning/indexing. Everything found so far stays in the catalog. */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Idle
        TelegramCatalog.refresh()
    }

    private suspend fun scan(chatId: Long) {
        val known = TelegramCatalog.knownIds()
        var from = 0L
        var found = 0
        var emptyPages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
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

    private class NameInfo(val track: Int, val artist: String, val title: String)

    private val trackPrefix = Regex("""^\s*(\d{1,3})\s*[.\-_)]\s*""")

    /** "1. BELLA.mp3" -> track 1, "BELLA";  "Artist - Song.flac" -> artist + title. */
    private fun fromFileName(name: String): NameInfo {
        var base = name.substringBeforeLast('.').replace('_', ' ').trim()
        var track = 0
        trackPrefix.find(base)?.let {
            track = it.groupValues[1].toIntOrNull() ?: 0
            base = base.substring(it.range.last + 1).trim()
        }
        var artist = ""
        var title = base
        if (" - " in base) {
            artist = base.substringBefore(" - ").trim()
            title = base.substringAfter(" - ").trim()
        }
        return NameInfo(track, artist, title)
    }

    private val coverLock = Any()

    private suspend fun enrich(context: Context) = coroutineScope {
        val todo = TelegramCatalog.unindexed()
        val coverDir = TelegramCatalog.coverDir(context)
        val done = AtomicInteger(0)
        val gate = Semaphore(3)
        _state.value = State.Indexing(0, todo.size)
        todo.map { t ->
            async {
                gate.withPermit { enrichOne(t, coverDir) }
                val d = done.incrementAndGet()
                if (isActive) _state.value = State.Indexing(d, todo.size)
                if (d % 25 == 0) TelegramCatalog.refresh()
            }
        }.awaitAll()
        Unit
    }

    private fun enrichOne(t: TgTrack, coverDir: File) {
        var updated = t.copy(indexed = true)
        var fileId = 0
        try {
            val msg = TelegramClient.call(TdApi.GetMessage(t.chatId, t.messageId))
            val i = info(msg)
            if (i != null) {
                fileId = i.file.id
                val fn = fromFileName(i.fileName)
                val reader = TelegramFileReader(i.file.id, i.file.size)
                val tags = try { TelegramTagReader.read(reader) } finally { reader.close() }

                val title = tags?.title ?: i.title.ifBlank { fn.title.ifBlank { t.title } }
                val artist = tags?.artist ?: i.performer.ifBlank { fn.artist }
                val albumArtist = tags?.albumArtist ?: ""
                val album = tags?.album ?: ""
                var cover: String? = null
                if (tags?.cover != null) {
                    val key = if (album.isBlank()) "t_${t.messageId}" else TelegramCatalog.albumKey(albumArtist.ifBlank { artist }, album)
                    val f = File(coverDir, "$key.jpg")
                    cover = synchronized(coverLock) {
                        if (f.exists()) f.absolutePath else TelegramTagReader.saveCover(tags.cover, f)
                    }
                }
                updated = t.copy(
                    title = title,
                    artist = artist,
                    albumArtist = albumArtist,
                    album = album,
                    year = tags?.year ?: 0,
                    trackNo = tags?.trackNo?.takeIf { it > 0 } ?: fn.track,
                    discNo = tags?.discNo ?: 0,
                    durationSec = tags?.durationSec?.takeIf { it > 0 } ?: t.durationSec,
                    coverPath = cover,
                    indexed = true,
                )
            }
        } catch (e: Exception) {
            // keep the basic info from Telegram; don't retry forever
        }
        TelegramCatalog.update(updated)
        // don't let tag-reading fill the phone, but never delete a file that is playing right now
        if (fileId != 0 && fileId !in TelegramActiveFiles.ids) TelegramClient.deleteLocalFile(fileId)
    }
}
