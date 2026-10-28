/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MusicSourceType { YOUTUBE, TELEGRAM }

/** Which music source the app is using right now. Remembered between launches. */
object MusicSource {
    private var prefs: SharedPreferences? = null
    private val _current = MutableStateFlow(MusicSourceType.YOUTUBE)
    val current: StateFlow<MusicSourceType> = _current

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("tg_poc", Context.MODE_PRIVATE)
        prefs = p
        _current.value = p.getString("source", null)
            ?.let { name -> runCatching { MusicSourceType.valueOf(name) }.getOrNull() }
            ?: MusicSourceType.YOUTUBE
    }

    fun set(type: MusicSourceType) {
        _current.value = type
        prefs?.edit()?.putString("source", type.name)?.apply()
    }

    fun toggle() = set(if (_current.value == MusicSourceType.YOUTUBE) MusicSourceType.TELEGRAM else MusicSourceType.YOUTUBE)
}

@Composable
fun rememberMusicSource(): MusicSourceType = MusicSource.current.collectAsState().value
