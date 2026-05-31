# CPU Refactor Phase α — Trace Parity Findings

## Setup
- VICE trace flags (already in source per `reference_vice_local_fork.md`):
  - `JAC64_TRACE_FILE` → enables EV-* events including EV-IrqService.
  - `JAC64_PC_TRACE_FILE` → per-instruction PC trace.
- JaC64 trace flags:
  - `-Djac64.traceIrqService=true -Djac64.tracePcFile=...`
  - `-Djac64.tracePcCycles=true -Djac64.tracePcFile=...`
  - `-Djac64.tracePcStart=N -Djac64.tracePcEnd=N` (clk window).

## Findings on colorsplit.prg

### PC sequence in test idle loop
**Identical sequence in both emulators** at the idle loop:
- PC=$E5CD: LDA zp (3 cyc)
- PC=$E5CF: STA zp (3 cyc)
- PC=$E5D1: STA abs (4 cyc)
- PC=$E5D4: BEQ (2-3 cyc)
- loop back

Per-instruction cycle counts match VICE byte-for-byte.

### IRQ entry frequency
- VICE: IRQ every ~46 cycles in idle loop (within 100-cycle snapshot).
- JaC64: IRQ less frequently (test loop runs 1700+ cycles between
  IRQs in trace window 6000000-6001696).

### IRQ entry delay (clk - irq_clk)
| | VICE | JaC64 |
|---|---|---|
| Measurement | clk - irq_clk_asserted | clk - irqCycleStart |
| Range | 3-4 cycles | 2-3 cycles |
| Apparent shift | — | -1 cycle |

**Caveat**: VICE measures from raw IRQ assertion clk; JaC64 measures
from `irqCycleStart = cycles + IRQ_DELAY` (= assertion + 2). So
JaC64's "delay=2" actually corresponds to handler-entry at
assertion + 4 cycles, vs VICE's clean assertion + 3. Net: JaC64
fires ~1 cycle LATER than VICE, not earlier as initially read.

### Frame-level drift
- VICE writes $D021 split at rast=$106 cyc=36/56 each frame.
- JaC64 writes $D021 split at rast=$107 cyc=18/38 each frame.
- ~+45 cycles per-frame drift (JaC64 later than VICE).

## What this means for Phase β
The IRQ entry delay alone (1 cyc × 22 IRQs/frame = 22 cyc) accounts
for ~half of the 45-cyc drift. Other ~23 cyc comes from:
- CIA timer underflow → IRQ assertion clk drift.
- VIC raster-compare → IRQ line assertion clk drift.
- Possibly RMW handling around `INC $D019` (the stable-IRQ pattern).

Phase β should:
1. Port VICE's exact `interrupt_check_irq_delay` semantics.
2. Verify the delay measurement direction (assert+0 vs assert+2).
3. Validate irq-ack-vicii 48/48 after each change.

## Tried flag combinations (no improvement)
| Flags | colorsplit cell-diff |
|---|---|
| default | 2676 |
| `-Djac64.vicIrqDelayCounter=true` | 2676 |
| `-Djac64.irqAssertPreIncrement=true` | 2692 |
| both | 2676 |

The existing tunable flags don't close the gap. Phase β needs
deeper alignment than flag-toggling.

## Phase α exit
✅ Trace infrastructure in place in both emulators.
✅ First divergent event identified: IRQ entry delay.
✅ Concrete data for Phase β to act on.

## Recommendation for Phase β
**Recommend doing Phase β in a focused fresh session**, not rushed
in the same session as Phase α. Reasons:
1. `irq-ack-vicii` passes 48/48 today. Phase β changes to
   `MOS6510Core.doInterrupt` risk breaking this — needs careful
   incremental testing.
2. The delay-measurement direction ambiguity (assert+0 vs assert+2)
   needs to be resolved with VICE source review BEFORE editing.
3. CIA timer drift (γ contribution) might be a larger fraction
   than IRQ entry drift; Phase γ trace before β might better
   prioritize.

## Status: Phase α complete. Phase β queued for next session.
