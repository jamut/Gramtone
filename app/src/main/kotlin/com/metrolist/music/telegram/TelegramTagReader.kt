/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Random-access reader over a Telegram file; downloads only the byte ranges that are asked for. */
class TelegramFileReader(private val fileId: Int, private val fileSize: Long) : MediaDataSource() {
    private var raf: RandomAccessFile? = null
    private var path: String? = null
    private var availStart = 0L
    private var availEnd = 0L

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize) return -1
        val want = minOf(size.toLong(), fileSize - position).toInt()
        if (!(path != null && position >= availStart && position < availEnd)) {
            val f = TelegramClient.call(TdApi.DownloadFile(fileId, 16, position, maxOf(want.toLong(), CHUNK), true), 120)
            val local = f.local
            if (path != local.path) {
                raf?.close()
                raf = RandomAccessFile(local.path, "r")
                path = local.path
            }
            val readable = local.downloadOffset + local.downloadedPrefixSize - position
            if (readable <= 0) return -1
            availStart = position
            availEnd = position + readable
        }
        val r = raf ?: return -1
        r.seek(position)
        return r.read(buffer, offset, minOf(want.toLong(), availEnd - position).toInt())
    }

    override fun close() {
        raf?.close(); raf = null; path = null
    }

    companion object {
        private const val CHUNK = 512L * 1024
    }
}

data class ReadTags(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationSec: Int,
    val cover: ByteArray?,
)

object TelegramTagReader {
    /** Reads title/artist/album/year/track/cover from the file's own tags. Returns null if unreadable. */
    fun read(reader: TelegramFileReader): ReadTags? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(reader)
            fun s(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            ReadTags(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                year = s(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0,
                trackNo = firstInt(s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = firstInt(s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationSec = ((s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt(),
                cover = r.embeddedPicture,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun firstInt(v: String?): Int = v?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

    /** Saves a downscaled JPEG of the cover (max ~600px) and returns its path. */
    fun saveCover(bytes: ByteArray, target: File): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1200) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scaled = if (bmp.width > 600) {
                Bitmap.createScaledBitmap(bmp, 600, (bmp.height * 600f / bmp.width).toInt().coerceAtLeast(1), true)
            } else bmp
            FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
