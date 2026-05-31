# VIC-II: sprite DMA cycle schedule — JaC64 vs VICE viciisc

> Status (2026-04-23): In-progress investigation into the "staircase" artifact
> on Krestage 3's KRESTAGE banner. Documents the authoritative cycle-exact
> schedule in VICE and the current JaC64 divergence. Root cause of the
> visible stair effect is still open.

## The artifact

In Krestage 3's beast scene KRESTAGE banner (bottom of screen), JaC64
renders with a staircase — each successive letter (K→R→E→S→T→A→G→E) has
its grey-stripe pattern shifted down by a scan line or so. VICE shows the
same grey stripe pattern aligned across all 8 letters; the stripes even
extend across the left/right border of the bar. JaC64 shows those borders
as solid grey instead of striped.

The KRESTAGE banner is built from the 8 hardware sprites
(sprite 0 = K, sprite 1 = R, …, sprite 7 = E). All 8 have `Y = $FA = 250`
in this scene (verified in a live trace).

## Authoritative: VICE `viciisc/vicii-chip-model.c` cycle_tab_pal

PAL raster line, 1-based cycle numbering (both Phi1 and Phi2 half-cycles
listed):

| cycle | Phi1 type          | Phi2 type          | BA mask         |
|-------|--------------------|--------------------|-----------------|
|  1    | SprPtr(3)          | SprDma0(3)         | BaSpr2(3,4)     |
|  2    | SprDma1(3)         | SprDma2(3)         | BaSpr3(3,4,5)   |
|  3    | SprPtr(4)          | SprDma0(4)         | BaSpr2(4,5)     |
|  4    | SprDma1(4)         | SprDma2(4)         | BaSpr3(4,5,6)   |
|  5    | SprPtr(5)          | SprDma0(5)         | BaSpr2(5,6)     |
|  6    | SprDma1(5)         | SprDma2(5)         | BaSpr3(5,6,7)   |
|  7    | SprPtr(6)          | SprDma0(6)         | BaSpr2(6,7)     |
|  8    | SprDma1(6)         | SprDma2(6)         | BaSpr2(6,7)     |
|  9    | SprPtr(7)          | SprDma0(7)         | BaSpr1(7)       |
| 10    | SprDma1(7)         | SprDma2(7)         | BaSpr1(7)       |
| 11-14 | Refresh            | —                  | Fetch / Fetch   |
| 15-54 | FetchG             | FetchC             | Fetch           |
| 55    | FetchG (last)      | —                  | BaSpr1(0) + ChkSprDma |
| 56    | Idle               | —                  | BaSpr1(0) + ChkSprDma |
| 57    | Idle               | —                  | BaSpr2(0,1)     |
| 58    | SprPtr(0)          | SprDma0(0)         | BaSpr2(0,1) + ChkSprDisp + UpdateRc |
| 59    | SprDma1(0)         | SprDma2(0)         | BaSpr3(0,1,2)   |
| 60    | SprPtr(1)          | SprDma0(1)         | BaSpr2(1,2)     |
| 61    | SprDma1(1)         | SprDma2(1)         | BaSpr3(1,2,3)   |
| 62    | SprPtr(2)          | SprDma0(2)         | BaSpr2(2,3)     |
| 63    | SprDma1(2)         | SprDma2(2)         | BaSpr3(2,3,4)   |

**Key points**:

- Each sprite takes **two full cycles** to fetch (ptr + 3 data bytes), one
  cycle for ptr+dma0 and one for dma1+dma2.
- Sprites 0–2 fetch at cycles 58–63 of the current line (after the visible
  area ends at ~cycle 54).
- Sprites 3–7 fetch at cycles 1–10 of the **next** line (before the
  visible area starts at cycle 15).
- All 8 sprites that were activated display on the same raster line
  because the load happens around the boundary, in time for that line's
  visible cycles.

## JaC64 current schedule (C64Screen.java switch-case)

