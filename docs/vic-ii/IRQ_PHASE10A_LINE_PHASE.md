# Phase 10.A: Line transition phase delta — measurement

## Method

Added `EV-LineInc clk=N from=A to=B` event to BOTH emulators
(JaC64 in `C64Screen.java` chips.clock vbeam increment; VICE
x64sc in `viciisc/vicii-cycle.c` `vicii.raster_line++`).
Captured + diffed across irq-ack-vicii.prg test.

## Empirical findings

For the same logical instruction (handler_2's `lda $d012` at
$0ae6), comparing line-relative cycle position:

```
JaC64 LDA $D012 reads at line 70 cyc = 60 (some frames) or 59 (others)
VICE  LDA $D012 reads at line 70 cyc = 58 (some frames) or 59 (others)
```

JaC64's CPU is consistently **1-2 cycles LATER within line** than
VICE's at the same logical instruction. This is a CONSTANT phase
difference between JaC64's vbeam transition and VICE's raster_line
transition.

## Theoretical analysis (does NOT match empirical)

Per VICE source (`viciisc/vicii.c:285-291` vicii_reset):
- raster_cycle = 6 at reset.
- VICII_PAL_CYCLE(1) = 0 (line transition cycle).
- First vicii_cycle increments raster_cycle 6→7 at clk 1.
- raster_cycle wraps to 0 at clk 57 (= 1 + 56).
- VICE "line 1 cyc 0" first reached at maincpu_clk 57.
- VICE line 70 cyc 0 at clk -6 + 70*63 = 4404.

JaC64 (C64Screen.java reset):
- lastLine = cpu.cycles = 0 at boot.
- Case 62 dispatcher runs at vicCycle 62 = clk 62, lastLine += 63 → 63.
- vbeam transitions to 1 at vicCycle 0 of new line = clk 63.
- JaC64 "line 1 cyc 0" first reached at clk 63.
- JaC64 line 70 cyc 0 at clk 70*63 = 4410.

By this analysis, JaC64's lines start **6 cycles LATER** than VICE's
(relative to reset). But empirically JaC64's line phase is only
**1-2 cycles LATER** within the line at LDA. The 4-5 cycles of
discrepancy between theory and measurement is unaccounted for.

## Where the discrepancy hides

Possibilities:
1. **VICE's `interrupt_delay()` in CLK_INC** (mainc64cpu.c:97-110)
   uses OLD maincpu_clk, then maincpu_clk++, then vicii_cycle. JaC64's
   sampleIrqLine does similar but the EXACT order may shift IRQ
   delivery 1 cycle.
2. **Sprite Y-comparison + display flag** at cycle 55 may shift in
   JaC64 vs VICE, causing BA-low events to fire 1 cycle differently
   when sprites get enabled in SS-COL testset.

## Phase 10.A status

- Trace tooling: `EV-LineInc` event in both emulators. Permanent.
- Empirical phase delta: 1-2 cycles JaC64 later within line.
- Cause: not yet pinpointed. Theory suggests 6-cycle delta but
  empirical is 1-2.

## Phase 10.B: Boot anchor measured + lineAlign fix applied

Captured first 5 line transitions at boot in both emulators:

```
JaC64 (BEFORE fix):    clk=64, 127, 190, 253, 316
VICE:                  clk=63, 126, 189, 252, 315
                       ── 1 cycle delta — JaC64 transitions LATER
```

Root cause: JaC64's `cpu.cycles` is **1 at reset** (not 0), so
`lastLine = cpu.cycles` puts lastLine at 1. Subsequent
`lastLine += 63` keeps the +1 offset. VICE's first line transition
at clk 63 (after a 6-cycle reset state, raster_cycle=6 wrapping to 0
at clk 1+56=57 in theory but actual first wrap empirically at 63).

### Fix: `lastLine = cpu.cycles - 1` at reset (commit 49e41d0)

Gated by `-Djac64.vicLineAlign=true` (default ON). Empirically:
- JaC64 line transitions: clk=63, 126, 189, 252, 315 — **byte-for-byte
  match VICE**.
- CIA timer test: no regression (verified diff identical).
- irq-ack-vicii.prg: still 47/48 (slot 5 LDA SS-COL still fails).

### Why the fix doesn't fix slot 5

LDA $D012 cyc-within-line in handler_2 unchanged. Reason: shifting
`lastLine` also shifts BA-low events (`setBaLowUntil(lastLine + N)`),
which shifts CPU stalls equally. CPU clk and line both shift -1 →
cyc-within-line preserved.

This is the "uniform shift" trap from Phase 9.4. lineAlign is a
correctness improvement (lines now align with VICE) but slot 5
needs a *differential* fix.

### Hypotheses for slot 5 root cause (unsolved)

Per-instruction CPU cycles match (Phase 9.1). Frame periods match
(Phase 9.A frames 0-5). Line transitions now match (Phase 10.B).
Yet LDA $D012 reads cyc=60 in JaC64 vs cyc=59 in VICE.

This contradicts cycle-accurate equivalence. The 1-cycle delta
must come from one of:

1. **Different testset trace coverage**: JaC64 trace covers RASTER
   testset (no sprites); VICE trace covers SS-COL testset (sprites
   enabled). BA-low for sprite DMA may shift CPU position differently
   between RASTER (none) and SS-COL (lots). Need direct same-frame
   comparison.

2. **Subtle instruction-cycle quirk**: per-instruction cycles match
   for SLOT-5 chain, but earlier frames may have a 1-cycle drift
   in INC/ASL RMW or LDA $D019 read that accumulates.

3. **IRQ entry ordering**: handler entry takes 7 cycles in both,
   but JaC64's IRQ delivery may sample at a different cycle phase
   (per Phase 5/Phase 6 docs).

### Next phase: Phase 10.C — same-frame comparison

The data confusion in this Phase 10.B (RASTER vs SS-COL frames in
different traces) means we need to capture the SAME LOGICAL FRAME
in both emulators. Suggested approach:

1. Add `EV-FrameMark clk=N test_set=R/S slot=N` event in both that
   fires at handler entry, indexed by test phase. Diff frame N's
   complete event stream between emulators.
2. Run irq-ack-vicii.prg on both. Find the EXACT first frame where
   LDA $D012 cyc differs.
3. Look for divergent BA-low events or instruction-level cycle counts
   in just that one frame.

This narrows the search from "the whole test" to "exactly one
frame's divergence" — much more tractable.
