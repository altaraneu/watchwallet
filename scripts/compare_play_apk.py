#!/usr/bin/env python3
"""Compares the code in a build from source with an APK from Google Play.

    python scripts/compare_play_apk.py app-release-unsigned.apk 101.apk

Play rewrites resources and the manifest, so scripts/compare_apks.py reports most entries as different.
This tool looks only at what runs: dex files, assets and native libraries. Native libraries that differ
are compared by their loaded segments, ignoring the ELF header fields that describe the section table,
because stripping debug symbols changes those and nothing that runs.

The second argument may be a universal APK, or a base APK with its splits given after it. Exit code 0
means all code matches. Resources and the manifest are not compared; check permissions separately with
`aapt2 dump permissions`.
"""

from __future__ import annotations

import struct
import sys
import zipfile

# Offsets of e_shoff, e_shnum and e_shstrndx in the ELF header. e_shentsize never changes.
SECTION_FIELDS = {1: (range(32, 36), range(48, 52)), 2: (range(40, 48), range(60, 64))}
PT_LOAD = 1


def is_code(name: str) -> bool:
    return (name.endswith(".dex") and "/" not in name) or name.startswith(("assets/", "lib/"))


def read_entries(paths: list[str]) -> dict[str, bytes]:
    entries: dict[str, bytes] = {}
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            entries.update({name: archive.read(name) for name in archive.namelist() if is_code(name)})
    return entries


def load_segments(data: bytes) -> list[tuple[int, bytes]]:
    is_64 = data[4] == 2
    if is_64:
        table = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
    else:
        table = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
    segments = []
    for index in range(count):
        at = table + index * entry_size
        if is_64:
            kind, _, offset, address, _, size = struct.unpack_from("<IIQQQQ", data, at)
        else:
            kind, offset, address, _, size = struct.unpack_from("<IIIII", data, at)
        if kind == PT_LOAD:
            segments.append((address, data[offset:offset + size]))
    return segments


def without_section_fields(data: bytes) -> bytes:
    cleared = bytearray(data)
    for field in SECTION_FIELDS[data[4]]:
        for offset in field:
            cleared[offset] = 0
    return bytes(cleared)


def same_loaded_code(first: bytes, second: bytes) -> bool:
    if first[:4] != b"\x7fELF" or second[:4] != b"\x7fELF":
        return False
    return load_segments(without_section_fields(first)) == load_segments(without_section_fields(second))


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    first, second = read_entries(argv[:1]), read_entries(argv[1:])
    identical = [name for name in first if name in second and first[name] == second[name]]
    stripped = [name for name in first if name.endswith(".so") and name in second and name not in identical and same_loaded_code(first[name], second[name])]
    different = [name for name in first if name in second and name not in identical and name not in stripped]
    only_first = sorted(set(first) - set(second))
    only_second = sorted(set(second) - set(first))
    print(f"Identical: {len(identical)}")
    print(f"Native libraries with the same loaded code, stripped differently: {len(stripped)}")
    for name in sorted(stripped):
        print(f"  {name}")
    for label, names in (("Different", different), ("Only in the first", only_first), ("Only in the second", only_second)):
        print(f"{label}: {len(names)}")
        for name in sorted(names):
            print(f"  {name}")
    return 1 if different or only_first or only_second else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
