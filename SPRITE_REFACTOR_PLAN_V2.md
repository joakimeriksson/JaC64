# Strict Detailed Plan: VICE-Accurate Sprite Pipeline for JaC64

## Goal
Pass Crest's `NO VIC INSIDE` probe in Krestage 3. That requires matching VICE's
`x64sc` behavior on mid-line `$D010`, `$D01C`, `$D01D` writes — including the
undocumented VIC-II quirks (MC-bug, expand-X repeat pixels) that the probe
relies on.

## Source of truth
`/Users/joakimeriksson/work/vice-emu/vice/src/`

| VICE file | What to port | JaC64 target |
|---|---|---|
| `raster/raster-changes.h` | Sorted pointer-change queue API | `RasterChangeQueue.java` (rewrite) |
| `raster/raster-sprite.h` | Sprite fields: `x, x_shift, x_shift_sum, mc_bug, in_background, exp_flag, dma_flag, memptr, memptr_inc` | `SpriteSequencer.java` fields |
| `raster/raster-sprite-status.h` | Status struct: `dma_msk, new_dma_msk, sprite_data[2 buffers], collisions` | New `SpriteStatus.java` |
| `vicii/viciitypes.h:124` | `VICII_RASTER_X(cycle) = (cycle - 17) * 8 + screen_leftborderwidth` | New `rasterX()` in C64Screen |
| `vicii/vicii-mem.c:201-266` | `store_sprite_x_position_lsb/msb` | Replace `$D010` / `$D000-E` handlers |
| `vicii/vicii-mem.c:685-747` | `d01c_store` with MC-bug computation | Replace `$D01C` handler |
| `vicii/vicii-mem.c:749-795` | `d01d_store` with X-expand + x_shift | Replace `$D01D` handler |
| `vicii/vicii-sprites.c:1228-1310` | `vicii_sprites_set_x_position` | New method |
| `vicii/vicii-sprites.c:404-538` | `SPRITE_PIXEL`, `SPRITE_MASK`, `MCSPRITE_MASK` macros | Port to Java methods |
| `vicii/vicii-sprites.c:540-1100` | `draw_*_sprite_*` variants (hires/MC × normal/expanded × `mc_bug`) | Port to `SpriteSequencer` |
| `vicii/vicii-sprites.c` (top) | `sprite_doubling_table[256]`, `mcsprtable[256]` pre-computed tables | Static arrays in `SpriteSequencer` |

## Prerequisite: alignment with VICE's coordinate system

JaC64 currently mixes `xPos` (screen pixel position, 0..383) and sprite
register X (0..511). VICE uses `raster_x = (cycle-17)*8 +
screen_leftborderwidth`. Sprite register X is mapped via:
```
sprite_x_internal = sprite_register_x + screen_leftborderwidth - 0x20
```
(see `vicii-mem.c:710`).

**Decision**: add a `screenLeftBorderWidth` constant to `C64Screen` and use
VICE's formulas verbatim. Any existing calls that assume JaC64's pixel-space
go through a translation layer.

## Phases (strict order — each produces a buildable, non-regressing checkpoint)

### Phase A — Coordinate system + dual-buffer sprite data (4h)

1. **Add `screenLeftBorderWidth = 0x20`** in `C64Screen` (PAL; see
   `vicii-color.c` for constant).
2. **Add `rasterX(cycle)`** method: `return (cycle - 17) * 8 +
   screenLeftBorderWidth`.
3. **Replace `currentRasterX` field** — compute per call, don't cache.
4. **Allocate `sprite_data_1[8]`, `sprite_data_2[8]`** (double-buffer for
   current line being drawn + next line being fetched). Mirror VICE's
   `sprite_data` swap in `raster_sprite_status_t`. In JaC64, this maps
   to two 24-bit registers per sprite.
5. **Rewire `readSpriteData()`** to fill `new_sprite_data[n]`; swap into
   `sprite_data[n]` at line transition (cycle 62 / cycle 0).
6. **Test**: Let's Scroll It renders identically (legacy path).

### Phase B — Pointer-based RasterChangeQueue (3h)

Current `RasterChangeQueue` uses an integer `target` enum — can't change
arbitrary pointers. VICE stores a pointer to the variable plus new value.
We can't use real pointers in Java; use **field setters** via a functional
interface.

