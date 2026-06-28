#!/usr/bin/env bash
# Build tinydj's debug APK using a Dockerized Android toolchain (JDK 21, SDK 35,
# NDK 27, CMake 3.22.1, Gradle 8.10.2). The first run builds the toolchain image;
# subsequent runs reuse it plus a cached Gradle volume.
#
#   ./build.sh                  # -> :app:assembleDebug
#   ./build.sh :app:assembleRelease
#   TINYDJ_BASE=habits-android:1.7.5 ./build.sh   # pick the Android SDK base image
set -euo pipefail
cd "$(dirname "$0")"

export DOCKER_DEFAULT_PLATFORM=linux/amd64
TASK="${1:-:app:assembleDebug}"
BASE_IMAGE="${TINYDJ_BASE:-tinydj-base:latest}"

# 1. Decoder headers (public-domain, not committed).
if [ ! -f app/src/main/cpp/third_party/dr_flac.h ]; then
  ./app/src/main/cpp/third_party/download_dr_libs.sh
fi

# 2. Toolchain image.
if ! docker image inspect tinydj-builder:latest >/dev/null 2>&1; then
  echo ">> building tinydj-builder image from base: $BASE_IMAGE"
  docker build --build-arg BASE="$BASE_IMAGE" -t tinydj-builder:latest docker/
fi

# 3. Build, repo mounted, Gradle cache persisted.
docker run --rm \
  -v "$PWD":/app \
  -v tinydj-gradle-cache:/root/.gradle \
  -w /app \
  tinydj-builder:latest \
  gradle "$TASK" --no-daemon --console=plain

echo ""
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
