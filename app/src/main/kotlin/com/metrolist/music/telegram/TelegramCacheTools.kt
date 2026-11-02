/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import androidx.media3.datasource.cache.Cache

object TelegramCacheTools {
    /**
     * Songs played before the FLAC fix may have their (uncorrected) first bytes saved in the player's cache,
     * and the player would keep reading those. Forget the Telegram songs in that cache, once.
     * Downloaded songs are left alone; re-download a FLAC if it still cannot seek.
     */
    fun dropOldFlacCacheOnce(context: Context, cache: Cache) {
        val prefs = context.getSharedPreferences("tg_poc", Context.MODE_PRIVATE)
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
}