1. **New class `RasterChange`**:
   ```java
   class RasterChange {
     int where;                    // raster_x
     IntConsumer apply;            // applies new_value to target
     int newValue;
   }
   ```
2. **`RasterChangeQueue` becomes sorted list** (insertion sort by `where`,
   mirroring `raster_changes_add_sorted_int`).
3. **`drainUpTo(rasterXLimit)`** calls `apply.accept(newValue)` for each
   change where `where <= rasterXLimit`.
4. **Separate queues** for sprites and next-line (matching VICE's
   `raster->changes->sprites` and `raster->changes->next_line`).
5. **Test**: queue functionality unit test (Java main with asserts).

### Phase C — Sprite X position writes (4h)

Port `store_sprite_x_position_lsb`/`_msb` from `vicii-mem.c:201-266`.

1. **Replace `$D000/$D002/.../$D00E` (LSB) handler** in `setIOByte`:
   ```java
   case 0xd000: case 0xd002: case 0xd004: case 0xd006:
   case 0xd008: case 0xd00a: case 0xd00c: case 0xd00e:
     storeSpriteXLsb(address, data); break;
   ```
   where `storeSpriteXLsb` computes `new_x = value | (regs[0x10] & bit ? 0x100 : 0)`
   then calls `spritesSetXPosition(n, new_x, rasterX())`.

2. **Replace `$D010` handler** with `storeSpriteXMsb`: iterates sprites 0..7,
   calls `spritesSetXPosition` per sprite.

3. **New method `spritesSetXPosition(n, new_x, raster_x)`**: port
   `vicii_sprites_set_x_position()` from `vicii-sprites.c:1228-1310` —
   this includes the complex `next_pos / last_pos / change_pos` logic
   that decides when to apply the change vs defer.

4. **Test**: run probe — expect `$D01E` to produce collisions that
   correspond to the 10-cycle window when sprite 0 is at `$170`. Bits 0
   and 2 should now be set in $D01E.

### Phase D — $D01C (multicolor) write with MC-bug (5h)

Port `d01c_store` from `vicii-mem.c:685-747`. This is THE critical piece for
Crest's probe — the three `STA $D01C` writes at `$745E-$746B` trigger
specific MC-bug transitions.

1. **Add fields to SpriteSequencer**: `mc_bug`, `multicolor`.
2. **Port d01c_store logic**:
   - For each sprite, check if `$D01C` bit for this sprite changed.
   - If sprite is currently displaying (`sprite_x < raster_x < sprite_x + 48/24`):
     - Compute `delayed_load`, `delayed_shift`, `delayed_pixel` per VICE
       formula (differs for HIRES→MC vs MC→HIRES).
   - Queue `mc_bug` change at `raster_x + delayed_pixel` using
     `RasterChangeQueue`.
   - Queue `multicolor` change at same position.
3. **Rendering uses mc_bug**: `delayed_shift = mc_bug >> 1`,
   `delayed_load = mc_bug & 1`. Port to sequencer's pixel emission.
4. **Test**: run probe — expect `$D01E` to have sprites 1 and possibly
   0 collision (the MC-bug causes pixel shifts that produce unexpected
   overlaps). Bit 1 should be set.

### Phase E — $D01D (X-expand) write with x_shift (3h)

Port `d01d_store` from `vicii-mem.c:749-795`.

1. **Add fields `x_shift, x_shift_sum, x_expanded`**.
2. **Port d01d_store**:
   - For each sprite where bit changed:
     - Queue `x_expanded` change at `raster_x + 6`.
     - If `raster_x > sprite->x`, compute `actual_shift`:
       - HIRES→EXPAND: `sprite->x - raster_x`
       - EXPAND→HIRES: `(raster_x - sprite->x) / 2`
     - Update `x_shift_sum += actual_shift`, queue `x_shift` change.
3. **Rendering applies x_shift to sprite X**: `sprite_offset = x + x_shift`
   at fetch time.
4. **Test**: demos that use X-expand transitions render correctly.

### Phase F — Port sprite rendering (pixel sequencer + macros) (10h)

The big one. Replace current `SpriteSequencer.nextPixel()` with a port of
VICE's rendering macros.

