<div align="center">

# Gramtone

### YouTube Music and your own Telegram music library, in one Android app

<!-- logo coming soon: assets/gramtone-logo.png -->

</div>

Gramtone is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist), the open-source YouTube Music client for Android. It keeps everything Metrolist does and adds a second music source: **your own private Telegram channel**, used as a personal music cloud.

> **Status: early and experimental.** This is a personal project. Expect rough edges, and keep backups of any music you can't replace.

## What Gramtone adds

- **Telegram music library.** Point the app at a private Telegram channel that holds your music. Songs sent as audio or as plain files (MP3, FLAC, M4A and more) are found automatically.
- **Streaming without a full download.** Songs play straight from Telegram, fetching only the parts being played, so a big FLAC doesn't need to be stored on your phone first.
- **Albums, artists and covers.** The app reads tags (title, artist, album, year, track number, cover art) from the start of each file and builds a local catalog. Untagged files fall back to the file name.
- **Smooth playback.** The next songs in the queue are loaded ahead of time.
- **Offline downloads.** Telegram songs download through Metrolist's own downloader, so they play with no internet.
- **One app, both sources.** Home, Library and Search show your Telegram music next to YouTube Music.
- **Scan control.** Stop a scan at any time and switch to a different channel.

Everything from Metrolist is still there: YouTube Music playback, lyrics, queue, equalizer, downloads, and more. See the [Metrolist README](https://github.com/MetrolistGroup/Metrolist#readme) for its full feature list.

## How it works

```
Private Telegram channel (your music)
        |  TDLib (Telegram's official client library)
        v
Gramtone  --  local catalog (tags, albums, covers)
        |  ranged reads, only the bytes being played
        v
Metrolist's player (Media3 / ExoPlayer)
```

Your Telegram login and music stay on your device and your own Telegram account. There is no Gramtone server.

## Set it up

1. Create a free API ID and API hash at [my.telegram.org](https://my.telegram.org) (API development tools).
2. Make a **private** Telegram channel and upload your music to it. Well-tagged files give the best albums and covers. A tag editor such as Mp3tag helps.
3. Open Gramtone, go to **Library**, tap **Telegram**, enter your API ID and hash, log in with your phone number, and pick your channel.
4. Wait for the first scan to read the tags. You can press **Stop** at any time.

## Build it yourself

You need a fork on GitHub (no local setup required) or JDK 21 with the Android SDK.

- **With GitHub Actions:** the included workflow (`.github/workflows/telegram-build.yml`) builds a debug APK and uploads it as an artifact on every push.
- **Locally:**
  ```
  ./gradlew :app:assembleFossDebug
  ```

## Known limits

- Search shows Telegram matches above the YouTube results, but there is no merged ranking yet.
- Telegram songs are not added to Metrolist's own YouTube-based recommendations.
- Albums need tags. Untagged songs appear in Songs but not in Albums.
- Telegram is not designed as a bulk music store, and heavy use may be rate limited. Use your own music and keep backups.

## Legal

Gramtone is for music you own or have the right to store. It is not affiliated with, endorsed by, or sponsored by Telegram, Google, YouTube, or the Metrolist project. Those names belong to their owners.

## License and credits

Gramtone is free software under the **GNU GPL v3.0**, the same license as Metrolist. See [LICENSE](LICENSE).

It is built on the work of the [Metrolist](https://github.com/MetrolistGroup/Metrolist) team and its contributors. The Telegram connection uses [TDLib](https://github.com/tdlib/td).
