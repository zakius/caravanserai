#!/bin/bash
# Builds dragoman's Windows packages from the release app image (createReleaseDistributable, whose Skia ICU data is
# trimmed):
# - dist/dragoman-<version>-x64-full.msi, with its own Java;
# - dist/dragoman-<version>-x64.msi and dist/dragoman-<version>-windows-x64.zip, the small builds: the app without its
#   Java runtime, started by packaging/windows/Launcher.cs, which finds Java 21 or later on the machine or downloads
#   Temurin's JRE once (about 49 MB). This follows finimal's small build, minus libmpv. The small app image stays in
#   desktop/build/windows-small for CI.
# The .msi from packageReleaseMsi isn't used, because that task unpacks Skia's files again, untrimmed. It still runs
# first, to fetch the WiX toolset that jpackage needs.
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
  -out:"$(cygpath -w "$tmp/dragoman.exe")" -win32icon:"$(cygpath -w packaging/dragoman.ico)" \
  -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll -r:System.Windows.Forms.dll -r:System.Drawing.dll \
  "$(cygpath -w packaging/windows/Launcher.cs)" "$(cygpath -w "$tmp/Pins.cs")"

small=desktop/build/windows-small/dragoman
rm -rf "$small"
mkdir -p "$(dirname "$small")" dist
cp -r desktop/build/compose/binaries/main-release/app/dragoman "$small"
rm -rf "$small/runtime"
rm -f "$small/dragoman.exe" # jpackage's launcher, read-only
cp "$tmp/dragoman.exe" "$small/dragoman.exe"
(cd "$(dirname "$small")" && 7z a -tzip -mx=9 "$OLDPWD/dist/dragoman-$version-windows-x64.zip" dragoman > /dev/null)

# Both installers share one upgrade code, so either build replaces the other.
candle=$(find desktop/build build ~/AppData/Local -name candle.exe 2> /dev/null | head -n 1)
[ -n "$candle" ] || { echo "No WiX toolset (candle.exe) found" >&2; exit 1; }
# Builds an .msi from an app image. Arguments: app image, output file.
msi() {
  rm -rf "$tmp/msi"
  PATH="$(dirname "$candle"):$PATH" "$JAVA_HOME/bin/jpackage" --type msi --app-image "$(cygpath -w "$1")" \
    --name dragoman --app-version "$version" --vendor zakius --description "YouTube Music, every title and lyric romanized" \
    --license-file LICENSE --win-per-user-install --win-menu --win-menu-group "dragoman" --win-shortcut \
    --win-upgrade-uuid 664c4038-0414-4573-a00c-30cd42d48574 --dest "$(cygpath -w "$tmp/msi")"
  cp "$tmp"/msi/*.msi "$2"
}
msi "$small" "dist/dragoman-$version-x64.msi"
msi desktop/build/compose/binaries/main-release/app/dragoman "dist/dragoman-$version-x64-full.msi"
ls -l dist/dragoman-"$version"-*
