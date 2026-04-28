# IRQ pipeline / BA-low investigation findings

Detailed VICE-vs-JaC64 study driven by the question "where exactly is
the 1-cycle drift coming from on irq-ack-vicii.prg LDA SS-COL slot 5?"

## What we fixed (committed)

**`a3a63e0` — BA-low setter MAX semantics.**

`C64Screen.setBaLowUntil(until, source)` was unconditionally writing
the new `baU`, which let a SHORTER setter shrink an already-active
BA-low window. Concrete bug in irq-ack-vicii.prg F2→F3 frame:

```
cyc=11: BADLINE-START sets baU = lastLine + 54
cyc=54: BA-SPR0 sets baU = lastLine + 59 (extends by 5)
cyc=55: BADLINE-C55 wrote baU = lastLine + 54 (5 cycles in PAST!)
```

CPU stall released 7 cycles early. STA $D019 finished early. With MAX:
JaC64 STA $D019 in F2 now takes 54 cycles (matches VICE byte-for-byte).
handler_1→handler_2 chain in JaC64 went from variable to uniform 62
cycles across F1-F6.

Matches VICE's `viciisc/vicii-cycle.c:594-600` semantics where ba_low
is the OR of multiple sources (badline c-fetch, sprite_dma & sprite_ba_mask).

## What's still wrong

irq-ack-vicii.prg still 47/48 cells. LDA SS-COL slot 5 still reads
`$f4` where VICE reads `$70`.

## Where the remaining drift lives

After BA-MAX, JaC64 has UNIFORM handler_1→handler_2 chain length of
62 cycles across all 6 LDA SS-COL frames. VICE's chain VARIES:
64/64/62/62/64/62.

| Frame | $fd | JaC64 chain | VICE chain |
|-------|-----|-------------|-----------|
| F1 | 6 | 62 | 64 |
| F2 | 5 | 62 | 64 |
| F3 | 4 | 62 | 62 |
| F4 | 3 | 62 | 62 |
| F5 | 2 | 62 | 64 |
| F6 | 1 | 62 | 62 |

JaC64 always preempts handler_1 at $ad2 NOP boundary (offset 55).
VICE sometimes preempts at $ad3 (offset 57, chain=64).

### Why VICE's chain varies

VICE's IRQ-delivery decision is `irq_delay_cycles >= 2` evaluated at
each opcode boundary. With irq source set at handler_1 offset 54
(= line $46 cyc=0):

- `irq_delay_cycles >= 2` first true at handler_1 offset 56.
- $ad2 ends at offset 55 (delay = 1, not yet).
- $ad3 ends at offset 57 (delay = 3, take IRQ).
- → VICE preempts at $ad3, chain=64.

If irq source set at offset 53 (line $46 cyc=0 lands one cycle earlier
in handler_1), then `>= 2` first true at offset 55 = $ad2 boundary →
chain=62.

VICE varies because handler_1 entry on line $45 varies frame-to-frame
(due to entry_loop JMP alignment with line $45 raster IRQ source).

### Why JaC64 always preempts at $ad2

JaC64 enters handler_1 at line $45 cyc=11 in F1 (vs VICE $45 cyc=9).
That makes line $46 cyc=0 land at handler_1 offset 52 in JaC64 (vs 54
in VICE). IRQ delivery requirement: offset 54 (= 52+2). $ad2 ends at
55, satisfies → preempt at $ad2.

JaC64's handler_1 entry is **2 cycles LATER on the line** than VICE's.

### Where the 2-cycle entry shift comes from

Both emulators run JMP $aae as 3-cycle entries. Both check IRQ at
boundary `cycles >= source_clk + 2`.

For F1:
- VICE source_clk = line $45 cyc=0 = clk 3856923.
- VICE $aae JMP boundaries: 3856916, 3856919, 3856922, **3856925**, ...
- Source+2 = 3856925 = exactly a JMP boundary. IRQ taken at 3856925.
- VICE handler_1 $ab1 at clk=3856932 (= 3856925 + 7 vector). $45 cyc=9.

- JaC64 source_clk = clk 7866748.
- JaC64 $aae JMP boundaries: 7866746, 7866749, **7866752**, ...
- Source+2 = 7866750. Next boundary >= 7866750 is 7866752.
- JaC64 IRQ taken at 7866752 = source+4 (= 2 cycles late vs VICE).
- JaC64 handler_1 $ab1 at clk=7866759 = $45 cyc=11.

JaC64's JMP boundaries are at $45 cyc 1, 4, 7, ... (= cyc%3 == 1).
VICE's are at cyc 2, 5, 8, ... (= cyc%3 == 2).

**The boundary alignment differs by 1 mod-3 between emulators.**

This compounds when source_clk doesn't exactly align with a boundary.
For source+2 = clk K:
- If K % 3 == JMP_phase, IRQ taken exactly at K.
- Otherwise, taken at K rounded up to next boundary (= K + 1 or 2).

VICE happens to align in F1 (source+2 = JMP boundary exactly). JaC64
doesn't. So JaC64 takes IRQ 2 cycles late.

### What controls the 1-mod-3 phase difference

The `cycles % 3` phase of $aae JMP boundaries depends on:
- How many cycles elapsed from system start to entry_loop's first JMP.
- That depends on: KERNAL boot, BASIC startup, autostart program load,
  test program's pre-IRQ setup (SEI/CLI sequences).

JaC64 and VICE differ by 1 cycle somewhere in this LONG chain. The
1-cycle phase shift propagates indefinitely (since entry_loop is
3-cycle JMPs and irq sources fire at fixed line-cycle boundaries).

To fix: hunt down the 1-cycle accumulation source between system
reset and the first irq-ack-vicii test slot. Could be:
- KERNAL/BASIC startup cycle accounting
- Autostart program load timing
- Pre-CLI test setup instructions
- Initial `sta $d019` ack at entrypoint

This is a multi-day archaeology dig. Each step would need to compare
absolute clk between emulators at specific PC events.

## Strategic options

1. **Accept 47/48** as a hard limit for irq-ack-vicii.prg without the
   archaeology work. The test ALMOST passes, and the remaining cell
   is from a system-wide 1-cycle phase shift that isn't connected to
   any specific emulation bug.

2. **Hunt the phase shift** in the boot/autostart/startup chain.
   Multi-day. Will likely find one specific cycle where JaC64 takes
   1 more or fewer cycles than VICE in some KERNAL/BASIC routine.
   Fix that, slot 5 will pass — but might break demos that work with
   the current phase.

3. **Patch the test program's first slot to match phase.** Out of
   scope (don't modify VICE-testprogs).

## What we got out of this

Real BA-low fix that aligns JaC64's BA semantics with VICE's "any
source low" model. The fix doesn't change the irq-ack-vicii cell
result but makes JaC64 more accurate on per-cycle BA-low timing,
which can only help future demo-accuracy work.

Total commits in irq-ack-vicii arc:
- `cb298e8` Phase 1 ground truth + tooling
- `e1c1f29` Phase 2/3 trace diff identifies Phi1/Phi2 ordering bug
- `7b5b31a` Phase 4: $D019 Phi2 write-order fix → STA SS-COL slot 4 fixed
- `11bd26b` Phase 5: LDA slot 5 is slot-spacing drift (not Phi2)
- `306dd1a` Phase 5-next: per-PC cycle diff identifies IRQ pipeline race
- `0278fe7` Phase 6: pipeline overhaul finding (math already matches)
- `a3a63e0` BA-low MAX semantics (this fix)

The arc went from many-wrong-cells → 2 wrong → 1 wrong over the
session. The last cell is a system-phase issue out of immediate scope.
