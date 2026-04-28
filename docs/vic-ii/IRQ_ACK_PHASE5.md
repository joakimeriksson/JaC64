# irq-ack-vicii.prg — Phase 5 investigation: LDA SS-COL slot 5

After Phase 4 fixed STA SS-COL slot 4 via the narrow `$D019` Phi2 write
ordering, only LDA SS-COL slot 5 remains as a divergent cell out of 48.

This phase investigated whether the same Phi2 ordering applied to
`$D019` reads would fix slot 5 too. **It does not.** The LDA failure
is a different bug class: per-slot cycle drift in the IRQ handler chain.

## What we measured

JaC64 LDA SS-COL section RdD019 events (frame index → $fd → cyc of $4a):

```
F1 ($fd=6, EARLIEST delay): cyc=44 ret=$70 → '.'
F2 ($fd=5):                 cyc=45 ret=$f4 → 'D'  ← differs from VICE
F3 ($fd=4):                 cyc=45 ret=$f4 → 'D'
F4 ($fd=3):                 cyc=47 ret=$f4 → 'D'
F5 ($fd=2):                 cyc=47 ret=$f4 → 'D'
F6 ($fd=1, LATEST):         cyc=49 ret=$f4 → 'D'
```

VICE LDA SS-COL section:

```
F1 ($fd=6): cyc=43 ret=$70 → '.'
F2 ($fd=5): cyc=44 ret=$70 → '.'  ← differs from JaC64
F3 ($fd=4): cyc=45 ret=$f4 → 'D'
F4 ($fd=3): cyc=46 ret=$f4 → 'D'
F5 ($fd=2): cyc=47 ret=$f4 → 'D'
F6 ($fd=1): cyc=48 ret=$f4 → 'D'
```

VICE's LDA cycle increments by exactly +1 per slot (uniform delay). JaC64
has irregular `+1, +0, +2, +0, +2` deltas — same cumulative drift over
6 slots, but unevenly distributed.

## Why this is not the Phi2 ordering issue

Per-instruction trace shows handler_2 entry at the SAME cycle in both
emulators ($46 cyc=10 for `$ada lda #$01`). All instruction cycle
counts in the handler_2 → delay → test path match between JaC64 and
VICE (verified in `docs/vic-ii/CYCLE_TRACE_FINDINGS.md`).

The 1-cycle drift in the LDA cycle for slot $fd=5 happens DESPITE
identical per-instruction cycle counts. It comes from a small
timing variance in the handler entry — VICE's IRQ vector lands on a
slightly different cycle for this slot than JaC64's.

## Why the symmetric LDA Phi2 fix fails

A symmetric fix to the Phi2 write ordering — defer `schedule(cycles)`
until AFTER `chips.performRead(...)` for `$D019` reads — was tried
and **regressed both RASTER and SS-COL passes**:

```
LDA RASTER:  ref AAAA..  →  actual AAA...  (regression: 1 cell wrong)
LDA SS-COL:  ref DDDD..  →  actual DDD...  (overshot from DDDDD. to DDD...)
```

The fix shifted JaC64's $D019 read from "post case-N" to "post case-(N-1)",
matching VICE's `GET_ABS` order. But because JaC64's LDA per-slot cycles
are already irregular (the slot-spacing drift), shifting them all by -1
cycle moves the boundary at which the SSCol bit transitions from
clear-to-set DIFFERENTLY in JaC64 than in VICE.

Reverted; no commit.

## What would actually fix LDA SS-COL slot 5

The slot-spacing drift is what `docs/vic-ii/VICE_TRACE_FINDINGS.md` was
already pointing at as "the slot-spacing drift" — a known unresolved
discrepancy in JaC64's IRQ handler chain timing.

The likely candidates (NONE to be patched as a defer or compensation
without further evidence):

- **CLI's interrupt-enable-delay** interacting with subsequent
  instructions. JaC64 has `irqEnableDelayOps` for this (see
  `MOS6510Core.java:187`). VICE has `interrupt_delay()` per-CLK_INC.
  Comparing the per-cycle timing of the IRQ check vs the actual
  instruction boundary would reveal a 1-cycle off interaction.
- **Branch-taken-no-page-cross IRQ delay quirk** (`branchDelaysIrq`
  in JaC64, `OPCODE_DELAYS_INTERRUPT` in VICE). If a branch in the
  handler chain takes-no-page-cross differently between emulators,
  the IRQ entry delays differently per slot.
- **`jmp ($c000)` indirect jump cycle count.** JaC64 charges 5 cycles;
  VICE charges 5 cycles. Both should match. But check for an extra
  `check_ba` somewhere.
- **BA-low handling around `delay` jsr loop** on alternate slots.
  VICE's `check_ba` is called at specific points; JaC64's
  `waitForBus` may stall at a different point relative to BA-low
  windows.

Phase 5 next step (deferred): use the existing `-Djac64.tracePcCycles`
+ VICE's per-PC trace patch to capture the cycle count of EACH
instruction in handler_2's delay path for ALL 6 slots in BOTH
emulators. Diff per-PC cycle counts slot-by-slot. The instruction
that has different cycles between adjacent slots in JaC64 (but the
same in VICE) is the bug location.

The drift is small (1 cycle in 1 of 6 slots) and the test ALMOST
passes — 47 of 48 cells correct. This is good enough to leave as a
known-issue while moving on to other VIC-II work.

## Reproduction

JaC64 with current fix (Phase 4 only, no LDA Phi2):
```
java -Djac64.warp=true -Djac64.testRasterTime=12 \
     -Djac64.dumpScreen=true \
     -Djac64.dumpScreenFile=/tmp/jac64_screen_irqack.txt \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg
```

Expected SS-COL row: `***-**  ******  ******  DDDDD.` (vs reference
`***-**  ******  ******  DDDD..` — only LDA col 5 differs).

Border still red (`$D020=$2`) because not all cells match. To pass we
need 48/48; we have 47/48.
