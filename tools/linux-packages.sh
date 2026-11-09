#!/bin/bash
# Builds the desktop app for Debian/Ubuntu and Fedora: dist/dragoman_<version>_amd64.deb and
# dist/dragoman-<version>-1.x86_64.rpm, from the app files of the ProGuard-shrunk release app image. Both install dragoman
# to /opt/dragoman with /usr/bin/dragoman and run on the distribution's Java 21 or later, so Java fixes arrive with
# system updates. This follows finimal's packages.
# rpmbuild comes from the host (on Ubuntu, the rpm package) or, if the host has none, from a Fedora container.
# Usage: tools/linux-packages.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createReleaseDistributable
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root=$stage/root
mkdir -p "$root/opt/dragoman" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/scalable/apps" "$root/usr/share/metainfo" dist
app=$PWD/desktop/build/compose/binaries/main-release/app/dragoman/lib/app
cp -a "$app"/libskiko-*.so "$app"/*.jar "$root/opt/dragoman/"
cp -a "$app/resources" "$root/opt/dragoman/"
rm -rf "$root/opt/dragoman/resources/windows"
chmod 755 "$root/opt/dragoman/resources/roma-aac" # the app image loses its executable bit
sed -e 's|@APPDIR@|/opt/dragoman|' packaging/dragoman-system-java.sh > "$root/usr/bin/dragoman"
chmod 755 "$root/usr/bin/dragoman"
install -m644 packaging/io.github.zakius.dragoman.desktop "$root/usr/share/applications/"
install -m644 packaging/dragoman.svg "$root/usr/share/icons/hicolor/scalable/apps/io.github.zakius.dragoman.svg"
install -m644 packaging/io.github.zakius.dragoman.metainfo.xml "$root/usr/share/metainfo/"

mkdir "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CONTROL
Package: dragoman
Version: $version
Architecture: amd64
Maintainer: zakius <zakius@users.noreply.github.com>
Depends: java21-runtime, libgl1
Section: sound
Priority: optional
Installed-Size: $(du -sk "$root" | cut -f1)
Homepage: https://github.com/zakius/caravanserai
Description: YouTube Music, every title and lyric romanized
 A YouTube Music client that romanizes every non-Latin title, artist and lyric line, with karaoke lyrics, and
 controls that never move.
CONTROL
dpkg-deb --root-owner-group -Zxz -b "$root" "dist/dragoman_${version}_amd64.deb" > /dev/null
rm -r "$root/DEBIAN"

cp packaging/dragoman.spec "$stage/"
build="rpmbuild -bb --define '_topdir /tmp/rpmbuild' --define 'stage $root' --define 'version $version' $stage/dragoman.spec && cp /tmp/rpmbuild/RPMS/x86_64/*.rpm $PWD/dist/"
if command -v rpmbuild > /dev/null; then bash -c "$build"
else
  docker run --rm -v "$stage:$stage" -v "$PWD/dist:$PWD/dist" fedora:latest bash -c \
    "dnf -q -y install rpm-build > /dev/null && $build && chown $(id -u):$(id -g) $PWD/dist/*.rpm"
fi
ls -l dist/dragoman_"$version"_amd64.deb dist/dragoman-"$version"-1.x86_64.rpm
