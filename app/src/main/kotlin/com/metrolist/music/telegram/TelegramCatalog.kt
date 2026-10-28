/**
 * Metrolist Telegram source (experimental)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** One song that lives in a Telegram chat. */
data class TgTrack(
    val id: String,
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationSec: Int,
    val fileName: String,
    val coverPath: String?,
    val indexed: Boolean,
) {
    /** Stable key used to group tracks into albums. */
    val albumKey: String get() = TelegramCatalog.albumKey(albumArtist.ifBlank { artist }, album)
    val displayArtist: String get() = artist.ifBlank { albumArtist }.ifBlank { "Unknown artist" }
    val displayAlbum: String get() = album.ifBlank { "Unknown album" }
}

data class TgAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val coverPath: String?,
    val tracks: List<TgTrack>,
)

data class TgArtist(val name: String, val albums: Int, val tracks: Int, val coverPath: String?)

fun TgTrack.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = displayArtist)),
        duration = durationSec,
        thumbnailUrl = coverPath?.let { Uri.fromFile(File(it)).toString() },
    )

fun TgTrack.toMediaItem(): MediaItem = toMediaMetadata().toMediaItem()

/** Local SQLite catalog of everything found in the Telegram music chat. Separate from Metrolist's own database. */
object TelegramCatalog {
    private const val DB_NAME = "telegram_catalog.db"
    private const val TABLE = "tracks"

    private var helper: Helper? = null
    private val _tracks = MutableStateFlow<List<TgTrack>>(emptyList())
    val tracks: StateFlow<List<TgTrack>> = _tracks

    @Synchronized
    fun init(context: Context) {
        if (helper != null) return
        helper = Helper(context.applicationContext)
        refresh()
    }

    private val db: SQLiteDatabase get() = helper!!.writableDatabase

    fun albumKey(artist: String, album: String): String =
        (artist.trim().lowercase() + "|" + album.trim().lowercase()).hashCode().toUInt().toString(16)

    fun coverDir(context: Context): File = File(context.filesDir, "tg_covers").also { it.mkdirs() }

    fun knownIds(): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT id FROM $TABLE", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun insertBasic(t: TgTrack) {
        db.insertWithOnConflict(TABLE, null, t.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun update(t: TgTrack) {
        db.insertWithOnConflict(TABLE, null, t.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unindexed(): List<TgTrack> = query("indexed = 0")

    fun clear() {
        db.delete(TABLE, null, null)
        refresh()
    }

    fun refresh() {
        _tracks.value = query(null)
    }

    private fun query(where: String?): List<TgTrack> {
        val out = ArrayList<TgTrack>()
        db.rawQuery("SELECT * FROM $TABLE" + (where?.let { " WHERE $it" } ?: ""), null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TgTrack(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        chatId = c.getLong(c.getColumnIndexOrThrow("chat_id")),
                        messageId = c.getLong(c.getColumnIndexOrThrow("message_id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        artist = c.getString(c.getColumnIndexOrThrow("artist")),
                        albumArtist = c.getString(c.getColumnIndexOrThrow("album_artist")),
                        album = c.getString(c.getColumnIndexOrThrow("album")),
                        year = c.getInt(c.getColumnIndexOrThrow("year")),
                        trackNo = c.getInt(c.getColumnIndexOrThrow("track_no")),
                        discNo = c.getInt(c.getColumnIndexOrThrow("disc_no")),
                        durationSec = c.getInt(c.getColumnIndexOrThrow("duration")),
                        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
                        coverPath = c.getString(c.getColumnIndexOrThrow("cover_path")),
                        indexed = c.getInt(c.getColumnIndexOrThrow("indexed")) == 1,
                    ),
                )
            }
        }
        return out
    }

    private fun TgTrack.toValues() = ContentValues().apply {
        put("id", id)
        put("chat_id", chatId)
        put("message_id", messageId)
        put("title", title)
        put("artist", artist)
        put("album_artist", albumArtist)
        put("album", album)
        put("year", year)
        put("track_no", trackNo)
        put("disc_no", discNo)
        put("duration", durationSec)
        put("file_name", fileName)
        put("cover_path", coverPath)
        put("indexed", if (indexed) 1 else 0)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY, chat_id INTEGER, message_id INTEGER,
                    title TEXT, artist TEXT, album_artist TEXT, album TEXT,
                    year INTEGER, track_no INTEGER, disc_no INTEGER, duration INTEGER,
                    file_name TEXT, cover_path TEXT, indexed INTEGER)""",
            )
        }

        // v2: tag reader was rewritten, so throw the old (empty-tag) rows away and re-read everything
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
