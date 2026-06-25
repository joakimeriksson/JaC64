#!/bin/bash
# Build and run the raster test harness.
#
# Usage:
#   ./test_raster.sh [url-or-path]
#   ./test_raster.sh                    # uses default c64.com demo
#   ./test_raster.sh /path/to/game.d64  # local file
#
# Defaults are tuned for repeatable automated runs. Override with:
#   JAC64_HEADLESS=false JAC64_WARP=false JAC64_CAPTURE_FRAMES=30 ./test_raster.sh ...

set -euo pipefail

if [ -x ./gradlew ]; then
  GRADLE=./gradlew
else
  GRADLE=gradle
fi

: "${JAC64_HEADLESS:=true}"
: "${JAC64_WARP:=true}"
: "${JAC64_CAPTURE_FRAMES:=1}"

echo "Building..."
"$GRADLE" jar

echo ""
echo "Running raster test..."
java \
  -Djac64.headless="$JAC64_HEADLESS" \
  -Djac64.warp="$JAC64_WARP" \
  -Djac64.captureFrames="$JAC64_CAPTURE_FRAMES" \
  -cp build/libs/JaC64.jar \
  TestRaster "$@"
