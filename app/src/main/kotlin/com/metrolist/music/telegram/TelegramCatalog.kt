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
    val lyrics: String = "",
) {
    /** Stable key used to group tracks into albums. */
    val albumKey: String get() = TelegramCatalog.albumKey(albumArtistList.firstOrNull() ?: artistList.firstOrNull() ?: "", album)

    /** "Kanye West; Playboi Carti" in the tags becomes two artists. */
    val artistList: List<String> get() = splitArtists(artist)
    val albumArtistList: List<String> get() = splitArtists(albumArtist)

    /** Artists shown for this song: the song's own, else the album's, else unknown. */
    val artistNames: List<String> get() = artistList.ifEmpty { albumArtistList }.ifEmpty { listOf("Unknown artist") }
    val displayArtist: String get() = artistNames.joinToString(", ")
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

/** Splits "A; B" (also zero bytes) into separate, trimmed, distinct names. */
fun splitArtists(raw: String): List<String> =
    raw.split(';', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun TgTrack.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
        id = id,
        title = title,
        artists = artistNames.map { MediaMetadata.Artist(id = null, name = it) },
        duration = durationSec,
        thumbnailUrl = coverPath?.let { Uri.fromFile(File(it)).toString() },
    )

fun TgTrack.toMediaItem(): MediaItem = toMediaMetadata().toMediaItem()

/** Local SQLite catalog of everything found in the Telegram music chat. Separate from Metrolist's own database. */
object TelegramCatalog {
    private const val DB_NAME = "telegram_catalog.db"
    private const val TABLE = "tracks"
    private const val COLS = "id, chat_id, message_id, title, artist, album_artist, album, year, track_no, disc_no, duration, file_name, cover_path, indexed"

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

    /** Many rows in a single transaction: far faster than one write per song. */
    fun insertBasicBatch(list: List<TgTrack>) {
        if (list.isEmpty()) return
        val d = db
        d.beginTransaction()
        try {
            for (t in list) d.insertWithOnConflict(TABLE, null, t.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Updates one row. Lyrics are kept as they are unless the new row carries lyrics. */
    fun update(t: TgTrack) {
        val v = t.toValues()
        if (t.lyrics.isBlank()) v.remove("lyrics")
        if (db.update(TABLE, v, "id = ?", arrayOf(t.id)) == 0) {
            db.insertWithOnConflict(TABLE, null, t.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun hasUnindexed(): Boolean =
        db.rawQuery("SELECT 1 FROM $TABLE WHERE indexed = 0 LIMIT 1", null).use { it.moveToFirst() }

    /** Lyrics are not kept in the in-memory list (thousands of songs); fetch them on demand. */
    fun lyricsOf(id: String): String =
        db.rawQuery("SELECT lyrics FROM $TABLE WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }

    fun unindexed(): List<TgTrack> = query("indexed = 0")

    /** Remember the length ExoPlayer found when it had to be guessed from the tags. */
    fun setDurationIfMissing(id: String, seconds: Int) {
        db.execSQL("UPDATE $TABLE SET duration = ? WHERE id = ? AND duration <= 0", arrayOf<Any>(seconds, id))
        refresh()
    }

    fun clear() {
        db.delete(TABLE, null, null)
        refresh()
    }

    fun refresh() {
        _tracks.value = query(null)
    }

    private fun query(where: String?): List<TgTrack> {
        val out = ArrayList<TgTrack>()
        db.rawQuery("SELECT $COLS FROM $TABLE" + (where?.let { " WHERE $it" } ?: ""), null).use { c ->
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
        put("lyrics", lyrics)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY, chat_id INTEGER, message_id INTEGER,
                    title TEXT, artist TEXT, album_artist TEXT, album TEXT,
                    year INTEGER, track_no INTEGER, disc_no INTEGER, duration INTEGER,
                    file_name TEXT, cover_path TEXT, indexed INTEGER, lyrics TEXT)""",
            )
        }

        // v2/v3: tag reader was rewritten, so throw the old rows away and re-read everything
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
