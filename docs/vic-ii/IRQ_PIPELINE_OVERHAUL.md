# IRQ pipeline overhaul — what I found

User asked for a complete overhaul of JaC64's IRQ delivery pipeline to
match VICE x64sc. Before writing code I read the full VICE pipeline.
The conclusion is that JaC64's IRQ delivery math **already matches
VICE's**. The remaining LDA SS-COL slot 5 drift comes from somewhere
else, and the overhaul wouldn't close the gap.

## VICE's pipeline, in full

Files read: `interrupt.h`, `interrupt.c`, `mainc64cpu.c`,
`6510dtvcore.c`, `c64/c64cpusc.c`, `viciisc/vicii-irq.c`,
`viciisc/vicii-cycle.c`.

### Source set (e.g. raster IRQ)

`viciisc/vicii-cycle.c:480-496`: at start of each new raster line
(`raster_cycle == VICII_PAL_CYCLE(1)` → 0), `raster_line++`. If
match against `raster_irq_line`, call `vicii_irq_raster_trigger()`
which calls `vicii_irq_raster_set(maincpu_clk)` (`vicii-irq.c:58`).

`vicii-irq.c:58-62`: sets `irq_status |= 0x01`, calls
`vicii_irq_set_line_clk(mclk)`.

`vicii-irq.c:47-56`: if mask says delivery-OK, set bit 7 of irq_status
and call `maincpu_set_irq_clk(int_num, 1, mclk)`.

`interrupt.h:333-337`: `maincpu_set_irq_clk` → `interrupt_set_irq(...)`.

`interrupt.h:141-196` `interrupt_set_irq`: if first source becoming
active (`nirq == 0` → 1), set `cs->irq_clk = mclk`,
`global_pending_int |= IK_IRQ | IK_IRQPEND`, `irq_delay_cycles = 0`.

So **`irq_clk` = exact CPU clock when source went high.**

### Per-cycle delay tracking

`mainc64cpu.c:97-110` `interrupt_delay()`, called from `CLK_INC()`
(`c64/c64cpusc.c:47-50`):

```c
#define CLK_INC()                                  \
    interrupt_delay();    /* runs FIRST */         \
    maincpu_clk++;                                 \
    maincpu_ba_low_flags &= ~MAINCPU_BA_LOW_VICII; \
    maincpu_ba_low_flags |= vicii_cycle()
```

Inside `interrupt_delay`:

```c
if (maincpu_int_status->irq_clk <= maincpu_clk) {
    maincpu_int_status->irq_delay_cycles++;
}
```

So `irq_delay_cycles` counts cycles since source went high.

### Delivery decision

`mainc64cpu.c:690-710` `interrupt_check_irq_delay`:

```c
unsigned int delay_cycles = INTERRUPT_DELAY;  /* = 2 (interrupt.h:39) */
if (OPINFO_DELAYS_INTERRUPT(*cs->last_opcode_info_ptr)) delay_cycles++;
if (cs->irq_delay_cycles >= delay_cycles) {
    if (!OPINFO_ENABLES_IRQ(*cs->last_opcode_info_ptr)) return 1;
    else cs->global_pending_int |= IK_IRQPEND;
}
return 0;
```

Called from `6510dtvcore.c:391` inside `DO_INTERRUPT` macro, which is
itself called from the main loop (`6510dtvcore.c:1758-1763`) BEFORE
`FETCH_OPCODE`.

So: delivery requires `delay >= 2`, +1 if last opcode was a
branch-taken-no-page-cross (`OPINFO_DELAYS_INTERRUPT`), deferred 1
opcode if last opcode was CLI/PLP/RTI (`OPINFO_ENABLES_IRQ`).

## JaC64's pipeline

Files: `MOS6510Core.java` (CPU), `CPU.java` (memory bus +
sampleIrqLine), `C64Screen.java` (raster IRQ trigger).

### Source set

`C64Screen.java:1943-1945` (per-cycle in `clock(long cycles)`):

```java
while (rasterIrqClock != RASTER_IRQ_DISABLED && cycles >= rasterIrqClock) {
    triggerRasterIrq(rasterIrqClock);
}
```

`triggerRasterIrq` (`C64Screen.java:866`): sets `irqFlags |= 0x01`,
masks check, calls `setIRQ(VIC_IRQ)` →
`ExtChip.IRQManager.setIRQ` → `cpu.setIRQLow(true)`.

`MOS6510Core.java:140-159` `setIRQLow(true)`:

```java
irqRequested = true;
IRQLow = true;
irqCycleStart = cycles + IRQ_DELAY;  // IRQ_DELAY = 2
```

So **`irqCycleStart` = `cycles_at_source_set + 2`**. Equivalent to
VICE's `irq_clk + INTERRUPT_DELAY`.

### Delivery decision

`MOS6510Core.java:415-450` (in `emulateOp`, before `fetchByte` for
opcode):

```java
} else if ((PHASE_A_IRQ_LATCH
              ? (irqLineAtPrevCall && irqEnableDelayOps == 0)
              : (IRQLow && cycles >= irqCycleStart + (branchDelaysIrq ? 1 : 0)
                  && irqEnableDelayOps == 0))
          || brk) {
    // doInterrupt() -- vector dispatch
}
```

