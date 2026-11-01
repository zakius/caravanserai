#!/bin/bash
# The desktop app for Debian/Ubuntu and Fedora: dist/roma-ytm_<version>_amd64.deb and dist/roma-ytm-<version>-1.x86_64.rpm,
# from the release app image's app files (ProGuard-shrunk). Both install roma ytm to /opt/roma-ytm with /usr/bin/roma-ytm and run on
# the distribution's Java 21+, so its fixes come with system updates; as finimal's packages.
# The jars go in stored, not deflated: the package's xz then compresses across all classes.
# rpmbuild comes from the host (Ubuntu: the rpm package) or, without it, a Fedora container.
# Usage: tools/linux-packages.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createReleaseDistributable
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root=$stage/root
mkdir -p "$root/opt/roma-ytm" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/scalable/apps" "$root/usr/share/metainfo" dist
app=$PWD/desktop/build/compose/binaries/main-release/app/roma-ytm/lib/app
cp -a "$app"/libskiko-*.so "$root/opt/roma-ytm/"
cp -a "$app/resources" "$root/opt/roma-ytm/"
rm -rf "$root/opt/roma-ytm/resources/windows"
chmod 755 "$root/opt/roma-ytm/resources/roma-aac" # the app image loses its executable bit
for jar in "$app"/*.jar; do
  rm -rf "$stage/x"; mkdir "$stage/x"; (cd "$stage/x" && unzip -q "$jar" && zip -q -0 -r -X "$root/opt/roma-ytm/$(basename "$jar")" .)
done
rm -rf "$stage/x"
sed -e 's|@APPDIR@|/opt/roma-ytm|' packaging/roma-ytm-system-java.sh > "$root/usr/bin/roma-ytm"
chmod 755 "$root/usr/bin/roma-ytm"
install -m644 packaging/io.github.zakius.roma.ytm.desktop "$root/usr/share/applications/"
install -m644 packaging/roma-ytm.svg "$root/usr/share/icons/hicolor/scalable/apps/io.github.zakius.roma.ytm.svg"
install -m644 packaging/io.github.zakius.roma.ytm.metainfo.xml "$root/usr/share/metainfo/"

mkdir "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CONTROL
Package: roma-ytm
Version: $version
Architecture: amd64
Maintainer: zakius <zakius@users.noreply.github.com>
Depends: java21-runtime, libgl1
Section: sound
Priority: optional
Installed-Size: $(du -sk "$root" | cut -f1)
Homepage: https://github.com/zakius/roma
Description: YouTube Music, every title and lyric romanized
 A YouTube Music client that romanizes every non-Latin title, artist and lyric line, with karaoke lyrics, and
 controls that never move.
CONTROL
dpkg-deb --root-owner-group -Zxz -b "$root" "dist/roma-ytm_${version}_amd64.deb" > /dev/null
rm -r "$root/DEBIAN"

cp packaging/roma-ytm.spec "$stage/"
build="rpmbuild -bb --define '_topdir /tmp/rpmbuild' --define 'stage $root' --define 'version $version' $stage/roma-ytm.spec && cp /tmp/rpmbuild/RPMS/x86_64/*.rpm $PWD/dist/"
if command -v rpmbuild > /dev/null; then bash -c "$build"
else
  docker run --rm -v "$stage:$stage" -v "$PWD/dist:$PWD/dist" fedora:latest bash -c \
    "dnf -q -y install rpm-build > /dev/null && $build && chown $(id -u):$(id -g) $PWD/dist/*.rpm"
fi
ls -l dist/roma-ytm_"$version"_amd64.deb dist/roma-ytm-"$version"-1.x86_64.rpm
