#!/bin/bash
# survey_drift.sh — Run all tests via 3-way (REF/JaC/VICE), tabulate
# cell-diffs broken down by JaC-vs-REF AND JaC-vs-VICE.
#
# The JaC-vs-VICE number is what's ACTIONABLE. JaC-vs-REF can be
# inflated by reference drift.
#
# Usage: survey_drift.sh [test1 test2 ...]   # default: 16-test suite
set -e

VICE_BIN="${VICE_BIN:-/Users/joakimeriksson/work/vice-emu/vice/src/x64sc}"
TESTPROGS="${VICE_TESTPROGS:-/Users/joakimeriksson/work/VICE-testprogs}"
BUILD_DIR="${JAC64_BUILD:-/tmp/jac64-build}"
JAC64_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

DEFAULT_TESTS=(
    screenpos colorsplit rmwtest modesplit fetchsplit greydot
    vicii_reg_timing vicii_reg_timing-a5 vicii_reg_timing-ff
    ss-pri ss-pri-mc ss-hires-color ss-hires-mc
    ss-mc-color0 ss-mc-hires ss-xpos
)
TESTS=("$@")
[ ${#TESTS[@]} -eq 0 ] && TESTS=("${DEFAULT_TESTS[@]}")

# Build once.
if [ -z "$JAC64_SKIP_BUILD" ]; then
    javac -encoding UTF-8 -d "$BUILD_DIR" -cp "$JAC64_ROOT" \
        "$JAC64_ROOT"/com/dreamfabric/jac64/*.java \
        "$JAC64_ROOT"/com/dreamfabric/c64utils/*.java \
        "$JAC64_ROOT"/resid/*.java \
        "$JAC64_ROOT"/TestRaster.java 2>/tmp/jac64_build.log \
        || { echo "build failed" >&2; tail /tmp/jac64_build.log >&2; exit 1; }
fi

extra_flags() {
    case "$1" in
        colorsplit) echo "-Djac64.zeroScreenRam=true" ;;
        # border-mcbm reads color-RAM cells it never writes and relies on the
        # power-on pattern (which the VICE reference captured). Zeroing color
        # RAM here inflates the diff by ~2786 cells of pure init artifact
        # (2793 zeroed vs 7 with natural init). Use JaC's natural init.
        border-mcbm) echo "-Djac64.zeroColorRam=false" ;;
        *) echo "" ;;
    esac
}

printf "%-23s | %-12s | %-12s | %s\n" "Test" "JaC-vs-REF" "JaC-vs-VICE" "Verdict"
printf -- "------------------------+--------------+--------------+--------------------------\n"

total_jac_ref=0
total_jac_vice=0
n=0
for test in "${TESTS[@]}"; do
    # Find REF first; multiple PRGs can share a name (e.g. RS232/test1.prg vs
    # VICII/lp-trigger/test1.prg), but each REF is in a specific test dir.
    # Derive the matching PRG from the REF's directory.
    REF=$(find "$TESTPROGS" -path "*references*" -name "${test}.prg-8565.png" 2>/dev/null | head -1)
    [ -z "$REF" ] && REF=$(find "$TESTPROGS" -path "*references*" -name "${test}.prg.png" 2>/dev/null | head -1)
    [ -z "$REF" ] && { printf "%-23s | %-12s | %-12s | (no REF)\n" "$test" "?" "?"; continue; }
    PRG="$(dirname "$(dirname "$REF")")/${test}.prg"
    [ ! -f "$PRG" ] && PRG=$(find "$TESTPROGS" -name "${test}.prg" 2>/dev/null | head -1)
    [ -z "$PRG" ] || [ ! -f "$PRG" ] && { printf "%-23s | %-12s | %-12s | (no PRG)\n" "$test" "?" "?"; continue; }

    JAC_SHOT="/tmp/sd_jac_${test}.png"
    VICE_SHOT="/tmp/sd_vice_${test}.png"

    # JaC shot
    rm -f /tmp/jac64_test_frame_*.png 2>/dev/null
    extra=$(extra_flags "$test")
    java -Djac64.warp=true -Djac64.captureFrames=2 -Djac64.captureOnDone=true \
         -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
         -Djac64.zeroColorRam=true $extra \
         -cp "$BUILD_DIR:$JAC64_ROOT" TestRaster "$PRG" \
         > /tmp/sd_${test}_jac.log 2>&1 || true
    cp /tmp/jac64_test_frame_001.png "$JAC_SHOT" 2>/dev/null || continue

    # VICE shot
    "$VICE_BIN" -warp -limitcycles 7100000 -autostartprgmode 1 -autostart "$PRG" \
        -exitscreenshot "$VICE_SHOT" >/tmp/sd_${test}_vice.log 2>&1 || true
    [ ! -f "$VICE_SHOT" ] && { printf "%-23s | %-12s | %-12s | (vice failed)\n" "$test" "?" "?"; continue; }

    # Two cell-diffs
    d_ref=$(python3 "$JAC64_ROOT/tools/vice-compare/png_cell_diff.py" "$REF" "$JAC_SHOT" 2>&1 \
              | grep "Total cell-diffs" | awk -F'[: /]' '{print $4}')
    d_vice=$(python3 "$JAC64_ROOT/tools/vice-compare/png_cell_diff.py" "$VICE_SHOT" "$JAC_SHOT" 2>&1 \
              | grep "Total cell-diffs" | awk -F'[: /]' '{print $4}')

    [ -z "$d_ref" ] && d_ref=0
    [ -z "$d_vice" ] && d_vice=0
    total_jac_ref=$((total_jac_ref + d_ref))
    total_jac_vice=$((total_jac_vice + d_vice))
    n=$((n + 1))

    # Verdict
    if [ "$d_vice" -eq 0 ] && [ "$d_ref" -gt 0 ]; then
        verdict="REF drift only (no JaC bug)"
    elif [ "$d_vice" -eq 0 ]; then
        verdict="clean"
    elif [ "$d_vice" -lt $((d_ref / 4)) ]; then
        verdict="mostly REF drift, small JaC bug"
    elif [ "$d_ref" -lt $((d_vice / 4)) ]; then
        verdict="JaC ahead of VICE vs REF"
    else
        verdict="JaC bug ~$d_vice cells"
    fi
    printf "%-23s | %-12s | %-12s | %s\n" "$test" "$d_ref" "$d_vice" "$verdict"
done

printf -- "------------------------+--------------+--------------+--------------------------\n"
printf "%-23s | %-12s | %-12s |\n" "TOTAL (n=$n)" "$total_jac_ref" "$total_jac_vice"
