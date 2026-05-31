# Phase 10.J: Slot-5 root cause = sprite-at-X=248 paints 1 case early

## The bug

JaC64's `drawSpritesLegacy()` paints sprite-at-X=$F8 (= 248) at JaC64
**case 43**. VICE paints the same sprite at **raster_cycle 45**.

Per the established case mapping, JaC64 case N = VICE raster_cycle (N+1).
So JaC64 case 43 = VICE raster_cycle 44 — meaning JaC64 paints **1 cycle
earlier** than VICE.

## Mechanics

`drawSpritesLegacy` paints pixel range `[xPos-8, xPos)` then advances
`xPos += 8`. xPos is initialized to 16 in case 12. So:

| case N | xPos at entry | paint range |
|--------|---------------|-------------|
| 13 | 16 | [8, 16) |
| 14 | 24 | [16, 24) |
| 15 | 32 | [24, 32) |
| ... | ... | ... |
| 43 | **256** | **[248, 256)** ← sprite at X=248 painted |
| 44 | 264 | [256, 264) |

So `case 43` is when xPos first crosses 248.

## Why this causes slot-1 LDA SS-COL to fail

1. Sprite paint at JaC64 case 43 (line 74 vicCycle 43).
2. Collision detected during `drawSpritesLegacy`. Sets `sprColFirePending`
   at end of `clock(43)`.
3. `clockPhi2(43)` fires SSCol → bit 2 set in irqFlags.
4. NEXT CPU access (at vicCycle 44 or later) sees bit 2 set.

VICE flow:
1. Sprite paint at VICE raster_cycle 45.
2. Collision detected. Fire at end of vicii_cycle for raster_cycle 45.
3. CPU access at clk for raster_cycle 46 onwards sees fire.

For test4 SS-COL iter 2:
- VICE LDA $D019 at raster_cycle 44 (= 1 cyc BEFORE fire visibility at
  raster_cycle 46). Reads $70 → cell $00 ✓.
- JaC64 LDA $D019 at vicCycle 45 (= 1 cyc AFTER fire visibility at
  vicCycle 44). Reads $f4 → cell $84 ✗.

## Why JaC64 paints at case 43 instead of 44

`xPos = 16` initialized in case 12 (line 2249 in C64Screen.java). Then
each case from 13 onwards calls `drawSprites()` which advances xPos by
8. Sprite-at-X=248 lands in case 43's range.

If we want sprite-at-X=248 to paint at case 44 (matching VICE):
- Set `xPos = 8` at case 12 (instead of 16), OR
- Move `xPos = 16` initialization from case 12 to case 13.

Either changes ALL other sprite paint timings by 1 cycle. Risk: breaks
demos that expect sprites at specific X positions to paint at current
JaC64 case alignment.

## Why this isn't caught by the SSCol fire defer

The existing 1-cycle SSCol fire defer (commit 580fa6e in MEMORY) tried
to align JaC64's fire absolute-clock with VICE's at "cyc 45." But:
- The defer was about WHEN fire is visible relative to the absolute clk
  of the cycle, not WHEN sprite painting happens in the case dispatcher.
- The Phi2 refactor (Phase 10.G) replaced the manual defer with structural
  intra-cycle clock→clockPhi2 handoff. Same semantics.
- Both before and after Phi2, JaC64 paints sprite at case 43, fires at
  case 43's clockPhi2, visibility from case 44.
- VICE paints at raster_cycle 45, fires at end of vicii_cycle(45),
  visibility from raster_cycle 46.

The 1-case painting offset is the LOAD-BEARING bug.

## Path to fix

Three options:

**Option A: shift xPos init by 1 cycle.** `xPos = 8` at case 12 (was 16).
ALL sprite painting shifts 1 cycle later. Need to test against many
demos that depend on sprite timing — Krestage 3, sprite collision tests,
etc. Risk: medium-high regression.

**Option B: per-pixel sprite renderer (V2).** The `newSprites` V2 work
in `SPRITE_REFACTOR_PLAN_V2.md` ports VICE's pixel-level sprite
sequencer. It paints at exact VICE-equivalent cycles. Bigger refactor
(~39h estimated) but cleanest.

**Option C: accept 47/48.** The bug is specific to sprite-at-X=$F8 in
this test program; most demos don't rely on this exact pixel timing.
The architectural state is otherwise clean (Phi1/Phi2 split, lineAlign,
no compensation flags). Live with the 1-cell failure.

## Confirmed via direct VICE trace comparison

Per-instruction VICE PC trace (using JAC64_PC_TRACE_FILE patch in
`6510dtvcore.c`) and JaC64 PC trace match cycle-for-cycle through the
ENTIRE handler_2 → test4 chain. The only divergence is at the
SPRITE COLLISION DETECTION POINT in the case dispatcher.

Per-instruction Phase 9.1 verification stands. The bug is purely in
WHICH case dispatcher cycle the sprite collision happens.

## Empirical test of Option A (xPos shift)

Tried `xPos = 8` (instead of 16) at case 12 to shift sprite paint by 1 case.

Result for irq-ack-vicii row 03 (LDA SS-COL):
- Before: `ddddd.` (5 d's = slot 5 fail vs VICE 4 d's)
- After xPos=8: `ddd...` (3 d's = slot 3 fail = OVERSHOT by one)

Also row 03 STA SS-COL: was `***-**` (matches VICE), now `**-***` (1 cell off).

Net: 1 cell fixed, 2 cells broken. **Net WORSE.**

Reverted.

## Conclusion

Option A doesn't work because JaC64's drawSprites paints 8 pixels per
case dispatcher cycle. The sprite-X position to paint-cycle mapping is
in 8-pixel increments (= each case shifts by 8 pixels). To paint
sprite-at-X=248 at case 44 instead of 43, xPos init shifts by 8 pixels,
which moves ALL sprite paint cycles by 8 pixels → equivalent to 1 case
later for ALL sprite positions, not just X=248.

The proper fix is **Option B (per-pixel sprite renderer V2)** — see
`SPRITE_REFACTOR_PLAN_V2.md`. The newSprites V2 sequencer paints at
exact VICE-equivalent cycles per pixel position. ~39h of focused work.

Or **Option C: accept 47/48** — the architectural state is otherwise
clean. The 1-cell failure is a known sprite-pipeline limitation that
the V2 refactor resolves cleanly.

The **real fix path for slot-5** is the V2 sprite pipeline, not a
case-dispatcher tweak.
