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
class TelegramFileReader(private val fileId: Int, val fileSize: Long) : MediaDataSource() {
    private var raf: RandomAccessFile? = null
    private var path: String? = null
    private var availStart = 0L
    private var availEnd = 0L

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize) return -1
        val want = minOf(size.toLong(), fileSize - position).toInt()
        if (!(path != null && position >= availStart && position < availEnd)) {
            val f = TelegramClient.call(TdApi.DownloadFile(fileId, 1, position, maxOf(want.toLong(), CHUNK), true), 120) // priority 1: never slow down playback
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

    /** Reads exactly [len] bytes at [pos], or null if that's not possible. */
    fun readFully(pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0 || pos + len > fileSize) return null
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = readAt(pos + got, out, got, len - got)
            if (n <= 0) return null
            got += n
        }
        return out
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
    private const val MAX_PIC = 6 * 1024 * 1024
    private const val RETRIEVER_MAX_BYTES = 25L * 1024 * 1024

    /**
     * Reads tags from the file's own header (ID3v2 / FLAC / ID3v1) so only the first
     * few KB are downloaded. Other formats fall back to Android's retriever, only for small files.
     */
    fun read(reader: TelegramFileReader): ReadTags? {
        return try {
            val head = reader.readFully(0, minOf(12L, reader.fileSize).toInt()) ?: return null
            fun text(from: Int, len: Int) =
                if (head.size >= from + len) String(head, from, len, Charsets.ISO_8859_1) else ""
            when {
                text(0, 4) == "fLaC" -> readFlac(reader)
                text(0, 3) == "ID3" -> {
                    val footer = if ((u(head, 5) and 0x10) != 0) 10 else 0
                    val audioStart = 10L + syncsafe(head, 6) + footer
                    withMp3Duration(reader, readId3v2(reader) ?: readId3v1(reader), audioStart)
                }
                head.size >= 2 && u(head, 0) == 0xFF && (u(head, 1) and 0xE0) == 0xE0 ->
                    withMp3Duration(reader, readId3v1(reader), 0L)
                // m4a / ogg: Android's reader, only for small files (it can read a lot of the file)
                text(4, 4) == "ftyp" || text(0, 4) == "OggS" ->
                    if (reader.fileSize <= RETRIEVER_MAX_BYTES) readWithRetriever(reader) else null
                else -> null // unknown format: keep the file-name info
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun withMp3Duration(r: TelegramFileReader, tags: ReadTags?, audioStart: Long): ReadTags? {
        val d = estimateMp3Seconds(r, audioStart)
        if (tags == null) return if (d > 0) ReadTags(null, null, null, null, 0, 0, 0, d, null) else null
        return if (tags.durationSec > 0 || d <= 0) tags else tags.copy(durationSec = d)
    }

    private val BR_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BR_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BR_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BR_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** MP3 length from its first frame (exact with a Xing/Info header, otherwise assumes constant bitrate). */
    private fun estimateMp3Seconds(r: TelegramFileReader, audioStart: Long): Int {
        if (audioStart < 0 || audioStart >= r.fileSize) return 0
        val len = minOf(8192L, r.fileSize - audioStart).toInt()
        if (len < 4) return 0
        val b = r.readFully(audioStart, len) ?: return 0
        var i = 0
        while (i + 4 <= b.size) {
            if (u(b, i) == 0xFF && (u(b, i + 1) and 0xE0) == 0xE0) {
                val vBits = (u(b, i + 1) shr 3) and 3 // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
                val lBits = (u(b, i + 1) shr 1) and 3 // 1 = layer 3, 2 = layer 2, 3 = layer 1
                val brIdx = (u(b, i + 2) shr 4) and 0xF
                val srIdx = (u(b, i + 2) shr 2) and 3
                val mono = ((u(b, i + 3) shr 6) and 3) == 3
                if (vBits != 1 && lBits != 0 && brIdx in 1..14 && srIdx != 3) {
                    val v1 = vBits == 3
                    val layer = 4 - lBits
                    val kbps = when {
                        v1 && layer == 1 -> BR_V1_L1[brIdx]
                        v1 && layer == 2 -> BR_V1_L2[brIdx]
                        v1 -> BR_V1_L3[brIdx]
                        layer == 1 -> BR_V2_L1[brIdx]
                        else -> BR_V2_L23[brIdx]
                    }
                    val sr = when (vBits) {
                        3 -> intArrayOf(44100, 48000, 32000)[srIdx]
                        2 -> intArrayOf(22050, 24000, 16000)[srIdx]
                        else -> intArrayOf(11025, 12000, 8000)[srIdx]
                    }
                    val spf = when {
                        layer == 1 -> 384
                        layer == 2 -> 1152
                        v1 -> 1152
                        else -> 576
                    }
                    val side = if (v1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                    val x = i + 4 + side
                    if (x + 12 <= b.size) {
                        val tag = String(b, x, 4, Charsets.ISO_8859_1)
                        if ((tag == "Xing" || tag == "Info") && (be32(b, x + 4) and 1L) != 0L) {
                            val frames = be32(b, x + 8)
                            if (frames > 0) return (frames * spf / sr).toInt()
                        }
                    }
                    if (kbps > 0) return ((r.fileSize - audioStart - i) * 8 / (kbps * 1000L)).toInt()
                }
            }
            i++
        }
        return 0
    }

    // ---------- helpers ----------
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int): Long =
        (u(b, i).toLong() shl 24) or (u(b, i + 1).toLong() shl 16) or (u(b, i + 2).toLong() shl 8) or u(b, i + 3).toLong()
    private fun be24(b: ByteArray, i: Int): Int = (u(b, i) shl 16) or (u(b, i + 1) shl 8) or u(b, i + 2)
    private fun le32(b: ByteArray, i: Int): Long =
        (u(b, i + 3).toLong() shl 24) or (u(b, i + 2).toLong() shl 16) or (u(b, i + 1).toLong() shl 8) or u(b, i).toLong()
    private fun syncsafe(b: ByteArray, i: Int): Int =
        (u(b, i) shl 21) or (u(b, i + 1) shl 14) or (u(b, i + 2) shl 7) or u(b, i + 3)
    private fun firstInt(v: String?): Int = v?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
    private fun yearOf(v: String?): Int = v?.trim()?.take(4)?.toIntOrNull() ?: 0
    private fun clean(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (u(b, i) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun charsetOf(enc: Int) = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    private fun decodeText(b: ByteArray): String {
        if (b.size <= 1) return ""
        return String(b, 1, b.size - 1, charsetOf(u(b, 0))).substringBefore('\u0000').trim()
    }

    /** Index just after the string terminator that starts at [from]. */
    private fun afterTerminator(b: ByteArray, from: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (i - from) % 2 == 0) return i + 2
                i++
            }
            return b.size
        }
        while (i < b.size) {
            if (b[i].toInt() == 0) return i + 1
            i++
        }
        return b.size
    }

    // ---------- ID3v2 (MP3 and others) ----------
    private fun readId3v2(r: TelegramFileReader): ReadTags? {
        val head = r.readFully(0, 10) ?: return null
        if (head[0].toInt() != 'I'.code || head[1].toInt() != 'D'.code || head[2].toInt() != '3'.code) return null
        val ver = u(head, 3)
        if (ver !in 2..4) return null
        val flags = u(head, 5)
        val tagUnsync = (flags and 0x80) != 0
        val end = 10L + syncsafe(head, 6)
        var pos = 10L
        if (ver >= 3 && (flags and 0x40) != 0) {
            val eh = r.readFully(pos, 4) ?: return null
            pos += if (ver == 4) syncsafe(eh, 0).toLong() else be32(eh, 0) + 4
        }
        val idLen = if (ver == 2) 3 else 4
        val hdrLen = if (ver == 2) 6 else 10

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var track = 0
        var disc = 0
        var year = 0
        var cover: ByteArray? = null

        while (pos + hdrLen <= end) {
            val h = r.readFully(pos, hdrLen) ?: break
            if (h[0].toInt() == 0) break
            val id = String(h, 0, idLen, Charsets.ISO_8859_1)
            val size = when (ver) {
                2 -> be24(h, 3)
                4 -> syncsafe(h, 4)
                else -> be32(h, 4).toInt()
            }
            if (size <= 0 || pos + hdrLen + size > end) break
            val bodyPos = pos + hdrLen
            pos = bodyPos + size

            val isPic = id == "APIC" || id == "PIC"
            val isText = id in TEXT_IDS
            if (!isPic && !isText) continue
            if (isPic && (size > MAX_PIC || cover != null)) continue
            if (isText && size > 4096) continue
            var body = r.readFully(bodyPos, size) ?: break
            if (tagUnsync) body = deUnsync(body)

            when (id) {
                "TIT2", "TT2" -> title = clean(decodeText(body))
                "TPE1", "TP1" -> artist = clean(decodeText(body))
                "TPE2", "TP2" -> albumArtist = clean(decodeText(body))
                "TALB", "TAL" -> album = clean(decodeText(body))
                "TRCK", "TRK" -> track = firstInt(decodeText(body))
                "TPOS", "TPA" -> disc = firstInt(decodeText(body))
                "TDRC", "TYER", "TYE" -> if (year == 0) year = yearOf(decodeText(body))
                "APIC", "PIC" -> cover = parseId3Picture(body, ver)
            }
        }
        return ReadTags(title, artist, albumArtist, album, year, track, disc, 0, cover)
    }

    private fun parseId3Picture(b: ByteArray, ver: Int): ByteArray? {
        if (b.size < 8) return null
        val enc = u(b, 0)
        var p = 1
        p = if (ver == 2) p + 3 else afterTerminator(b, p, 0) // image format / mime
        p += 1 // picture type
        p = afterTerminator(b, p, enc) // description
        if (p >= b.size) return null
        return b.copyOfRange(p, b.size)
    }

    private val TEXT_IDS = setOf(
        "TIT2", "TT2", "TPE1", "TP1", "TPE2", "TP2", "TALB", "TAL",
        "TRCK", "TRK", "TPOS", "TPA", "TDRC", "TYER", "TYE",
    )

    // ---------- ID3v1 (last 128 bytes of old MP3s) ----------
    private fun readId3v1(r: TelegramFileReader): ReadTags? {
        if (r.fileSize < 128) return null
        val b = r.readFully(r.fileSize - 128, 128) ?: return null
        if (String(b, 0, 3, Charsets.ISO_8859_1) != "TAG") return null
        fun f(off: Int, len: Int) = clean(String(b, off, len, Charsets.ISO_8859_1).substringBefore('\u0000'))
        val track = if (b[125].toInt() == 0 && b[126].toInt() != 0) u(b, 126) else 0
        return ReadTags(f(3, 30), f(33, 30), null, f(63, 30), yearOf(f(93, 4)), track, 0, 0, null)
    }

    // ---------- FLAC ----------
    private fun readFlac(r: TelegramFileReader): ReadTags? {
        val magic = r.readFully(0, 4) ?: return null
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var track = 0
        var disc = 0
        var year = 0
        var duration = 0
        var cover: ByteArray? = null
        var coverIsFront = false

        var pos = 4L
        var last = false
        while (!last && pos + 4 <= r.fileSize) {
            val h = r.readFully(pos, 4) ?: break
            last = (u(h, 0) and 0x80) != 0
            val type = u(h, 0) and 0x7F
            val len = be24(h, 1)
            val bodyPos = pos + 4
            pos = bodyPos + len

            if (type == 0 && len >= 18) {
                val b = r.readFully(bodyPos, 18)
                if (b != null) {
                    val rate = (u(b, 10) shl 12) or (u(b, 11) shl 4) or (u(b, 12) shr 4)
                    val total = ((u(b, 13) and 0x0F).toLong() shl 32) or be32(b, 14)
                    if (rate > 0) duration = (total / rate).toInt()
                }
            } else if (type == 4 && len in 8..1_000_000) {
                val b = r.readFully(bodyPos, len)
                if (b != null) {
                    var p = (4 + le32(b, 0)).toInt()
                    if (p + 4 <= b.size) {
                        val count = le32(b, p).toInt()
                        p += 4
                        for (n in 0 until count) {
                            if (p + 4 > b.size) break
                            val l = le32(b, p).toInt()
                            p += 4
                            if (l < 0 || p + l > b.size) break
                            val entry = String(b, p, l, Charsets.UTF_8)
                            p += l
                            val eq = entry.indexOf('=')
                            if (eq <= 0) continue
                            val v = clean(entry.substring(eq + 1)) ?: continue
                            when (entry.substring(0, eq).uppercase()) {
                                "TITLE" -> title = v
                                "ARTIST" -> if (artist == null) artist = v
                                "ALBUMARTIST", "ALBUM ARTIST" -> albumArtist = v
                                "ALBUM" -> album = v
                                "TRACKNUMBER" -> track = firstInt(v)
                                "DISCNUMBER" -> disc = firstInt(v)
                                "DATE", "YEAR" -> if (year == 0) year = yearOf(v)
                            }
                        }
                    }
                }
            } else if (type == 6 && len in 32..MAX_PIC) {
                val b = r.readFully(bodyPos, len)
                if (b != null) {
                    val picType = be32(b, 0).toInt()
                    if (cover == null || (picType == 3 && !coverIsFront)) {
                        var p = 4
                        p += 4 + be32(b, p).toInt() // mime
                        p += 4 + be32(b, p).toInt() // description
                        p += 16 // width, height, depth, colors
                        val dl = be32(b, p).toInt()
                        p += 4
                        if (dl > 0 && p + dl <= b.size) {
                            cover = b.copyOfRange(p, p + dl)
                            coverIsFront = picType == 3
                        }
                    }
                }
            }
        }
        return ReadTags(title, artist, albumArtist, album, year, track, disc, duration, cover)
    }

    // ---------- fallback for small files of other formats (m4a, ogg...) ----------
    private fun readWithRetriever(reader: TelegramFileReader): ReadTags? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(reader)
            fun s(k: Int) = clean(r.extractMetadata(k))
            ReadTags(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                year = yearOf(s(MediaMetadataRetriever.METADATA_KEY_YEAR)),
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
