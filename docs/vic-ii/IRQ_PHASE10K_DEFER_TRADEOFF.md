# Phase 10.K: Why a simple 1-cycle defer can't fix slot-5 alone

## Setup

After Phase 10.J identified the sprite-paint cycle alignment as the
proximate cause, I tried to fix slot-5 by adding back a 1-cycle SSCol
fire defer (the mechanism removed during the Phi2 refactor).

## Result: cells SHIFT but net failure count stays the same

Adding 1-cycle defer (= delay fire visibility by 1 PHYSICAL cycle):

| iter | VICE LDA cyc | JaC64 LDA PHYSICAL CYCLE | Result |
|------|--------------|--------------------------|--------|
| 1 | 43 | 43 | both pre-fire ✓ |
| 2 | 44 | 44 | NOW pre-fire (FIXED!) |
| 3 | 45 | 44 | NOW pre-fire (BROKEN — VICE is post-fire) |
| 4 | 46 | 45 | unchanged (post-fire) |
| 5 | 47 | 47 | unchanged |
| 6 | 48 | 47 | unchanged |

Net: 1 cell fixed (iter 2), 1 cell broken (iter 3). Same total fail.

## Why iter 3 breaks: irregular inter-iteration cycle accounting

VICE inter-iter LDA clk delta: **consistent 19657** (= PAL frame + 1).
JaC64 inter-iter LDA clk delta: **19657, 19656, 19657, 19658, 19656** (variable).

JaC64's iter 2 → iter 3 takes 19656 cycles vs VICE's 19657.
JaC64 "loses" 1 cycle between iter 2 and iter 3.

Result: JaC64 iter 3's LDA lands at the SAME cyc-within-line as iter 2
(both at JaC64 vicCycle 45). VICE iter 3 LDA shifts +1 (raster_cycle 45
vs iter 2's 44).

For SSCol fire at fixed PHYSICAL CYCLE 45 (line 74):
- VICE iter 3 LDA AT cycle 45 → AT fire visibility → post-fire ✓
- JaC64 iter 3 LDA at cycle 44 (because JaC64 didn't shift) → BEFORE
  fire visibility → pre-fire ✗

So the bug isn't a single-cycle defer — it's that **JaC64 doesn't
consistently advance per iteration** the way VICE does.

## Per-instruction cycles match (Phase 9.1, Phase 10.I confirmed)

This means the divergence is in:
- **Number of instructions executed per iteration** (= some path is
  different between certain iterations).
- **Specific instruction cycle count under specific addressing**
  (page-cross sometimes, not other times).
- **BA-low timing** that depends on sprite Y / dma state which evolves
  per iteration.
- **CIA timer underflow** at slightly different cycles per iteration.

## Path forward

The FIX is to find the ONE instruction where JaC64 takes a different
cycle count than VICE for the SS-COL test4 path on iter 3 specifically.

Possible candidates to investigate:
1. **Sprite display / DMA state evolves per iteration**: SS-COL test
   has sprites enabled. Each iter, sprites' Y/X may shift. BA-low
   timing changes accordingly. JaC64 vs VICE BA-low alignment
   different per iter → cycle drift.
2. **`lda ($fe),y`** indirect-indexed with page-cross handling. $fe
   pointer advances per iteration. Some iterations cross a page,
   others don't.
3. **`bne` page-cross** for branches that may span page boundaries
   for specific iter values.
4. **CIA timer state** affecting NMI/IRQ delivery at iter boundary.

## Conclusion

The slot-5 fix requires **per-iteration cycle alignment** with VICE,
not just sprite paint cycle alignment. The sprite-pipeline V2 refactor
helps with paint cycle but doesn't fix per-iteration cycle drift.

This is a CPU-side / addressing-mode / BA-low timing issue that needs
its own targeted investigation — likely tracking down 1 specific
instruction whose cycle count differs from VICE for iter 3's specific
register state.

**Estimated effort to actually fix slot-5: 1-2 more sessions of
focused per-iter trace diff work.** The V2 sprite work is orthogonal
(needed for Krestage 3) but doesn't solve slot-5 alone.

## Tactical recommendation

Since the architectural state is now clean (Phi2 split, no compensation
flags, all per-instruction cycles match) and slot-5 needs a different
class of investigation:

1. **Accept 47/48 for now** — document slot-5 as a known per-iter timing
   drift that needs targeted work.
2. **Move on to V2 sprite pipeline** for Krestage 3 visual fixes (the
   user's original concern).
3. **Defer slot-5 final fix** to a focused session once the V2 work is
   done — at that point the sprite cycle alignment will also help.