PHASE_A_IRQ_LATCH defaults OFF. So the active path is:

```
IRQLow && cycles >= irqCycleStart + (branchDelaysIrq ? 1 : 0) && irqEnableDelayOps == 0
```

= `IRQLow && cycles - source_clk >= 2 (+1 if branch) && not deferred by CLI`.

**Mathematically identical to VICE's** `irq_delay_cycles >= 2 (+1 if
DELAYS_INTERRUPT) && not ENABLES_IRQ`.

### `irqEnableDelayOps`

Set to 1 by CLI / PLP-clearing-I-flag / RTI-clearing-I-flag
(`MOS6510Core.java:728, 765, 836`). Decremented at end of next opcode
(`MOS6510Core.java:1002-1004`). Equivalent to VICE's `OPINFO_ENABLES_IRQ`
defer-by-1-opcode.

### `branchDelaysIrq`

Set on branch-taken-no-page-cross (`MOS6510Core.java:343`). Cleared
at start of next opcode dispatch (`MOS6510Core.java:456`). Equivalent
to VICE's `OPINFO_DELAYS_INTERRUPT`.

## So where IS the slot 5 drift coming from?

Per Phase 5-next analysis (`docs/vic-ii/IRQ_ACK_PHASE5_NEXT.md`):

| Frame | $fd | JaC64 chain | VICE chain |
|-------|-----|-------------|-----------|
| F1 | 6 | 64 | 64 |
| F2 | 5 | 64 | 64 |
| F3 | 4 | 62 | 62 |
| F4 | 3 | **64** | **62** |
| F5 | 2 | **62** | **64** |
| F6 | 1 | **64** | **62** |

handler_1 → handler_2 chain length variance is correctly modeled
in BOTH emulators (62 = preempted at NOP $ad2, 64 = preempted at
$ad3). The CHOICE between them depends on:

- handler_1 entry cycle on line $45 (varies frame-to-frame)
- where line $46 raster IRQ pulse falls relative to handler_1's NOPs

For F4, VICE handler_1 entry at $45 cyc=11 → preempt at $ad2 (chain
62). JaC64 handler_1 entry at $45 cyc=9 → preempt at $ad3 (chain 64).

So the divergence is in **WHEN handler_1 entered**, not the IRQ
delivery logic. JaC64 entered handler_1 2 cycles EARLIER than VICE
on this frame.

## Where the entry-cycle drift comes from

handler_1 enters via line $45 raster IRQ vector dispatch. The CPU is
in `entry_loop: jmp entry_loop` when the IRQ pulses. JMP boundary
determines vector dispatch start.

For BOTH emulators, JMP $aae is 3 cycles. IRQ source set at $45 cyc=0.
Delivery at >= cyc 2. Vector 7 cycles. So handler_1 entry at $45 cyc =
(JMP_start + 3 + 7) where JMP_start is the cycle the JMP that got
preempted started.

Possible handler_1 entry cycs depend on alignment of JMP boundaries
to line $45 cyc=0.

Across multiple frames, both emulators accumulate small ±1 cycle
drifts from:
- BA-low handling around sprite DMA (sprites in SS-COL pass)
- RMW timing in irq_handler_2's `inc $d021; dec $d021`
- $D012 read-compare-beq in handler_2 (cmp can hit boundary differently)

Per-frame variance: VICE 19657 cycles uniform. JaC64 alternates
19656/19658 (= ±1). The variance is in the INSTRUCTION TIMING within
a frame, NOT in the IRQ pipeline.

## Why an overhaul wouldn't fix slot 5

The IRQ pipeline math is already correct. Replacing the
`cycles >= irqCycleStart + ...` check with an explicit
`irq_delay_cycles >= 2 + ...` gives the SAME result.

The remaining slot 5 cell would require tracking down EVERY 1-cycle
variance source between JaC64 and VICE. Candidates:
- VIC's case-dispatcher numbering being off-by-1 vs VICE's raster_cycle
  (the multi-week refactor noted in WORKPLAN.md)
- BA-low timing (sprite DMA stall start/end cycle)
- RMW write-cycle convention (when does the store actually fire)

These are all OUT OF SCOPE for an "IRQ pipeline overhaul." They
need targeted per-source investigation, each one its own session.

## Honest recommendation

We have a working test result of **47 of 48 cells correct** on
irq-ack-vicii.prg. The Phase 4 narrow `$D019` Phi2 fix did real
work (took us from many wrong cells to 1). Going from 47 → 48
requires fixing per-cycle variance in many independent VIC-II
subsystems, not the IRQ pipeline.

I'd recommend leaving this at 47/48 and moving on. If a specific
demo (Krestage 3, FLI scenes) requires sub-cycle accuracy, dig
into THAT demo's specific divergence points rather than chasing
the last cell of irq-ack-vicii.

If you still want me to attempt the overhaul, I'll do it — but
I expect 47/48 to remain 47/48 after.

## Phase 6 status

Phase 6.1 (read VICE pipeline) — done, documented above.
Phase 6.2 (map VICE → JaC64) — done, JaC64 already equivalent.
Phase 6.3 (implement) — NOT started, would be code that produces
no behavioral change.
Phase 6.4 (verify) — would confirm "no change."

Total time spent in this session: ~2 hours of investigation. No
code changes.
