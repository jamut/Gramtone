/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import java.util.concurrent.ConcurrentHashMap

/**
 * Some FLAC files (made by converters or rippers that write to a pipe) never had their total length filled
 * into the header: the "total samples" number is 0. Media3 then reports an unknown length and refuses to seek.
 *
 * This works out the real number from the last audio frame at the end of the file, and offers a 5-byte
 * correction that the data source puts over the header while the player reads it. The file itself is untouched.
 */
object TelegramFlacFix {
    /** Replace the bytes at [offset] (absolute position in the file) with [bytes]. */
    class Patch(val offset: Long, val bytes: ByteArray, val durationSec: Int) {
        /** Overwrites the part of [buffer] (which holds the file bytes starting at [position]) that this patch covers. */
        fun applyTo(position: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
            val from = maxOf(position, offset)
            val to = minOf(position + length, offset + bytes.size)
            var p = from
            while (p < to) {
                buffer[bufferOffset + (p - position).toInt()] = bytes[(p - offset).toInt()]
                p++
            }
        }
    }

    /** Header numbers read from the file, plus the correction when the length was missing. */
    class Result(val durationSec: Int, val patch: Patch?)

    private const val TAIL = 256 * 1024
    private val decided = ConcurrentHashMap<String, Optional>()

    /** Wrapper so "no patch needed" can be remembered too. */
    private class Optional(val patch: Patch?)

    /** Cached answer for a file, or null when we have not looked at it yet. */
    fun cached(key: String): Patch? = decided[key]?.patch

    /** Looks at the file once per key and remembers the answer. */
    fun patchFor(key: String, fileSize: Long, read: (pos: Long, len: Int) -> ByteArray?): Patch? {
        decided[key]?.let { return it.patch }
        val patch = analyze(fileSize, read)?.patch
        decided[key] = Optional(patch)
        return patch
    }

