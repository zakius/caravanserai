# caravan: the Subsonic app (plan, 2026-10-30)

caravan is the Subsonic and OpenSubsonic client built from this repository, romanized by roma; until 2026-11-01 it was named roma, and before that it was roma's second backend. A caravan travels between oases and handles many languages: the user's server is oasis, the desktop client mirage.

roma gets a second backend next to YouTube Music: any Subsonic or OpenSubsonic server, and first of all oasis (~/dev/oasis), the user's server that merges the local library with YouTube Music and later other services. Romanization and the fixed controls stay the point of the app; they work on any track's metadata. Decided with the user on 2026-10-30.

The music stack:

- **oasis**: a Python OpenSubsonic server. Clients never see where a track comes from. Every response carries `"type": "oasis"` in `subsonic-response`.
- **mirage**: a fooyin fork for the desktop (another thread).
- **caravan**: this app (roma's Subsonic backend until 2026-11-01); **roma ytm**, the YouTube Music one.

## Decisions

- Server-neutral: the Subsonic app speaks only Subsonic and OpenSubsonic. It never checks the server's `type` and reads no server's own fields; an oasis extra is fine only where other servers lose nothing without it (Home's mixes, from `oasisMix`); anything else only one server would do is a bug. No debug-only details either (decided 2026-11-01).
- One codebase. YouTube Music and Subsonic are two implementations of one small interface (`Source`) under the screens. No screen names a backend; a screen asks the source for what it shows, and the source says what it can't do (a dimmed control, a hidden hint). Separate builds, or a combined mode with both at once, can come later and must stay cheap: every id says which source it belongs to (Ids below), so a queue, a ban or a scrobble already finds its source per song.
- ~~On first launch roma asks which backend to use.~~ Since 2026-11-01 each backend is its own app (Two apps below); the build picks the source.
- Library feel on Subsonic: the whole library index (artists, albums, songs, cover ids) is synced into a local store, so browsing and search are instant and work offline.
- Each play is reported exactly once, by the side that knows the real source (Scrobbling below).

## The source interface

`Source` (shared/src/main/…/Source.kt). Each app has one, `backend`, defined by its flavor (Two apps below).

| What | YouTube Music (`YouTubeMusic`, the code that is there now, moved) | Subsonic (`SubsonicSource`) |
|---|---|---|
| Home | `YouTube.home` pages; the personal mixes row; Liked music | Shelves from `getAlbumList2` (recently added, recently played, most played, random), one page. Pinned tiles: the server's mixes (playlists marked `oasisMix`, which oasis publishes from YouTube Music's personal mixes; other servers have none), then Starred and Shuffle all. |
| Search | `YouTube.search` with filters, spelling correction, suggestions; "Seen before" from the local index | The synced index first, at once, romanized forms included (`matchDistance`); then the server's own `search3` results not already listed (on oasis, YouTube Music's). Same filters; Playlists from the playlist list. No suggestions, no correction line, no "Seen before" section. |
| Library tabs | Playlists, Albums, Artists from the account | Playlists (`getPlaylists`), Albums and Artists from the index. |
| Album, artist, playlist pages | `YouTube.album/artist/playlist` | Album and artist from the index (instant, offline), else `getAlbum`/`getArtist` (oasis: a YouTube Music album seen in search or radio, which it keeps out of the library lists); playlist from `getPlaylist`. An artist page lists the artist's albums and plays all their songs. |
| Stream | `Streams.audio` (VISIONOS, then InnerTubeX), loudness | `stream?id=…`, no transcoding asked; no loudness (ReplayGain later). |
| Radio and queue | YouTube's autoplay (`next`), related artists for the leash | `getSimilarSongs` on the seed song, each next page on the last song of the one before (oasis answers with YouTube Music's radio); when the server has none, random songs from the index. The same filters and leash (`filterRadio`). |
| Mixes, Liked music | Personal mixes, `LM` | Starred songs (`getStarred2`) for the Liked tile; Shuffle all plays the index at random. |
| Like | `likeVideo`, `likeStatus` | `star`/`unstar`; the status from the song's `starred`, kept from the sync and from `getSong`. |
| Lyrics: the reference provider | `YouTubeLyrics` (ANDROID_MUSIC browse) | OpenSubsonic `getLyricsBySongId`, only when the server lists the `songLyrics` extension; with `romanization`, its pronunciation layer too (Server romanization below). The third-party providers run as before. |
| Comments, Share | YouTube comments; a YouTube link | None: both buttons dimmed, in place. |
| Scrobbling | Last.fm from the app | Subsonic `scrobble`; Last.fm from the app only where the rule below says so. |
| Covers | Google-hosted URLs, sized in the URL | `subsonic:<coverArt id>` in the song; `Images` turns it into a `getCoverArt` URL with fresh auth and `size`. No credentials in stored songs. |

