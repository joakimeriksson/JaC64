# Multi-day archaeology: hunting JaC64 vs VICE timing divergence

## Goal

Match JaC64's instruction-cycle timing with VICE x64sc byte-for-byte
through the first 4M+ cycles of execution, eliminating the cumulative
phase shift that causes irq-ack-vicii.prg to fail at slot 5.

## Method

Both emulators have per-PC trace patches. Capture both, diff PC
sequences and per-instruction cycle counts. Each divergence is a
specific bug: one CPU port bit, one CIA timer cycle, one IRQ delivery
edge case. Fix one at a time, each with VICE source citation.

## Trace capture

```bash
# JaC64
java -Djac64.warp=true -Djac64.testRasterTime=5 \
     -Djac64.tracePcCycles=true -Djac64.tracePcStart=0 \
     -Djac64.tracePcEnd=4000000 \
     -Djac64.tracePcFile=/tmp/jac64_pc_full.trace \
     -cp build/libs/JaC64.jar TestRaster .../irq-ack-vicii.prg

# VICE x64sc (with the per-PC trace patch in 6510dtvcore.c)
JAC64_PC_TRACE_FILE=/tmp/vice_pc_full.trace \
  /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
  -warp -limitcycles 4000000 -autostartprgmode 1 \
  -autostart .../irq-ack-vicii.prg

# Diff PC sequences
awk -F'[ =]' '/^PC=/{ for(i=1;i<=NF;i++) if($i=="PC") print $(i+1)}' \
    /tmp/jac64_pc_full.trace > /tmp/j.pcs
awk -F'[ =]' '/^PC=/{ for(i=1;i<=NF;i++) if($i=="PC") print $(i+1)}' \
    /tmp/vice_pc_full.trace | tail -n +2 > /tmp/v.pcs
diff /tmp/j.pcs /tmp/v.pcs | head -20
```

Note: JaC64's per-PC trace filters to main CPU only (getName() ==
"C64 CPU") since C1541's MOS6510Core shares the same trace path.

## Bugs found and fixed

### 1. BA-low setter shrinking (commit `a3a63e0`)

`C64Screen.setBaLowUntil(until, source)` was unconditionally writing
the new value. A SHORTER setter could shrink an already-active BA-low
window. Fix: MAX semantics (only extend, never shrink).

VICE source: `viciisc/vicii-cycle.c:594-600` — ba_low is the OR of
multiple sources; CPU stays stalled while ANY are low.

### 2. CPU port `$0001` pullup semantics (commit `3c0341c`)

JaC64's `$0001` read returned raw `memory[1]`. VICE's `zero_read`
applies pullup logic: input bits read 1 by default unless an external
source drives them low. KERNAL's IRQ handler at `$EA61-$EA65`
(`LDA $01; AND #$10; BEQ ...`) takes different branches in JaC64 vs
VICE because of this bit difference.

VICE source: `c64/c64pla.c:55` —
`pport.data_read = (pport.data | ~pport.dir) & (pport.data_out | pullup)`.

### Status after both fixes

irq-ack-vicii.prg: 47/48 cells. The KERNAL boot sequence now matches
VICE byte-for-byte through the first ~12k instructions. After that,
**54+ more divergences** remain in the first 4M cycles, all CIA
timer / IRQ alignment related.

### 3. CIA NEW (8521) immediate IRQ raise (this commit)

JaC64's CIATimer fired `interruptNext` at the TOP of `update()`,
giving a 1-cycle delay between underflow and CPU IRQ source set.
That matches OLD CIA (6526) `CIA_IRQ_RAISE1` semantics. VICE x64sc
default emulates NEW CIA (8521) where underflow → IRQ source set
happens IN THE SAME CYCLE (`CIA_IRQ_RAISE0`).

Fix: move the `if (interruptNext)` block to AFTER the state machine
+ delayedWrite at the end of `update()`. So when state-machine
calls `triggerInterrupt(cycles)`, the IRQ source is set in that
same cycle.

VICE source: `core/ciacore.c:402-417`:
```c
if ((cia_context->model == CIA_MODEL_6526)
        || (cia_context->model == CIA_MODEL_6526A)) {
    cia_context->irq_enabled |= CIA_IRQ_RAISE1;   // OLD: delayed
} else {
    /* NEW CIA */
    cia_context->irq_enabled |= CIA_IRQ_RAISE0;   // NEW: immediate
    if (cia_context->irq_enabled & CIA_IRQ_RAISE0) {
        my_set_int(cia_context, true, rclk);
    }
}
```

