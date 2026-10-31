/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.drinkless.tdlib.TdApi
import java.io.IOException

/** URI format: tg://<chatId>/<messageId> */
object TelegramUri {
    const val SCHEME = "tg"
    const val ID_PREFIX = "tg_" // song ids look like tg_<chatId>_<messageId>

    fun songId(chatId: Long, messageId: Long) = "$ID_PREFIX${chatId}_$messageId"
    fun isTelegramId(id: String) = id.startsWith(ID_PREFIX)

    /** tg_<chat>_<msg> -> tg://<chat>/<msg>. Chat ids can be negative, so split on the last underscore. */
    fun uriForSongId(id: String): Uri {
        val body = id.removePrefix(ID_PREFIX)
        val i = body.lastIndexOf('_')
        return Uri.parse("$SCHEME://${body.substring(0, i)}/${body.substring(i + 1)}")
    }
}

/**
 * Streams a Telegram audio file through TDLib by downloading only the byte
 * range ExoPlayer asks for, so a whole FLAC never has to be stored.
 */
@UnstableApi
class TelegramDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var currentUri: Uri? = null
    private var fileId = 0
    private var fileSize = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var availStart = 0L // byte window Telegram has downloaded
    private var availEnd = 0L
    private var buf = ByteArray(0) // small read-ahead so we do not call TDLib for every 16 KB
    private var bufStart = 0L
    private var songKey = ""
    private var songId = ""
    private var maybeFlac = false
    private var flacPatch: TelegramFlacFix.Patch? = null

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val chatId = dataSpec.uri.host!!.toLong()
            val messageId = dataSpec.uri.pathSegments[0].toLong()
            songKey = "$chatId/$messageId"
            songId = TelegramUri.songId(chatId, messageId)
            TelegramClient.awaitReady()
            val msg = TelegramClient.call(TdApi.GetMessage(chatId, messageId))
            var fileName = ""
            var mime = ""
            val file = when (val c = msg.content) {
                is TdApi.MessageAudio -> { fileName = c.audio.fileName ?: ""; mime = c.audio.mimeType ?: ""; c.audio.audio }
                is TdApi.MessageDocument -> { fileName = c.document.fileName ?: ""; mime = c.document.mimeType ?: ""; c.document.document } // music sent as a plain file
                else -> throw IOException("Message is not an audio file")
            }
            maybeFlac = fileName.endsWith(".flac", ignoreCase = true) || mime.contains("flac", ignoreCase = true)
            fileId = file.id
            // size can be 0 on some files; expectedSize is Telegram's own figure
            fileSize = maxOf(file.size, file.expectedSize)
            if (fileSize <= 0L) throw IOException("Telegram did not report a file size")
            TelegramActiveFiles.ids.add(fileId)
        } catch (e: Exception) {
            throw IOException("Telegram resolve failed (${e.javaClass.simpleName}): ${e.message}", e)
        }
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else fileSize - position
        if (bytesRemaining < 0) throw IOException("Position beyond end of file")
        buf = ByteArray(0)
        availStart = 0
        availEnd = 0

        // Some FLACs have a blank length in their header, which makes the player lose the song length
        // and refuse to seek. Work out the real length and let the player see it (the file is not changed).
        flacPatch = if (!maybeFlac) null else try {
            if (dataSpec.position == 0L) TelegramFlacFix.patchFor(songKey, fileSize) { p, l -> readRaw(p, l) }
            else TelegramFlacFix.cached(songKey)
        } catch (e: Exception) {
            null
        }
        flacPatch?.let { TelegramCatalog.setDurationIfMissing(songId, it.durationSec) }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        if (position < bufStart || position >= bufStart + buf.size) fill(position, want)
        val inBuf = (position - bufStart).toInt()
        val n = minOf(want, buf.size - inBuf)
        System.arraycopy(buf, inBuf, buffer, offset, n)
        flacPatch?.applyTo(position, buffer, offset, n)
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /** Reads exactly [len] bytes at [pos] straight from Telegram's copy, or null if that is not possible. */
    private fun readRaw(pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0 || pos + len > fileSize) return null
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val avail = ensureDownloaded(pos + got, len - got)
            val n = minOf(avail, (len - got).toLong()).toInt()
            val d = TelegramClient.call(TdApi.ReadFilePart(fileId, pos + got, n.toLong()), 60).data
            if (d.isEmpty()) return null
            System.arraycopy(d, 0, out, got, d.size)
            got += d.size
        }
        return out
    }

    /** Loads the bytes at [pos] into [buf]. Retries once if Telegram's cache changed under us. */
    private fun fill(pos: Long, want: Int) {
        var lastError: Exception? = null
        repeat(2) {
            try {
                val avail = ensureDownloaded(pos, want)
                val count = minOf(avail, FILL, fileSize - pos).toInt()
                val data = TelegramClient.call(TdApi.ReadFilePart(fileId, pos, count.toLong()), 60).data
                if (data.isEmpty()) throw IOException("Telegram returned 0 bytes")
                buf = data
                bufStart = pos
                return
            } catch (e: Exception) {
                lastError = e
                availStart = 0
                availEnd = 0 // forget what we thought was downloaded, then try again
            }
        }
        throw IOException("Telegram read failed at byte $pos (${lastError?.javaClass?.simpleName}): ${lastError?.message}", lastError)
    }

    /**
     * Makes sure Telegram has downloaded the bytes at [pos] and returns how many are ready from there.
     * Uses TDLib's own size report, so it works for the start of a song and for seeking anywhere.
     */
    private fun ensureDownloaded(pos: Long, want: Int): Long {
        if (pos >= availStart && pos < availEnd) return availEnd - pos
        val chunk = maxOf(want.toLong(), if (pos < 1_000_000L) CHUNK else BIG_CHUNK)
        TelegramClient.call(TdApi.DownloadFile(fileId, 32, pos, chunk, true), 180)
        val avail = TelegramClient.call(TdApi.GetFileDownloadedPrefixSize(fileId, pos)).size
        if (avail <= 0) throw IOException("Telegram has no data at byte $pos")
        availStart = pos
        availEnd = pos + avail
        return avail
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (fileId != 0) TelegramActiveFiles.ids.remove(fileId)
        currentUri = null
        buf = ByteArray(0)
        flacPatch = null
        availStart = 0; availEnd = 0
        if (opened) { opened = false; transferEnded() }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource()
    }

    /** Routes tg:// to Telegram, everything else to the normal upstream. */
    class RoutingFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Routing(upstream.createDataSource(), TelegramDataSource())
    }

    private class Routing(
        private val default: DataSource,
        private val telegram: DataSource,
    ) : DataSource {
        private var active: DataSource = default

        override fun addTransferListener(l: androidx.media3.datasource.TransferListener) {
            default.addTransferListener(l); telegram.addTransferListener(l)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.uri.scheme == TelegramUri.SCHEME) telegram else default
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
        override fun getUri() = active.uri
        override fun getResponseHeaders() = active.responseHeaders
        override fun close() = active.close()
    }

    companion object {
        private const val CHUNK = 512L * 1024 // small first chunk so a song starts fast
        private const val BIG_CHUNK = 4L * 1024 * 1024 // then bigger reads for smooth playback
        private const val FILL = 256L * 1024 // how much we hand to the player per TDLib read
    }
}
