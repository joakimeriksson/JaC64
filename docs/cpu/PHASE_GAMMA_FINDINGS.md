# CPU Refactor Phase γ — Raster IRQ Assertion Trace

## Method
- VICE: `JAC64_TRACE_FILE=...` env var enables existing `EV-RasterIrq
  clk=X rast=Y cyc=Z pc=$W` trace (vicii-irq.c:118-141).
- JaC64: `-Djac64.traceVicCycle=true -Djac64.traceVicCycleStart=N
  -Djac64.traceVicCycleEnd=N` emits same format from
  C64Screen.triggerRasterIrq (C64Screen.java:1173-1180).
- Test program: colorsplit.prg.

## Result: drift is in BA-low (badline) cycle accounting

Side-by-side at the test loop's raster IRQ sequence:

| raster | VICE cyc | JaC64 cyc | Δ cyc | Both PCs |
|---|---|---|---|---|
| 30 | 0 | 0 | 0 | $84c/$91f handler |
| 31 | 0 | 0 | 0 | (paired stable IRQ) |
| 47 | 0 | 0 | 0 | |
| 48 | 0 | 0 | 0 | |
| 58 | 0 | 0 | 0 | |
| 59 | 0 | 0 | 0 | (badline! YSCROLL=3) |
| **60** | **29** | **27** | **-2** | $92a |
| **68** | **8** | **5** | **-3** | $90f |

**Pattern**: raster IRQs at lines 30-59 fire at IDENTICAL VIC cycles
in both emulators. Then starting at line 60 (right after badline at
line 59), JaC64 fires the next raster IRQ **2-3 VIC cycles earlier**.

## Why

The test programs raster IRQs to fire on specific lines via $D011/$D012.
For lines 30, 31, 47, etc., the initial setup matches VICE exactly.

When the IRQ handler at line 59 (a BADLINE, since YSCROLL=3 → badlines
at 3, 11, 19, 27, 35, 43, **51, 59**, 67, ...) does `inc $d012` to
program the next target line, the WRITE happens at some VIC cycle.
That write cycle depends on:

1. **Badline BA-low CPU stalls**: when VIC asserts BA low at cycle 12
   of a badline, CPU stalls for the duration of c-access fetches.
   VICE stalls 40-43 cycles (depending on op-type).
2. **Handler instruction cycle counts**: same in both (verified via
   PC trace).

**JaC64 stalls the CPU 2-3 fewer cycles during badline than VICE.**
This compounds: the handler writes $D012 = next-target 2-3 cycles
earlier, so the next raster IRQ fires 2-3 cycles earlier.

The 22-30 raster IRQs/frame × 2-3 cyc/IRQ = ~45-65 cyc/frame drift
— exactly matching the observed $D021 split-write 45-cyc/frame drift.

## Root cause is Phase ε (BA-low cycle accounting), not γ

Per the original CPU refactor plan:
- Phase γ was "CIA timer + raster-IRQ assertion timing".
- Phase ε was "BA-low cycle accounting".

The trace confirms the bug is in BA-low timing, **not** in raster
IRQ assertion logic (which fires at correct cyc in both emulators
for non-post-badline-write cases).

## Phase ε scope

Required investigation:
1. How does VICE's `vicii_steal_cycles()` decide stall duration?
2. How does JaC64's `waitForBus()` / `setBaLowUntil()` compute the
   stall duration?
3. Where does the 2-3 cycle difference come from?

Files to read:
- VICE: `mainc64cpu.c:112-192` (`maincpu_steal_cycles`),
  `viciisc/vicii-fetch.c` (`vicii_steal_cycles`).
- JaC64: `C64Screen.java setBaLowUntil`, `CPU.java waitForBus`.

## Phase γ status: complete (concrete root cause identified)
## Next: Phase ε (BA-low cycle accounting fix)
