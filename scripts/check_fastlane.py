#!/usr/bin/env python3
"""Checks the store metadata against F-Droid's limits and the version of the app.

Limits: https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
METADATA = ROOT / "fastlane/metadata/android"
LIMITS = {"title.txt": 50, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500
REQUIRED = {"short_description.txt", "full_description.txt"}


def main() -> int:
    match = re.search(r"versionCode\s*=\s*(\d+)", (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))
    if not match:
        print("No versionCode in app/build.gradle.kts")
        return 1
    version_code = match[1]
    problems = []
    locales = sorted(p for p in METADATA.iterdir() if p.is_dir())
    for locale in locales:
        for name, limit in LIMITS.items():
            file = locale / name
            if not file.exists():
                if name in REQUIRED:
                    problems.append(f"{file.relative_to(ROOT)}: missing")
                continue
            length = len(file.read_text(encoding="utf-8").strip())
            if length > limit:
                problems.append(f"{file.relative_to(ROOT)}: {length} characters, limit {limit}")
        for file in sorted((locale / "changelogs").glob("*.txt")):
            length = len(file.read_text(encoding="utf-8").strip())
            if length > CHANGELOG_LIMIT:
                problems.append(f"{file.relative_to(ROOT)}: {length} characters, limit {CHANGELOG_LIMIT}")
    if not (METADATA / "en-US/changelogs" / f"{version_code}.txt").exists():
        problems.append(f"en-US has no changelog for versionCode {version_code}")
    print("\n".join(problems) if problems else f"Metadata OK: {len(locales)} locales, versionCode {version_code}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
