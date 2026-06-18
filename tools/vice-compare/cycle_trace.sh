#!/bin/bash
# cycle_trace.sh — run JaC64 + VICE on a test with per-cycle EV-State tracing,
# then phase-align and diff (cycle_align.py). Defeats absolute-clk + demo-phase
# drift by matching frames on their FLI (YSCROLL/abl) fingerprint.
#
# Usage: cycle_trace.sh <test-name> [--rast LO-HI] [extra -Djac flags...]
#   e.g. cycle_trace.sh bitmap --rast 30-40
#
# Requires the local VICE fork (JAC64_TRACE_FILE_STATE) + JaC build dir.
set -e
TEST="$1"; shift || true
RAST=""
[ "$1" = "--rast" ] && { RAST="--rast $2"; shift 2; }
EXTRA="$*"

VICE="${VICE_BIN:-/Users/joakimeriksson/work/vice-emu/vice/src/x64sc}"
TP="${VICE_TESTPROGS:-/Users/joakimeriksson/work/VICE-testprogs}"
BUILD="${JAC64_BUILD:-/tmp/jac64-build}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PRG=$(find "$TP" -name "${TEST}.prg" | head -1)
[ -z "$PRG" ] && { echo "test not found: $TEST"; exit 1; }
LIMIT="${LIMIT:-7100000}"; CAP="${CAP:-7099000}"

# JaC capture clk is DECOUPLED from VICE's limit: some tests (notably
# fetchsplit) reach their steady-state display pattern far LATER in JaC than
# in VICE — VICE shows the fetchsplit pattern by ~7.1M cycles, JaC only by
# ~30M. The old default (CAP=7099000) captured JaC's *BASIC setup screen*
# ("READY."), NOT the test pattern, so the per-cycle diff compared JaC-setup
# vs VICE-pattern (meaningless). Set JAC_CAP to a clk where JaC actually shows
# the pattern. For fetchsplit, JAC_CAP=30000000 reproduces the survey's
# pixel-diff frame byte-for-byte (verified 0-cell diff vs the warp-loop
# capture). Verify with: captureAtCycle=$JAC_CAP screenshot == survey frame.
JAC_CAP="${JAC_CAP:-$CAP}"
echo "test=$PRG  vice-limit=$LIMIT vice-cap≈$LIMIT  jac-cap=$JAC_CAP"
echo "--- VICE (EV-State over full run) ---"
JAC64_TRACE_FILE_STATE=/tmp/ct_vice.state \
  "$VICE" -warp -limitcycles "$LIMIT" -autostartprgmode 1 -autostart "$PRG" \
  -exitscreenshot /tmp/ct_vice.png >/dev/null 2>&1 || true
echo "  $(grep -c EV-State /tmp/ct_vice.state 2>/dev/null) state lines"

echo "--- JaC (EV-State, deterministic capture window) ---"
java -Djac64.warp=true -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
     -Djac64.zeroColorRam=true -Djac64.captureAtCycle="$JAC_CAP" -Djac64.captureFile=/tmp/ct_jac.png \
     -Djac64.traceVicState=true -Djac64.traceVicStateStart=$((JAC_CAP-60000)) -Djac64.traceVicStateEnd="$JAC_CAP" \
     -Djac64.traceVicStateFile=/tmp/ct_jac.state $EXTRA \
     -cp "$BUILD:." TestRaster "$PRG" >/dev/null 2>&1 || true
echo "  $(grep -c EV-State /tmp/ct_jac.state 2>/dev/null) state lines"

echo "--- phase-aligned diff ---"
python3 "$ROOT/tools/vice-compare/cycle_align.py" /tmp/ct_jac.state /tmp/ct_vice.state $RAST
