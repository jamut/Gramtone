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
import java.io.RandomAccessFile

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
    private var path: String? = null
    private var raf: RandomAccessFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var availStart = 0L // byte window known to be on disk
    private var availEnd = 0L

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            TelegramClient.awaitReady()
            val chatId = dataSpec.uri.host!!.toLong()
            val messageId = dataSpec.uri.pathSegments[0].toLong()
            val msg = TelegramClient.call(TdApi.GetMessage(chatId, messageId))
            val file = (msg.content as TdApi.MessageAudio).audio.audio
            fileId = file.id
            fileSize = file.size
        } catch (e: Exception) {
            throw IOException("Telegram resolve failed: ${e.message}", e)
        }
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else fileSize - position
        if (bytesRemaining < 0) throw IOException("Position beyond end of file")
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val available = ensureDownloaded(position, want)
        val r = raf ?: throw IOException("File not open")
        r.seek(position)
        val n = r.read(buffer, offset, minOf(want.toLong(), available).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /** Makes sure [pos, pos+want) is on disk; returns how many bytes from pos are readable. */
    private fun ensureDownloaded(pos: Long, want: Int): Long {
        if (path != null && pos >= availStart && pos < availEnd) return availEnd - pos
        val chunk = maxOf(want.toLong(), CHUNK)
        val f = try {
            TelegramClient.call(TdApi.DownloadFile(fileId, 32, pos, chunk, true))
        } catch (e: Exception) {
            throw IOException("Telegram download failed: ${e.message}", e)
        }
        val local = f.local
        if (path != local.path) {
            raf?.close()
            raf = RandomAccessFile(local.path, "r")
            path = local.path
        }
        // downloadedPrefixSize counts from downloadOffset
        val readable = local.downloadOffset + local.downloadedPrefixSize - pos
        if (readable <= 0) throw IOException("Telegram returned no data at $pos")
        availStart = pos
        availEnd = pos + readable
        return readable
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        raf?.close(); raf = null; path = null
        currentUri = null
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
        private const val CHUNK = 512L * 1024
    }
}
