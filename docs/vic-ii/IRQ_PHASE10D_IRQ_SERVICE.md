# Phase 10.D: EV-IrqService trace + IRQ delivery cycle analysis

## Method

Added `EV-IrqService clk=N pc_pre=$X irq_clk=K` event to BOTH
emulators, fired at the moment `doInterrupt` is invoked. Traces
show clk where IRQ delivery starts.

## Frame 43 (slot 5 LDA SS-COL — the failing cell) data

```
                        JaC64                  VICE
Line 69 raster IRQ:
  IrqService clk:       7866751                3994517
  irq_clk (= fire):     7866747                3994515
  delta from fire:      4                      2

Line 70 raster IRQ (handler_2 entry):
  IrqService clk:       7866813                3994581
  irq_clk:              7866810                3994578
  delta from fire:      3                      3

Handler $ab1 first cycle (= IrqService clk + 8):
  JaC64:                7866759                3994525
  Line 69 cyc:          12                     10
                                               (JaC64 enters 2 cyc LATER)

Handler_2 $ada first cycle:
  JaC64:                7866821                3994589
  $ae6 LDA $D012:       7866870 (cyc=60)       3994637 (cyc=59)
                        ↑ 1 cyc later within line 70
```

## Where the cycles go

**JaC64 line 69 raster IRQ services 4 cycles after fire** (= 2 cyc
LATER than VICE which services at fire+2). This is +2 cycles
delay in JaC64's IRQ delivery.

**JaC64 handler→handler_2 chain takes 62 cycles** (`7866821 - 7866759`).
**VICE handler→handler_2 chain takes 64 cycles** (`3994589 - 3994525`).
JaC64 is 2 cycles SHORTER in handler chain.

Net effect: +2 (later handler entry) - 2 (shorter chain) + 1 (other
delta in handler_2-to-$ae6) = +1 cycle delta at $ae6 LDA $D012.

## Two distinct bugs found

### Bug 1: JaC64's first raster IRQ delivery is 2 cycles LATER than VICE's

For raster IRQ at line 69, JaC64 services at irq_clk + 4. VICE
services at irq_clk + 2.

This is the OPPOSITE of what we expected — JaC64 services SLOWER,
not FASTER. Probably because JaC64's CPU is mid-instruction (JMP)
when IRQ fires, and JaC64 accounts the "extra" cycles into the
boundary check differently from VICE.

### Bug 2: JaC64's handler chain is 2 cycles SHORTER than VICE's

From handler $ab1 entry to handler_2 $ada entry:
- JaC64: 62 cycles
- VICE: 64 cycles

Per-instruction cycle counts match (Phase 9.1 verified). So the
2-cycle delta must come from how JaC64 handles the SECOND raster
IRQ entry (= IRQ for line 70 → handler_2). VICE delays that IRQ
2 cycles longer than JaC64.

## Hypothesis: irqEnableDelayOps interaction with CLI

handler chain has `cli` at $ac7. After CLI, `irqEnableDelayOps`
prevents IRQ service for 1 instruction. That instruction is `ror $02`
(5 cyc). Then NOPs.

If JaC64 and VICE differ in HOW MANY instructions the IRQ-enable
delay covers, that's a 2-cycle delta.

VICE source (`6510dtvcore.c:391`): `OPINFO_DISABLES_IRQ` checks if
the previous opcode disables IRQ. CLI sets this.

JaC64 (`MOS6510Core.java:187`): `irqEnableDelayOps`. Set in CLI handler.

**Need to verify**: do JaC64 and VICE consume the same number of
instruction "delay" units after CLI before allowing IRQ?

## Phase 10.E plan

1. Find JaC64's CLI implementation. Check exact `irqEnableDelayOps`
   value set.
2. Compare to VICE's CLI delay (`OPINFO_DISABLES_IRQ` for CLI in
   `6510dtvcore.c`).
3. Adjust JaC64's `irqEnableDelayOps` initial value at CLI to
   match VICE byte-for-byte.

This is a focused, testable single-instruction-fix that should
close the 2-cycle handler-chain delta.

