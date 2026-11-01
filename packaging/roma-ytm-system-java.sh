#!/bin/sh
# Starts roma ytm from @APPDIR@ (/opt/roma-ytm in the deb and rpm) on the distribution's Java: the newest installed Java 21
# or later with the desktop (X11) part, whatever its folder is called, or whichever `java` the PATH has. As finimal's.
# The app's classes go into a class-data archive at the first start, kept with the app's cache: later starts take
# about a third less time. The JVM makes a new one after an update.
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
# KDE (on Wayland, with X11 apps scaling themselves) and other desktops give the scale only as Xft.dpi, which Java never
# reads (it follows GDK_SCALE, which KDE sets to 1), so roma came up at 1x on a 2x screen. Java's scale from Xft.dpi,
# then, unless J2D_UISCALE sets one; a whole number, as Java's X11 windows need (1.5 rounds to 2). As finimal's.
if [ -z "${J2D_UISCALE:-}" ] && dpi=$(xrdb -query 2>/dev/null | sed -n 's/^Xft\.dpi:[[:space:]]*//p') && [ -n "$dpi" ]; then
  export J2D_UISCALE=$(awk -v d="$dpi" 'BEGIN { s = int(d / 96 + 0.5); print (s < 1 ? 1 : s) }')
fi
cache="${XDG_CACHE_HOME:-$HOME/.cache}/roma-ytm"
mkdir -p "$cache"
exec "$java" --enable-native-access=ALL-UNNAMED -XX:SharedArchiveFile="$cache/roma-ytm.jsa" -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off \
  -Dcompose.application.resources.dir="$app/resources" -Dcompose.application.configure.swing.globals=true \
  -Dskiko.library.path="$app" \
  -cp "$app/*" com.zakius.roma.MainKt "$@"
