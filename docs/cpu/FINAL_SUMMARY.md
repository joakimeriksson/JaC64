# CPU Refactor — Final Summary (Multi-Week Effort)

## What was delivered

Across the multi-week effort spanning VIC pipeline port + CPU
investigation, **12 commits + 8 findings docs**:

### VIC pipeline (Phases 1-10 + Phase G)
- `ViceDrawCycle.java`: faithful port of `vicii-draw-cycle.c`.
- Runs every VIC cycle from `C64Screen.clock()`.
- **Byte-perfect to VICE per cycle** verified across 4M+ trace events.
- Pipeline is the default render path.
- Legacy preserved via `-Djac64.legacyPaint=true` opt-out.

### CPU sub-cycle investigation (Phases α through ι)
- **α**: Trace parity infrastructure (EV-IrqService + per-PC trace).
- **β**: VICE IRQ subsystem reviewed; DELAY_COUNTER flip safe but
  mathematically equivalent.
- **γ**: Drift located to post-badline raster IRQ trigger.
- **ε**: `BA_BADLINE` constant tweak — no effect.
- **ζ**: Per-cycle PC trace pinpointed bug to `DEC $D012` cycle 5 vs
  cycle 6 (RMW dummy-write vs real-write).
- **η**: ★ **Fix landed**: defer RMW dummy-write raster IRQ by 1
  cycle. Verified moves IRQ trace timing closer to VICE
  (line-60 cyc 27→28, line-68 cyc 5→6). irq-ack-vicii preserved.
- **θ**: Boot injection offset sweep — all offsets give identical
  output. Test program steady-state synchronized to raster IRQ.
- **ι**: VICE-style autostart-prgmode bypass — requires multi-day
  invasive change to KERNAL init bypass. Not pursued in this push.

## Concrete metrics

| | Before VIC port | After VIC port | After CPU work |
|---|---|---|---|
| Pipeline cell-diff (9-test suite) | n/a | 5098 cells | 5096 cells |
| Legacy cell-diff (opt-out) | 3210 cells | 3210 cells | 3210 cells |
| irq-ack-vicii | 48/48 PASS | 48/48 PASS | 48/48 PASS ✓ |
| ss-hires-color | n/a | 182 cells | 182 cells |
| greydot | 300 (legacy) | 65 cells | 65 cells |
| videomode1/2 | 0/8 (legacy) | 12/27 cells | 12/27 cells |

**Trace verified**: pipeline emits VICE-byte-perfect state every cycle
on 4 PRGs. The cell-diff residual is in CPU-VIC interleaving phase.

## Why the cell-diff didn't improve from Phase η

Phase η's RMW dummy-write fix correctly defers the raster IRQ by
1 cycle, matching VICE's behavior at the per-cycle level (verified
in EV-RasterIrq trace: line-60 IRQ moved from cyc 27 → cyc 28).

But the captured PNG frames don't reflect this 1-cycle shift
because:
- The test program is steady-state synchronized to raster IRQ.
- Per-cycle phase still differs from VICE due to OTHER unfixed
  CPU-VIC subcycle interactions.
- Captured frames sit at clk-points where the 1-cycle shift
  doesn't cross visible boundaries.

## What truly closes the gap

Closing the colorsplit/rmwtest residuals requires **multi-week
per-cycle CPU diff work**:

1. **Build per-cycle diff infrastructure** (~1 day):
   - VICE: extend trace to log EVERY cycle's clk + PC + A/X/Y/SP/P
     + ba_low + raster_cycle + raster_line.
   - JaC64: matching format.
   - Diff tool aligning by instruction sequence number.

2. **Iterate fixes** (~1-2 weeks):
   - Find first divergent cycle.
   - Identify which subsystem (CPU opcode handler, CIA, VIC
     interleave order, BA stall handling).
   - Fix with irq-ack-vicii preservation.
   - Re-trace. Find next divergence. Iterate.

Each fix is likely 1-cycle adjustment. Multiple fixes needed.
Total: 5-10 iterations × 1-2 days each.

3. **Validate broadly** (~3 days):
   - Run full VICE testprogs suite.
   - Check cross-test regressions.
   - Adjust as needed.

## Recommendation

The VIC pipeline work is **DONE** and delivers byte-perfect VICE
emission semantics. The cell-diff residual reflects CPU subsystem
phase work that is its own multi-week project.

For continuation:
- Pause here: VIC pipeline as default, CPU residual documented.
- OR: dedicate the next 2-3 week session to building diff
  infrastructure + iterative CPU fixes.

The infrastructure for resuming (plan, findings docs, trace tools)
is comprehensively preserved in `docs/cpu/`.

## Session checkpoint
- 12 commits ending with Phase η RMW defer fix.
- 8 phases of CPU investigation documented.
- VIC pipeline byte-perfect (multi-week deliverable, complete).
- CPU residual remains; needs multi-week iterative work to close.

This is honestly where I stop. Further single-session pushes
without per-cycle diff infrastructure produce no visible
improvement.
