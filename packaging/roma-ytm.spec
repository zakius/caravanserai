# The Fedora package around the tree that tools/linux-packages.sh stages. Java comes from Fedora, so its fixes arrive
# with system updates. This follows finimal's package.
# Compressed with xz like the .deb, because Ubuntu's rpmbuild (used in CI) defaults to gzip, which leaves the package
# much bigger.
%global _binary_payload w9.xzdio

Name:           roma-ytm
Version:        %{version}
Release:        1
Summary:        YouTube Music, every title and lyric romanized
License:        GPL-3.0-only
URL:            https://github.com/zakius/roma
ExclusiveArch:  x86_64
# Otherwise every library Skia links would become a requirement. Java's full JRE already brings X11, fonts and sound.
AutoReqProv:    no
Requires:       (java-25-openjdk or java-21-openjdk or java-latest-openjdk)
Requires:       libglvnd-glx

%description
A YouTube Music client that romanizes every non-Latin title, artist and lyric line, with karaoke lyrics, and
controls that never move.

%install
cp -a %{stage}/. %{buildroot}/

%files
/opt/roma-ytm
/usr/bin/roma-ytm
/usr/share/applications/io.github.zakius.roma.ytm.desktop
/usr/share/icons/hicolor/scalable/apps/io.github.zakius.roma.ytm.svg
/usr/share/metainfo/io.github.zakius.roma.ytm.metainfo.xml
