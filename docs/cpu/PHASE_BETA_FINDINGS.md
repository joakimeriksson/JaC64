# CPU Refactor Phase β — IRQ Delay Logic Findings

## What was tested
- Default: `VICE_IRQ_DELAY_COUNTER=false` (cycle-comparison mode).
- Modified: `-Djac64.vicIrqDelayCounter=true` (counter-based mode).

## Suite results

| Test | DC=OFF | DC=ON |
|---|---|---|
| greydot | 65 | 65 |
| ss-hires-color | 182 | 182 |
| ss-exp-unexp-hires | 314 | 314 |
| ss-pri | 179 | 179 |
| ss-xpos | 160 | 160 |
| colorsplit | 2676 | 2676 |
| videomode1 | 12 | 12 |
| videomode2 | 27 | 27 |
| rmwtest | 1481 | 1481 |
| **TOTAL** | **5096** | **5096** |

**Zero change.** irq-ack-vicii also passes (border=green=PASS) in
both modes.

## Conclusion
The two IRQ-delay-check implementations are **mathematically
equivalent** for the current JaC64 code path:
- Cycle-based: `cycles >= irqCycleStart` where `irqCycleStart = assert_clk + 2`.
- Counter-based: `irq_delay_cycles >= 2` where the counter is
  incremented per CLK_INC after IRQ assertion.

Both fire IRQ entry at exactly `assert_clk + 2` (or +3 for branch).

## Implication for next phase
The IRQ-entry drift identified in the trace data is NOT from the
CPU's IRQ-delay mechanism. The 1-cycle measurement discrepancy
must come from one of:

1. **IRQ ASSERTION timing**: when VIC sets `irq_clk` vs when JaC64
   calls `triggerRasterIrq`. Maybe the raster compare fires at
   different VIC cycle in the two emulators.
2. **CIA timer underflow timing** (= Phase γ scope).
3. **Subtle CPU instruction boundary behavior**: how `instructionStartPC`
   is captured relative to `cycles` at IRQ check time.

## Recommendation
**Skip wholesale Phase β default flip** — `DELAY_COUNTER=ON` is
equivalent to current default, so no point flipping the flag.

**Pivot to Phase γ** — trace VIC raster IRQ assertion clk +
CIA timer underflow clk side-by-side with VICE. The actual drift
is likely there.

## Files referenced (no edits made)
- `com/dreamfabric/jac64/MOS6510Core.java:150-152` (irqCycleStart setup)
- `com/dreamfabric/jac64/MOS6510Core.java:241-258` (counter increment)
- `com/dreamfabric/jac64/MOS6510Core.java:471-477` (per-instruction check)

## Phase β status: complete (no edit needed — equivalent paths)
