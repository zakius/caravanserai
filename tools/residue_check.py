#!/usr/bin/env python3
"""Fails when one app carries the other's code (docs/subsonic.md, Two apps).

  residue_check.py subsonic <artifact>...   caravan: no YouTube code, client or string
  residue_check.py youtube <artifact>...    dragoman: no Subsonic code

An artifact is an APK, a jar, or a directory: a desktop distribution, whose files are all read (a jar's entries one
by one), except the bundled Java runtime. Every file or entry is searched for each pattern, ignoring case, as UTF-8 and
as UTF-16 (binary XML and some resources keep their strings in UTF-16). Prints what each pattern found, or that it
found nothing, and exits 1 on any hit.
"""
import os
import sys
import zipfile

# Library strings that contain a pattern without being the other app's code. They are removed before searching.
# Android's and Compose's web-address pattern lists every top-level domain, including YouTube's (.youtube).
ALLOWED = [b"|you|youtube|yun|"]

PATTERNS = {
    # caravan is on Google Play, so it must carry no YouTube code and must not install apps (the Japanese dictionary
    # is downloaded as data).
    "subsonic": ["innertube", "metrolist", "botguard", "youtube", "googlevideo", "visionos", "packageinstaller", "request_install_packages"],
    "youtube": ["subsonic"],
}


def entries(path):
    """Yields (name, bytes) for every file in the artifact, opening nested archives."""
    if os.path.isdir(path):
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d != "runtime"]
            for f in sorted(files):
                full = os.path.join(root, f)
                yield from entries(full) if f.endswith((".jar", ".apk")) else [(os.path.relpath(full, path), open(full, "rb").read())]
    else:
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if not info.is_dir():
                    yield f"{os.path.basename(path)}!{info.filename}", z.read(info)


def check(flavor, artifact):
    needles = {p: [p.encode("utf-8"), p.encode("utf-16-le")] for p in PATTERNS[flavor]}
    hits = {p: [] for p in needles}
    count = 0
    for name, data in entries(artifact):
        count += 1
        low = data.lower()
        for a in ALLOWED:
            low = low.replace(a, b"").replace(a.decode().encode("utf-16-le"), b"")
        for p, forms in needles.items():
            if any(f in low for f in forms):
                hits[p].append(name)
    print(f"{artifact}: {count} files, against {flavor}'s list")
    for p, found in hits.items():
        print(f"  {p:12} {'clean' if not found else f'{len(found)} files: ' + ', '.join(found[:5]) + (' …' if len(found) > 5 else '')}")
    return not any(hits.values())


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in PATTERNS:
        sys.exit(__doc__)
    ok = [check(sys.argv[1], a) for a in sys.argv[2:]]
    sys.exit(0 if all(ok) else 1)