| cycle | operation                            |
|-------|--------------------------------------|
|  1    | `sprites[3].readSpriteData()` (3 bytes in one call) |
|  3    | `sprites[4].readSpriteData()`        |
|  5    | `sprites[5].readSpriteData()`        |
|  7    | `sprites[6].readSpriteData()`        |
|  9    | `sprites[7].readSpriteData()`        |
| 54    | `check sprite DMA` (y == ypos → dma=true) |
| 57    | `painting = true` for DMA-active sprites, then `sprites[0].readSpriteData()` |
| 59    | `sprites[1].readSpriteData()`        |
| 61    | `sprites[2].readSpriteData()`        |

So JaC64:

- Does all 3-byte fetch in a single cycle call (VICE splits over 2 cycles).
- Checks DMA at cycle 54 (VICE: cycle 55 & 56).
- Fetches sprite 0 at cycle 57 (VICE: cycles 58–59).
- Fetches sprite 1 at cycle 59 (VICE: cycles 60–61).
- Fetches sprite 2 at cycle 61 (VICE: cycles 62–63).
- Fetches sprite 3 at cycle 1 (VICE: cycles 1–2). ← aligned!
- Fetches sprite 7 at cycle 9 (VICE: cycles 9–10). ← aligned!

So sprites 0/1/2 are fetched **one cycle earlier** than VICE. Sprites
3–7 are roughly aligned (ignoring the 2-cycle split).

## What this does and doesn't explain

- **Earliest first-display line**: JaC64 trace (`jac64.traceSprFirstPaint`)
  shows all 8 sprites first render non-blank data at vbeam=250, same as
  VICE would. So the fetch-line alignment between the two fetch groups
  (0-2 vs 3-7) is correct in terms of which *raster line* the data lands
  on — not a straightforward 1-line staircase cause.
- But JaC64 also has **sprite multiplex** going on in this scene: in the
  same line, sprite 0's renderX was observed at $37, $77, $177 and $200
  — the demo reprograms sprite X mid-line to reuse one sprite for multiple
  positions. The sprite-repeat bug zone cross-check, the shift register
  reload timing on mid-line pointer writes, and the interaction with
  partial-render orchestration are all suspect. We haven't verified which
  of those is producing the visible offset.

## Suspected root cause (not yet confirmed)

One hypothesis: VICE's shift register is loaded in two half-cycles
(Phi1 + Phi2), and rendering can read the partially-loaded value on the
cycle **between** the two fetches. JaC64 loads all 3 bytes atomically at
one cycle, which means for sprites 0/1/2 the fully-loaded register is
available one cycle earlier than in VICE. If partial-render orchestration
reads shift register state at exactly that cycle (e.g., when a $D000
write triggers a mid-line sprite repositioning), JaC64 picks up the new
data but VICE hasn't latched it yet — producing per-sprite one-cycle
offsets that compound into the staircase.

Alternative hypothesis: the demo writes sprite pointer (screen RAM
$07F8+i) and/or $D000+2i mid-line, and JaC64 doesn't re-fetch the sprite
data in response, so multiplexed positions show sprite-data from the
wrong row index.

## What to verify next

1. Cross-check with VICE trace: run `x64sc -trace` against the same PRG
   at the banner scene, capture actual per-cycle sprite shift-register
   contents, diff against JaC64's.
2. Instrument JaC64 to log screen-RAM `$07F8+i` writes between vbeam 200
   and 260. If present, sprite data must be re-fetched on those writes.
3. Confirm whether the grey borders in VICE come from sprites covering
   those X positions or from a wider char-mode background.

## Source pointers

- VICE cycle table: `../vice-emu/vice/src/viciisc/vicii-chip-model.c:110`+
- VICE fetch functions: `../vice-emu/vice/src/viciisc/vicii-fetch.c:60`+
- VICE DMA activation / display-bits: `../vice-emu/vice/src/viciisc/vicii-cycle.c:62-113`
- JaC64 sprite cycle schedule: `com/dreamfabric/jac64/C64Screen.java`
  cases 1, 3, 5, 7, 9, 54, 57, 59, 61.
- JaC64 sprite data read: `com/dreamfabric/jac64/C64Screen.java:3111`
  (Sprite.readSpriteData).
