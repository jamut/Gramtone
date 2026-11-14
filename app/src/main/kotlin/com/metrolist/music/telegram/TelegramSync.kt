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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        // Telegram only fetches a channel's newest messages once the chat is opened, so open it first and
        // start from the newest message it reports. Without this, a Sync after a new upload found nothing.
        runCatching { TelegramClient.call(TdApi.OpenChat(chatId)) }
        val newestId = runCatching { TelegramClient.call(TdApi.GetChat(chatId)).lastMessage?.id ?: 0L }.getOrDefault(0L)
        var from = newestId
        var firstOffset = if (newestId != 0L) -1 else 0
        var found = 0
        var emptyPages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val msgs = TelegramClient.call(TdApi.GetChatHistory(chatId, from, firstOffset, 100, false)).messages
            firstOffset = 0
            if (msgs.isEmpty()) {
                if (++emptyPages >= 2) break else continue
            }
            emptyPages = 0
            val batch = ArrayList<TgTrack>()
            for (m in msgs) {
                val i = info(m) ?: continue
                found++
                val id = TelegramUri.songId(chatId, m.id)
                if (id in known) continue
                val baseName = i.fileName.substringBeforeLast('.').ifBlank { "Track ${m.id}" }
                batch.add(
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
            TelegramCatalog.insertBasicBatch(batch) // one database transaction per page
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

    /** Album covers some worker is already fetching, so parallel workers do not all download the same picture. */
    private val coverClaims = ConcurrentHashMap.newKeySet<String>()

    private const val WORKERS = 10

    /**
     * Reads the tags of every song that has not been read yet.
     * Built for channels with thousands of songs:
     *  - song info comes 100 at a time (one request instead of one per song);
     *  - 10 songs are read at once, and only the first bytes of each file are downloaded;
     *  - an album's cover is downloaded once, by the first song that finds it missing;
     *  - results are saved in batches, and the on-screen list is refreshed every few seconds.
     */
    private suspend fun enrich(context: Context) {
        coroutineScope {
            val todo = TelegramCatalog.unindexed()
            if (todo.isEmpty()) return@coroutineScope
            val coverDir = TelegramCatalog.coverDir(context)
            val total = todo.size
            val done = AtomicInteger(0)
            val lastRefresh = AtomicLong(System.currentTimeMillis())
            val pending = ArrayList<TgTrack>()
            coverClaims.clear()
            _state.value = State.Indexing(0, total)

            fun flush() {
                val batch = synchronized(pending) {
                    val copy = ArrayList(pending)
                    pending.clear()
                    copy
                }
                if (batch.isNotEmpty()) TelegramCatalog.updateBatch(batch)
            }

            val queue = Channel<Pair<TgTrack, Info?>>(200)
            val producer = launch {
                for (chunk in todo.chunked(100)) {
                    val infos = fetchInfos(chunk)
                    for (t in chunk) queue.send(Pair(t, infos[t.messageId]))
                }
                queue.close()
            }
            try {
                val workers = List(WORKERS) {
                    launch {
                        for (item in queue) {
                            val updated = enrichOne(item.first, item.second, coverDir)
                            val flushNow = synchronized(pending) {
                                pending.add(updated)
                                pending.size >= 40
                            }
                            if (flushNow) flush()
                            val d = done.incrementAndGet()
                            if (isActive && (d % 10 == 0 || d == total)) _state.value = State.Indexing(d, total)
                            val now = System.currentTimeMillis()
                            val last = lastRefresh.get()
                            if (now - last > 2500 && lastRefresh.compareAndSet(last, now)) {
                                flush()
                                TelegramCatalog.refresh()
                            }
                        }
                    }
                }
                workers.joinAll()
                producer.join()
            } finally {
                flush() // keep what was read even when stopped
                TelegramCatalog.dropMissingCovers()
            }
        }
    }

    /** File info for up to 100 songs in one request. A song missing from the answer is fetched on its own later. */
    private fun fetchInfos(chunk: List<TgTrack>): Map<Long, Info> {
        return try {
            val ids = LongArray(chunk.size) { chunk[it].messageId }
            val msgs = TelegramClient.call(TdApi.GetMessages(chunk.first().chatId, ids)).messages
            val out = HashMap<Long, Info>()
            for (m in msgs) {
                if (m == null) continue
                val i = info(m) ?: continue
                out[m.id] = i
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun enrichOne(t: TgTrack, pre: Info?, coverDir: File): TgTrack {
        var updated = t.copy(indexed = true)
        var fileId = 0
        try {
            val i = pre ?: info(TelegramClient.call(TdApi.GetMessage(t.chatId, t.messageId)))
            if (i != null) {
                fileId = i.file.id
                val fn = fromFileName(i.fileName)
                val reader = TelegramFileReader(i.file.id, i.file.size)
                fun coverFileFor(album: String?, albumArtist: String?, artist: String?): File {
                    val a = album ?: ""
                    val base = (albumArtist ?: "").ifBlank { artist ?: i.performer.ifBlank { fn.artist } }
                    val key = if (a.isBlank()) "t_${t.messageId}" else TelegramCatalog.albumKey(base.substringBefore(';').trim(), a)
                    return File(coverDir, "$key.jpg")
                }
                // The first song of an album fetches its cover; the rest reuse it.
                var claimed: String? = null
                val tags = try {
                    TelegramTagReader.read(reader) { partial ->
                        val f = coverFileFor(partial.album, partial.albumArtist, partial.artist)
                        if (f.exists() || !coverClaims.add(f.path)) {
                            false
                        } else {
                            claimed = f.path
                            true
                        }
                    }
                } finally {
                    reader.close()
                }

                val title = tags?.title ?: i.title.ifBlank { fn.title.ifBlank { t.title } }
                val artist = tags?.artist ?: i.performer.ifBlank { fn.artist }
                val albumArtist = tags?.albumArtist ?: ""
                val album = tags?.album ?: ""
                val f = coverFileFor(tags?.album, tags?.albumArtist, tags?.artist)
                val mine = claimed
                val cover: String? = synchronized(coverLock) {
                    when {
                        f.exists() -> f.absolutePath
                        tags?.cover != null -> TelegramTagReader.saveCover(tags.cover, f)
                        coverClaims.contains(f.path) && mine != f.path -> f.absolutePath // another worker is saving it
                        else -> null
                    }
                }
                if (mine != null && !File(mine).exists()) coverClaims.remove(mine) // claimed but the file had no picture

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
                    lyrics = tags?.lyrics ?: "",
                )
            }
        } catch (e: Exception) {
            // keep the basic info from Telegram; don't retry forever
        }
        // don't let tag-reading fill the phone, but never delete a file that is playing right now
        if (fileId != 0 && fileId !in TelegramActiveFiles.ids) TelegramClient.deleteLocalFile(fileId)
        return updated
    }
}
