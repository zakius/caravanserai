#!/bin/bash
# Builds the release APK, signs it with the SDK debug key and installs it on $SERIAL (an emulator, never a phone).
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/air/jbr
export PATH="$JAVA_HOME/bin:$PATH:$HOME/Android/Sdk/platform-tools"
BT=~/Android/Sdk/build-tools/37.0.0
./gradlew -q :app:assembleYoutubeRelease
IN=app/build/outputs/apk/youtube/release/app-youtube-release-unsigned.apk
OUT=/tmp/roma-latency-$$.apk
rm -f $OUT.aligned
$BT/zipalign -f -p 4 $IN $OUT.aligned
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out $OUT $OUT.aligned
adb -s ${SERIAL:?} install -r $OUT || { adb -s $SERIAL uninstall com.zakius.dragoman; adb -s $SERIAL install $OUT; }
rm -f $OUT $OUT.aligned
