#!/bin/bash
# three_way_diff.sh — Run a test on both JaC64 and local VICE,
# then compare REF + JaC64 + VICE_shot pixel-by-pixel.
#
# Purpose: distinguish "JaC bug" (JaC differs from VICE) from
# "REF palette drift" (JaC and VICE both differ from REF in the same way).
#
# I burned hours on 2026-05-23 chasing a phantom sprite-pipeline bug
# that was actually JaC's border boundary differing from VICE — invisible
# without seeing what VICE itself produces. Always run this BEFORE diving
# into per-cycle traces.
#
# Usage:
#   three_way_diff.sh <test-name>           # e.g. ss-mc-hires
#   three_way_diff.sh <test-name> <x> <y>   # zoom into specific pixel
#
# Env:
#   VICE_BIN=/path/to/x64sc                 # defaults to /Users/joakimeriksson/work/vice-emu/vice/src/x64sc
#   VICE_TESTPROGS=/path/to/testprogs       # defaults to /Users/joakimeriksson/work/VICE-testprogs
#   JAC64_BUILD=/tmp/jac64-build            # where batch_diff.sh built into

set -e

TEST="$1"
ZOOM_X="$2"
ZOOM_Y="$3"

if [ -z "$TEST" ]; then
    echo "Usage: $0 <test-name> [x] [y]" >&2
    exit 1
fi

VICE_BIN="${VICE_BIN:-/Users/joakimeriksson/work/vice-emu/vice/src/x64sc}"
TESTPROGS="${VICE_TESTPROGS:-/Users/joakimeriksson/work/VICE-testprogs}"
BUILD_DIR="${JAC64_BUILD:-/tmp/jac64-build}"
JAC64_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

PRG=$(find "$TESTPROGS" -name "${TEST}.prg" 2>/dev/null | head -1)
[ -z "$PRG" ] && { echo "PRG not found: ${TEST}.prg" >&2; exit 1; }

REF=$(find "$TESTPROGS" -path "*references*" -name "${TEST}.prg-8565.png" 2>/dev/null | head -1)
[ -z "$REF" ] && REF=$(find "$TESTPROGS" -path "*references*" -name "${TEST}.prg.png" 2>/dev/null | head -1)
[ -z "$REF" ] && { echo "REF not found for $TEST" >&2; exit 1; }

JAC_SHOT="/tmp/three_way_jac_${TEST}.png"
VICE_SHOT="/tmp/three_way_vice_${TEST}.png"

