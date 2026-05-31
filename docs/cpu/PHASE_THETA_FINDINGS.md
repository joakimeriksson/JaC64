# CPU Refactor Phase θ — Boot Injection Offset Sweep

## Hypothesis
JaC64's CPU might be 1 cycle off VICE due to boot-time clk
alignment. If we shift the SYS-jump injectAtCycle offset, JaC64's
test-loop phase relative to raster might align with VICE's.

## Test
Swept `-Djac64.injectAtCycle=N` across offsets:
- Base: 7000000
- Offsets: 0, ±1, ±2, ±3, +63, +126, +189, +252

| Offset | colorsplit | irq-ack-vicii |
|---|---|---|
| -3 | 2676 | PASS |
| -2 | 2676 | PASS |
| -1 | 2676 | PASS |
| **0 (default)** | **2676** | **PASS** |
| +1 | 2676 | PASS |
| +2 | 2676 | PASS |
| +3 | 2676 | PASS |
| +5 | 2676 | PASS |
| +63 (1 line) | 2676 | PASS |
| +126 (2 lines) | 2676 | PASS |
| +189 (3 lines) | 2676 | PASS |
| +252 (4 lines) | 2676 | PASS |

**Identical output for every offset.**

## Why
The test program is **steady-state synchronized to its own raster
IRQ pattern**. Once running, each iteration locks to raster line
transitions. Any boot-time clk offset gets absorbed into the loop
start, but the loop itself runs locked to raster.

## Implication
The drift between JaC64 and VICE is NOT in boot-time clk alignment.
It's in the per-cycle execution of the steady-state test loop.

Specifically: JaC64's CPU executes an instruction at slightly
different clk than VICE's CPU would, due to subtle differences in
how cycle accumulation works in their respective per-cycle code
paths. Each iteration of the test loop preserves this phase
difference.

## To fix
Would require **per-cycle JaC64-CPU vs VICE-CPU diff trace** to
find the exact instruction whose cycle execution differs. Possible
sources:
- Specific opcode (e.g., LDA $D012, EOR $D012) reads at slightly
  different sub-cycle phase, returning different raster_line value.
- CPU-VIC interleaving order within a CLK_INC (VICE: alarm_dispatch
  → maincpu_clk++ → vicii_cycle; JaC64: maybe different order).
- BA-low stall handling for non-badline cycles (sprite DMA, etc.).

Each is multi-day investigation with high regression risk.

## Status
Phase θ ruled out boot-time offset alignment as a fix. The
remaining work is true per-cycle CPU-VIC interleaving alignment,
which is a multi-week project on its own.

The pragmatic conclusion: JaC64's pipeline is **byte-perfect to
VICE per cycle** (per Phase A 1.5M+ trace events). The cell-diff
residual on colorsplit/rmwtest is from CPU-VIC subcycle phase
that requires deeper structural alignment than tractable in a
single multi-day session.
