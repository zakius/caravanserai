#!/bin/bash
# The audio-only ffmpeg the desktop app runs for songs YouTube has only in AAC (about 1 in 150; desktop/Media.kt,
# AacPcm): ffmpeg's own AAC decoder reading ADTS frames on stdin and writing 16-bit PCM to stdout, nothing else.
# A static binary of about 1.3 MB, LGPL (no GPL or non-free parts). Pinned sources, checked against their sha256.
# Linux's links musl (musl-tools), not glibc: half the size, and static either way, so it runs on any distribution.
# Usage: build-ffmpeg.sh linux|windows <out dir>; writes <out dir>/roma-aac (roma-aac.exe for Windows).
# Windows is cross-built with llvm-mingw, so Linux and CI build both the same way.
set -euo pipefail
target=$1
out=$(realpath -m "$2")
FFMPEG=9.0.2
FFMPEG_SHA=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
LLVM_MINGW=20261020
LLVM_MINGW_SHA=55064b60b991a1e1e8090b8c2f84e82b22cfe5903d12c159d65064cf5e8ae335
cache=${ROMA_TOOLS_CACHE:-$HOME/.cache/roma-tools}
mkdir -p "$cache" "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fetch() { # url, file, sha256
  [ -f "$2" ] && echo "$3  $2" | sha256sum -c --quiet 2>/dev/null && return
  curl -fsSL "$1" -o "$2.tmp" && echo "$3  $2.tmp" | sha256sum -c --quiet && mv "$2.tmp" "$2"
}
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG.tar.xz" "$cache/ffmpeg-$FFMPEG.tar.xz" $FFMPEG_SHA
tar -xJf "$cache/ffmpeg-$FFMPEG.tar.xz" -C "$work"
cd "$work/ffmpeg-$FFMPEG"

flags=(
  --disable-everything --disable-autodetect --disable-doc --disable-debug --disable-network
  --disable-ffplay --disable-ffprobe --disable-avdevice --disable-swscale --disable-x86asm --enable-small
  --enable-protocol=pipe --enable-demuxer=aac --enable-parser=aac --enable-decoder=aac
  --enable-muxer=pcm_s16le --enable-encoder=pcm_s16le --enable-filter=aformat,anull,aresample
  --enable-lto --extra-cflags="-ffunction-sections -fdata-sections" --extra-ldflags="-static -Wl,--gc-sections"
)
case $target in
  linux) ./configure "${flags[@]}" --cc=musl-gcc ;;
  windows)
    fetch "https://github.com/mstorsjo/llvm-mingw/releases/download/$LLVM_MINGW/llvm-mingw-$LLVM_MINGW-ucrt-ubuntu-22.04-x86_64.tar.xz" \
      "$cache/llvm-mingw-$LLVM_MINGW.tar.xz" $LLVM_MINGW_SHA
    [ -d "$cache/llvm-mingw-$LLVM_MINGW" ] || { tar -xJf "$cache/llvm-mingw-$LLVM_MINGW.tar.xz" -C "$cache" && mv "$cache/llvm-mingw-$LLVM_MINGW-ucrt-ubuntu-22.04-x86_64" "$cache/llvm-mingw-$LLVM_MINGW"; }
    export PATH="$cache/llvm-mingw-$LLVM_MINGW/bin:$PATH"
    ./configure "${flags[@]}" --target-os=mingw32 --arch=x86_64 --cross-prefix=x86_64-w64-mingw32- --cc=x86_64-w64-mingw32-clang ;;
  *) echo "target: linux or windows" >&2; exit 1 ;;
esac
exe=ffmpeg; [ "$target" = windows ] && exe=ffmpeg.exe
make -j"$(nproc)" "$exe" >/dev/null
strip_tool=strip; [ "$target" = windows ] && strip_tool=x86_64-w64-mingw32-strip
$strip_tool "$exe"
cp "$exe" "$out/roma-aac${exe#ffmpeg}"
ls -l "$out/roma-aac${exe#ffmpeg}"
