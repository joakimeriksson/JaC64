# Phase 9.A: Measurement-only phase analysis

## Method
Compare JaC64 and VICE x64sc absolute-clock timing of `irq_handler`
entry (`PC=$0ab1`) across all 48 frames of the irq-ack-vicii test
run. Both emulators executing identical code; baseline offset
established at first frame; per-frame offset drift measured.

## Result

**Frames 0-5 (STA RASTER tests): 100% match.** JaC64 and VICE have
identical per-frame deltas. Offset stable at 3872233.

**Frames 6+ diverge.** Per-frame deltas differ by ±1 to ±3 cycles
between JaC64 and VICE. Cumulative offset wobbles in [3872231,
3872235] range across the test run.

**At slot 5 LDA SS-COL (frame 43): cumulative offset = 3872235
(= +2 from baseline).** This 2-cycle drift accounts for JaC64's
SSCol IRQ entry landing at PC=$ad3 (1 NOP earlier than VICE's
$ad4).

## Critical signal: divergence triggered by RMW or read

| testset      | match? |
|--------------|--------|
| STA RASTER   | all match |
| INC RASTER   | starts diverging at slot 5 |
| ASL RASTER   | diverges throughout |
| LDA RASTER   | diverges throughout |
| STA SS-COL   | mostly matches (sprites + STA = no problem) |
| INC SS-COL   | diverges |
| ASL SS-COL   | diverges |
| LDA SS-COL   | diverges (slot 5 is the failing cell) |

The bug appears to be triggered by either:
- RMW dummy-write timing on $D019 (INC, ASL).
- $D019 READ Phi1/Phi2 ordering (LDA).

STA $D019 has neither — it just writes once, no RMW dummy.

## Conclusion (measurement-only)

The "VIC raster_cycle alignment" hypothesis from Phase 9 doc is
**partially right but not the full story**. The 1-2 cycle phase
drift is NOT a fixed boot-time offset; it accumulates from
specific $D019 RMW + read interactions during INC/ASL/LDA tests.

**Real bug candidates (next sessions to investigate):**
1. INC $D019 RMW dummy-write cycle: JaC64 may write before VICE,
   shifting subsequent VIC-cycle by 1.
2. LDA $D019 read Phi1/Phi2 ordering: -Djac64.vicD019Phi2 fix
   only covers writes; reads may have their own ordering issue.
3. ASL $D019 same pattern as INC.

## Why STA SS-COL slot 5 passes but LDA SS-COL slot 5 fails

STA SS-COL slot 5 (frame 25) = match. The test cell `***-**` for
STA RASTER slot 4 is the previously-fixed cell (commit 7b5b31a).

LDA SS-COL slot 5 (frame 43) = +2 cycle cumulative drift. The
cumulative drift from earlier INC/ASL/LDA tests pushes the LDA
slot 5 LDA $D019 read into a cycle where JaC64's SSCol fire and
the LDA read collide on the same cycle.

## Phase 9.2 fix attempt (rejected — reverted)

Tried a `vbeamForCpuRead` 1-cycle lag for $D012/$D011 reads (gated
by `-Djac64.vicD012Lag`). Hypothesis: VICE increments raster_line
at raster_cycle 0 (= JaC64 vicCycle (-1) per the case-N=VICE-(N+1)
convention), so JaC64's vbeam transition appears 1 cycle EARLIER
to CPU reads than VICE's; lag the read by 1 cycle.

