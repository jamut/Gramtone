/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Artist photos for the Artists tab. Looks the name up on Deezer, then Wikipedia (both free, no account),
 * keeps the picture on the phone, and only asks again if nothing was found after 30 days.
 * Only the artist's name leaves the phone.
 */
object TelegramArtistImages {
    private const val RETRY_AFTER_MS = 30L * 24 * 3600 * 1000
    private const val USER_AGENT = "Gramtone/1.0 (personal music app; https://github.com/jamut)"

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths

    private val gate = Semaphore(2)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "tg_artists").also { it.mkdirs() }

    private fun fileKey(name: String) = name.trim().lowercase().hashCode().toUInt().toString(16)

    suspend fun ensure(context: Context, name: String) {
        if (name.isBlank() || name == "Unknown artist") return
        val d = dir(context)
        val key = fileKey(name)
        val photo = File(d, "$key.jpg")
        if (photo.exists()) {
            publish(name, photo)
            return
        }
        val none = File(d, "$key.none")
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_AFTER_MS) return
        if (!inFlight.add(key)) return
        try {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    try {
                        val url = deezer(name) ?: wikipedia(name)
                        val bytes = url?.let { getBytes(it) }
                        if (bytes != null && TelegramTagReader.saveCover(bytes, photo) != null) {
                            publish(name, photo)
                        } else {
                            none.writeText("") // looked, nothing found: do not ask again for a month
                        }
                    } catch (e: IOException) {
                        // offline or the site is down: just try again next time
                    }
                }
            }
        } finally {
            inFlight.remove(key)
        }
    }

    private fun publish(name: String, f: File) {
        _paths.value = _paths.value + (name to f.absolutePath)
    }

    private fun deezer(name: String): String? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val json = getText("https://api.deezer.com/search/artist?q=$q&limit=5") ?: return null
        val arr = JSONObject(json).optJSONArray("data") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optString("name").equals(name.trim(), ignoreCase = true)) continue
            val u = o.optString("picture_xl").ifBlank { o.optString("picture_big") }
            // Deezer hands out a grey placeholder for artists without a photo
            if (u.isNotBlank() && !u.contains("/artist//")) return u
        }
        return null
    }

    private fun wikipedia(name: String): String? {
        val title = URLEncoder.encode(name.trim().replace(' ', '_'), "UTF-8").replace("+", "%20")
        val json = getText("https://en.wikipedia.org/api/rest_v1/page/summary/$title") ?: return null
        val o = JSONObject(json)
        if (o.optString("type") != "standard") return null
        val about = (o.optString("description") + " " + o.optString("extract").take(240)).lowercase()
        // make sure it is a musician, not a place or a film with the same name
        if (!Regex("singer|rapper|musician|band|dj\\b|producer|songwriter|group|duo|composer|vocalist").containsMatchIn(about)) return null
        return o.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** Text of a page, or null when the page does not exist. */
    private fun getText(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun getBytes(url: String): ByteArray? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            val data = c.inputStream.use { it.readBytes() }
            return data.takeIf { it.isNotEmpty() && it.size < 8 * 1024 * 1024 }
        } finally {
            c.disconnect()
        }
    }
}

/** Round artist photo, falling back to the album cover while (or if) no photo is found. */
@Composable
fun TgArtistAvatar(name: String, fallbackCover: String?, size: Dp) {
    val context = LocalContext.current
    LaunchedEffect(name) { TelegramArtistImages.ensure(context, name) }
    val photos by TelegramArtistImages.paths.collectAsState()
    val photo = photos[name]
    if (photo != null) {
        AsyncImage(
            model = File(photo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        TgCover(fallbackCover, size)
    }
}
