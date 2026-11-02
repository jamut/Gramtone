/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import java.io.ByteArrayOutputStream

/** Tags read from the start (and sometimes the end) of an MP4/M4A/ALAC, Ogg (Vorbis, Opus) or WAV file. */
class ContainerTags(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationSec: Int,
    val lyrics: String?,
    /** Loads the cover picture only when asked, so albums that already have one do not download it again. */
    val loadCover: (() -> ByteArray?)?,
)

/**
 * Reads a few small pieces of the file instead of all of it. [read] returns exactly `len` bytes at `pos`, or null.
 */
object TelegramContainerTags {
    private const val MAX_COVER = 8 * 1024 * 1024
    private const val MAX_LYRICS = 200_000

    fun read(fileSize: Long, read: (Long, Int) -> ByteArray?): ContainerTags? {
        val head = read(0, minOf(12L, fileSize).toInt()) ?: return null
        return when {
            head.size >= 8 && str(head, 4, 4) == "ftyp" -> readMp4(fileSize, read)
            head.size >= 4 && str(head, 0, 4) == "OggS" -> readOgg(fileSize, read)
            head.size >= 12 && (str(head, 0, 4) == "RIFF" || str(head, 0, 4) == "RF64") && str(head, 8, 4) == "WAVE" ->
                readWav(fileSize, read)
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun str(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.ISO_8859_1)
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int): Long =
        (u(b, i).toLong() shl 24) or (u(b, i + 1).toLong() shl 16) or (u(b, i + 2).toLong() shl 8) or u(b, i + 3).toLong()
    private fun be64(b: ByteArray, i: Int): Long = (be32(b, i) shl 32) or be32(b, i + 4)
    private fun le16(b: ByteArray, i: Int): Int = u(b, i) or (u(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int): Long =
        u(b, i).toLong() or (u(b, i + 1).toLong() shl 8) or (u(b, i + 2).toLong() shl 16) or (u(b, i + 3).toLong() shl 24)
    private fun le64(b: ByteArray, i: Int): Long = le32(b, i) or (le32(b, i + 4) shl 32)
    private fun clean(v: String?): String? = v?.trim()?.trim('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
    private fun firstInt(v: String?): Int = v?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
    private fun yearOf(v: String?): Int = v?.trim()?.take(4)?.toIntOrNull() ?: 0

    // ------------------------------------------------------------------ MP4 / M4A / ALAC
    private fun readMp4(size: Long, read: (Long, Int) -> ByteArray?): ContainerTags? {
        // top level: walk the boxes until 'moov' (it can sit before or after the audio data)
        var pos = 0L
        var moovStart = -1L
        var moovEnd = 0L
        var hops = 0
        while (pos + 8 <= size && hops++ < 64) {
            val (type, dataStart, end) = boxAt(pos, size, read) ?: return null
            if (type == "moov") { moovStart = dataStart; moovEnd = end; break }
            pos = end
        }
        if (moovStart < 0) return null

        var durationSec = 0
        val artists = ArrayList<String>()
        var title: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var year = 0
        var track = 0
        var disc = 0
        var lyrics: String? = null
        var coverPos = -1L
        var coverLen = 0

        var p = moovStart
        var count = 0
        while (p + 8 <= moovEnd && count++ < 400) {
            val (type, dataStart, end) = boxAt(p, moovEnd, read) ?: break
            if (type == "mvhd") {
                val b = read(dataStart, minOf(32L, end - dataStart).toInt())
                if (b != null && b.size >= 20) {
                    val v1 = u(b, 0) == 1
                    val timescale = if (v1) be32(b, 20) else be32(b, 12)
                    val dur = if (v1 && b.size >= 32) be64(b, 24) else if (!v1) be32(b, 16) else 0L
                    if (timescale > 0 && dur > 0) durationSec = (dur / timescale).toInt()
                }
            } else if (type == "udta") {
                // udta -> meta (a box with 4 extra bytes) -> ilst -> one box per tag
                var q = dataStart
                var n = 0
                while (q + 8 <= end && n++ < 50) {
                    val (t2, d2, e2) = boxAt(q, end, read) ?: break
                    if (t2 == "meta") {
                        var r = d2 + 4
                        var m = 0
                        while (r + 8 <= e2 && m++ < 50) {
                            val (t3, d3, e3) = boxAt(r, e2, read) ?: break
                            if (t3 == "ilst") {
                                var s = d3
                                var k = 0
                                while (s + 8 <= e3 && k++ < 200) {
                                    val (key, itemData, itemEnd) = boxAt(s, e3, read) ?: break
                                    // the item holds one 'data' box: size, 'data', flags(4), locale(4), value
                                    val dh = read(itemData, 16)
                                    if (dh != null && str(dh, 4, 4) == "data") {
                                        val dsz = be32(dh, 0)
                                        val valuePos = itemData + 16
                                        val valueLen = (dsz - 16).toInt().coerceAtLeast(0)
                                        if (key == "covr") {
                                            if (coverPos < 0 && valueLen in 1..MAX_COVER) { coverPos = valuePos; coverLen = valueLen }
                                        } else if (valueLen in 1..MAX_LYRICS) {
                                            val v = read(valuePos, valueLen)
                                            if (v != null) {
                                                val text = String(v, Charsets.UTF_8)
                                                when (key) {
                                                    "\u00A9nam" -> title = clean(text)
                                                    "\u00A9ART" -> clean(text)?.let { artists.add(it) }
                                                    "aART" -> albumArtist = clean(text)
                                                    "\u00A9alb" -> album = clean(text)
                                                    "\u00A9day" -> year = yearOf(text)
                                                    "\u00A9lyr" -> lyrics = clean(text)
                                                    "trkn" -> if (v.size >= 4) track = (u(v, 2) shl 8) or u(v, 3)
                                                    "disk" -> if (v.size >= 4) disc = (u(v, 2) shl 8) or u(v, 3)
                                                }
                                            }
                                        }
                                    }
                                    s = itemEnd
                                }
                            }
                            r = e3
                        }
                    }
                    q = e2
                }
            }
            p = end
        }
        val cp = coverPos
        val cl = coverLen
        return ContainerTags(
            title, artists.distinct().joinToString(";").takeIf { it.isNotEmpty() }, albumArtist, album,
            year, track, disc, durationSec, lyrics,
            if (cp >= 0) ({ read(cp, cl) }) else null,
        )
    }

    /** (type, where the box content starts, where the box ends) for the box at [pos]. */
    private fun boxAt(pos: Long, limit: Long, read: (Long, Int) -> ByteArray?): Triple<String, Long, Long>? {
        val want = minOf(16L, limit - pos).toInt()
        if (want < 8) return null
        val h = read(pos, want) ?: return null
        var size = be32(h, 0)
        val type = str(h, 4, 4)
        var header = 8
        if (size == 1L && h.size >= 16) { size = be64(h, 8); header = 16 }
        else if (size == 0L) size = limit - pos
        if (size < header || pos + size > limit + 8) return null
        return Triple(type, pos + header, minOf(pos + size, limit))
    }

    // ------------------------------------------------------------------ Ogg (Vorbis and Opus)
    private fun readOgg(size: Long, read: (Long, Int) -> ByteArray?): ContainerTags? {
        read(0, minOf(size, 262144L).toInt()) // one bigger read so the page reads below stay local
        val packets = ArrayList<ByteArray>()
        var current = ByteArrayOutputStream()
        var pos = 0L
        var pages = 0
        while (packets.size < 2 && pos + 27 <= size && pages++ < 600) {
            val hdr = read(pos, 27) ?: break
            if (str(hdr, 0, 4) != "OggS") break
            val nseg = u(hdr, 26)
            val segs = read(pos + 27, nseg) ?: break
            var bodyLen = 0
            for (s in segs) bodyLen += s.toInt() and 0xFF
            val body = read(pos + 27 + nseg, bodyLen) ?: break
            var off = 0
            for (s in segs) {
                val l = s.toInt() and 0xFF
                current.write(body, off, l)
                off += l
                if (l < 255) {
                    packets.add(current.toByteArray())
                    current = ByteArrayOutputStream()
                    if (packets.size >= 2) break
                }
            }
            pos += 27 + nseg + bodyLen
            if (current.size() > 1_500_000) break // a huge embedded picture: read the text tags, skip the rest
        }
        if (packets.size == 1 && current.size() > 16) packets.add(current.toByteArray())
        if (packets.size < 2) return null

        val ident = packets[0]
        val comments = packets[1]
        var rate = 0L
        var preSkip = 0L
        val start: Int
        when {
            ident.size >= 16 && ident[0].toInt() == 1 && str(ident, 1, 6) == "vorbis" -> { rate = le32(ident, 12); start = 7 }
            ident.size >= 12 && str(ident, 0, 8) == "OpusHead" -> { rate = 48000; preSkip = le16(ident, 10).toLong(); start = 8 }
            else -> return null
        }
        val okHeader = if (start == 7) comments.size > 7 && comments[0].toInt() == 3 else comments.size > 8 && str(comments, 0, 8) == "OpusTags"
        if (!okHeader) return null

        val artists = ArrayList<String>()
        val albumArtists = ArrayList<String>()
        var title: String? = null
        var album: String? = null
        var year = 0
        var track = 0
        var disc = 0
        var lyrics: String? = null
        var picture: ByteArray? = null
        try {
            var p = start
            val vendor = le32(comments, p).toInt(); p += 4 + vendor
            val count = le32(comments, p).toInt(); p += 4
            for (n in 0 until count) {
                if (p + 4 > comments.size) break
                val l = le32(comments, p).toInt(); p += 4
                if (l < 0 || p + l > comments.size) break
                val entry = String(comments, p, l, Charsets.UTF_8); p += l
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                val v = entry.substring(eq + 1)
                when (entry.substring(0, eq).uppercase()) {
                    "TITLE" -> title = clean(v)
                    "ARTIST" -> clean(v)?.let { artists.add(it) }
                    "ALBUMARTIST", "ALBUM ARTIST" -> clean(v)?.let { albumArtists.add(it) }
                    "ALBUM" -> album = clean(v)
                    "DATE", "YEAR" -> if (year == 0) year = yearOf(v)
                    "TRACKNUMBER" -> track = firstInt(v)
                    "DISCNUMBER" -> disc = firstInt(v)
                    "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (lyrics == null) lyrics = clean(v)
                    "METADATA_BLOCK_PICTURE" -> if (picture == null) {
                        picture = try { pictureFromFlacBlock(java.util.Base64.getDecoder().decode(v.trim())) } catch (e: Exception) { null }
                    }
                }
            }
        } catch (e: Exception) {
            // keep whatever was read before the problem
        }

        // length = position of the last page (granule position) divided by the sample rate
        var durationSec = 0
        try {
            val tailLen = minOf(70000L, size).toInt()
            val tail = read(size - tailLen, tailLen)
            if (tail != null && rate > 0) {
                var i = tail.size - 27
                while (i >= 0) {
                    if (tail[i].toInt() == 'O'.code && str(tail, i, 4) == "OggS" && tail[i + 4].toInt() == 0) {
                        val granule = le64(tail, i + 6)
                        if (granule > 0) {
                            durationSec = ((granule - preSkip) / rate).toInt().coerceAtLeast(0)
                            break
                        }
                    }
                    i--
                }
            }
        } catch (e: Exception) {
            // no length
        }
        val pic = picture
        return ContainerTags(
            title, artists.distinct().joinToString(";").takeIf { it.isNotEmpty() },
            albumArtists.distinct().joinToString(";").takeIf { it.isNotEmpty() }, album,
            year, track, disc, durationSec, lyrics, if (pic != null) ({ pic }) else null,
        )
    }

    /** A FLAC "picture" block (also used inside Ogg comments): returns the image bytes. */
    private fun pictureFromFlacBlock(b: ByteArray): ByteArray? {
        var p = 4
        p += 4 + be32(b, p).toInt() // mime type
        p += 4 + be32(b, p).toInt() // description
        p += 16 // width, height, depth, colours
        val dl = be32(b, p).toInt()
        p += 4
        return if (dl > 0 && p + dl <= b.size) b.copyOfRange(p, p + dl) else null
    }

    // ------------------------------------------------------------------ WAV
    private fun readWav(size: Long, read: (Long, Int) -> ByteArray?): ContainerTags? {
        var pos = 12L
        var byteRate = 0L
        var dataLen = -1L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var year = 0
        var track = 0
        var hops = 0
        while (pos + 8 <= size && hops++ < 40) {
            val h = read(pos, 8) ?: break
            val id = str(h, 0, 4)
            val len = le32(h, 4)
            val body = pos + 8
            when (id) {
                "fmt " -> read(body, 16)?.let { byteRate = le32(it, 8) }
                "data" -> dataLen = if (len == 0L || len == 0xFFFFFFFFL || body + len > size) size - body else len
                "LIST" -> {
                    val t = read(body, 4)
                    if (t != null && str(t, 0, 4) == "INFO" && len in 4..65536) {
                        var q = body + 4
                        val end = body + len
                        var n = 0
                        while (q + 8 <= end && n++ < 40) {
                            val sh = read(q, 8) ?: break
                            val sid = str(sh, 0, 4)
                            val sl = le32(sh, 4).toInt()
                            if (sl <= 0 || q + 8 + sl > end + 1) break
                            val v = read(q + 8, minOf(sl, 4096)) ?: break
                            val text = clean(String(v, Charsets.UTF_8))
                            when (sid) {
                                "INAM" -> title = text
                                "IART" -> artist = text
                                "IPRD" -> album = text
                                "ICRD" -> year = yearOf(text)
                                "ITRK", "IPRT" -> track = firstInt(text)
                            }
                            q += 8 + sl + (sl and 1)
                        }
                    }
                }
            }
            if (id == "data" && (len == 0L || len == 0xFFFFFFFFL)) break // unknown end: cannot keep walking
            pos = body + len + (len and 1L)
        }
        val seconds = if (dataLen > 0 && byteRate > 0) (dataLen / byteRate).toInt() else 0
        return ContainerTags(title, artist, null, album, year, track, 0, seconds, null, null)
    }
}