**Result: BROKE more cells than it fixed.** Specifically:
- Row 00 (RASTER reference): col 5 changed from `*` → `-`
- Row 01 (RASTER actual STA slot 5): `aaaa..` → `aaaaa.`
  (handler_3 fired in slot 5 with the fix, but didn't without)
- Row 03 (LDA SS-COL row): still 5 D's instead of 4

**Root cause of the regression:** handler_2's `lda $d012; cmp $d012;
beq` idiom at $0ae6/$0ae9/$0aec is *specifically designed* to
compensate for line-transition-during-handler timing. Changing the
$D012 read timing changes the BEQ outcome, which adds/removes
1 cycle in handler_2, which shifts the test instruction position,
which changes whether handler_3 fires before or after the test
read. Net: the regression is an indirect cascade.

**Empirical: JaC64 BEQ NEVER taken (50/50 frames). VICE BEQ taken
in 18/50 frames.** JaC64's `lda $d012; cmp $d012` always returns
different values (line transitions in window). VICE sometimes
returns equal.

Per the asm intent, BEQ-taken adds 1 cyc to compensate for
line-transition-during-handler. JaC64 never compensates — JaC64's
handler_2 is consistently 1 cycle FASTER than VICE in frames where
VICE compensates. This is the slot-spacing drift's mechanism.

The right fix is not to change $D012 read timing alone — it has
to make JaC64 ALSO take BEQ in the same frame pattern as VICE
(18/50). This requires aligning JaC64's frame timing pattern to
VICE's, which means fixing the underlying cycle accuracy at a
deeper level (the cumulative phase drift between IRQs).

**Status:** Reverted. Baseline 47/48 restored.

## Phase 9.3 fix attempt (also rejected — reverted)

Tried `lastLine = cpu.cycles + 1` at reset (gated by
`-Djac64.vicLastLineShift`) to shift JaC64's entire VIC line
boundary by +1 cycle. Hypothesis: shift everything later by 1
cycle so vbeam transition aligns with VICE's raster_line transition.

**Result: NO observable change.** BEQ still 0/50 taken (vs VICE
18/50). Test still 47/48. The lastLine shift uniformly shifts
ALL VIC events together (line transition, sprite paint, raster
IRQ, BA-low) by 1 cycle — relative timing of CPU instructions
vs line transitions is preserved.

**Conclusion:** The bug is not a uniform clock-phase offset.
JaC64 and VICE process the SAME VIC events at the SAME relative
positions per frame. The divergence comes from somewhere else —
likely BA-low absorption alignment, or a per-cycle CPU/VIC
interaction that's subtly different between the emulators.

## Phase 9.4 EV-RdD012 trace diff (definitive measurement)

Added `EV-RdD012 clk=N val=V line=L cyc=C pc=$X` event to both
emulators (JaC64 in `C64Screen.java` $D012 read; VICE in
`viciisc/vicii-mem.c` `d01112_read`). Ran `irq-ack-vicii.prg`
on both, diffed first 5 events:

```
JaC64: clk=7021571 val=69 line=69 cyc=31 pc=$ac3   ← LDX in handler
JaC64: clk=7021663 val=70 line=70 cyc=60 pc=$ae9   ← LDA in handler_2
JaC64: clk=7021667 val=71 line=71 cyc=1  pc=$aec   ← CMP in handler_2

VICE:  clk=3660394 val=69 line=69 cyc=31 pc=$ac0   ← LDX in handler
VICE:  clk=3660484 val=70 line=70 cyc=58 pc=$ae6   ← LDA in handler_2
VICE:  clk=3660488 val=70 line=70 cyc=62 pc=$ae9   ← CMP in handler_2
```

**Smoking gun:** Both emulators read `val=70` (line 70) for the
LDA, but at different cycles within the line:
- JaC64 LDA at line 70 cyc=60, CMP at line 71 cyc=1 (LDA-CMP
  window straddles the line transition → BEQ NOT TAKEN)
- VICE LDA at line 70 cyc=58, CMP at line 70 cyc=62 (window
  entirely within line 70 → BEQ TAKEN, both read 70)

**JaC64's CPU position within the line is 2 cycles "later" than
VICE's at the same logical handler position.**

## Phase 9.4 fix attempt: lineShift (also rejected — reverted)

Tried shifting `lastLine` at reset by N cycles
(`-Djac64.vicLastLineShift=2`). Hypothesis: shift JaC64's line
boundaries forward by 2 cycles to align with VICE's.

**Result: no observable change in cyc value.** With shift=2,
the LDA STILL reads at cyc=60 (just at a different absolute clk).
Reason: shifting `lastLine` shifts BA-low absorption events with
it, so CPU stalls shift equally. Net relative position within
line is preserved.

**Diagnosis:** The CPU IS at cyc=60 within line in JaC64 vs
cyc=58 in VICE for the same logical instruction position
(same handler chain, same per-instruction cycles). The 2-cycle
discrepancy must come from a VIC-side event between IRQ delivery
and the LDA $D012 — likely:

1. **Raster IRQ fire cycle within line**: JaC64 fires raster IRQ
   2 cyc later within line than VICE. CPU enters handler 2 cyc
   later. All subsequent code lands 2 cyc later within line.
2. **IRQ delivery overhead**: JaC64's IRQ entry might consume
   2 extra cycles vs VICE in some path.
3. **BA-low absorption bias**: certain BA-low events may consume
   different cycle counts between emulators.

The next investigation should compare RASTER IRQ fire timing
(via a new `EV-RasterIrq clk=N rast=$L cyc=C` event in both
emulators) at the same logical frames, similar to the EV-RdD012
diff that pinpointed this 2-cycle discrepancy.

## Trace tooling (Phase 9.4 ships even though fix didn't)

`EV-RdD012` is now permanently enabled in both emulators when
`-Djac64.traceVicCycle=true` (JaC64) and the build of
`tools/vice-trace-patches/vice_trace_patches.diff` (VICE). This
gives byte-for-byte $D012 read comparison — invaluable for
future cycle-accuracy work since handler_2's `lda $d012; cmp
$d012; beq` idiom is the canary for VIC-line-vs-CPU-position
alignment.

## Phase 9.5: EV-RasterIrq trace diff

Added `EV-RasterIrq clk=N rast=L cyc=C pc=$X` event to both
emulators (JaC64 in `triggerRasterIrq`; VICE in
`vicii_irq_raster_trigger`). Diffed across irq-ack-vicii.prg
runs. Both emulators fire raster IRQ at `cyc=0` of the line
(= same line-relative timing).

**Key divergence**: JaC64 fires raster IRQ at line 74 in 25
RASTER-testset frames. VICE fires **0 times** at line 74.

```
                rast=69   rast=70   rast=74   rast=255
JaC64:            50        50        25       102
VICE:             24        24         0         1
```

In JaC64's RASTER frames, the test flow:
1. handler runs at line 69, sets $D012=70.
2. handler_2 runs at line 70, reads $D012 (val=71 line=71),
   adc #$03, sets $D012=74.
3. handler_2 runs delay/test code through line 74.
4. JaC64's raster IRQ at line 74 cyc 0 fires (during JMP
   $b14 in irq_ack_test1 — PC=$b4c at trace fire time).
5. handler_3 runs (sets $fb='*'), acks bit 0.
6. After handler_3 returns, irq_reset_frame runs:
   sta $d012=69, sta $d019=#$05.

In VICE's RASTER frames, no rast=74 fire. Possibilities:
- VICE's irq_reset_frame's sta $d012=69 happens BEFORE raster
  reaches line 74 → raster_irq_line was 69, not 74 → no match.
- Or VICE's `vicii.raster_irq_triggered` flag stays set across
  the line in some way.
- Or VICE's `if (!(vicii.irq_status & 0x1))` guard suppresses
  the trace because bit 0 was set from prior line's IRQ.

JaC64's trigger has NO `raster_irq_triggered`-style guard. It
fires whenever `rasterIrqClock <= cycles` in case 0 dispatcher.
This may explain the over-firing (rast=255 = 102 vs VICE 1).

## Status

Phase 9.5 confirmed:
1. Both fire raster IRQ at cyc=0 — line-relative timing OK.
2. JaC64 fires EXTRA raster IRQs (rast=74 in RASTER frames,
   rast=255 in idle loop) that VICE doesn't.
3. The extra rast=74 IRQ in JaC64 fires DURING the test
   instruction's JMP, possibly causing a cascading IRQ entry
   that shifts cycle accounting.

Open: whether the EXTRA fires are merely cosmetic (CPU services
them post-test, no effect on test cells) or causal for the
slot-5 LDA SS-COL failure. Further investigation needed.

## Hypotheses still open for future investigation

1. **BA-low absorption variation.** When sprite DMA enables (SS-COL
   testset), CPU stalls during BA-low cycles. JaC64 and VICE may
   handle the read/write timing of stalled cycles differently,
   causing per-frame timing variance.
2. **CIA timer interaction with raster IRQ.** Both emulators have
   raster IRQ enabled. CIA1 timer A also fires periodically. The
   relative phase of CIA timer ticks vs raster IRQ may shift
   handler entry timing.
3. **VIC IRQ acknowledge handling.** `lda $d019` (VICE) vs
   immediate ack (JaC64) for $D019 reads may differ subtly.

Each requires its own measurement-only investigation and a fix
candidate that doesn't break the handler_2 BEQ idiom or other
test cells. No clean win available without deeper architectural
review.
