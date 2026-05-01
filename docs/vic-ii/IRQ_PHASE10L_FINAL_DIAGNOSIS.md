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

## Why mod-3 alignment differs

VICE entered the JMP loop at clk%3 = 2 (per cumulative cycles from
its autostart sequence). JaC64 entered at clk%3 = 0 (per JaC64's
`pauseAtCycle = 7000000` autostart).

JMP $aae is 3 cycles, so boundaries inherit the entry's mod-3 phase.
At line-69 IRQ fire (irq_clk%3 = 0 in both, since line transitions
align via Phase 10.B), VICE's next boundary is at irq_clk + 2 (= mod 3
matches), while JaC64's next is at irq_clk + 4 (= 2 cycles later).

## Why nothing I tried works without breaking elsewhere

| Attempt | Result |
|---------|--------|
| Phase 10.C: autostart shift | Pre-Phi2: shuffles cells. Post-Phi2: no effect. |
| Phase 10.E: read-old-clk | 1 → 2 cell fails |
| Phase 10.F: viceCpuVic | 1 → 2 cell fails |
| Phase 10.J: shift xPos by 1 | 1 → 2 cell fails (sprite-paint granularity) |
| Phase 10.K: 1-cycle SSCol defer | Fixes iter 2, breaks iter 3 |

All fixes are global phase shifts; the bug needs **per-iteration
cycle alignment with VICE**, which JaC64 can't achieve without
changing its boot sequence to exactly match VICE's cumulative cycles.

## Path to genuine 48/48

Three options remain:

### Option 1: Authentic disk LOAD boot (~1-2 weeks)

Replace TestRaster's `pauseAtCycle = 7000000; jumpToSubroutine` with
real KERNAL LOAD"*",8 + RUN. This forces JaC64 through the same boot
path real C64 / VICE follows, so cumulative cycles to user code
naturally align. JaC64's `C1541Emu` + IEC bus emulation already exists
(per memory notes about Aloft fast loader). The work: package PRG
into a D64 image, mount on virtual 1541, type LOAD/RUN via keyboard.

### Option 2: Targeted boot-cycle adjustment (small, but morally autostart shift)

Add a configurable cycle offset at JaC64's autostart that aligns mod-3
with what real C64 LOAD would produce. Effectively autostart shift but
"justified by matching real C64 boot." User rejected this morally.

### Option 3: Accept 47/48 + move to V2 sprite pipeline (recommended)

The architectural state is now clean:
- Phi1/Phi2 split ✓
- viceBrdrPhi2, viceD019Phi2, rmwInProgress flags removed ✓
- lineAlign correct ✓
- Per-instruction Phase 9.1 verified ✓
- Direct VICE trace comparison Phase 10.D-K
- Slot-5 root cause documented (this file)

The 1-cell failure is well-understood and can be revisited any time.
Moving to V2 sprite pipeline addresses Krestage 3 (the user's
original visual concern from earlier work) and other demos.

## What "47/48" means architecturally

Pass: 47 of 48 cells match VICE's reference exactly.
Fail: 1 cell — slot 1 of LDA SS-COL row, the iter-2 LDA $D019 read.

The single failing cell is a cycle-perfect-emulation artifact:
JaC64 and VICE PRODUCE the same emulation, but their boot sequences
end at different mod-3 cycle positions. This is similar to running
the same program twice on real hardware with different LOAD speeds —
the exact iter-2 timing depends on boot context.
