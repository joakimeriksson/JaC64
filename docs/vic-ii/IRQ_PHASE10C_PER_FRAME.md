# Phase 10.C: Per-frame cyc analysis

## Method

Extracted every JaC64 LDA $D012 in handler_2 (val=70, pc=$ae9
since JaC64 traces NEXT instruction's PC) sequentially. Ran for
the FULL irq-ack-vicii.prg test (50 frames covered).

Compared to VICE's same events (24 SS-COL frames covered).

## Per-slot cyc-within-line at handler_2's `lda $d012`

```
                  STA  INC  ASL  LDA       (testset)
                  RASTER         RASTER
JaC64 slot 6:     60   59   59   60
JaC64 slot 5:     59   60   60   59
JaC64 slot 4:     60   60   60   59
JaC64 slot 3:     60   60   60   60
JaC64 slot 2:     60   60   59   59
JaC64 slot 1:     60   60   60   59

                  STA  INC  ASL  LDA       (SS-COL testset)
JaC64 slot 6:     59   59   59   59     ← VICE=58 in some
JaC64 slot 5:     60   60   60   60 ★   ← VICE=58/59
JaC64 slot 4:     59   59   59   60     ← VICE=58/59
JaC64 slot 3:     59   59   59   59     ← VICE=58/59
JaC64 slot 2:     60   59   59   59     ← VICE=58/59
JaC64 slot 1:     59   59   59   60     ← VICE=58
```

LDA SS-COL slot 5 (★ = the failing test cell):
- JaC64: cyc=60
- VICE:  cyc=59

**1-cycle delta — JaC64's CPU is 1 cycle "later within line" at
handler_2's `lda $d012` for the failing frame.**

This delta carries through handler_2's wait code → delay() → test
instruction (LDA $D019 in test4) → SAME 1-cycle delta there. The
SSCol IRQ flag set at line 74 cyc 45 falls BETWEEN VICE's cyc N
and JaC64's cyc N+1 → JaC64 reads the flag (=$84), VICE doesn't
(=$00). That's slot 5 LDA SS-COL.

## Source of the 1-cycle CPU phase delta (UNSOLVED)

Per-instruction CPU cycles match (Phase 9.1).
Frame periods match (Phase 9.A frames 0-5).
Line transitions match (Phase 10.B).

Yet handler_2's `lda $d012` lands at cyc=60 in JaC64 vs cyc=59 in
VICE. This contradicts cycle-accurate equivalence.

Direct calculation:
- JaC64 $ab1 to $ae6 LDA = 112 cycles (verified per-instruction).
- VICE same = 112 cycles.
- If handler entry at line 69 cyc=11 in JaC64, $ae6 at line 70
  cyc=60. (= 63 + 60 - 11 = 112 ✓)
- If handler entry at line 69 cyc=10 in VICE, $ae6 at line 70
  cyc=59. (= 63 + 59 - 10 = 112 ✓)

So **JaC64 enters handler 1 cycle LATER within line** than VICE
(line 69 cyc 11 vs cyc 10). This is the IRQ delivery boundary
phase issue documented in `IRQ_PIPELINE_FINDINGS.md`.

CPU services IRQ at instruction boundary >= irq_clk + 2 (= 6502
INTERRUPT_DELAY). In jmp loop ($aae), each JMP is 3 cycles. CPU
boundaries align mod 3. Whether JaC64's mod-3 boundary at IRQ
service time is 1 cycle later than VICE's depends on cumulative
clk mod 3 from boot.

Possible cause: **autostart cycle count differs between
JaC64 and VICE**. JaC64's `detSysJump` pauses at exactly cycle
7,000,000. VICE's natural autostart reaches `$a00` at cycle
3,131,552. The cumulative `mod 3` differs:
- JaC64 7000000 mod 3 = 1
- VICE 3131552 mod 3 = 2
- Difference: 1 cycle mod 3 → could explain the 1-cycle JMP
  boundary phase shift.

## Hypothesis tested: autostart phase shift (REJECTED)

Hypothesized that shifting `detSysJump` target by ±1 would align
JaC64's clk mod 3 with VICE's. Tested empirically:

```
target=7000000 → cyc=60 (current default)
target=7000001 → cyc=59  ← matches VICE!
target=7000002 → cyc=59
target=7000003 → cyc=59
```

The cyc value at handler_2's `lda $d012` DID change to match VICE
with target=7000001. **But the test still fails 1/48 cells** —
just a different cell. With target=7000001:
- Slot 5 LDA SS-COL: fixed (4 D's, matches VICE).
  No wait — re-checked, still 5 D's.
- Slot 5 STA RASTER: row 00 col 5 changed `*` → `-` (= regression).

**Net: same number of failed cells, just shuffled.** The autostart
phase shift is a NON-FIX — it's just papering over the underlying
bug by lucky alignment for one cell at the cost of another.

### Why this CAN'T be the right approach

If shifting WHEN the test starts running fixes/breaks cells, the
issue is fundamentally a CPU↔VIC cycle-accuracy delta, not a
boot-time alignment quirk. The CPU's phase relative to VIC line
transitions should be invariant of boot timing. If it's not, the
underlying CPU/VIC tick model is wrong by 1 cycle somewhere.

## Real root cause (still unsolved)

JaC64 cyc varies 59/60 per frame. VICE varies 58/59. JaC64's range
is **shifted +1** relative to VICE's. This +1 shift is the actual
bug.

Per-instruction cycles match (Phase 9.1).
Frame periods match (Phase 9.A frames 0-5).
Line transitions match (Phase 10.B).

The remaining suspect: CPU↔VIC ordering. Possibilities:
1. **IRQ sampling phase**: when CPU samples the IRQ line relative
   to vicii_cycle within a CLK_INC may differ.
2. **BA-low absorption order**: JaC64's `waitForBus` may stall the
   CPU 1 cycle differently from VICE's BA-low handling.
3. **6510 PHI1/PHI2 split**: VICE has explicit Phi1 fetch +
   Phi2 access; JaC64 has a single chips.clock per cycle. The
   relative ordering of "VIC reads from memory" vs "CPU reads
   from memory" within a cycle may differ.

## Phase 10.D plan (next)

Add `EV-IrqService clk=N pc_pre_irq=$X pc_handler=$Y` event in
both emulators, fired exactly when CPU enters `doInterrupt`
(JaC64) / `do_interrupt` (VICE x64sc).

Diff: at frame 0's first raster IRQ at line 69, find the EXACT
clk where CPU services. If JaC64 services at clk K+4 (line 69
cyc 4) and VICE at clk K+3 (line 69 cyc 3), that's the
1-cycle bug — CPU's IRQ check timing differs.

Fix would target `irqCycleStart` calculation in
`MOS6510Core.setIRQLow()` or the `cycles >= irqCycleStart` check
in `emulateOp()`.
