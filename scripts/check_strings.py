#!/usr/bin/env python3
"""Checks user-facing text in the repository. Run from the repository root.

1. Translation completeness: every `values-<lang>/strings.xml` defines exactly the keys of the
   English source `values/strings.xml` in the same module, with the same placeholders.
2. No hardcoded user-facing text: `Text("...")` with a string literal is not allowed in
   production Kotlin code (use string resources).

Exit code 0 when clean, 1 when problems were found.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLACEHOLDER = re.compile(r"%(\d+\$)?[sdf]")
HARDCODED_TEXT = re.compile(r"""\bText\(\s*(text\s*=\s*)?"[^"]*[A-Za-z][^"]*"\s*[,)]""")
SKIP_DIRS = {"build", ".gradle", ".git", ".kotlin"}


def load_strings(path: Path) -> dict[str, list[str]]:
    """Returns {name: sorted placeholders} for <string> and <plurals> entries."""
    result: dict[str, list[str]] = {}
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if name is None or element.get("translatable") == "false":
            continue
        if element.tag == "string":
            result[name] = sorted(PLACEHOLDER.findall(element.text or ""))
        elif element.tag == "plurals":
            items = [item.text or "" for item in element.findall("item")]
            result[name] = sorted({p for text in items for p in PLACEHOLDER.findall(text)})
    return result


def check_translations() -> list[str]:
    problems: list[str] = []
    for source in sorted(ROOT.glob("**/composeResources/values/strings.xml")):
        if SKIP_DIRS & set(source.relative_to(ROOT).parts):
            continue
        reference = load_strings(source)
        resources = source.parent.parent
        for translation in sorted(resources.glob("values-*/strings.xml")):
            rel = translation.relative_to(ROOT)
            translated = load_strings(translation)
            for name in sorted(reference.keys() - translated.keys()):
                problems.append(f"{rel}: missing '{name}'")
            for name in sorted(translated.keys() - reference.keys()):
                problems.append(f"{rel}: '{name}' does not exist in the English source")
            for name in sorted(reference.keys() & translated.keys()):
                if reference[name] != translated[name]:
                    problems.append(
                        f"{rel}: '{name}' placeholders {translated[name]} differ from source {reference[name]}"
                    )
    return problems


def check_hardcoded_text() -> list[str]:
    problems: list[str] = []
    for source in sorted(ROOT.glob("**/src/*Main/kotlin/**/*.kt")):
        if SKIP_DIRS & set(source.relative_to(ROOT).parts):
            continue
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            if HARDCODED_TEXT.search(line):
                problems.append(f"{source.relative_to(ROOT)}:{number}: hardcoded text: {line.strip()}")
    return problems


def main() -> int:
    problems = check_translations() + check_hardcoded_text()
    for problem in problems:
        print(problem)
    if problems:
        print(f"\n{len(problems)} problem(s) found.")
        return 1
    print("Strings OK: translations complete, no hardcoded text.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
