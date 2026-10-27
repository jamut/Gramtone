/**
 * Metrolist Telegram source (experimental) - proof-of-concept screen.
 * Login -> pick channel -> tap a track -> it streams through MusicService.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata as MetrolistMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.queues.ListQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

class TelegramTestActivity : ComponentActivity() {
    private var service: MusicService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) { service = (b as? MusicBinder)?.service }
        override fun onServiceDisconnected(n: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MusicService.isRunning) {
            val i = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.startForegroundService(this, i) else startService(i)
        }
        bindService(Intent(this, MusicService::class.java), conn, BIND_AUTO_CREATE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onDestroy() { runCatching { unbindService(conn) }; super.onDestroy() }

    private fun buildItem(chatId: Long, m: TdApi.Message): MediaItem {
        val a = (m.content as TdApi.MessageAudio).audio
        val md = MetrolistMetadata(
            id = TelegramUri.songId(chatId, m.id),
            title = a.title.ifBlank { a.fileName },
            artists = listOf(MetrolistMetadata.Artist(id = null, name = a.performer.ifBlank { "Unknown" })),
            duration = a.duration,
        )
        return md.toMediaItem()
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val auth by TelegramClient.auth.collectAsState()
        val prefs = remember { getSharedPreferences("tg_poc", MODE_PRIVATE) }
        var apiId by remember { mutableStateOf(prefs.getString("id", "") ?: "") }
        var apiHash by remember { mutableStateOf(prefs.getString("hash", "") ?: "") }
        var input by remember { mutableStateOf("") }
        var chats by remember { mutableStateOf<List<TdApi.Chat>>(emptyList()) }
        var tracks by remember { mutableStateOf<List<TdApi.Message>>(emptyList()) }
        var chatId by remember { mutableStateOf(0L) }
        var status by remember { mutableStateOf("") }

        Column(Modifier.padding(16.dp)) {
            Text("Telegram PoC - $auth  $status")
            when (auth) {
                TelegramClient.AuthState.NOT_STARTED, TelegramClient.AuthState.ERROR -> {
                    OutlinedTextField(apiId, { apiId = it }, label = { Text("api_id (my.telegram.org)") })
                    OutlinedTextField(apiHash, { apiHash = it }, label = { Text("api_hash") })
                    Button(onClick = {
                        prefs.edit().putString("id", apiId).putString("hash", apiHash).apply()
                        TelegramClient.start(applicationContext, apiId.trim().toInt(), apiHash.trim())
                    }) { Text("Start") }
                }
                TelegramClient.AuthState.WAIT_PHONE -> {
                    OutlinedTextField(input, { input = it }, label = { Text("Phone, e.g. +639...") })
                    Button(onClick = { TelegramClient.submitPhone(input.trim()); input = "" }) { Text("Send code") }
                }
                TelegramClient.AuthState.WAIT_CODE -> {
                    OutlinedTextField(input, { input = it }, label = { Text("Login code") })
                    Button(onClick = { TelegramClient.submitCode(input.trim()); input = "" }) { Text("Submit") }
                }
                TelegramClient.AuthState.WAIT_PASSWORD -> {
                    OutlinedTextField(input, { input = it }, label = { Text("2FA password") })
                    Button(onClick = { TelegramClient.submitPassword(input); input = "" }) { Text("Submit") }
                }
                TelegramClient.AuthState.READY -> {
                    if (chats.isEmpty()) {
                        Button(onClick = {
                            scope.launch {
                                status = "loading chats"
                                chats = withContext(Dispatchers.IO) { runCatching { TelegramClient.listChats() }.getOrElse { status = it.message ?: "error"; emptyList() } }
                            }
                        }) { Text("Load my chats / channels") }
                    }
                    if (tracks.isEmpty()) {
                        LazyColumn { items(chats) { c ->
                            Text(c.title, Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    status = "scanning ${c.title}"
                                    chatId = c.id
                                    tracks = withContext(Dispatchers.IO) { runCatching { TelegramClient.listAudio(c.id) }.getOrElse { status = it.message ?: "error"; emptyList() } }
                                    status = "${tracks.size} tracks"
                                }
                            }.padding(12.dp))
                        } }
                    } else {
                        LazyColumn { items(tracks.size) { idx ->
                            val a = (tracks[idx].content as TdApi.MessageAudio).audio
                            Text("${a.performer} - ${a.title.ifBlank { a.fileName }}", Modifier.fillMaxWidth().clickable {
                                val items = tracks.map { buildItem(chatId, it) }
                                service?.playQueue(ListQueue(title = "Telegram", items = items, startIndex = idx))
                            }.padding(12.dp))
                        } }
                    }
                }
            }
        }
    }
}