# --- Build if needed ---
if [ -z "$JAC64_SKIP_BUILD" ]; then
    javac -encoding UTF-8 -d "$BUILD_DIR" -cp "$JAC64_ROOT" \
        "$JAC64_ROOT"/com/dreamfabric/jac64/*.java \
        "$JAC64_ROOT"/com/dreamfabric/c64utils/*.java \
        "$JAC64_ROOT"/resid/*.java \
        "$JAC64_ROOT"/TestRaster.java 2>/tmp/jac64_build.log || {
            echo "JaC64 build failed — see /tmp/jac64_build.log" >&2
            exit 1
        }
fi

# --- per-test extra JaC flags (mirrors batch_diff.sh test_extra_flags) ---
case "$TEST" in
    colorsplit) EXTRA_FLAGS="-Djac64.zeroScreenRam=true" ;;
    *) EXTRA_FLAGS="" ;;
esac

# --- JaC64 shot ---
echo "Running JaC64..." >&2
rm -f /tmp/jac64_test_frame_*.png
java -Djac64.warp=true -Djac64.captureFrames=2 -Djac64.captureOnDone=true \
     -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
     -Djac64.zeroColorRam=true \
     $EXTRA_FLAGS \
     -cp "$BUILD_DIR:$JAC64_ROOT" TestRaster "$PRG" > /tmp/jac64_${TEST}.log 2>&1
cp /tmp/jac64_test_frame_001.png "$JAC_SHOT"

# --- VICE shot ---
echo "Running VICE..." >&2
# VICE exits with code 1 when limitcycles fires (it's how it signals
# normal stop after hitting the cycle limit). Don't let that abort us.
"$VICE_BIN" -warp -limitcycles 7100000 -autostartprgmode 1 -autostart "$PRG" \
    -exitscreenshot "$VICE_SHOT" >/tmp/vice_${TEST}.log 2>&1 || true

# --- 3-way report ---
# WARNING: the per-pixel classifier below compares RAW RGB with NO image
# alignment and NO palette normalization. JaC screenshots are 282 rows tall vs
# the 272-row REF/VICE shots, and VICE's -exitscreenshot palette differs from
# the -8565 reference palette. Its jac_bug/triple breakdown is UNRELIABLE and
# must NOT be used to characterize residuals. For real numbers use
# survey_drift.sh (png_cell_diff.py: pattern-based, title-aligned,
# palette-independent). The png_cell_diff "Total cell-diffs" line below
# (REF vs JaC) is the trustworthy figure.
# (head -N would SIGPIPE upstream; just print the lines we want)
python3 "$JAC64_ROOT/tools/vice-compare/png_cell_diff.py" "$REF" "$JAC_SHOT" 2>&1 | grep "Total cell" || true
echo
python3 -c "
from PIL import Image
import numpy as np
ref  = np.array(Image.open('$REF').convert('RGB'))
jac  = np.array(Image.open('$JAC_SHOT').convert('RGB'))
vice = np.array(Image.open('$VICE_SHOT').convert('RGB'))

h = min(ref.shape[0], jac.shape[0], vice.shape[0])
w = min(ref.shape[1], jac.shape[1], vice.shape[1])

# Classify each pixel:
#   match    = all three agree
#   jac_bug  = JaC differs from VICE (REF can be anything)
#   ref_drift = JaC and VICE match each other but differ from REF (= palette/version drift)
#   triple   = all three different
match = 0; jac_bug = 0; ref_drift = 0; triple = 0
for y in range(h):
    for x in range(w):
        r = tuple(ref[y,x]); j = tuple(jac[y,x]); v = tuple(vice[y,x])
        jv = (j == v); jr = (j == r); vr = (v == r)
        if jv and jr: match += 1
        elif jv:      ref_drift += 1   # JaC and VICE agree; REF disagrees
        elif jr:      triple += 1      # JaC matches REF but VICE doesn't (rare — modern VICE drift)
        elif vr:      jac_bug += 1     # VICE matches REF; JaC bug
        else:         triple += 1
total = h * w
print(f'Total pixels: {total}')
print(f'  match (all 3 agree):           {match:>7} ({100*match/total:.1f}%)')
print(f'  jac_bug (VICE matches REF):    {jac_bug:>7} ({100*jac_bug/total:.1f}%)  ← ACTIONABLE')
print(f'  ref_drift (JaC matches VICE):  {ref_drift:>7} ({100*ref_drift/total:.1f}%)  ← reference is stale')
print(f'  triple (all 3 differ):         {triple:>7} ({100*triple/total:.1f}%)')

zoom_x = '$ZOOM_X'
zoom_y = '$ZOOM_Y'
if zoom_x and zoom_y:
    zx = int(zoom_x)
    zy = int(zoom_y)
    print()
    print(f'Zoom at y={zy} x={zx}..{zx+8}:')
    for x in range(zx, zx+8):
        r = tuple(int(c) for c in ref[zy,x])
        j = tuple(int(c) for c in jac[zy,x])
        v = tuple(int(c) for c in vice[zy,x])
        if j == v == r:   tag = 'match'
        elif j == v:      tag = 'ref-drift'
        elif v == r:      tag = 'JAC-BUG'
        elif j == r:      tag = 'vice-drift'
        else:             tag = 'all-diff'
        print(f'  x={x}: REF{r}  JAC{j}  VICE{v}  [{tag}]')
"

echo
echo "Shots saved: $JAC_SHOT, $VICE_SHOT"
