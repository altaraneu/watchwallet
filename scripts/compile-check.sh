#!/usr/bin/env bash
# Fast compile check: compiles every shared and feature module for Android without packaging
# the APK (no dex, resource merge or signing). Prints only compiler errors.
#
#   scripts/compile-check.sh            # all modules
#   scripts/compile-check.sh :core:data # one module and its dependencies
set -uo pipefail
cd "$(dirname "$0")/.."
target="${1:-:shared}"
./gradlew "${target}:compileAndroidMain" :app:compileDebugKotlin --continue --console=plain -q 2>&1 \
  | grep -E '^e:|What went wrong|FAILED' \
  | sed -e "s#file:///$(pwd | sed 's#^/\([a-z]\)/#\U\1:/#')/##" \
  || echo "Compile OK"
