#!/usr/bin/env python3
"""Fails when a network host in production code is missing from PRIVACY.md.

Complements EndpointSourceConsistencyTest, which covers only blockchain servers. PRIVACY.md may name a
host in full, as a wildcard (`*.publicnode.com`), or as a Blockscout short form (`eth.`).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
HOST = re.compile(r"(?:https?|wss)://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)")
WILDCARD = re.compile(r"`\*\.([a-z0-9.-]+)`")
SOURCE_SUFFIXES = {".kt", ".html"}
# Namespaces; no request goes to them.
NOT_CONTACTED = {"www.w3.org", "schemas.android.com"}
SHORT_FORM_DOMAIN = "blockscout.com"


def production_files():
    for path in REPO.rglob("*"):
        if path.suffix not in SOURCE_SUFFIXES or "build" in path.parts or ".claude" in path.parts:
            continue
        if any(part.endswith("Main") or part == "main" for part in path.parts):
            yield path
    yield REPO / "app" / "build.gradle.kts"


def code_hosts() -> set[str]:
    hosts: set[str] = set()
    for path in production_files():
        hosts.update(host.lower() for host in HOST.findall(path.read_text(encoding="utf-8", errors="ignore")))
    return hosts - NOT_CONTACTED


def is_listed(host: str, text: str, wildcards: set[str]) -> bool:
    if host in text:
        return True
    if any(host.endswith("." + suffix) for suffix in wildcards):
        return True
    return host.endswith("." + SHORT_FORM_DOMAIN) and f"`{host.split('.')[0]}.`" in text


def main() -> int:
    text = (REPO / "PRIVACY.md").read_text(encoding="utf-8")
    wildcards = set(WILDCARD.findall(text))
    hosts = code_hosts()
    missing = sorted(host for host in hosts if not is_listed(host, text, wildcards))
    for host in missing:
        print(f"Not in PRIVACY.md: {host}", file=sys.stderr)
    print(f"Checked {len(hosts)} hosts, {len(missing)} missing.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
