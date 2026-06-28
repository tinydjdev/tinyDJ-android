#!/usr/bin/env bash
# Fetch the single-header public-domain decoders used by the native audio engine.
# Run this once before the first Gradle build:
#   ./app/src/main/cpp/third_party/download_dr_libs.sh
set -euo pipefail
cd "$(dirname "$0")"

base="https://raw.githubusercontent.com/mackron/dr_libs/master"
for f in dr_flac.h dr_mp3.h; do
  if [ -f "$f" ]; then
    echo "$f already present, skipping."
  else
    echo "Downloading $f ..."
    curl -fsSL "$base/$f" -o "$f"
  fi
done
echo "Done. dr_flac.h and dr_mp3.h are in $(pwd)."
