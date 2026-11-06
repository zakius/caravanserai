#!/usr/bin/env python3
"""Recompresses an unsigned APK with Zopfli (pigz -11), the strongest deflate, since Android reads
only stored and deflated entries. Deflated entries and the native libraries (useLegacyPackaging,
so extractNativeLibs) are recompressed; stored ones (resources.arsc) stay stored. zipalign and
signing come after. Taken from tennessine's tools/repack_apk.py.

It also embeds AGP's baseline profile at assets/art-profile/baseline.prof, where ART (Android 15+) reads it when the
APK is installed and compiles the profiled code. A sideloaded install otherwise runs uncompiled until the device's
idle-time dexopt, and every update resets that. AGP only writes assets/dexopt/, which ProfileInstaller handles later.

Usage: repack_apk.py IN OUT
"""
import json, os, subprocess, sys, zipfile


class Zopfli:
    """A compressor for zipfile: collects an entry, then deflates it all at once with pigz."""

    def __init__(self):
        self.parts = []

    def compress(self, data):
        self.parts.append(data)
        return b''

    def flush(self):
        out = subprocess.run(['pigz', '-11', '-z', '-p', str(os.cpu_count()), '-c'],
                             input=b''.join(self.parts), capture_output=True, check=True).stdout
        return out[2:-4]  # The raw deflate stream inside the zlib header and Adler-32.


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    stock = zipfile._get_compressor
    zipfile._get_compressor = lambda kind, level=None: (
        Zopfli() if kind == zipfile.ZIP_DEFLATED else stock(kind, level))
    with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], 'w') as dst:
        for info in src.infolist():
            deflate = info.compress_type == zipfile.ZIP_DEFLATED or (
                info.filename.startswith('lib/') and info.filename.endswith('.so'))
            out = zipfile.ZipInfo(info.filename, info.date_time)
            out.external_attr = info.external_attr
            out.compress_type = zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED
            dst.writestr(out, src.read(info))
        # Takes the profile for API 31 and later (format 015) from the .dm next to the input APK. It names the dex files
        # by checksum, which stays valid because the repack doesn't change their bytes.
        built = os.path.dirname(sys.argv[1])
        with open(os.path.join(built, 'output-metadata.json')) as f:
            dm = next(p['baselineProfiles'][0] for p in json.load(f)['baselineProfiles'] if p['minApi'] >= 31)
        with zipfile.ZipFile(os.path.join(built, dm)) as z:
            dst.writestr(zipfile.ZipInfo('assets/art-profile/baseline.prof', (1981, 1, 1, 1, 1, 0)), z.read('primary.prof'))


if __name__ == '__main__':
    main()
