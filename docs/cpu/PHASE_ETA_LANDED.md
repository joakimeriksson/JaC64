# CPU Refactor Phase η — Landed

## What landed
`C64Screen.updateRasterIrqLine`: when `triggerNow` fires during an
RMW dummy write (`cpu.isRmwDummyWrite()`), defer the raster IRQ
trigger to the next `clock()` iteration via `pendingRasterIrqFireClk`.

```java
if (cpu instanceof MOS6510Core && ((MOS6510Core)cpu).isRmwDummyWrite()) {
    pendingRasterIrqFireClk = cpu.cycles + 1;
    return;
}
triggerRasterIrq(cpu.cycles);
```

And in `clock()`:
```java
if (pendingRasterIrqFireClk != RASTER_IRQ_DISABLED
    && cycles >= pendingRasterIrqFireClk) {
    long fireClk = pendingRasterIrqFireClk;
    pendingRasterIrqFireClk = RASTER_IRQ_DISABLED;
    triggerRasterIrq(fireClk);
}
```

## Effect on colorsplit (EV-RasterIrq trace)

| Test point | Before | After | VICE target |
|---|---|---|---|
| line 60 IRQ raster cycle | 27 | **28** | 29 |
| line 68 IRQ raster cycle | 5 | **6** | 8 |

Each post-RMW IRQ moves 1 cycle later (closer to VICE). +1 cyc
improvement per RMW-IRQ-ack pair.

## Test validation
- **irq-ack-vicii: PASS** (border=$5 = green). 48/48 still passes.
- **Flag-off path: byte-identical** to baseline (legacy paint preserved).
- **9-test suite TOTAL: 5096 cells unchanged** — the cell-diff metric
  is not sensitive to this specific 1-cycle shift on the captured
  frames.

## Remaining gap: accumulating phase drift

Per-cycle PC trace at line 58 IRQ entry shows:

| Event | VICE (line 58 cyc) | JaC64 (line 58 cyc) |
|---|---|---|
| JMP $84c starts | **0** (= clk 3030678) | **-1** (= clk 7020845, line 57 cyc 62) |
| JMP $84c ends | 3 | 2 |
| Handler $900 starts | 10 | 9 |

JaC64's idle JMP-loop happens to land on a different raster cycle
than VICE's at this point. This means each instruction in the
subsequent handler runs at a SLIGHTLY different raster cycle, even
though the instructions themselves take identical cycle counts.

The 1-cycle drift accumulates over the test loop's IRQ pattern.
Closing it requires identifying which specific instruction (or
which earlier loop) causes the +/- 1 cycle phase shift between
JaC64 and VICE.

## What it would take to close further
Several possible sources, all multi-day:

1. **CIA timer initial alignment**: how CIA1/2 are programmed
   during BASIC autostart determines initial cycle phase.
2. **VIC raster counter increment timing**: JaC64 might increment
   `vbeam` 1 cycle later than VICE's `raster_line`.
3. **Specific instruction cycle micro-timing**: some opcode running
   differently in earlier loops (interrupts, RMW, etc.) accumulates
   phase.

Each is a multi-day investigation in its own right, with high
regression risk (irq-ack-vicii's 48/48 pass is fragile).

## Phase η summary
- Phase η fix is **safe and structurally correct** per Phase ζ root
  cause analysis.
- It moves IRQ entry 1 cycle later for RMW dummy-write scenarios,
  matching VICE behavior.
- Cell-diff metric doesn't reflect the change because the captured
  frames sit at clk-points where the 1-cycle shift doesn't cross
  visible test-loop boundaries.
- Closing the remaining 1-cycle phase drift is its own multi-day
  project on top of this work.
