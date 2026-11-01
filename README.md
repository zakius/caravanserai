# roma

roma romanizes every non-Latin title, artist and lyric line. This repository holds its romanization engine and two music players for Android, Linux and Windows built on it, whose controls stay in the same place:

- **caravan**, for your own Subsonic or OpenSubsonic server (oasis, Navidrome, …), romanized by roma, headed for Google Play.
- **roma ytm**, for YouTube Music, from [Releases](https://github.com/zakius/roma/releases). The rest of this page is about it.

https://github.com/user-attachments/assets/a9b0ed15-224c-46ec-89f7-82014df466c7

Tiny and fast: roma ytm is a 1.7 MB download (caravan 1.5 MB); romanizing Japanese needs an optional dictionary, downloaded separately. Install it and tap around; that shows the speed better than a benchmark.

- **Romanization everywhere:** lists, player, queue, search, lyrics, notification.
  - Japanese: [Sudachi](https://github.com/WorksApplications/Sudachi) with SudachiDict core, Hepburn.
  - Korean: Revised Romanization.
  - Chinese: pinyin.
  - Cyrillic: per-language national standards (Russian, Ukrainian, Belarusian, Kazakh, Kyrgyz, Serbian, Macedonian, Bulgarian, Mongolian).
  - Greek and others through ICU.
  - Official Latin names win; user corrections can be scoped to a string, a song or an artist.
- **Fixed controls:** a control never moves or disappears. If it isn't available, it's dimmed in place.
- **Lyrics:** all providers are queried in parallel and one result is chosen deterministically against YouTube Music's own text. Word-level karaoke on the original and the romanization, plus a translation layer.
- **Playback:** the current song keeps playing until the next one can start. Optional radio drift limits, duplicate and ban filters.
- **Last.fm scrobbling** with the original metadata, using your own API key.
- **No Google Play Services needed.**

The design and its rules are in [docs/design.md](docs/design.md).

## Build

Requirements: JDK 21 and the Android SDK (compileSdk 37).

```sh
./gradlew :core:test :youtube:test :subsonic:test :app:assembleYoutubeDebug
```

The same code builds caravan, for Subsonic servers and Google Play, without any YouTube code: `:app:assembleSubsonicDebug` and `:desktop-subsonic:run` ([docs/subsonic.md](docs/subsonic.md#two-apps-2026-11-01)).

The tests and the dictionary's download (`:core:dictZip`) need SudachiDict: their first build downloads it (about 77 MB, checked against a pinned sha256) into the Gradle cache. Building the apps doesn't.

Release builds are made with `tools/release.sh`: R8 shrinking, Zopfli-recompressed, signed with a key that you supply in `keystore.properties` (not in the repository). A merge to master that sets a new `versionName` builds and publishes it as a GitHub release (`.github/workflows/release.yml`); there's no tag to push.

The desktop app shares the Android app's screens and logic (`shared/`, see [docs/design.md](docs/design.md#desktop-2026-10-29)). Its packages need a JDK with `jmods` (Temurin 21 has them, Temurin 24 and later don't):

```sh
./gradlew :desktop:run                      # run it
tools/linux-packages.sh                     # dist/roma-ytm_<version>_amd64.deb and dist/roma-ytm-<version>-1.x86_64.rpm
```

Its first build also builds roma-aac (`tools/build-ffmpeg.sh`, needs musl-gcc: Debian's and Ubuntu's `musl-tools`), the small ffmpeg that decodes the few songs YouTube has only in AAC.

## Install

### Android

Get `roma-ytm-<version>.apk` from [Releases](https://github.com/zakius/roma/releases). If you want Japanese romanized, roma ytm offers to download its Japanese dictionary on the first start (32 MB, 62 MB on the phone), kept apart so its own updates stay small. The "roma Japanese dictionary" app that roma ytm 0.1.57 and earlier installed is no longer used: you can uninstall it.

### Linux

[Releases](https://github.com/zakius/roma/releases) has two packages for x86_64, which run on your distribution's Java (21 or newer):

| File | For |
|------|-----|
| `roma-ytm_*_amd64.deb` | Debian 13, Ubuntu 24.04 and newer: `sudo apt install ./roma-ytm_*_amd64.deb` |
| `roma-ytm-*.x86_64.rpm` | Fedora: `sudo dnf install ./roma-ytm-*.x86_64.rpm` |

### Windows

`roma-ytm-*-x64.msi` installs for your user without administrator rights; `roma-ytm-*-windows-x64.zip` is the same app to unpack and run (`roma-ytm.exe`). Both use your Java 21 or newer, or download [Eclipse Temurin](https://adoptium.net)'s on the first start (about 49 MB). `roma-ytm-*-x64-full.msi` includes Java.

### On the desktop

The Japanese dictionary downloads on the first start if you want Japanese romanized (32 MB). Signing in and some songs need a Chromium-family browser (Chrome, Edge, Chromium, Brave, Vivaldi, Opera, oganesson…); roma ytm starts it with its own profile, never yours. Without one, roma ytm downloads Chromium (CEF, about 150 MB) when first needed. Esc and the mouse's back button go back.

## Credits

- YouTube Music API: the `innertube` module from [Metrolist](https://github.com/MetrolistGroup/Metrolist).
- Playback: [Media3](https://developer.android.com/media/media3); on the desktop [Concentus](https://github.com/lostromb/concentus) for Opus and [FFmpeg](https://ffmpeg.org)'s AAC decoder (LGPL; its source is attached to each release).
- Desktop: [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), [ICU4J](https://icu.unicode.org), [jcefmaven](https://github.com/jcefmaven/jcefmaven) (CEF).
- Skipping ASUS's font rebuild at start: [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).
- Japanese: [Sudachi](https://github.com/WorksApplications/Sudachi) and [SudachiDict](https://github.com/WorksApplications/SudachiDict).
- Lyrics: LRCLIB, KuGou, NetEase, LyricsPlus and YouTube Music; translations into your language from Google Translate, on a tap.

Not affiliated with YouTube or Google.

## Licence

GPL-3.0; see [LICENSE](LICENSE).
