#!/bin/sh
# Starts roma ytm from @APPDIR@ (/opt/roma-ytm in the deb and rpm) on the distribution's Java. It picks the newest
# installed Java 21 or later that has the desktop (X11) part, whatever its folder is called, including whichever `java`
# is on the PATH. This is the same launcher as finimal's.
# At the first start the JVM writes the app's classes into a class-data archive in the app's cache, which makes later
# starts about a third faster. The JVM makes a new archive after an update.
app=@APPDIR@
java=
best=0
path_java=$(command -v java 2>/dev/null) && path_home=$(dirname "$(dirname "$(readlink -f "$path_java")")")
for home in /usr/lib/jvm/* ${path_home:+"$path_home"}; do
  [ -x "$home/bin/java" ] && [ -e "$home/lib/libawt_xawt.so" ] || continue
  version=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$home/release" 2>/dev/null)
  if [ -n "$version" ] && [ "$version" -ge 21 ] && [ "$version" -gt "$best" ]; then java="$home/bin/java"; best=$version; fi
done
if [ -z "$java" ]; then
  echo "roma ytm needs Java 21 or newer, with the desktop part (not a headless runtime)." >&2
  exit 1
fi
# KDE (on Wayland, with X11 apps scaling themselves) and some other desktops give the scale only as Xft.dpi. Java never
# reads that; it follows GDK_SCALE, which KDE sets to 1, so the app would come up at 1x on a 2x screen. So unless
# J2D_UISCALE is already set, this derives Java's scale from Xft.dpi, rounded to a whole number because Java's X11
# windows need one (1.5 rounds to 2).
if [ -z "${J2D_UISCALE:-}" ] && dpi=$(xrdb -query 2>/dev/null | sed -n 's/^Xft\.dpi:[[:space:]]*//p') && [ -n "$dpi" ]; then
  export J2D_UISCALE=$(awk -v d="$dpi" 'BEGIN { s = int(d / 96 + 0.5); print (s < 1 ? 1 : s) }')
fi
cache="${XDG_CACHE_HOME:-$HOME/.cache}/roma-ytm"
mkdir -p "$cache"
exec "$java" --enable-native-access=ALL-UNNAMED -XX:SharedArchiveFile="$cache/roma-ytm.jsa" -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off \
  -Dcompose.application.resources.dir="$app/resources" -Dcompose.application.configure.swing.globals=true \
  -Dskiko.library.path="$app" \
  -cp "$app/*" com.zakius.roma.MainKt "$@"
