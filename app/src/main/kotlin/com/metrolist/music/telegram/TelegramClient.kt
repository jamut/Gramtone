/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Thin wrapper over TDLib: auth state, blocking requests, ranged file download. */
object TelegramClient {
    enum class AuthState { NOT_STARTED, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, ERROR }

    private val _auth = MutableStateFlow(AuthState.NOT_STARTED)
    val auth: StateFlow<AuthState> = _auth

    private var client: Client? = null
    private var apiId = 0
    private var apiHash = ""
    private lateinit var appContext: Context

    @Synchronized
    fun start(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        appContext = context.applicationContext
        this.apiId = apiId
        this.apiHash = apiHash
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ update -> onUpdate(update) }, null, null)
    }

    /** Restart the saved session after process death (TDLib keeps the login on disk). */
    fun autoStart(context: Context) {
        val prefs = context.getSharedPreferences("tg_poc", Context.MODE_PRIVATE)
        val id = prefs.getString("id", null)?.toIntOrNull() ?: return
        val hash = prefs.getString("hash", null) ?: return
        start(context, id, hash)
    }

    /** Blocks until logged in; used by the player before it reads from Telegram. */
    fun awaitReady(timeoutSec: Long = 20) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (_auth.value != AuthState.READY) {
            if (System.currentTimeMillis() > deadline) error("Telegram not logged in")
            Thread.sleep(100)
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) {
            when (update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val p = TdApi.SetTdlibParameters()
                    p.databaseDirectory = appContext.filesDir.absolutePath + "/tdlib"
                    // Partially downloaded audio lives here; see trimCache().
                    p.filesDirectory = appContext.cacheDir.absolutePath + "/tdlib_files"
                    p.databaseEncryptionKey = ByteArray(0)
                    p.useFileDatabase = true
                    p.useChatInfoDatabase = true
                    p.useMessageDatabase = true
                    p.useSecretChats = false
                    p.apiId = apiId
                    p.apiHash = apiHash
                    p.systemLanguageCode = "en"
                    p.deviceModel = "Android"
                    p.systemVersion = android.os.Build.VERSION.RELEASE
                    p.applicationVersion = "1.0"
                    client?.send(p, null)
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = AuthState.WAIT_PHONE
                is TdApi.AuthorizationStateWaitCode -> _auth.value = AuthState.WAIT_CODE
                is TdApi.AuthorizationStateWaitPassword -> _auth.value = AuthState.WAIT_PASSWORD
                is TdApi.AuthorizationStateReady -> {
                    _auth.value = AuthState.READY
                    trimCache() // drop chunks left over from earlier sessions
                }
                is TdApi.AuthorizationStateClosed -> { client = null; _auth.value = AuthState.NOT_STARTED }
                else -> Unit
            }
        }
    }

    fun submitPhone(phone: String) { client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), null) }
    fun submitCode(code: String) { client?.send(TdApi.CheckAuthenticationCode(code), null) }
    fun submitPassword(pw: String) { client?.send(TdApi.CheckAuthenticationPassword(pw), null) }

    /** Blocking request. Never call on the main thread. */
    @Suppress("UNCHECKED_CAST")
    fun <T : TdApi.Object> call(fn: TdApi.Function<T>, timeoutSec: Long = 60): T {
        val c = client ?: error("Telegram client not started")
        var result: TdApi.Object? = null
        val latch = CountDownLatch(1)
        c.send(fn) { result = it; latch.countDown() }
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) error("Telegram request timed out")
        val r = result
        if (r is TdApi.Error) error("Telegram error ${r.code}: ${r.message}")
        return r as T
    }

    fun listChats(): List<TdApi.Chat> {
        runCatching { call(TdApi.LoadChats(TdApi.ChatListMain(), 100)) } // 404 when all loaded
        val ids = call(TdApi.GetChats(TdApi.ChatListMain(), 100)).chatIds
        return ids.map { call(TdApi.GetChat(it)) }
    }

    /** Audio messages of a chat, newest first. Pages with getChatHistory (stable API across TDLib versions). */
    fun listAudio(chatId: Long, maxCount: Int = 200): List<TdApi.Message> {
        val out = ArrayList<TdApi.Message>()
        var from = 0L
        while (out.size < maxCount) {
            val msgs = call(TdApi.GetChatHistory(chatId, from, 0, 50, false)).messages
            if (msgs.isEmpty()) break
            msgs.filterTo(out) { it.content is TdApi.MessageAudio }
            from = msgs.last().id
        }
        return out
    }

    fun deleteLocalFile(fileId: Int) {
        client?.send(TdApi.DeleteFile(fileId), null)
    }

    /** Delete downloaded chunks so Telegram streaming doesn't eat phone storage. */
    fun trimCache() {
        // size=0, ttl=0, count=0 -> delete everything older than the 300s immunity window
        // (so the track currently playing survives).
        client?.send(TdApi.OptimizeStorage(0L, 0, 0, 300, null, null, null, false, 0), null)
    }
}
