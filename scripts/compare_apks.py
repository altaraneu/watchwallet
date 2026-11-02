#!/usr/bin/env python3
"""Compares two APKs by content, ignoring what signing adds.

    python scripts/compare_apks.py a.apk b.apk     # exit 1 and list the entries that differ
    python scripts/compare_apks.py --list a.apk    # one "sha256  name" line per entry

Signing changes only the v1 files under META-INF and the signing block outside the zip entries, so
an unsigned build and a signed copy of it have equal entries here. Play-delivered APKs differ
further, see docs/reproducible-builds.md.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile

SIGNING_FILE = re.compile(r"^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$|^stamp-cert-sha256$")


def entries(path: str) -> dict[str, str]:
    with zipfile.ZipFile(path) as apk:
        return {
            info.filename: hashlib.sha256(apk.read(info)).hexdigest()
            for info in apk.infolist()
            if not SIGNING_FILE.match(info.filename)
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="print the entries of one APK")
    parser.add_argument("apks", nargs="+")
    args = parser.parse_args()
    if args.list:
        for name, digest in sorted(entries(args.apks[0]).items()):
            print(f"{digest}  {name}")
        return 0
    if len(args.apks) != 2:
        parser.error("two APKs to compare")
    first, second = entries(args.apks[0]), entries(args.apks[1])
    different = sorted(n for n in first.keys() | second.keys() if first.get(n) != second.get(n))
    for name in different:
        note = "only in second" if name not in first else "only in first" if name not in second else "content"
        print(f"{note}: {name}")
    print(f"{len(first)} and {len(second)} entries, {len(different)} differ")
    return 1 if different else 0


if __name__ == "__main__":
    sys.exit(main())
