#!/usr/bin/env bash
# Fast compile check: compiles every shared and feature module for Android without packaging
# the APK (no dex, resource merge or signing). Prints only compiler errors.
#
#   scripts/compile-check.sh            # all modules
#   scripts/compile-check.sh :core:data # one module and its dependencies
set -uo pipefail
cd "$(dirname "$0")/.."
target="${1:-:shared}"
log="$(mktemp)"
./gradlew "${target}:compileAndroidMain" :app:compileDebugKotlin --continue --console=plain -q >"$log" 2>&1
status=$?
grep -E '^e:|What went wrong|FAILED' "$log" | sed -e 's#file:///[A-Za-z]:/[^ ]*/watchwallet/##'
rm -f "$log"
if [ "$status" -eq 0 ]; then echo "Compile OK"; else echo "Compile FAILED"; exit 1; fi
