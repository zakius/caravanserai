# roma: Subsonic backend (plan, 2026-10-30)

roma gets a second backend next to YouTube Music: any Subsonic or OpenSubsonic server, and first of all oasis (~/dev/oasis), the user's server that merges the local library with YouTube Music and later other services. Romanization and the fixed controls stay the point of the app; they work on any track's metadata. Decided with the user on 2026-10-30.

The music stack:

- **oasis**: a Python OpenSubsonic server. Clients never see where a track comes from. Every response carries `"type": "oasis"` in `subsonic-response`.
- **mirage**: a fooyin fork for the desktop (another thread).
- **roma**: this app, with either backend.

## Decisions

- One codebase. YouTube Music and Subsonic are two implementations of one small interface (`Source`) under the screens. No screen names a backend; a screen asks the source for what it shows, and the source says what it can't do (a dimmed control, a hidden hint). Separate builds, or a combined mode with both at once, can come later and must stay cheap: every id says which source it belongs to (Ids below), so a queue, a ban or a scrobble already finds its source per song.
- ~~On first launch roma asks which backend to use.~~ Since 2026-11-01 each backend is its own app (Two apps below); the build picks the source.
- Library feel on Subsonic: the whole library index (artists, albums, songs, cover ids) is synced into a local store, so browsing and search are instant and work offline.
- Each play is reported exactly once, by the side that knows the real source (Scrobbling below).

## The source interface

`Source` (shared/src/main/…/Source.kt). Each app has one, `backend`, defined by its flavor (Two apps below).

| What | YouTube Music (`YouTubeMusic`, the code that is there now, moved) | Subsonic (`SubsonicSource`) |
|---|---|---|
| Home | `YouTube.home` pages; the personal mixes row; Liked music | Shelves from `getAlbumList2` (recently added, recently played, most played, random), one page. Pinned tiles: the server's mixes (playlists owned by "YouTube Music", which oasis publishes from YouTube Music's personal mixes), then Starred and Shuffle all. |
| Search | `YouTube.search` with filters, spelling correction, suggestions; "Seen before" from the local index | The synced index first, at once, romanized forms included (`matchDistance`); then the server's own `search3` results not already listed (on oasis, YouTube Music's). Same filters; Playlists from the playlist list. No suggestions, no correction line, no "Seen before" section. |
| Library tabs | Playlists, Albums, Artists from the account | Playlists (`getPlaylists`), Albums and Artists from the index. |
| Album, artist, playlist pages | `YouTube.album/artist/playlist` | Album and artist from the index (instant, offline), else `getAlbum`/`getArtist` (oasis: a YouTube Music album seen in search or radio, which it keeps out of the library lists); playlist from `getPlaylist`. An artist page lists the artist's albums and plays all their songs. |
| Stream | `Streams.audio` (VISIONOS, then InnerTubeX), loudness | `stream?id=…`, no transcoding asked; no loudness (ReplayGain later). |
| Radio and queue | YouTube's autoplay (`next`), related artists for the leash | `getSimilarSongs` on the seed song, each next page on the last song of the one before (oasis answers with YouTube Music's radio); when the server has none, random songs from the index. The same filters and leash (`filterRadio`). |
| Mixes, Liked music | Personal mixes, `LM` | Starred songs (`getStarred2`) for the Liked tile; Shuffle all plays the index at random. |
| Like | `likeVideo`, `likeStatus` | `star`/`unstar`; the status from the song's `starred`, kept from the sync and from `getSong`. |
| Lyrics: the reference provider | `YouTubeLyrics` (ANDROID_MUSIC browse) | OpenSubsonic `getLyricsBySongId`, only when the server lists the `songLyrics` extension. The third-party providers run as before. |
| Comments, Share | YouTube comments; a YouTube link | None: both buttons dimmed, in place. |
| Scrobbling | Last.fm from roma | Subsonic `scrobble`; Last.fm from roma only where the rule below says so. |
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
- Prefs (user storage): `subsonic.url`, `subsonic.user`, `subsonic.password` (token auth needs the password itself; it never leaves the device except as `md5(password + salt)`), `subsonic.lastfm`. The detected server type and extensions go to the cache with the index.
- Another server or user starts Home, Search, Library and the open pages over (`Store.dataChanged`).

## Index sync

