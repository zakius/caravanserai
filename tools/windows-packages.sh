#!/bin/bash
# The Windows packages, from the release app image (createReleaseDistributable, whose Skia ICU data is trimmed):
# roma ytm's:
# - dist/roma-ytm-<version>-x64-full.msi, with its own Java;
# - dist/roma-ytm-<version>-x64.msi and dist/roma-ytm-<version>-windows-x64.zip, small: the app without its Java runtime,
#   started by packaging/windows/Launcher.cs, which finds Java 21+ on the machine or downloads Temurin's JRE once
#   (about 49 MB). As finimal's small build, without libmpv. Its image stays in desktop/build/windows-small for CI.
# Not packageReleaseMsi's .msi: that task unpacks Skia's files again, untrimmed. It runs first for the WiX toolset
# jpackage needs.
# Runs on Windows (Git Bash) after :desktop:packageReleaseMsi :desktop:createReleaseDistributable.
# Usage: tools/windows-packages.sh
set -euo pipefail
cd "$(dirname "$0")/.."
jre_url=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip
jre_sha256=2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628
jre_dir=jdk-21.0.13+9-jre
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/Pins.cs" <<PINS
static class Pins
{
    public const string JreUrl = "$jre_url";
    public const string JreSha256 = "$jre_sha256";
    public const string JreDir = "$jre_dir";
}
PINS
/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe -nologo -optimize -target:winexe \
  -out:"$(cygpath -w "$tmp/roma-ytm.exe")" -win32icon:"$(cygpath -w packaging/roma.ico)" \
  -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll -r:System.Windows.Forms.dll -r:System.Drawing.dll \
  "$(cygpath -w packaging/windows/Launcher.cs)" "$(cygpath -w "$tmp/Pins.cs")"

small=desktop/build/windows-small/roma-ytm
rm -rf "$small"
mkdir -p "$(dirname "$small")" dist
cp -r desktop/build/compose/binaries/main-release/app/roma-ytm "$small"
rm -rf "$small/runtime"
rm -f "$small/roma-ytm.exe" # jpackage's launcher, read-only
cp "$tmp/roma-ytm.exe" "$small/roma-ytm.exe"
(cd "$(dirname "$small")" && 7z a -tzip -mx=9 "$OLDPWD/dist/roma-ytm-$version-windows-x64.zip" roma-ytm > /dev/null)

# The installers: the same upgrade code, so either build replaces the other.
candle=$(find desktop/build build ~/AppData/Local -name candle.exe 2> /dev/null | head -n 1)
[ -n "$candle" ] || { echo "No WiX toolset (candle.exe) found" >&2; exit 1; }
msi() { # app image, output
  rm -rf "$tmp/msi"
  PATH="$(dirname "$candle"):$PATH" "$JAVA_HOME/bin/jpackage" --type msi --app-image "$(cygpath -w "$1")" \
    --name roma-ytm --app-version "$version" --vendor zakius --description "YouTube Music, every title and lyric romanized" \
    --license-file LICENSE --win-per-user-install --win-menu --win-menu-group "roma ytm" --win-shortcut \
    --win-upgrade-uuid 6d0b7c3e-1f5a-4d7e-9a43-6f8f0b2c9e41 --dest "$(cygpath -w "$tmp/msi")"
  cp "$tmp"/msi/*.msi "$2"
}
msi "$small" "dist/roma-ytm-$version-x64.msi"
msi desktop/build/compose/binaries/main-release/app/roma-ytm "dist/roma-ytm-$version-x64-full.msi"
ls -l dist/roma-ytm-"$version"-*