What stays as it is: romanization (every name enters through `Romanizer` once, as before), the lyrics rules, the queue sections, the filters, bans, corrections, the player, the controls.

What Subsonic can't provide, and what the UI does:

- No home feed and no personal mixes: Home shows the server's album lists and two fixed tiles. "Your mixes" becomes "Your library" on Subsonic.
- Similar songs only through `getSimilarSongs`. A server without them gets radio from the index at random, leash applied, so "close" still keeps the seed's artist.
- Lyrics from the server only with OpenSubsonic `getLyricsBySongId`; otherwise the third-party providers alone, under rule 5 (no reference text).
- No comments and no share link; their slots stay, dimmed (a control never moves).
- No spelling correction or suggestions in Search; the local search tolerates typos already.

## Ids

A Subsonic id is stored as `s.` + the server's id, with every character other than `A–Z a–z 0–9 _ -` written as `.` and two hex digits per UTF-8 byte. YouTube ids never contain a dot, so `Sources.of(id)` needs no lookup; the stored id is safe as a file name on Windows (lyrics cache, desktop audio cache) and in roma's `roma://<id>` media URIs. Songs, albums, artists and playlists all use it. Existing YouTube data (queue, bans, played, corrections, liked) is unchanged.

One server at a time: changing the server's URL or user drops the index and the server's pages. A combined mode with several servers would put a server tag after `s`.

## First launch and settings