- Core class `SubsonicIndex` (roma.core, unit-tested): artists, albums and songs with their original fields (id, title, artist, artist id, album, album id, track, disc, year, duration, cover id, starred, created).
- Sync: `search3` with an empty query (`query=""`, which oasis, Navidrome and others read as "everything"), paged with `songCount=500&songOffset=…`, `albumCount`/`albumOffset` and `artistCount`/`artistOffset` the same way, until every list comes back short. One full pass; Subsonic has no change feed. Albums and artists not seen in the songs (an empty album) are kept as the server lists them.
- When: at start, after the first frame, if the last sync is older than 6 hours; on "Sync now"; after the server changes. A sync runs in the background and the old index serves until the new one is complete; a failed sync keeps the old one and says so in Settings.
- Store: cache/subsonic/index.json (it can be fetched again, so cache, per design.md's Storage rule). Then every name is romanized once in the background, as roma `Song` and `Item` values (`Romanizer.song` for Subsonic songs), and kept in cache/subsonic/romanized.json stamped with the romanization version and app version, like the Names cache. A romanization change romanizes the index again in the background; until then the old forms show.
- Search, Library, album and artist pages read the romanized index in memory. Starred songs from the sync go into the liked set.

## Scrobbling

Each play is reported exactly once, by the side that knows the real source (`Source.lastFm`, `scrobblesToLastFm` in :subsonic).

- YouTube Music songs: roma scrobbles to Last.fm itself, as now.
- Subsonic songs on oasis (`type == "oasis"` in any response): only Subsonic `scrobble`. `submission=false` when the song starts (now playing), `submission=true` once `shouldScrobble` holds. oasis passes the play on to Last.fm, ListenBrainz and YouTube Music history. roma's own Last.fm scrobbling is off for these songs, whatever the setting.
- Subsonic songs on other servers: always `scrobble` as above. roma's Last.fm scrobbling as well only with the per-server switch "Also scrobble to Last.fm from roma", off by default, because Navidrome and others forward to Last.fm themselves.
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
| roma (com.zakius.roma) | Subsonic/OpenSubsonic only | Google Play | `:app:assembleSubsonicRelease`, `:desktop-subsonic` |

Each app has its colour, on one icon design (the play triangle with a bar beneath it, packaging/*.svg): roma Subsonic's orange (#F28C28), roma ytm YouTube Music's red, a shade off YouTube's own (#E62117), so its icon isn't YouTube's logo. The colour is also the accent where the system gives none (`Source.brandColor`; Android 12+ follows the wallpaper).

Names, ids and slugs: gradle.properties (decided 2026-11-01: roma, the name the app had, goes to the Play app; nothing had been distributed, so the old releases were deleted and nobody's install moved).

Google Play review can find innertube calls, client spoofing or BotGuard in an APK even when no screen uses them, so the Subsonic app's YouTube code is compiled out, not hidden.

- Modules: `:core` (romanization, lyrics rules and LRCLIB, radio filters, scrobbling, roma's models) knows no service. `:youtube` has innertube, InnerTubeX, the stream lookup, comments, the personal mixes and the other lyrics providers. `:subsonic` has the client and index.
- Screens: shared/src/main holds every screen and the `Source` interface, which also brings what only one source shows: its settings page, Home's tiles, its first-start step, the comments view, picture sizing. shared/src/youtube and shared/src/subsonic each define `backend` and their source's own screens.
- Android: product flavors `youtube` (id com.zakius.roma, as before: existing installs upgrade with their data) and `subsonic` (its own id, name, icon, Last.fm link scheme; no self-update). Desktop: `:desktop` and `:desktop-subsonic`, one build script (desktop/build.gradle.kts), their own data folders and packages. The BotGuard WebView, the sign-in, Chromium and CEF are roma's alone.
- Both apps play through ExoPlayer, each with only its source's containers (the flavor's `containers()`: WebM and MP4 for roma ytm; MP3, FLAC, Ogg, WAV, M4A and ADTS AAC for the Subsonic app) and a one-track selector (`FirstAudioTrack`) instead of DefaultTrackSelector. Sizes after release.sh's repack: roma (Subsonic) 1.47 MB, roma ytm 1.69 MB (1.74 before). Android's MediaPlayer under the desktop's queue player made the Subsonic APK 1.19 MB, but went back out (2026-11-01): it had no gapless change, no seek past the downloaded part, no partial download and no ranged cache.
- The Japanese dictionary in the Subsonic app is downloaded as data, as the desktop does it (shared/src/portable/…/Dictionary.kt): the dictionary app's gzipped APK is streamed and only its system_core.dic kept, in filesDir/dict (left out of device transfers), memory-mapped from there. Google Play forbids installing apps (REQUEST_INSTALL_PACKAGES) and running downloaded code, not downloading data. The fallback, should a review object to the .apk.gz name or the download itself, is an install-time asset pack in the Play bundle: assets only, no library, 31 MB on every install.
- Left out of the Subsonic app: roma's self-update, the dictionary app's install, the YouTube Music link to oasis, YouTube share links, YouTube lyrics, NetEase, KuGou and LyricsPlus. Its lyrics: the server's `getLyricsBySongId` as the reference (oasis gathers every provider's), LRCLIB beside it. The server's mixes keep the server's order (roma ordered them by their YouTube playlist ids).
- `tools/residue_check.py` fails the build when one app carries the other's code: every file of the Subsonic APK and desktop distribution (jars entry by entry, UTF-8 and UTF-16) against innertube, metrolist, BotGuard, YouTube, googlevideo and VISIONOS; and PackageInstaller or REQUEST_INSTALL_PACKAGES (Google Play); roma's against Subsonic. release.sh runs it on both release APKs, the Linux desktop job on both release distributions. One library string is allowed: the web-address pattern's list of top-level domains, which holds `.youtube`.
- Checked 2026-11-01 on the desktop (agent device access was off, so not on the emulator): the Subsonic app against a test oasis (server form at first start, sync of 4,709 songs, Home's mixes, Search "yoru ni" finding 夜に駆ける, playback, romanized lyrics, Comments and Share disabled, Settings with LRCLIB alone); roma signed out (no source question, YouTube Music search, playback, lyrics, comments, its YouTube settings).

## Known limits

- Desktop: the shared code runs, but the desktop player decodes only Opus in WebM and AAC in MP4. MP3, FLAC and Ogg files from a server need decoders there (or a server that transcodes); Android plays them through MediaCodec.
- No ReplayGain yet: Subsonic songs play at their own level.
- Names get machine romanization only: a server has no official Latin names, so 米津玄師 reads "Yonetsu Genshi" (the dictionary's reading) where YouTube gives "Kenshi Yonezu". A correction for that artist fixes it.
- oasis 5194342 (2026-10-30) added mixes as playlists, similar songs, `getLyricsBySongId` and transcoding. Checked against it from the JVM: search with YouTube results, a YouTube album and artist on demand, radio, `.lrc` lyrics. The mixes need a signed-in YouTube Music account on the server and are untested live. The desktop could ask for `stream?format=opus` to play MP3 and FLAC; not done (transcoded streams have no Range, which the desktop's audio cache uses).
