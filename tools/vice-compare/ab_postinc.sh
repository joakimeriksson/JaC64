#!/bin/bash
# ab_postinc.sh — fast A/B for the post-inc leading-cell work.
# Runs screenpos + blackmail-ee with the given extra JaC flags, diffs vs
# cached VICE shots, prints per-col breakdown.
# Usage: ab_postinc.sh "-Djac64.viceVcPostInc=true ..."
set -e
ROOT=/Users/joakimeriksson/work/JaC64
BUILD=/tmp/jac64-build
TP=/Users/joakimeriksson/work/VICE-testprogs
FLAGS="$1"

if [ -z "$AB_SKIP_BUILD" ]; then
  javac -encoding UTF-8 -d "$BUILD" -cp "$ROOT" \
    "$ROOT"/com/dreamfabric/jac64/*.java \
    "$ROOT"/com/dreamfabric/c64utils/*.java \
    "$ROOT"/resid/*.java "$ROOT"/TestRaster.java 2>/tmp/ab_build.log \
    || { echo "BUILD FAILED"; tail -20 /tmp/ab_build.log; exit 1; }
fi

run() {
  local name="$1" prg="$2" vice="$3"
  rm -f /tmp/jac64_test_frame_*.png 2>/dev/null || true
  java -Djac64.warp=true -Djac64.captureFrames=2 -Djac64.captureOnDone=true \
       -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
       -Djac64.zeroColorRam=true $FLAGS \
       -cp "$BUILD:$ROOT" TestRaster "$prg" > /tmp/ab_${name}.log 2>&1 || true
  cp /tmp/jac64_test_frame_001.png /tmp/ab_jac_${name}.png 2>/dev/null || { echo "$name: no shot"; return; }
  echo "=== $name ==="
  python3 "$ROOT/tools/vice-compare/png_cell_diff.py" "$vice" /tmp/ab_jac_${name}.png 2>&1 \
    | grep -E "Total cell-diffs|col +[0-9]+:" | head -12
}

run screenpos "$TP/VICII/screenpos/screenpos.prg" /tmp/sd_vice_screenpos.png
run blackmail-ee "$TP/VICII/flibug/blackmail-ee.prg" /tmp/sd_vice_blackmail-ee.png
