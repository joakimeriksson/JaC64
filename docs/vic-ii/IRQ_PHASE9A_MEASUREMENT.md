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
2. LDA $D019 read Phi1/Phi2 ordering: -Djac64.viceD019Phi2 fix
   only covers writes; reads may have their own ordering issue.
3. ASL $D019 same pattern as INC.

## Why STA SS-COL slot 5 passes but LDA SS-COL slot 5 fails

STA SS-COL slot 5 (frame 25) = match. The test cell `***-**` for
STA RASTER slot 4 is the previously-fixed cell (commit 7b5b31a).

LDA SS-COL slot 5 (frame 43) = +2 cycle cumulative drift. The
cumulative drift from earlier INC/ASL/LDA tests pushes the LDA
slot 5 LDA $D019 read into a cycle where JaC64's SSCol fire and
the LDA read collide on the same cycle.

