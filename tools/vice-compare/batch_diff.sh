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

test_extra_flags() {
    case "$1" in
        colorsplit) echo "-Djac64.zeroScreenRam=true" ;;
        *) echo "" ;;
    esac
}

# Reference variants. Sentinel "_" = canonical (no suffix).
VARIANTS=("_" "-8565" "-8565early" "-8565late" "-ntsc" "-ntscold")

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
    java -Djac64.warp=true -Djac64.framesToCapture=2 -Djac64.captureOnDone=true \
         -Djac64.injectAtCycle=7005254 -Djac64.detSysJump=true \
         -Djac64.zeroColorRam=true $extra \
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
    snap="/tmp/jac64_${test}_frame010.png"
    cp /tmp/jac64_test_frame_010.png "$snap"
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
        cells=$(cell_diff "$ref" "/tmp/jac64_${test}_frame010.png")
        [ -z "$cells" ] && continue
        sum=$((sum + cells)); n=$((n + 1))
    done
    printf " | %-12s" "${sum}(n=${n})"
done
echo
