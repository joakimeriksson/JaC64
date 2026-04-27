# irq-ack-vicii.prg — Phase 1 ground truth (2026-04-27)

## What we did

Captured the final screen RAM ($0400-$07FF) from BOTH JaC64 and VICE
x64sc on `irq-ack-vicii.prg`, decoded the 48 result cells (4 ack
mechanisms × 6 timing slots × 2 IRQ types), and diffed byte-for-byte.

## How to reproduce

```bash
# JaC64
java -Djac64.warp=true -Djac64.testRasterTime=12 \
     -Djac64.dumpScreen=true \
     -Djac64.dumpScreenFile=/tmp/jac64_screen_irqack.txt \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg

# VICE x64sc (with the c64memsc.c + viciisc/vicii-mem.c hook patch)
JAC64_SCREEN_FILE=/tmp/vice_screen_irqack.txt \
  /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
    -warp -limitcycles 12000000 -autostartprgmode 1 \
    -autostart /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg

diff /tmp/jac64_screen_irqack.txt /tmp/vice_screen_irqack.txt
```

The dump fires on the first `$d020` write carrying the test's pass/fail
signal ($02 = fail, $05 = pass) — see `c64memsc.c:colorram_store →
jac64_dump_screen_ram_on_signal` and the hook in
`viciisc/vicii-mem.c:d020_store`.

## Result table

Reference rows are **hardcoded** in the .prg (the values VICE produces
when it passes). Test compares actual rows to reference rows; mismatch
→ `$d020 = $02` (FAIL).

| Test | RASTER actual | RASTER ref | Match | SS-COL actual | SS-COL ref | Match |
|------|---------------|------------|-------|---------------|------------|-------|
| STA  | `***-**`      | `***-**`   | ✓     | `******`      | `***-**`   | ✗ slot 4 |
| INC  | `***-**`      | `***-**`   | ✓     | `******`      | `******`   | ✓     |
| ASL  | `******`      | `******`   | ✓     | `******`      | `******`   | ✓     |
| LDA  | `AAAA..`      | `AAAA..`   | ✓     | `DDDDD.`      | `DDDD..`   | ✗ slot 5 |

**46 of 48 cells match.** The bug is two cells, both in SS-COL pass:

1. **STA SS-COL slot 4** — JaC64 outputs `*` ($2a) where VICE outputs
   `-` ($2d). Meaning: JaC64's `STA $D019` in slot 4 fails to ACK the
   IRQ before `irq_handler_3` re-enters and sets `$fb='*'`. VICE's
   STA succeeds in this slot.

2. **LDA SS-COL slot 5** — JaC64 outputs `D` ($84) where VICE outputs
   ` ` ($00). Meaning: JaC64's `LDA $D019` in slot 5 reads the SS-COL
   flag still set (bit 2 = 1, after `AND #$87` value $84), where VICE
   reads the flag clear (value $00, just bit 7 high — and `AND #$87`
   masks that... wait, $80 & $87 = $80, but the cell shows $00).

   Note: $84 vs $00 in the cell. $80 ($00 with bit 7? no, $00 IS $00) —
   the LDA $D019 in slot 5 returns $00 in VICE, meaning the IRQ flag
   was already cleared. In JaC64 it returns $84 (bit 2 still set). So
   JaC64 sees the IRQ active 1 cycle later than VICE.

## What this rules out

- **NOT** an RMW dummy-write bug. INC and ASL both PASS byte-for-byte
  in both RASTER and SS-COL passes. The previous theory that the
  failure was in `$D019` RMW dummy-write semantics is contradicted by
  this data.
- **NOT** a generic IRQ pipeline bug. RASTER pass works perfectly for
  all 4 ack mechanisms × 6 slots = 24 cells.
- **NOT** an LDA $D019 read bug at large. Only slot 5 of LDA SS-COL
  drifts; slots 1-4 and 6 are correct.

## What it points at

Both failing cells are **SS-COL pass only**, where sprites 0/1 are
enabled (`$D015 = $03`) and overlap, generating sprite-sprite
collisions and adding sprite DMA BA-low cycles to every line. The
SS-COL pass differs from the RASTER pass in:

1. Sprite DMA: BA-low cycles 54-60 of each line where sprites are
   active. This stalls the CPU 7 cycles per line in the affected
   region, shifting the absolute clock at which test code runs.
2. SSCol IRQ source: the SSCol fire detection runs each cycle that
   draws sprite pixels, instead of (or in addition to) the raster IRQ
   that fires on the line $48 transition.

The previous fix (`C64Screen.java` 1-stage SSCol fire defer, commit
`580fa6e`) shifted the SSCol fire by +1 cycle. That fix made LDA
slots 1-4 match VICE byte-for-byte. The two remaining drift cells
suggest the +1 defer was directionally correct but the underlying
mechanism is more nuanced than a uniform 1-cycle shift — there's
still a slot-spacing or BA-low timing detail that diverges.

## Phase 2 plan

Capture targeted EV-* traces for these two specific events:

1. The SECOND raster IRQ fire (line $48) during STA SS-COL slot 4 in
   both emulators. Compare absolute cycles. Identify the cycle window
   where JaC64's IRQ delivery to the CPU differs from VICE's.

2. The SSCol fire vs the LDA $D019 in slot 5 in both emulators.
   Compare cycle-of-fire vs cycle-of-LDA. Identify the cycle gap.

Required new EV-* events:

- `EV-IRQDeliver`: when the CPU vectors to `irq_handler_3` (i.e., the
  IRQ is delivered, opcode fetch starts at vector). VICE side likely
  in `mainc64cpu.c` IRQ entry path. JaC64 side in `MOS6510Core.java`
  IRQ check.
- `EV-IRQSample`: when the CPU samples the IRQ line for delivery. VICE
  `interrupt_delay()`. JaC64 `sampleIrqLine()`.
- Tag dummy-vs-final on `EV-WrD019` (already-distinct in JaC64 via
  `rmwDummyWrite` flag; needs threading through to the trace event).

Window: line $45 → line $4A in the SS-COL pass, slot 4 of STA test.
About 5 raster lines, ~315 cycles. Easy to read by hand.

## Files modified for Phase 1

- `TestRaster.java` — `dumpScreenRam()` helper, hex+ASCII output.
- `tools/vice-trace-patches/vice_trace_patches.diff` — needs update
  to include the new c64memsc.c + viciisc/vicii-mem.c hook so the
  patch is re-appliable.

## Files in VICE we patched

- `c64/c64memsc.c` — added `jac64_dump_screen_ram_on_signal()`.
- `viciisc/vicii-mem.c` — added `d020_store()` call to dump hook.