- First launch of the Subsonic app: "Where is your music?", the server's URL, user and password, before the scripts question (`SetupScreen`, `Source.Setup`). Continue pings it (`ping`, then `getOpenSubsonicExtensions`) and says what failed in plain words (no connection, wrong password, not a Subsonic server).
- Settings: a "Server" category, first in the list, shows the server. It edits the server, shows the last sync and its counts, and has "Sync now", and holds the per-server Last.fm switch (Scrobbling below).
- oasis links a streaming account per user on its own web account page. (From 2026-10-30 to 11-01 roma handed its own sign-in to `oasisLinkYouTubeMusic`; the Subsonic app can't, having no YouTube code.)
- Prefs (user storage): `subsonic.url`, `subsonic.user`, `subsonic.password` (token auth needs the password itself; it never leaves the device except as `md5(password + salt)`), `subsonic.lastfm`, `subsonic.romanization` (the server lists the `romanization` extension; set on connecting and by each sync). The detected server type and extensions go to the cache with the index.
- Another server or user starts Home, Search, Library and the open pages over (`Store.dataChanged`).

## Index sync

- Core class `SubsonicIndex` (roma.core, unit-tested): artists, albums and songs with their original fields (id, title, artist, artist id, album, album id, track, disc, year, duration, cover id, starred, created).
- Sync: `search3` with an empty query (`query=""`, which oasis, Navidrome and others read as "everything"), paged with `songCount=500&songOffset=…`, `albumCount`/`albumOffset` and `artistCount`/`artistOffset` the same way, until every list comes back short. One full pass; Subsonic has no change feed. Albums and artists not seen in the songs (an empty album) are kept as the server lists them.
- When: at start, after the first frame, if the last sync is older than 6 hours; on "Sync now"; after the server changes. A sync runs in the background and the old index serves until the new one is complete; a failed sync keeps the old one and says so in Settings.
- Store: cache/subsonic/index.json (it can be fetched again, so cache, per design.md's Storage rule). Then every name is romanized once in the background, as roma `Song` and `Item` values (`Romanizer.song` for Subsonic songs), and kept in cache/subsonic/romanized.json stamped with the romanization version and app version, like the Names cache. A romanization change romanizes the index again in the background; until then the old forms show.
- Search, Library, album and artist pages read the romanized index in memory. Starred songs from the sync go into the liked set.

## Scrobbling

Each play is reported exactly once, by the side that knows the real source (`Source.lastFm`).

- YouTube Music songs: roma scrobbles to Last.fm itself, as now.
- Subsonic songs: always Subsonic `scrobble`, `submission=false` when the song starts (now playing), `submission=true` once `shouldScrobble` holds. caravan scrobbles to Last.fm as well with the per-server switch "Also scrobble to Last.fm from caravan". Until the user sets it, it follows `getUser`'s `scrobblingEnabled`: off when the server says it scrobbles to Last.fm itself, on otherwise. Every server is treated alike.
- Offline: finished plays go into the timestamped queue (`ScrobbleQueue`, one file per target: scrobbles.json for Last.fm as now, subsonic-scrobbles.json for the server). The server's queue is sent as `scrobble` calls with `submission=true` and `time` in milliseconds, one call per batch of up to 50 ids with their times; oasis stores that time. Now-playing calls are never queued.
- Known gap, not in scope: with the YouTube Music backend roma never reports plays to YouTube Music history, only to Last.fm.

## Step order

Each step is a commit (or a few) on the `subsonic-source` branch; the app works after every one.

1. This plan.
2. `Source` extracted, `YouTubeMusic` behind it; no behaviour change. Screens and `Playback` call the source; the YouTube calls move unchanged.
3. `SubsonicClient` in core: auth (salt and token), JSON responses, errors, the calls above. Unit tests against a local test server (the JDK's HttpServer) with canned responses.
4. `SubsonicIndex` and the sync, with unit tests (paging, short pages, starred, id encoding).
5. `SubsonicSource` in shared: pages, search, radio, likes, lyrics, streams and covers from the index and client. Android's player gets the extractors for local formats (MP3, FLAC, Ogg, WAV, ADTS AAC).
6. First-launch backend picker and the Music source settings.
7. Scrobble routing, with unit tests for the decision and the server's queue.
8. Checked on the emulator against a real oasis server with a generated test library (Japanese, Korean, Chinese, Cyrillic and Greek tags).

## Checked on the emulator (2026-10-30)

Against oasis (commit 18b9e5e, run from its venv) with a generated library of 15 songs in 7 albums: Japanese, Korean, Chinese, Russian, Greek and English tags; MP3, FLAC, Opus in Ogg and AAC in M4A; folder covers. From the AVD the host is 10.0.2.2.

- First start on a cleared install: the source question, the scripts question, then Home. An existing install went straight to its YouTube Music queue, and YouTube Music still searched, played, found lyrics and loaded comments.
- Connect: a wrong password says "Wrong user or password."; the right one syncs the library (15 songs, 7 albums, 7 artists).
- Home's tiles and shelves (recently added from the index, recently and most played from the server), Library's albums and artists, album and artist pages, all romanized: Yoru ni Kakeru, Gunjō, Bampyeonji, Qíng Tiān, Gruppa krovi, Ta tragoudia mou.
- Search over the index: "yoru ni" finds 夜に駆ける, "gunjo" finds 群青.
- Playback of all four formats, the album queued and radio from the library after it; Like stars the song on the server; Starred plays the starred songs; Comments dimmed.
- Scrobbling: a play past half the song reached oasis once, with the start time roma sent. With oasis stopped, the play waited in subsonic-scrobbles.json; after oasis came back, roma's next start sent it, and oasis stored the original time.
- With oasis stopped, Search and Library still worked from the index.

## Two apps (2026-11-01)

The YouTube Music and Subsonic backends became two apps built from this repo, with no code of the other in either:

| App | Backend | Ships to | Built by |
|---|---|---|---|
| roma ytm (com.zakius.roma.ytm, files roma-ytm) | YouTube Music only | GitHub releases | `:app:assembleYoutubeRelease`, `:desktop` |
| caravan (com.zakius.caravan, files caravan) | Subsonic/OpenSubsonic only | Google Play | `:app:assembleSubsonicRelease`, `:desktop-subsonic` |

Each app has its colour, on one icon design (the play triangle with a bar beneath it, packaging/*.svg): caravan Subsonic's orange (#F28C28), roma ytm YouTube Music's red, a shade off YouTube's own (#E62117), so its icon isn't YouTube's logo. The colour is also the accent where the system gives none (`Source.brandColor`; Android 12+ follows the wallpaper).

Names, ids and slugs: gradle.properties. Decided 2026-11-01: roma, the name the app had, went to the Play app (nothing had been distributed, so the old releases were deleted and nobody's install moved); later that day the user named the Play app caravan (com.zakius.caravan), so roma stays the project's, the romanization engine's and the dictionary's name. caravan's desktop data folder is its own (~/.local/share/caravan, %APPDATA%\caravan), not roma's old one. The flavors and Gradle projects keep the names `subsonic` and `youtube`, `:desktop-subsonic` and `:desktop`.

Google Play review can find innertube calls, client spoofing or BotGuard in an APK even when no screen uses them, so the Subsonic app's YouTube code is compiled out, not hidden.

- Modules: `:core` (romanization, lyrics rules and LRCLIB, radio filters, scrobbling, roma's models) knows no service. `:youtube` has innertube, InnerTubeX, the stream lookup, comments, the personal mixes and the other lyrics providers. `:subsonic` has the client and index.
- Screens: shared/src/main holds every screen and the `Source` interface, which also brings what only one source shows: its settings page, Home's tiles, its first-start step, the comments view, picture sizing. shared/src/youtube and shared/src/subsonic each define `backend` and their source's own screens.
- Android: product flavors `youtube` (roma ytm) and `subsonic` (caravan: its own id, name, icon, Last.fm link scheme; no self-update). Desktop: `:desktop` and `:desktop-subsonic`, one build script (desktop/build.gradle.kts), their own data folders and packages. The BotGuard WebView, the sign-in, Chromium and CEF are roma ytm's alone.
- Both apps play through ExoPlayer, each with only its source's containers (the flavor's `containers()`: WebM and MP4 for roma ytm; MP3, FLAC, Ogg, WAV, M4A and ADTS AAC for the Subsonic app) and a one-track selector (`FirstAudioTrack`) instead of DefaultTrackSelector. Sizes after release.sh's repack: caravan 1.47 MB, roma ytm 1.69 MB (1.74 before). Android's MediaPlayer under the desktop's queue player made the Subsonic APK 1.19 MB, but went back out (2026-11-01): it had no gapless change, no seek past the downloaded part, no partial download and no ranged cache.
- The Japanese dictionary is downloaded as data, as in every app since 2026-11-01 (docs/design.md, Japanese dictionary): `roma-dict-<version>.zip`, unpacked into filesDir/dict (left out of device transfers), checked against its sha256 and memory-mapped from there. Google Play forbids installing apps (REQUEST_INSTALL_PACKAGES) and running downloaded code, not downloading data.
- Left out of caravan: roma ytm's self-update (and with it REQUEST_INSTALL_PACKAGES), the YouTube Music link to oasis, YouTube share links, YouTube lyrics, NetEase, KuGou and LyricsPlus. Its lyrics: the server's `getLyricsBySongId` as the reference (oasis gathers every provider's), LRCLIB beside it.
- `tools/residue_check.py` fails the build when one app carries the other's code: every file of the Subsonic APK and desktop distribution (jars entry by entry, UTF-8 and UTF-16) against innertube, metrolist, BotGuard, YouTube, googlevideo and VISIONOS; and PackageInstaller or REQUEST_INSTALL_PACKAGES (Google Play); roma ytm's against Subsonic. release.sh runs it on both release APKs, the Linux desktop job on both release distributions. One library string is allowed: the web-address pattern's list of top-level domains, which holds `.youtube`.
- Checked 2026-11-01 on the desktop (agent device access was off, so not on the emulator): the Subsonic app against a test oasis (server form at first start, sync of 4,709 songs, Home's mixes, Search "yoru ni" finding 夜に駆ける, playback, romanized lyrics, Comments and Share disabled, Settings with LRCLIB alone); roma signed out (no source question, YouTube Music search, playback, lyrics, comments, its YouTube settings).

## Server romanization (2026-11-01)

The OpenSubsonic extension `romanization` v1, agreed with the user on 2026-11-01; its canonical text is in oasis's docs. oasis implements the server side. The client decides only by `getOpenSubsonicExtensions` listing `romanization`, like any other extension; a server without it gets today's behaviour, with the dictionary.

- Detection: connecting (`ping`, then `getOpenSubsonicExtensions`) and every sync set `subsonic.romanization` (`SubsonicSource.romanizes`, `Source.romanizes`). A pref, not the cached index, so the first start's scripts question and the dictionary's offer know it before the library is read. When a sync finds it changed, lyrics and the queue start over.
- Names: with the extension every call carries `romanization=both` (`SubsonicClient.romanization`, set by `sync`). The parsed songs, albums, artists and playlists keep the `…Latin` fields (`SubSong.titleLatin` and so on), filled with the original where the server sent none. `Romanizer.text`'s `served` takes that form as it is, after the user's corrections; nothing is romanized on the device and a name without a `…Latin` field shows as written. `Text.served` keeps it, so `Romanizer.again` (a correction or setting change) doesn't fall back to the device. Playlist owners are user names and stay as written.
- Lyrics: `getLyricsBySongId` with `enhanced=true` and `kinds` from the lyrics display: `main` for the original only, `pronunciation` for the romanization only, both for both (`SubsonicSource.lyricsKinds`). The pronunciation entry gives each main line its `Line.latin`, paired by start time when both are synced, else by position when the counts match; with `cueLine` on both, each timed word gets the romaji timed from its start to the next word's (`Line.wordLatin`). Pronunciation alone is the lyrics, its cues the words. A response without pronunciation (a song already in Latin script, a server with nothing for it) falls back to the main lyrics romanized as before; asked for pronunciation only, the song is asked again for `main`. A server that ignores `kinds` sends every layer; main and pronunciation are used, translations left out.
- The lyrics are kept per display (`Source.lyricsVariant` in the file names), and switching Show original / Show romanization loads the current song's again. `romanizeLyrics` keeps a line's server Latin form, with its timed words (or the whole line) as the ruby layout's pieces; the user's word readings replace the pieces they name. When a third party's lyrics win the rules over the server's, the server's Latin lines go to their lines with the same text.
- The Japanese dictionary: with the extension it is never downloaded or offered (`Dictionary.ensure` and `install`), and the first start and Settings don't mention it. The schemes still apply to lyrics from elsewhere (LRCLIB), which Settings says. A dictionary already on disk from an earlier server stays and is used for those.
- The display options are the same with and without the extension: lists and the player (Placement, Custom: Latin only, original only, both, ruby) and lyrics (Show original, Show romanization).
- Checked 2026-11-01 again as caravan (release desktop build, Xvfb) against an isolated oasis with `romanization` and a generated Japanese, Korean and Chinese library with .lrc lyrics: names and lyrics come romanized from the server, the Japanese dictionary is neither offered nor downloaded, and the Last.fm switch starts off for a user whose `getUser` says `scrobblingEnabled` and on for one whose doesn't. The same server behind a proxy that hides the extension: the first start offers the dictionary, and names and lyrics are romanized on the device.

## Known limits

- Desktop: the shared code runs, but the desktop player decodes only Opus in WebM and AAC in MP4. MP3, FLAC and Ogg files from a server need decoders there (or a server that transcodes); Android plays them through MediaCodec.
- No ReplayGain yet: Subsonic songs play at their own level.
- Names get machine romanization only, unless the server romanizes them (Server romanization): a plain server has no official Latin names, so 米津玄師 reads "Yonetsu Genshi" (the dictionary's reading) where YouTube gives "Kenshi Yonezu". A correction for that artist fixes it.
- oasis 5194342 (2026-10-30) added mixes as playlists, similar songs, `getLyricsBySongId` and transcoding. Checked against it from the JVM: search with YouTube results, a YouTube album and artist on demand, radio, `.lrc` lyrics. The mixes need a signed-in YouTube Music account on the server and are untested live. The desktop could ask for `stream?format=opus` to play MP3 and FLAC; not done (transcoded streams have no Range, which the desktop's audio cache uses).
