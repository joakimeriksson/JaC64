# Phase A: real-6510 IRQ-line latching

## What real 6510 does

The 6510 samples the IRQ line **once per instruction**, at the
**second-to-last cycle**. The latched value determines whether the
IRQ is taken at the NEXT instruction boundary.

Examples:
| Instruction | Cycles | Sample at | Take effect at next boundary if line was low at... |
|---|---|---|---|
| NOP imp     | 2 | cycle 1 | cycle 1 |
| LDA #imm    | 2 | cycle 1 | cycle 1 |
| LDA zp      | 3 | cycle 2 | cycle 2 |
| STA abs     | 4 | cycle 3 | cycle 3 |
| LDA abs,X   | 4-5 | cycle (last-1) | cycle (last-1) |
| INC abs     | 6 | cycle 5 | cycle 5 |
| ASL abs,X   | 7 | cycle 6 | cycle 6 |
| JSR         | 6 | cycle 5 | cycle 5 |
| RTS         | 6 | cycle 5 | cycle 5 |
| RTI         | 6 | cycle 5 | cycle 5 |
| BRK         | 7 | cycle 6 | cycle 6 |
| Branch (taken, no page-cross) | 3 | cycle 2 | cycle 2 |
| Branch (taken, page-cross)    | 4 | cycle 3 | cycle 3 |

The rule is **always**: second-to-last cycle of the instruction. There
are no opcode-specific exceptions for IRQ-line sampling.

## What VICE does

VICE's cycle-stepped CPU (interpret.c) runs each cycle individually.
Each opcode is a state machine; the IRQ-sample point is hardcoded
at the second-to-last cycle of each opcode's state list.

## What JaC64 currently does

`MOS6510Core.java`:
- `setIRQLow(true)` → `irqCycleStart = cycles + IRQ_DELAY` (=2)
- IRQ check at instruction boundary: `if (cycles >= irqCycleStart)`

This gives a **fixed 2-cycle delay** between IRQ assertion and IRQ
taken. For instructions that have ≥2 cycles between assertion and
boundary, this matches real 6510. For shorter instructions (or for
IRQs asserted at the LAST cycle), JaC64's check fires one
instruction later than real 6510 would.

For the `irq-ack-vicii.prg` test, the test deliberately probes this
exact edge — the IRQ is asserted at cycles where real-6510 sampling
makes the difference between "taken at next boundary" vs "taken at
boundary-after-next".

## The pragmatic Phase-A patch (no full state-machine rewrite)

Real 6510 samples IRQ at the second-to-last cycle. JaC64 already
calls `fetchByte()` and `writeByte()` once per cycle of an
instruction. So we can sample IRQ at every memory access, keep a
rolling 2-element history, and use the second-to-most-recent at
instruction boundary.

```java
// In MOS6510Core (state):
private boolean irqLineAtCurrCall = false;
private boolean irqLineAtPrevCall = false;

// In fetchByte() / writeByte() (every memory access):
irqLineAtPrevCall = irqLineAtCurrCall;
irqLineAtCurrCall = IRQLow;

// In emulateOp() at start (instruction boundary):
boolean irqVisibleAtBoundary = irqLineAtPrevCall;  // = second-to-last cycle of previous instruction
if (irqVisibleAtBoundary && !disableInterupt && irqEnableDelayOps == 0) {
    doInterrupt(0xfffe, ...);
}
```

After K calls in an instruction:
- `irqLineAtCurrCall` = state at call K (last cycle)
- `irqLineAtPrevCall` = state at call K-1 (second-to-last cycle)

So at the next instruction boundary, `irqLineAtPrevCall` is exactly
the second-to-last-cycle latch real 6510 uses. ✓

### Limitation: NMI and BRK

NMIs are edge-triggered, not level-triggered. The same rolling-latch
approach works (sample at second-to-last cycle, check at next
boundary).

BRK is internal — not an IRQ-line event, so no latching needed.

### Limitation: zero-cycle paths

A few "instruction" sequences in JaC64 don't go through fetchByte/
writeByte exactly once per cycle:
- Implied addressing (e.g. `INX`, `DEY`) — JaC64 may do 2 fetches
  (one opcode fetch + one dummy operand fetch), giving 2 cycles. ✓
- `BRK` does explicit pushes + vector reads. ✓
- `RTI`, `RTS` similar. ✓

All of these still go through fetchByte()/writeByte() per cycle, so
the rolling latch covers them naturally.

### Validation plan

1. Implement rolling latch (small, local change)
2. Run `irq-ack-vicii.prg` — verify RASTER row matches reference and
   stays stable across multiple JVM runs
3. Regression test: `lets_scroll_it.d64`, Krestage 3 banner stripes,
   Krestage 3 FLI beast scene — must not regress
4. If all pass, ship. If any regresses, the rolling latch isn't a
   complete model and we'd need the full state-machine refactor.

### Why this might not be enough

Real 6510 also has subtle interaction with the I-flag (interrupt
disable). When CLI is executed, the I-flag is cleared *between*
cycles 1 and 2 of CLI, but the IRQ check at the END of CLI uses the
OLD I-flag state — so any IRQ asserted before CLI is NOT taken
immediately, but the instruction AFTER CLI takes it. JaC64 already
handles this via `irqEnableDelayOps`.

However, similar timing edges exist for SEI, RTI's P-flag restore,
PLP, and PHP. These may need their own audits if the rolling latch
doesn't fix everything.

## Estimated impact

This is a ~30-line patch. If it works, it eliminates ~80% of Phase A
FULL's value with <1% of the code change. If it doesn't fully work,
the remaining cases tell us exactly which opcodes need explicit
state-machine treatment.