1. **Pre-compute tables** in `SpriteSequencer` static init:
   ```java
   static final int[] SPRITE_DOUBLING_TABLE = new int[256];
   static final int[] MCSPR_TABLE = new int[256];
   static {
     int w = 0;
     for (int i = 0; i <= 0xff; i++) {
       MCSPR_TABLE[i] = i | ((i & 0x55) << 1) | ((i & 0xaa) >> 1);
       SPRITE_DOUBLING_TABLE[i] = w;
       w++;
       w |= (w & 0x5555) << 1;
     }
   }
   ```
2. **Port `SPRITE_PIXEL`** from `vicii-sprites.c:404-411` as
   `renderPixel(doDraw, spriteBit, pos, color, collMsk)` method.
3. **Port `SPRITE_MASK` / `MCSPRITE_MASK`** as loop-based renderers.
4. **Port `draw_hires_sprite_normal` / `_expanded` / `_mc_sprite_*`**
   from `vicii-sprites.c:540-1100` — 4 variants (hires/mc × normal/expanded).
5. **Port "repeat pixels" logic** at sprite-X boundaries where expand-X
   creates the specific pixel-repeat pattern (see `vicii-sprites.c:564-583`).
6. **Dispatch in nextPixel/drawSpritesV2**: based on sprite's
   `multicolor` and `x_expanded`, call correct renderer.
7. **Test**: run Krestage 3 probe — with all previous phases, probe should
   pass ($D01E & 0x07 == 0x07). Verify Let's Scroll It still renders.

### Phase G — Sprite DMA cycle alignment (2h)

JaC64's sprite DMA happens at cycle 54 (Y-match). VICE's at cycles 55-56.
Sprite data fetch for sprites 0-2 at cycles 58-62 of DMA-trigger line
(for display on line+1). Align.

1. **Move DMA trigger** from cycle 54 to cycle 55 (or wherever VICE has it
   — cross-check `vicii-fetch.c`).
2. **Move sprite-0-2 fetch** to cycles 58, 60, 62 (matching VICE).
3. **Move sprite-3-7 fetch** to cycles 1, 3, 5, 7, 9 (verify matches VICE).
4. **`painting=true`** — replace with DMA-based gating in V2 path.
5. **Test**: sprite Y positioning should be correct (one-line shift corrected);
   Krestage 3 probe still passing.

### Phase H — Collision register accumulation (2h)

VICE sets `sprite_sprite_collisions` and `sprite_background_collisions` at
bits per-sprite, and `$D01E`/`$D01F` accumulate across lines until read
(read clears to 0).

1. **Verify JaC64's `sprCol` reset-on-read semantics** (should already match
   — see C64Screen.java line ~770).
2. **Verify per-pixel collision mask logic** in the ported rendering
   matches VICE's `SPRITE_PIXEL`:
   ```
   collmsk_return |= collmskptr[pos];
   collmskptr[pos] |= sprite_bit;
   ```
3. **Test**: known-collision sprite demos produce correct values.

### Phase I — Final integration + cleanup (3h)

1. **Remove `-Djac64.newSprites` flag**; make V2 default.
2. **Delete legacy `drawSpritesLegacy()` and legacy `Sprite.getPixel()`**.
3. **Remove legacy fields from `Sprite`** (keep only DMA/enabled
   coordination with CPU side).
4. **Regression suite**: Let's Scroll It, Dutch Breeze (if downloadable),
   Lorenz CPU tests (sprite-independent but run anyway), Krestage 3
   (original + EMUFIXED).
5. **Commit sequence**: one commit per phase.

### Phase J — VICE trace diff verification (3h)

Even if probe passes, verify JaC64 matches VICE cycle-by-cycle.

1. **Run VICE with `-monlog -moncommands kr3.mon`** where kr3.mon contains
   `tr exec $7420 $7495` to log probe execution.
2. **Run JaC64 with `-Djac64.execTraceFrom=0x7420 -Djac64.execTraceTo=0x7495`**.
3. **Diff the two traces** — CPU cycle counts should match within ~1 cycle
   at each PC. Any larger divergence indicates residual timing bug.
4. **Fix residual issues** until traces match.

## Total estimate

