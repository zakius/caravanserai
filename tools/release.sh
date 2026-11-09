#!/bin/bash
# Builds the release files. APKs are signed with the key named in keystore.properties, which is kept outside git.
#   dist/dragoman-<version>.apk: dragoman (the youtube flavor), shrunk by R8, recompressed with Zopfli
#     (tools/repack_apk.py) and zipaligned. One APK covers arm64 and x86_64.
#   dist/caravan-<version>.apk: caravan (the subsonic flavor), made the same way. tools/residue_check.py checks that
#     neither carries the other's code, and fails the run if one does.
#   dist/roma-dict-<dictionary version>.zip: the Japanese dictionary with its notices (:core:dictZip), built only when
#     that file is missing. It is data, not an app, so there is nothing to sign.
# roma fetches the dictionary from its GitHub release (dict-<version>), or from ROMA_DICT_BASE if set.
# Usage: release.sh [app] [dict] [test]. All three run by default. "test" runs the JVM modules' tests, which need the
# slim dictionary.
# The GitHub workflow (.github/workflows/release.yml) runs the parts in separate jobs for each push to master, and signs
# (with RELEASE set) when the version is new.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/air/jbr}
export PATH="$JAVA_HOME/bin:$PATH"
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
tools=$(ls -d "$sdk/build-tools/"* | sort -V | tail -n 1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
dictVersion=$(sed -n 's/^val dictRelease = "\(.*\)"/\1/p' core/build.gradle.kts)
dict="dist/roma-dict-$dictVersion.zip"
parts=" ${*:-app dict test} "
app=; buildDict=; test=
[[ $parts == *" app "* ]] && app=1
[[ $parts == *" dict "* && ! -f $dict ]] && buildDict=1
[[ $parts == *" test "* ]] && test=1
tasks=()
[ -n "$test" ] && tasks+=(:core:test :youtube:test :subsonic:test :desktop:test)
[ -n "$app" ] && tasks+=(:app:assembleYoutubeRelease :app:assembleSubsonicRelease)
[ -n "$buildDict" ] && tasks+=(:core:dictZip)
./gradlew -q "${tasks[@]}" ${ROMA_DICT_BASE:+-Proma.dictBase=$ROMA_DICT_BASE}
apks=app/build/outputs/apk
if [ -n "$app" ]; then
  python3 tools/residue_check.py youtube $apks/youtube/release/app-youtube-release-unsigned.apk
  python3 tools/residue_check.py subsonic $apks/subsonic/release/app-subsonic-release-unsigned.apk
fi
if [ -n "$buildDict" ]; then mkdir -p dist; cp "core/build/dict/${dict#dist/}" "$dict"; ls -l "$dict"; fi
[ -n "$app" ] || exit 0 # only tests or the dictionary, so nothing to sign
# Without a key (CI on a merge that isn't a release, or a checkout without one) the APKs are built but not signed. A
# release must be signed.
if [ ! -f keystore.properties ]; then
  [ -z "${RELEASE:-}" ] || { echo "keystore.properties missing for a release" >&2; exit 1; }
  echo "No keystore.properties: built, not signed"; exit 0
fi
source <(sed 's/^\([a-zA-Z]*\)=\(.*\)$/\1="\2"/' keystore.properties)
# Android 7 and later check only the v2/v3 signatures, and the JAR (v1) signature files would add about 6 KB.
sign() { "$tools/apksigner" sign --v1-signing-enabled false --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" --out "$2" "$1"; rm -f "$2.idsig"; }
mkdir -p dist
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for flavor in youtube:dragoman subsonic:caravan; do
  out="dist/${flavor#*:}-$version.apk"
  python3 tools/repack_apk.py $apks/${flavor%:*}/release/app-${flavor%:*}-release-unsigned.apk "$tmp/repacked.apk"
  "$tools/zipalign" -f -P 16 4 "$tmp/repacked.apk" "$tmp/aligned.apk"
  sign "$tmp/aligned.apk" "$out"
  ls -l "$out"
done
