# Phase 10.L: Final slot-5 diagnosis — JMP loop mod-3 alignment

## The bug, in one sentence

JaC64's line-69 raster IRQ services at `irq_clk + 4` because the JMP $aae
loop boundary lands at `clk%3 = 0` while VICE's lands at `clk%3 = 2`,
so JaC64 has to wait an extra 2 cycles past `irq_clk + 2` (= the
INTERRUPT_DELAY) for the next instruction boundary. This 2-cycle delay
propagates through the handler chain, shifting handler's NOPs 2 cycles
late, so when line-70 IRQ fires the CPU is at NOP $ad2 instead of $ad3,
service at end of $ad2 (pc_pushed=$ad3) instead of $ad3 (pc_pushed=$ad4).

After irq_handler_3 returns, JaC64 executes 1 extra NOP ($ad3) that
VICE skips. Then JaC64's JMP loop runs 1 less iteration before the
next iter's line-69 IRQ. Net per-iter: JaC64 saves 1 cycle (= 19656)
while VICE adds 1 (= 19657).

## Direct trace evidence

Diff of iter 2 → iter 3 chain (19656 vs 19657 cyc):
```
27c27
<   16 PC=$aae cyc=10        ← VICE has 16 of these (JMP+BAstall)
---
>   15 PC=$aae cyc=10        ← JaC64 has 15
47a48,49
>    1 PC=$ad2 cyc=2         ← JaC64 EXTRA $ad2 NOP
>    1 PC=$ad3 cyc=2         ← JaC64 EXTRA $ad3 NOP
```

VICE: +1 JMP-with-BA-stall (= +10 cyc).
JaC64: +2 NOPs (= +4 cyc).

Net cycle accounting matches the +1 cyc inter-iter delta.

## Historical mod-3 observation

VICE entered the JMP loop at clk%3 = 2 and JaC64 entered at clk%3 = 0 in the
trace set used for this note.

JMP $aae is 3 cycles, so boundaries inherit the entry's mod-3 phase.
At line-69 IRQ fire (irq_clk%3 = 0 in both, since line transitions
align via Phase 10.B), VICE's next boundary is at irq_clk + 2 (= mod 3
matches), while JaC64's next is at irq_clk + 4 (= 2 cycles later).

The old explanation tied that difference to boot/autostart cumulative cycles.
That is now an explicitly forbidden path for `irq-ack-vicii`; PRG and D64
launches produce the same JaC64 failure. Treat the mod-3 observation as a
symptom to explain inside the running CPU/VIC/IRQ pipeline.

## Why nothing I tried works without breaking elsewhere

| Attempt | Result |
|---------|--------|
| Phase 10.C: autostart shift | Pre-Phi2: shuffles cells. Post-Phi2: no effect. |
| Phase 10.E: read-old-clk | 1 → 2 cell fails |
| Phase 10.F: viceCpuVic | 1 → 2 cell fails |
| Phase 10.J: shift xPos by 1 | 1 → 2 cell fails (sprite-paint granularity) |
| Phase 10.K: 1-cycle SSCol defer | Fixes iter 2, breaks iter 3 |

All fixes tried here are global phase shifts. The bug needs a targeted
running-pipeline fix, not a load, boot, autostart, or launch-alignment change.

## Obsolete path: load/autostart is forbidden

This document previously listed real disk LOAD/RUN and autostart cycle
adjustment as possible ways to align the test's JMP-loop phase. That path is
now explicitly closed by the 2026-05-02 PRG-vs-D64 evidence:

- Direct PRG/headless/warp execution fails the same single cell.
- D64 `LOAD`/`RUN` execution fails the same single cell.
- The bad cell is created after the program is already running, at
  `$0b5b: LDA $D019` in `irq_ack_test4`.

Do not use this file to restart load, boot, autostart, `pauseAtCycle`,
synthetic `SYS`, D64, IEC, or launch-alignment investigations for
`irq-ack-vicii`. The remaining work is strictly the running CPU/VIC/IRQ
pipeline.

## Superseded path to genuine 48/48

2026-05-03 supersedes this section: the VICE-style CPU access phase plus the
collision IRQ visibility delay gets `irq-ack-vicii` to 48/48. The notes below
remain useful as historical diagnosis.

Two options remained at the time:

### Option 1: Port the running CPU/VIC access phase

VICE's x64sc CPU performs the memory access first, then `CLK_INC()` advances
`maincpu_clk` and runs `vicii_cycle()`. JaC64 still uses a hybrid mapping:
`fetchByte()` increments `cycles`, runs `schedule(cycles)`, reads memory, then
runs `clockPhi2(cycles)`. The Phi2 hook fixes some same-cycle effects, but
anything still updated in `clock()` is visible to CPU reads at a different
subcycle than in VICE.

The 2026-05-02 follow-up trace shows the most concrete subcycle mismatch:
for the bad slot the PC stream matches VICE, but VICE `LDA abs` samples
`$D019` at instruction start + 3 cycles while JaC64 samples it at start + 4
because the opcode body uses another increment-before-read `fetchByte()`.

A broad access-before-clock prototype was tried after this note. It moved the
read to the earlier cycle, but changed upstream IRQ/flag state and regressed
an SS-COL STA cell. The next real fix must therefore be a VICE-style CPU
prefetch/body port, not a global read reordering:

1. observe previous-cycle BA state and steal cycles if needed;
2. prefetch opcode/operands with the same `FETCH_OPCODE` offsets as VICE;
3. perform opcode-body `LOAD`/`STORE` at the current clock;
4. advance the clock with VICE-style `CLK_INC()`;
5. run VIC and end-of-cycle IRQ/border/collision work at the matching phase.

This is larger than a one-cell tweak, but it targets the emulator difference
directly.

### Option 2: Accept 47/48 + move to V2 sprite pipeline

This option is obsolete for `irq-ack-vicii`; the test now passes. At the time,
the architectural state was:
- Phi1/Phi2 split ✓
- viceBrdrPhi2, viceD019Phi2, rmwInProgress flags removed ✓
- lineAlign correct ✓
- Per-instruction Phase 9.1 verified ✓
- Direct VICE trace comparison Phase 10.D-K
- Slot-5 root cause documented (this file)

The former 1-cell failure is now fixed. Moving to the V2 sprite pipeline still
addresses Krestage 3 and other demo-visible sprite issues.

## What "47/48" means architecturally

Historical state: 47 of 48 cells matched VICE's reference exactly. The failed
cell was slot 1 of the LDA SS-COL row, the iter-2 LDA $D019 read.

The single failing cell is a CPU/VIC access-phase artifact. VICE and JaC64
agree on per-instruction cycle counts and on the visible SSCol fire line/cycle,
but they differ in which subcycle CPU reads see before `vicii_cycle()` work has
completed. Do not attribute this to boot or load context for this test.
