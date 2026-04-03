# VIC-II Timing Notes

## Current State

This tree is closer to VICE on FLD-heavy demos than the baseline, but it is
not clean yet. The current result on `scrollit.prg` is:

- The demo no longer starts the effect many raster lines early.
- The major raster IRQ chain is now on the right lines.
- The remaining mismatch is a small CPU/VIC timing drift, not a gross badline
  bug.

The visible symptom is that the FLD area is recognizable, but still not fully
continuous like VICE.

## Fixes Landed In This Tree

### CPU / IRQ side

- BA stalls now affect CPU writes as well as reads.
- VIC IRQ release to the CPU is delayed instead of dropping immediately.
- RMW dummy writes are tracked so `INC $D019` does not acknowledge the IRQ too
  early.
- Exec tracing is available through:
  - `jac64.execTraceFrom`
  - `jac64.execTraceTo`
  - `jac64.execTraceFile`

### VIC-II side

- Raster IRQ scheduling is based on a scheduled compare clock instead of a
  simple line equality test.
- `$D019` acknowledge handling now matches the real late-ack cases much better.
- Mid-line badline start uses the current VIC cycle to derive the fetch column,
  which removed a one-column FLD lag.
- `BA_SP4` is currently `SCAN_RATE + 5`; this matched the tested demo better
  than the previous value.
- BA tracing is available through:
  - `jac64.baTrace`
  - `jac64.baTraceFrom`
  - `jac64.baTraceTo`
  - `jac64.baTraceFile`

## Measured Mismatch

The remaining drift shows up before the first visible IRQ body, not inside the
main handler body itself.

Representative sample from the same execution state (`A=$0f`, `X=$03`,
`Y=$40`):

### JaC64

```text
EXEC pc=$0a2b vbeam=10 cyc=49
EXEC pc=$0a2d vbeam=10 cyc=52
EXEC pc=$0a2b vbeam=11 cyc=10
EXEC pc=$0b00 vbeam=11 cyc=17
```

### VICE

```text
#1 (Trace  exec 0a2b)   10/$00a,  49/$31
#1 (Trace  exec 0a2d)   10/$00a,  52/$34
#1 (Trace  exec 0a2b)   11/$00b,  11/$0b
#1 (Trace  exec 0b00)   11/$00b,  21/$15
```

So the current JaC64 tree still reaches `$0b00` about 4 cycles early in that
sample.

## What The Trace Work Showed

### The pre-IRQ loop is correct

Both emulators are in the same wait loop before the IRQ:

- `$0a2b`: `LDA $09`
- `$0a2d`: `BNE $0a2b`

That means the remaining mismatch is not from taking a different control path.

### The drift starts before the final wait loop

Tracing back from the previous IRQ showed JaC64 already one cycle early just
after returning through `$106b` (`RTI`) and then drifting further before the
next IRQ entry.

This is why more generic IRQ-entry fixes were not kept: they did not move the
sample in the right direction.

### The BA trace narrowed the next suspect

The new BA trace shows the CPU repeatedly getting released at line start and
then re-stalled almost immediately on top-border/offscreen lines:

```text
BA-WAIT-END   vbeam=10 cyc=0
BA-SET src=SPR5 vbeam=10 cyc=1
BA-WAIT-START until=...
BA-SET src=SPR6 vbeam=10 cyc=3
BA-SET src=SPR7 vbeam=10 cyc=5
BA-WAIT-END   vbeam=10 cyc=10
```

and later in the same line:

```text
BA-SET src=SPR0 vbeam=10 cyc=54
BA-WAIT-START until=...
BA-SET src=SPR1 vbeam=10 cyc=56
BA-SET src=SPR2 vbeam=10 cyc=57
BA-SET src=SPR3 vbeam=10 cyc=61
BA-SET src=SPR4 vbeam=10 cyc=62
```

That makes the next likely bug very specific: BA ownership across the
`311 -> 0 -> 10` wrap, especially whether JaC64 is giving the CPU an extra
usable cycle at cycle 0 where VICE still keeps it blocked.

## Things Tried And Rejected

These experiments were tested and backed out because they did not improve the
measured sample or the frame diff:

- Delaying IRQ start until BA release.
- Treating the remaining mismatch as a generic IRQ-entry timing problem.
- Shifting `SP5-SP7` later.
- Preserving `baLowUntil` across the offscreen line start by removing the
  `cycle 0` clear.

## Suggested Next Step

The next pass should fix BA source ownership, not raster IRQ code.

Concrete plan:

1. Compare JaC64 BA release/reassertion against local VICE across raster
   `311 -> 0 -> 10`.
2. Verify whether the CPU should still be blocked at cycle 0 on those lines.
3. Adjust the sprite BA handoff once the exact disagreement is confirmed.
4. Re-check the same `$0a2b/$0a2d/$0b00` sample before touching anything else.

## Useful Commands

### Build

```sh
javac -encoding UTF-8 \
  com/dreamfabric/jac64/C64Screen.java \
  com/dreamfabric/jac64/CPU.java \
  com/dreamfabric/jac64/MOS6510Core.java \
  com/dreamfabric/jac64/VICConstants.java \
  TestRaster.java
```

### JaC64 trace run

```sh
java \
  -Djac64.traceDelayMs=18000 \
  -Djac64.captureFrames=0 \
  -Djac64.execTraceFrom=0x0a00 \
  -Djac64.execTraceTo=0x1100 \
  -Djac64.execTraceFile=/tmp/jac64_exec_ba.log \
  -Djac64.irqTrace=true \
  -Djac64.irqTraceFile=/tmp/jac64_irq_ba.log \
  -Djac64.baTrace=true \
  -Djac64.baTraceFrom=300 \
  -Djac64.baTraceTo=20 \
  -Djac64.baTraceFile=/tmp/jac64_ba_trace.log \
  -cp . TestRaster /tmp/scrollit.prg
```

### Matching VICE trace

Use local VICE with a monitor command file that traces the same PC range and
the relevant VIC register accesses. The exact command used during this pass was
the local `x64sc` build with `-monlog`, `-moncommands`, and `-limitcycles`.

## Files

- `com/dreamfabric/jac64/C64Screen.java`
- `com/dreamfabric/jac64/CPU.java`
- `com/dreamfabric/jac64/MOS6510Core.java`
- `com/dreamfabric/jac64/VICConstants.java`
- `TestRaster.java`
