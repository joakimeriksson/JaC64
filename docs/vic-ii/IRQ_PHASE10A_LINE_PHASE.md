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
1. **JaC64's reset takes different cycles than expected.** `cpu.reset()`
   may consume cycles before `lastLine = cpu.cycles` is set.
2. **JaC64 detSysJump pauses differ from VICE autostart.** TestRaster's
   `cpu.pauseAtCycle = 7000000` followed by `jumpToSubroutine` might
   leave VIC in a state that differs from VICE's natural autostart
   resumption.
3. **VICE's `interrupt_delay()` in CLK_INC** (mainc64cpu.c:97-110)
   uses OLD maincpu_clk, then maincpu_clk++, then vicii_cycle. JaC64's
   sampleIrqLine does similar but the EXACT order may shift IRQ
   delivery 1 cycle.
4. **Sprite Y-comparison + display flag** at cycle 55 may shift in
   JaC64 vs VICE, causing BA-low events to fire 1 cycle differently
   when sprites get enabled in SS-COL testset.

## Phase 10.A status

- Trace tooling: `EV-LineInc` event in both emulators. Permanent.
- Empirical phase delta: 1-2 cycles JaC64 later within line.
- Cause: not yet pinpointed. Theory suggests 6-cycle delta but
  empirical is 1-2.

## Phase 10.B next step (Phase 10.A continues into B)

The 4-5 cycle "missing" between theory and empirical points to
something in the JaC64 boot/init sequence. Suggested:

1. Add `EV-VicState clk=N vbeam=L vicCycle=C lastLine=K` to JaC64,
   fired at the FIRST chips.clock after reset and at every vicCycle
   0 transition. Compare to VICE's equivalent at same logical clks.
2. Determine the EXACT physical clock where each emulator first
   "starts" its line counting. This is the boot anchor.
3. Once anchor is known, the constant phase delta has a definitive
   source. Fix is then a matter of shifting JaC64's lastLine init or
   VIC dispatcher by exactly that delta.

The fix CANNOT be a uniform `lastLine + N` shift (Phase 9.4 verified
this fails — BA-low absorption shifts equally). Must be a
*differential* fix: shift VIC line phase WITHOUT shifting BA-low
event scheduling. Possibly: change `setBaLowUntil` constants in
`VICConstants.java` rather than lastLine.