    /**
     * Reads the FLAC header and, if its total length is 0, finds the real one.
     * Returns null for anything that is not a FLAC file or cannot be analysed.
     */
    fun analyze(fileSize: Long, read: (pos: Long, len: Int) -> ByteArray?): Result? {
        if (fileSize < 64) return null
        val head = read(0, 10) ?: return null
        var start = 0L
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            start = 10L + size + footer
        }
        val marker = read(start, 8) ?: return null
        if (String(marker, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
        // first metadata block must be STREAMINFO (type 0, 34 bytes)
        if ((marker[4].toInt() and 0x7F) != 0) return null
        val si = read(start + 8, 34) ?: return null

        val minBlock = ((si[0].toInt() and 0xFF) shl 8) or (si[1].toInt() and 0xFF)
        val sampleRate = ((si[10].toInt() and 0xFF) shl 12) or ((si[11].toInt() and 0xFF) shl 4) or
            ((si[12].toInt() and 0xFF) shr 4)
        if (sampleRate <= 0) return null
        val total = ((si[13].toLong() and 0x0F) shl 32) or
            ((si[14].toLong() and 0xFF) shl 24) or ((si[15].toLong() and 0xFF) shl 16) or
            ((si[16].toLong() and 0xFF) shl 8) or (si[17].toLong() and 0xFF)
        if (total > 0) return Result((total / sampleRate).toInt(), null) // header is fine

        val tailLen = minOf(TAIL.toLong(), fileSize).toInt()
        val tail = read(fileSize - tailLen, tailLen) ?: return null
        val real = totalSamplesFromFrames(tail, minBlock) ?: return null
        val seconds = real / sampleRate
        if (seconds < 1 || seconds > 12L * 3600) return null // not believable

        val fixed = ByteArray(5)
        fixed[0] = ((si[13].toInt() and 0xF0) or ((real shr 32).toInt() and 0x0F)).toByte()
        fixed[1] = (real shr 24).toByte()
        fixed[2] = (real shr 16).toByte()
        fixed[3] = (real shr 8).toByte()
        fixed[4] = real.toByte()
        return Result(seconds.toInt(), Patch(start + 8 + 13, fixed, seconds.toInt()))
    }

    private class Frame(val pos: Int, val number: Long, val blockSize: Int, val variable: Boolean)

    /** Total samples = first sample of the last frame + its length. */
    private fun totalSamplesFromFrames(tail: ByteArray, fixedBlock: Int): Long? {
        val frames = ArrayList<Frame>()
        var i = 0
        while (i + 8 < tail.size) {
            if ((tail[i].toInt() and 0xFF) == 0xFF && (tail[i + 1].toInt() and 0xFE) == 0xF8) {
                parseFrame(tail, i)?.let { frames.add(it) }
            }
            i++
        }
        if (frames.isEmpty()) return null

        // Real frames follow each other; a lucky byte pattern inside audio data does not.
        var best: List<Frame> = emptyList()
        for (s in frames.indices) {
            val chain = arrayListOf(frames[s])
            for (k in s + 1 until frames.size) {
                val prev = chain.last()
                val f = frames[k]
                val expected = if (prev.variable) prev.number + prev.blockSize else prev.number + 1
                if (f.variable == prev.variable && f.number == expected) chain.add(f)
            }
            val longer = chain.size > best.size
            val sameButLater = chain.size == best.size && best.isNotEmpty() && chain.last().pos > best.last().pos
            if (longer || sameButLater) best = chain
        }
        if (best.size < 2 && frames.size > 1) return null // cannot tell real frames from lookalikes
        val last = best.last()
        val firstSample = if (last.variable) last.number else last.number * fixedBlock
        if (!last.variable && fixedBlock <= 0) return null
        return firstSample + last.blockSize
    }

    private fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (k in from until to) {
            crc = crc xor (b[k].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    /** Parses and checks a FLAC frame header at [i]; null when it is not a genuine one. */
    private fun parseFrame(b: ByteArray, i: Int): Frame? {
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        val b3 = b[i + 3].toInt() and 0xFF
        val variable = (b1 and 1) == 1
        val bsCode = b2 shr 4
        val srCode = b2 and 0x0F
        val chan = b3 shr 4
        val sizeCode = (b3 shr 1) and 7
        if (bsCode == 0 || srCode == 15 || chan > 10 || sizeCode == 3 || (b3 and 1) != 0) return null

        // frame number (fixed block size) or sample number (variable): UTF-8 style, up to 7 bytes
        var p = i + 4
        val lead = b[p].toInt() and 0xFF
        val extra = when {
            lead < 0x80 -> 0
            lead and 0xE0 == 0xC0 -> 1
            lead and 0xF0 == 0xE0 -> 2
            lead and 0xF8 == 0xF0 -> 3
            lead and 0xFC == 0xF8 -> 4
            lead and 0xFE == 0xFC -> 5
            lead == 0xFE -> 6
            else -> return null
        }
        if (p + extra + 1 >= b.size) return null
        var value = if (extra == 0) lead.toLong() else (lead and (0x3F shr extra)).toLong()
        for (k in 1..extra) {
            val c = b[p + k].toInt() and 0xFF
            if ((c and 0xC0) != 0x80) return null
            value = (value shl 6) or (c and 0x3F).toLong()
        }
        p += extra + 1

        var blockSize = when (bsCode) {
            1 -> 192
            2, 3, 4, 5 -> 576 shl (bsCode - 2)
            6, 7 -> 0 // read below
            else -> 256 shl (bsCode - 8)
        }
        if (bsCode == 6) {
            if (p >= b.size) return null
            blockSize = (b[p].toInt() and 0xFF) + 1
            p += 1
        } else if (bsCode == 7) {
            if (p + 1 >= b.size) return null
            blockSize = (((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)) + 1
            p += 2
        }
        p += when (srCode) { 12 -> 1; 13, 14 -> 2; else -> 0 }
        if (p >= b.size) return null
        if (crc8(b, i, p) != (b[p].toInt() and 0xFF)) return null
        return Frame(i, value, blockSize, variable)
    }
}

/**
 * Songs played before this fix may have their (uncorrected) first bytes saved in the player's cache,
 * and the player would keep reading those. Forget the Telegram songs in that cache, once.
 * Downloaded songs are left alone; re-download a FLAC if it still cannot seek.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun TelegramFlacFix.dropOldCacheOnce(context: android.content.Context, cache: androidx.media3.datasource.cache.Cache) {
    val prefs = context.getSharedPreferences("tg_poc", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("flac_cache_dropped_v1", false)) return
    prefs.edit().putBoolean("flac_cache_dropped_v1", true).apply()
    try {
        for (key in cache.keys.toList()) {
            if (TelegramUri.isTelegramId(key)) cache.removeResource(key)
        }
    } catch (e: Exception) {
        // best effort
    }
}