Result: irq-ack-vicii first-4M divergences cut from **54 → 18**.
Slot 5 cell still fails — remaining 18 divergences are all in
the BASIC ROM ready/polling loop ($e5cd-$e5d4 area) caused by
autostart timing differences (VICE injects RUN earlier than
JaC64's deterministic SYS at cycle 7M). Once both reach the
test body the CPU/VIC alignment drives slot 5.

### 4. Slot 5 deep dive (NOT YET FIXED — root cause confirmed)

Per-PC trace at test execution time (clk 7M+) reveals the exact
NOP boundary at IRQ entry per slot:

```
slot LDA $D019 at clk N: RTI returned to $adX
JaC64: all 6 slots → $ad3 (constant)
VICE:  6,5,4 → $ad4   3,2 → $ad3   1 → $ad4
```

VICE wobbles between two NOP boundaries depending on slot's
delay phase; JaC64 lands at the EARLIER boundary every slot.
That's the slot-spacing drift: JaC64's IRQ entry is ~2 cycles
EARLIER (= 1 NOP earlier) than VICE in 4-of-6 slots, exactly
synchronized in 2-of-6 slots.

JaC64 SSCol fire on slot 5 (line 74 cyc 45) at clk 7886764.
JaC64 LDA $D019 read on slot 5 at clk 7886764 — SAME cycle.
chips.clock(N) runs before CPU read on N, so JaC64 reads $84
(flag set). VICE's fire is at cyc 45 too but its CPU LDA $D019
reads 1 cycle BEFORE the fire — VICE reads $00.

A 2-cycle defer of SSCol fire was tested empirically: it fixes
slot 5 ($84 → $00) but BREAKS slot 4 ($84 → $00) and the IRQ
pattern in row 03 col 3 (`*` → `-`). Net WORSE than 1-cycle.
This re-confirms the prior-session conclusion: the 2-cycle defer
is a hack masking a separate slot-spacing drift.

### Phase 9 deep dive (2026-04-28)

Per-PC cycle-count diff for the entire slot-5 handler chain
(102 instructions from $ada handler_2 entry to $b5b LDA $D019)
shows **ZERO cycle-count divergences** between JaC64 and VICE.
Every instruction takes the same cycles in both emulators. The
bug is NOT a wrong-cycle CPU instruction.

VICE source (`viciisc/vicii-irq.c:82-86`):
```c
void vicii_irq_sscoll_set(void)
{
    vicii.irq_status |= 0x4;
    vicii_irq_set_line();    // calls maincpu_set_irq, NOT _clk variant
}
```

`vicii_irq_set_line()` calls `maincpu_set_irq(int_num, 1)` which
maps to `interrupt_set_irq(maincpu_int_status, int_num, 1, maincpu_clk)`.
The `cpu_clk` parameter is the GLOBAL `maincpu_clk` AFTER it was
incremented by `CLK_INC()`. So in VICE, `irq_clk = maincpu_clk`
at the time the SSCol fire fires, which is INSIDE `vicii_cycle()`
which runs AFTER `maincpu_clk++`. JaC64's `setIRQLow()` sets
`irqCycleStart = cpu.cycles + IRQ_DELAY` where `cpu.cycles` is
the just-incremented value at `chips.clock(cycles)`. Same model.

**Empirical timing comparison at slot 5:**
| event                        | JaC64 clk    | VICE-equiv (offset 3872234) | Δ |
|------------------------------|--------------|------------------------------|---|
| LDA $D019 read (= last cyc)  | 7886764      | 4014530                      | — |
| SSCol fire (irq_clk set)     | 7886764      | 4014530 (vs VICE 4014531)    | 1 cycle EARLIER |

JaC64 sets the SSCol IRQ bit 1 cycle EARLIER than VICE in
absolute clock terms. The CPU read happens at the same clock as
the fire in JaC64 (chips.clock runs before CPU read on cycle N),
so $D019 returns $84. VICE's fire is 1 cycle AFTER LDA's read,
so $D019 reads $00.

VICE source (`viciisc/vicii.c:285-291`):
```c
void vicii_reset(void)
{
    raster_reset(&vicii.raster);
    vicii.raster_line = 0;
    vicii.raster_cycle = 6;        // <-- non-zero start
    ...
}
```

VICE's `raster_cycle` starts at **6** at reset. Each subsequent
`vicii_cycle()` calls `next_vicii_cycle()` which increments
`raster_cycle` BEFORE doing the cycle's work. So the first
`vicii_cycle()` after reset processes `raster_cycle = 7`.

JaC64's `lastLine = cpu.cycles` at reset (`vicCycle = 0`). The
first `chips.clock(cpu.cycles+1)` after reset processes
`vicCycle = 1`.

The `case-N = VICE-cycle-(N+1)` convention noted in
`C64Screen.java:2444` is therefore approximately correct. Older notes here
talked about boot/reset absolute-clock alignment, but that is not an allowed
`irq-ack-vicii` fix direction after the 2026-05-02 PRG-vs-D64 check. Use this
only as historical background for cycle numbering, not as a load/autostart
investigation.

### Conclusion

47/48 stays the current baseline until the running CPU/VIC access phase is
ported. The remaining work is to make CPU reads/writes observe the same
subcycle as VICE `LOAD/STORE` before `CLK_INC()`/`vicii_cycle()`, while keeping
sprite paint, BA-low, border checks, and IRQ fire at the right phase.

This is multi-day architectural work:
1. **Phase 9.A**: Pick a reference event (e.g. raster IRQ fire at
   line $30 cycle 1 = VICE's `vicii_irq_raster_set` at end of
   `raster_cycle 1`). Empirically verify JaC64's matching event
   fires at the same offset from cpu reset as VICE's. Identify
   the exact 1-2 cycle phase delta.
2. **Phase 9.B**: Adjust `C64Screen.reset()` so that the initial
   `lastLine` and first `chips.clock` invocation produce
   `vicCycle = 7` at the same absolute-clock as VICE's first
   `vicii_cycle()` runs at `raster_cycle = 7`.
3. **Phase 9.C**: Walk the case dispatcher (cases 0..62) and
   verify each action's clock matches the corresponding
   `viciisc/vicii-cycle.c` work for `raster_cycle = case + 1`.
   Adjust where divergent.
4. **Phase 9.D**: Regression test irq-ack-vicii (target 48/48)
   AND Krestage 3 banner / FLI / cia-timer / RASTER tests.

Risk: any phase change to the case dispatcher will potentially
shift sprite paint, BA-low, border-state, raster-IRQ, and badline
fetch timing. Demos depending on the exact JaC64 phase will
break and need re-tuning.

## Remaining work

### Pattern of remaining divergences

```
585817d585816    ← JaC64 takes IRQ 67 instructions later than VICE
585884a585884    ← then catches up (= same +67 misalignment, opposite direction)
595428d595427    ← next IRQ pair, ~10k instructions later
595497a595497
... 25+ more pairs
```

Each pair is ~67 instructions apart (= one frame of KERNAL IRQ
handler). JaC64's CIA1 timer A fires at slightly different cycle
than VICE's. Over each frame, the misalignment gets caught up at
some boundary, but persists.

### Where to look next

VICE's CIA timer A logic in `cia-timer.c` and `ciatimer.c`. JaC64's
in `CIA.java` + `CIATimer.java`. Possible divergences:

1. **Timer init cycle** — when does CIA timer A start counting after
   reset? VICE: `cia_reset` sets specific state. JaC64: `CIA.reset`
   may differ.
2. **Underflow → IRQ delay** — VICE has a 1-cycle delay between
   timer underflow and IRQ source set. JaC64 may fire immediately.
3. **Timer A reload cycle** — VICE reloads on cycle after underflow;
   JaC64 timing may differ.
4. **CIA register read/write timing** — VICE's CIA register
   reads/writes have specific cycle handling for force-load and
   counter sample.

### Concrete next-session task

Capture trace at the SECOND divergence (line 585817 = $ea30 vs $ff48).
Identify what triggers the IRQ in VICE that JaC64 misses. That tells
us which CIA timer state differs. Find the specific line in JaC64's
CIA code that emits the wrong cycle. Fix.

Repeat for the next divergence pair, etc. Each pair is one bug. Likely
3-5 distinct bugs total once we account for cascading effects.

## Strategic note

This work is genuinely multi-day. Each fix:
- Requires identifying WHICH cycle of which CIA event diverges
- Requires reading VICE source for the exact behavior
- Requires careful patching of JaC64 with risk of breaking demos

User asked for the archaeology. So we're doing it. The investment
should be amortized: once JaC64's CIA + CPU + VIC-II all match
VICE byte-for-byte at the cycle level, MANY currently-broken demos
should also start working correctly. The slot-5 cell is the canary,
not the only beneficiary.

## Test corpus

After each fix, re-run:
- irq-ack-vicii.prg (should improve from 47/48 toward 48/48)
- Krestage 3 banner / FLI scenes (regression check)
- Any other Lorenz cycle tests (regression check)

## Commits in this archaeology arc

- `cb298e8` Phase 1 ground truth tooling
- `e1c1f29` Phase 2/3 trace diff identifies Phi1/Phi2 ordering
- `7b5b31a` Phase 4: $D019 Phi2 write-order fix
- `11bd26b` Phase 5: slot 5 is slot-spacing drift
- `306dd1a` Phase 5-next: per-PC cycle diff, IRQ pipeline race
- `0278fe7` Phase 6: pipeline math already matches VICE
- `a3a63e0` BA-low MAX semantics
- `743f223` Pipeline findings doc
- `3c0341c` CPU port pullup
- (this doc)
