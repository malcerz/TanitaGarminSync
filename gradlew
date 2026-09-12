#!/usr/bin/env sh
set -eu
VERSION=9.6.0
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DIST="$ROOT/.gradle-local/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$ROOT/.gradle-local"
  ZIP="$ROOT/.gradle-local/gradle.zip"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -L "$URL" -o "$ZIP"; else wget "$URL" -O "$ZIP"; fi
  unzip -q -o "$ZIP" -d "$ROOT/.gradle-local"
  rm -f "$ZIP"
fi
exec "$DIST/bin/gradle" "$@"