| Phase | Work | Hours |
|---|---|---|
| A | Coordinate system + double-buffer | 4 |
| B | Pointer-based queue | 3 |
| C | Sprite X writes with proper deferral | 4 |
| D | $D01C MC-bug | 5 |
| E | $D01D X-expand | 3 |
| F | Pixel-level rendering (4 variants + macros) | 10 |
| G | DMA cycle alignment | 2 |
| H | Collision semantics verification | 2 |
| I | Final integration | 3 |
| J | VICE trace diff verification | 3 |
| **Total** | | **39 hours** |

5 focused engineering days. Each phase is a separate commit. If blocked on
any phase, the previous phase still ships behind the feature flag.

## What NOT to port (scope boundaries)

- **NTSC-specific code** (`sprite_wrap_x != 0x200`). Keep PAL-only for now.
- **VIC-IIe** (C128 extensions). Out of scope.
- **Sprite data cache** (`raster_sprite_cache`). Performance optimization,
  not correctness. Skip.
- **`SPRITE_REPEAT_PIXELS` for non-expanded sprites**. VICE does this for
  certain X positions on expand-X change. Rare corner case — verify
  Krestage 3 doesn't need it, defer if possible.

## Risks + mitigation

| Risk | Mitigation |
|---|---|
| Pointer-based change queue adds GC pressure in Java | Use object pool — pre-allocate `RasterChange[1024]`, reuse |
| Porting 4 sprite rendering variants is tedious and error-prone | Start with one (hires normal), add variants incrementally, test each |
| `screen_leftborderwidth` constant differs PAL vs NTSC — JaC64 may hardcode | Add as configurable constant, default PAL |
| VICE's `raster->changes->next_line` mechanism crosses line boundaries | Drain next_line queue at cycle 0 of each line (port from VICE's line transition) |
| Testing requires warp mode which is currently broken | Use PRG files extracted via `c1541 -extract` to bypass slow IEC |
| Legacy demos may break due to subtle render differences | Feature flag until all regressions closed |

## Verification matrix

After each phase, run and check:

| Phase | Krestage 3 probe `$D01E` | Let's Scroll It | Lorenz CPU |
|---|:---:|:---:|:---:|
| Current (V2 stages 1-6 + pixel fix) | `$00` | — | pass |
| A (coords + double buffer) | `$00` (unchanged) | identical to legacy | pass |
| B (pointer queue) | `$00` (unchanged) | identical | pass |
| C (sprite X deferral) | `$05+` (bits 0, 2 fire) | mostly identical | pass |
| D (MC-bug) | `$07`, **probe passes** | identical | pass |
| E-I | `$07` | identical | pass |
| J | traces match VICE within 1 cycle | identical | pass |

## Key VICE source excerpts to reference

Cycle→raster_x: `vicii/viciitypes.h:124` —
`#define VICII_RASTER_X(cycle) (((int)(cycle) - 17) * 8 + vicii.screen_leftborderwidth)`

Sprite X internal coord: `vicii-mem.c:710` —
`sprite_x = (vicii.regs[2 * i] | (vicii.regs[0x10] & b ? 0x100 : 0)) + vicii.screen_leftborderwidth - 0x20;`

MC-bug delayed values: `vicii-mem.c:714-734` — the full computation.

Sprite doubling table init: `vicii-sprites.c:390-401`.

Sprite X change decision tree: `vicii-sprites.c:1261-1310`.

## Start here next session

1. `git status` — verify stages 1-6 still present.
2. Phase A step 1: add `screenLeftBorderWidth = 0x20`, `rasterX(cycle)`
   method. Replace `currentRasterX` with `rasterX(vicCycle)` calls.
3. Test: Let's Scroll It with legacy path still renders.
4. Move to A step 4 (double-buffer sprite data).

Keep going phase by phase. At each checkpoint:
```sh
gradle jar
# Run both Krestage 3 and Let's Scroll It
java -Djac64.newSprites=true -Djac64.captureFrames=20 \
     -cp build/libs/JaC64.jar TestRaster /tmp/krestage3.prg
java -Djac64.newSprites=true -Djac64.captureFrames=30 \
     -cp build/libs/JaC64.jar TestRaster test-demos/lets_scroll_it/let's_scroll_it_a.d64
```
