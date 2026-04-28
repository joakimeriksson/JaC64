# irq-ack-vicii.prg — Phase 5-next: per-PC cycle diff for slot-spacing drift

Phase 5 (`docs/vic-ii/IRQ_ACK_PHASE5.md`) noted that LDA SS-COL slot 5
remains the last failing cell. This phase did the per-instruction
cycle diff that Phase 5 punted to a future session.

## Method

Per-PC cycle traces from both emulators:

```bash
# JaC64
java -Djac64.warp=true -Djac64.testRasterTime=12 \
     -Djac64.tracePcCycles=true -Djac64.tracePcStart=7700000 \
     -Djac64.tracePcEnd=8000000 \
     -Djac64.tracePcFile=/tmp/jac64_pc_lda.trace \
     -cp build/libs/JaC64.jar TestRaster .../irq-ack-vicii.prg

# VICE x64sc (per-PC trace patch in 6510dtvcore.c writes
# every emulateOp call's PC + clk to JAC64_PC_TRACE_FILE)
JAC64_PC_TRACE_FILE=/tmp/vice_pc_lda.trace \
JAC64_TRACE_FILE=/tmp/vice_irqack_full.trace \
  /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
  -warp -limitcycles 12000000 -autostartprgmode 1 \
  -autostart .../irq-ack-vicii.prg
```

## What we found

For each LDA SS-COL frame (F1=$fd=6 to F6=$fd=1), measured the
length of the chain from `irq_handler` entry (`$ab1 lda #<irq_handler_2`)
to `irq_handler_2` entry (`$ada lda #$01`):

| Frame | $fd | JaC64 chain | VICE chain | Diff |
|-------|-----|-------------|-----------|------|
| F1 | 6 | 64 | 64 | 0 |
| F2 | 5 | 64 | 64 | 0 |
| F3 | 4 | 62 | 62 | 0 |
| F4 | 3 | 64 | 62 | **+2** |
| F5 | 2 | 62 | 64 | **-2** |
| F6 | 1 | 64 | 62 | **+2** |

The chain length is **57 cycles of handler_1 body + 7 cycles of IRQ
vector dispatch = 64**, except when line $46 raster IRQ pends EARLIER
relative to handler_1's NOP loop, in which case handler_1 is preempted
at NOP $ad2 instead of $ad3 → 55 + 7 = 62.

Both emulators have this variation. They just disagree on WHICH frames
hit the early-preempt vs late-preempt path.

## Why the variation

handler_1's body after CLI ($ac7) has 3× ROR $02 (15 cycles) + 11 NOPs
(22 cycles) + RTI (6 cycles). Line $46 IRQ fires at $46 cyc=0 (raster
match). CPU samples IRQ at instruction boundary; vector dispatch
starts at the next opcode fetch.

If line $46 cyc=0 lands DURING NOP $ad2 → CPU finishes that NOP, then
checks IRQ at next boundary ($ad3 fetch). IRQ pending → vector
dispatches starting from $ad3 → handler_2 enters 7 cycles later.

If line $46 cyc=0 lands DURING NOP $ad3 → vector dispatch starts at
$ad4 → handler_2 enters 7 cycles later.

The +/-1 cycle in WHEN line $46 cyc=0 lands relative to handler_1's
NOP boundary determines the chain length.

JaC64 and VICE compute this 1-cycle alignment slightly differently
because of:

1. **IRQ sampling convention**: VICE's `interrupt_delay()` at the
   start of `CLK_INC` (in `c64/c64cpusc.c:47-50`) tracks when the IRQ
   line went low and schedules delivery. JaC64's `PHASE_A_IRQ_LATCH`
   (`MOS6510Core.java:188-199`) samples at fetchByte/writeByte and
   uses a 1-cycle propagation rolling history.

2. **Where in `vicii_cycle()` raster IRQ source is set**: VICE sets in
   `viciisc/vicii-cycle.c:489-496` immediately when `raster_line`
   transitions to `raster_irq_line`. JaC64 sets in `C64Screen.clock()`
   based on its case-numbering (which is off-by-one vs VICE's
   raster_cycle, per the WORKPLAN).

These are SMALL differences in IRQ-line-to-CPU-sample propagation,
sufficient to push the handler_1 preemption point 1 cycle earlier
or later in some frames.

## Why we're stopping here

The drift is a 1-cycle race in the IRQ delivery pipeline that has
NO clean fix. Options:

A. **Port VICE's interrupt_delay() exactly.** Major refactor of
   JaC64's IRQ handling in MOS6510Core.java + CPU.java. Multi-day,
   uncertain whether it'd produce identical results without other
   regressions.

B. **Apply a "1-cycle defer" compensation** for handler_1 preemption
   timing. This is exactly the anti-pattern WORKPLAN.md warns
   against ("cascading defers"). We won't.

C. **Accept the 1-cell drift** as a known limitation. The test
   produces 47/48 cells correct. The fix would require touching
   the IRQ pipeline core which has cross-cutting effects.

We pick C. Documented for future investigation if VIC-II accuracy
becomes critical for some specific demo.

## Where the missing cell lands

The remaining failing cell, **LDA SS-COL slot 5 ($fd=5, F2)**, has
chain length 64 in BOTH emulators (i.e., this slot's IRQ delivery
DOESN'T diverge). Yet F2 is the failing cell.

So the LDA reading $f4 vs $70 in F2 isn't from the chain-length
variance directly. It's from the CUMULATIVE shift across earlier
frames affecting where F2 actually lands within line $4a.

Looking at LDA $D019 cycles per frame:
- JaC64: 44, 45, 45, 47, 47, 49 (irregular: +1, +0, +2, +0, +2)
- VICE:  43, 44, 45, 46, 47, 48 (uniform +1)

Slots where JaC64 = VICE: F3 ($fd=4, both at cyc=45) and F5
($fd=2, both at cyc=47). Slots where JaC64 differs by +1: F1, F2,
F4, F6.

For F2 specifically: JaC64 at cyc=45 reads $f4 (SSCol bit set),
VICE at cyc=44 reads $70 (clear). The SSCol fire is at $4a cyc=45
in both emulators. So:

- VICE F2 LDA at cyc=44: reads BEFORE SSCol fires → $70.
- JaC64 F2 LDA at cyc=45: reads AT/AFTER SSCol fires → $f4.

Even though the PHI2 fix corrected STA $D019 at the same cyc=45,
LDA's read happens at the END of the LDA instruction (after VIC's
case-N runs) which already sees the SSCol bit. The PHI2-symmetric
fix for reads breaks RASTER (per Phase 5 doc).

The clean fix would be to make JaC64's per-frame timing IDENTICAL
to VICE's (no irregularity in LDA cycles per slot), which means
fixing the IRQ delivery pipeline. Same conclusion as Phase 5-next:
out of scope.

## Conclusion

Test stays at 47/48 cells. No clean 1-instruction fix exists.
Recorded in `docs/vic-ii/IRQ_ACK_PHASE5_NEXT.md` for posterity.
