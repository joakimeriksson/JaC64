#!/bin/bash
# batch_diff.sh — Run JaC64 against VICE-testprogs and report cell-diff.
#
# Per-variant mode: for each VIC-II chip-variant reference, compute cell-diff
# across ALL applicable tests and sum. The variant with the lowest total
# tells us which chip JaC64 most closely emulates.

set -e

BUILD_DIR="${JAC64_BUILD:-/tmp/jac64-build}"
TESTPROGS="${VICE_TESTPROGS:-/Users/joakimeriksson/work/VICE-testprogs}"
JAC64_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Auto-build into BUILD_DIR unless caller set JAC64_SKIP_BUILD=1.
# Without this, stale .class files at /tmp/jac64-build silently shadow
# any source changes — wasted a 30-min bisect on 2026-05-23 chasing a
# "regression" that was just an unbuilt commit. javac is fast enough
# (~3s) that always-building is cheaper than the debugging cost when it
# silently doesn't.
if [ -z "$JAC64_SKIP_BUILD" ]; then
    if ! javac -encoding UTF-8 -d "$BUILD_DIR" -cp "$JAC64_ROOT" \
            "$JAC64_ROOT"/com/dreamfabric/jac64/*.java \
            "$JAC64_ROOT"/com/dreamfabric/c64utils/*.java \
            "$JAC64_ROOT"/resid/*.java \
            "$JAC64_ROOT"/TestRaster.java 2>/tmp/jac64_build.log; then
        echo "BUILD FAILED — see /tmp/jac64_build.log" >&2
        tail -5 /tmp/jac64_build.log >&2
        exit 1
    fi
fi

test_extra_flags() {
    case "$1" in
        colorsplit) echo "-Djac64.zeroScreenRam=true" ;;
        *) echo "" ;;
    esac
}

# Reference variants. Sentinel "_" = canonical (no suffix).
# PAL-only by design: JaC64 emulates 6569/8565 PAL. NTSC refs (-ntsc /
# -ntscold) are excluded — they're for the NTSC test PRG variants and
# would distort PAL totals.
VARIANTS=("_" "-8565" "-8565early" "-8565late")

default_tests=(
    screenpos colorsplit rmwtest modesplit vicii_reg_timing
    ss-exp-unexp-hires ss-pri-mc-exp ss-hires-color
)

tests=("$@")
[ ${#tests[@]} -eq 0 ] && tests=("${default_tests[@]}")

run_jac64() {
    local test=$1 prg=$2 extra
    extra=$(test_extra_flags "$test")
    rm -f /tmp/jac64_test_frame_*.png 2>/dev/null || true
    # captureFrames=2 (the actual property — was framesToCapture which is
    # not read by TestRaster, so it fell back to default 30 and we'd pick
    # frame 29 = many seconds of warp later, giving misleading cell-diffs
    # for tests whose output evolves over time (greydot, fetchsplit, ...).
    # Cap at 2 and use frame_001 below.
    java -Djac64.warp=true -Djac64.captureFrames=2 -Djac64.captureOnDone=true \
         -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
         -Djac64.zeroColorRam=true $extra ${JAC64_EXTRA_FLAGS:-} \
         -cp "$BUILD_DIR:$JAC64_ROOT" TestRaster "$prg" \
         > "/tmp/${test}.log" 2>&1
}

cell_diff() {
    python3 "$JAC64_ROOT/tools/vice-compare/png_cell_diff.py" \
        "$1" "$2" 2>&1 \
        | grep "Total cell-diffs" \
        | awk -F'[: /]' '{print $4}'
}

# Print header.
printf "%-23s" "Test"
for v in "${VARIANTS[@]}"; do
    label="${v:1}"
    [ "$v" = "_" ] && label="canon"
    printf " | %-12s" "$label"
done
echo
dash() { printf '%.0s-' $(seq 1 "$1"); }
printf "%s" "$(dash 23)"
for v in "${VARIANTS[@]}"; do printf -- "-+%s" "$(dash 13)"; done
echo

# Run each test, diff against each variant ref immediately (so frame_010 is
# still current). Track rows + per-variant totals.
TESTS_OK=()
for test in "${tests[@]}"; do
    prg=$(find "$TESTPROGS" -name "${test}.prg" 2>/dev/null | head -1)
    if [ -z "$prg" ]; then continue; fi
    run_jac64 "$test" "$prg"
    # Pick frame_001: 1 wall-clock second of warp past SYS-jump. That's
    # ~30M emulated cycles in, which is well past test completion for
    # every VICE-testprogs PRG. Falls back to LATEST if frame_001 absent.
    snap_src="/tmp/jac64_test_frame_001.png"
    [ ! -f "$snap_src" ] && snap_src=$(ls -1 /tmp/jac64_test_frame_*.png 2>/dev/null | sort | tail -1)
    snap="/tmp/jac64_${test}_frame010.png"
    if [ -z "$snap_src" ]; then
        echo "WARN: no frames captured for $test" >&2
        continue
    fi
    cp "$snap_src" "$snap"
    TESTS_OK+=("$test")

    printf "%-23s" "$test"
    for v in "${VARIANTS[@]}"; do
        suffix=""; [ "$v" != "_" ] && suffix="$v"
        ref=$(find "$TESTPROGS" -path "*references*" -name "${test}.prg${suffix}.png" 2>/dev/null | head -1)
        if [ -z "$ref" ]; then
            printf " | %-12s" "-"
        else
            cells=$(cell_diff "$ref" "$snap")
            printf " | %-12s" "${cells:-?}"
        fi
    done
    echo
done

# Per-variant totals (counting only tests where the variant ref exists).
printf "%s" "$(dash 23)"
for v in "${VARIANTS[@]}"; do printf -- "-+%s" "$(dash 13)"; done
echo
printf "%-23s" "TOTAL (n tests)"
for v in "${VARIANTS[@]}"; do
    suffix=""; [ "$v" != "_" ] && suffix="$v"
    sum=0; n=0
    for test in "${TESTS_OK[@]}"; do
        ref=$(find "$TESTPROGS" -path "*references*" -name "${test}.prg${suffix}.png" 2>/dev/null | head -1)
        [ -z "$ref" ] && continue
        snap="/tmp/jac64_${test}_frame010.png"
        [ ! -f "$snap" ] && continue
        cells=$(cell_diff "$ref" "$snap")
        [ -z "$cells" ] && continue
        sum=$((sum + cells)); n=$((n + 1))
    done
    printf " | %-12s" "${sum}(n=${n})"
done
echo
