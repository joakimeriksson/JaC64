# VICE-style Sprite Pipeline Refactor — Plan

## Goal

Port VICE's sprite rendering mechanism into JaC64 so Krestage 3's probe
passes (and the deer-panel dual-image renders correctly). The probe at
`$7470-$7491` reads `$D01E` after two mid-line `$D010` writes and expects
sprites 0, 1, 2 to have collision bits set.

## Scope (what this refactor touches)

- `com/dreamfabric/jac64/C64Screen.java`:
  - Sprite inner class → extract / expand into `SpriteSequencer`
  - `drawSprites()` → replaced with pixel-level sequencer
  - `$D010`, `$D000-$D00E`, `$D01D` (X-expand) write handlers → enqueue
    changes via a new raster-changes queue
  - Cycle handlers (`case 0..62` in `clock()`) → call sequencer at
    pixel granularity where needed
- Potentially new files:
  - `SpriteSequencer.java` — per-sprite state machine
  - `RasterChangeQueue.java` — deferred register-change queue

## Reference files (already on disk)

`/Users/joakimeriksson/work/vice-emu/vice/src/vicii/`
- `vicii-sprites.c` — pixel-level sprite rendering
- `vicii-sprites.h` — sequencer state fields
- `vicii-mem.c:201-266` — `store_sprite_x_position_lsb`/`_msb`; calls
  `vicii_sprites_set_x_position()` with current `raster_x`
- `raster/raster-sprite.h`, `raster/raster-sprite-status.h` — data
  structures to port
- `raster/raster-changes.c` — the deferred-change queue API

## Test corpus (already in test-demos/)

| Demo | Purpose |
|---|---|
| `lets_scroll_it_a.d64` | Regression — must still render scroll + mountains |
| `krestage3.d64` (original) | Probe pass — must reach demo body (not "NO VIC INSIDE") |
| `krestage3.d64` (`KRESTAGE 3/CREST`) | Dual-image — deer panel should render cleanly |
| `lorenz/Disk1.d64` | CPU timing sanity |

Test command:
```sh
java -Djac64.warp=true -Djac64.captureFrames=20 \
     -cp build/libs/JaC64.jar TestRaster <demo.d64>
```

VICE reference screenshot for each demo: `screencapture -l <wid>` (see
`reference_vice_comparison.md`).

## Staged approach

Each stage is independently buildable + testable. Commit after each so we
can bisect if a regression appears.

### Stage 1 — Raster-X tracking (prep, no behavior change)

Add a `currentRasterX` field on `C64Screen` that tracks the current VIC
pixel column (0-503 on PAL). Update it inside `clock()` based on
`vicCycle * 8`. This is pure bookkeeping — nothing reads it yet.

Regression test: Let's Scroll It must still render identically (no
visual change).

**Output**: 1 field, ~10 lines in `clock()`. ~20 min.

### Stage 2 — Register-change queue (infrastructure only)

Add `RasterChangeQueue` class:
```java
class RasterChange {
  int rasterX;
  int target;   // 0..7 = sprite X, 8 = $D010, 9 = $D01D, etc.
  int value;
}
class RasterChangeQueue {
  RasterChange[] entries = new RasterChange[32];
  int size;
  void add(int rasterX, int target, int value);
  void drainUpTo(int rasterX, Callback cb);
  void clearForLine();
}
```

Populate it (but don't consume yet) from the write handlers for
`$D010` and `$D000-$D00E`. Drain it at cycle 0 of each line.

Regression test: Let's Scroll It must still render (queue is populated
but not consumed by render pipeline yet).

**Output**: 1 new file (~80 LOC), changes in `setIOByte` $D010/$D00x
cases. ~1 hour.

### Stage 3 — SpriteSequencer state machine (not wired yet)

Add new class modeled on VICE's `raster_sprite_status_t`:
```java
class SpriteSequencer {
  int x;                  // register X (bits 0-8)
  int y;
  int shiftRegister;      // 24 bits
  int shiftCounter;       // 0-47, current pixel within sprite
  boolean displaying;     // false before X match, true during, false after
  boolean expandX, expandY;
  boolean multicolor;
  boolean dma;
  boolean expandXFlip;
  int color, mc0, mc1;
  int dataPointer;

  // called once per VIC pixel by C64Screen
  int nextPixel(int rasterX);
  void onXMatch(int rasterX);
  void loadData(int[] memory, int base);
}
```

Port from `vicii-sprites.c`:
- `vicii_sprite_update_start()` — X match logic
- `SPRITE_PIXEL` macro / `sprite_render_pixel` — shift/output logic
- Expand-X flip-flop

Regression test: unit-style test — instantiate `SpriteSequencer`,
feed known X positions and data, verify pixels emitted match expected.
No integration yet.

**Output**: 1 new file (~200 LOC). ~2 hours.

### Stage 4 — Wire new sequencer into drawSprites (behind flag)

Modify `drawSprites()` to call `SpriteSequencer.nextPixel()` for each
pixel in the rendered span. Gate the new path with a system property
`-Djac64.newSprites=true` so the old path still works:

```java
private void drawSprites() {
  if (Boolean.getBoolean("jac64.newSprites")) {
    drawSpritesV2();
  } else {
    drawSpritesLegacy();
  }
}
```

In `drawSpritesV2()`: for each pixel j in [lastX, xPos), call
`spriteSeqs[i].nextPixel(rasterX + j)` for each sprite. Accumulate
collision bits identically to the old code.

Regression test: Let's Scroll It with and without `-Djac64.newSprites`
— both must render the same.

Then: Krestage 3 with `-Djac64.newSprites=true`. Check if probe
passes. If not, trace what the sequencer does vs expected VICE
behavior.

**Output**: ~100 LOC in `C64Screen.java`. ~3 hours.

### Stage 5 — Tie register queue to sequencer

In `drawSpritesV2()`, before processing each pixel, drain the
raster-change queue up to `rasterX`. When a sprite-X change
fires at raster_x R, update `spriteSeqs[i].x` — but only
the sequencer re-reads `sprite.x` on the *next* X match check, so mid-
line changes affect future pixels, not pixels already emitted.

This is the fix that makes Krestage 3 work: the two `$D010` writes at
cycles C1, C2 enqueue changes at raster_x=C1*8 and C2*8. Between
them, sprite 0's X is briefly `$170` — the sequencer sees the X match
when raster reaches `$170+8`, sprite 0 starts displaying, collides
with sprite 2. $D01E accumulates bits.

Regression test: Krestage 3 probe passes (demo body starts).
Let's Scroll It still renders.

**Output**: ~30 LOC. ~1 hour.

### Stage 6 — Sprite 0-2 first-display-line fix

On real VIC, sprites 0-2 data is fetched at cycles 58-62 of line N-1
for display on line N. JaC64 currently fetches at cycles 57-61 of the
same line where `painting=true` is set, with emission starting at
cycle 57 of that line (too late for a proper start).

With the new sequencer, this is mostly corrected because display is
driven by X match (not by `painting` boolean). But we still need to:
- Move sprite 0-2 data fetch to cycle 58-62 (one cycle later, matching
  VICE's actual fetch cycles)
- Initialize sequencer state for the new display line

Regression test: all demos.

**Output**: ~20 LOC. ~1 hour.

### Stage 7 — Delete legacy path, flip default

Remove the `-Djac64.newSprites` gate; make the new path default.
Delete `drawSpritesLegacy()` and the old `Sprite` inner class. Final
regression pass.

**Output**: net deletion of ~200 LOC. ~30 min.

## Total estimate

| Stage | Time |
|---|---|
| 1. Raster-X tracking | 20 min |
| 2. Register-change queue | 1 hr |
| 3. SpriteSequencer class | 2 hr |
| 4. Wire new drawSprites (flagged) | 3 hr |
| 5. Tie queue to sequencer | 1 hr |
| 6. Sprite 0-2 fetch-line fix | 1 hr |
| 7. Cleanup, flip default | 30 min |
| **Total** | **~9 hours** |

Plus regression-testing buffer (≈2 hr) for a full 2-day effort.

## Checkpoints (commit after each)

- After stage 1: `git commit -m "Track raster_x in VIC-II cycle loop"`
- After stage 2: `... "Add raster-change queue for sprite register writes"`
- After stage 3: `... "Add SpriteSequencer ported from VICE (unused)"`
- After stage 4: `... "Wire SpriteSequencer under -Djac64.newSprites flag"`
- After stage 5: `... "Apply raster changes to SpriteSequencer — Krestage 3 probe passes"`
- After stage 6: `... "Fix sprite 0-2 first-display line"`
- After stage 7: `... "Remove legacy sprite path"`

## Success criteria

1. Krestage 3: probe passes, demo body runs (not "NO VIC INSIDE")
2. Deer panel in Krestage 3 beast scene renders legibly
3. Let's Scroll It: unchanged visually
4. Lorenz CPU testsuite: unchanged (CPU tests, sprite-independent)

## Rollback plan

Each stage commits separately. If stage N breaks regressions:
`git revert` back to stage N-1 and revisit.

If the whole approach turns out wrong by stage 5, the feature flag
(`-Djac64.newSprites`) means we can ship a build with the legacy path
while iterating on V2.

## Open questions to answer from VICE source during stage 3

1. Exactly which cycles sprite X is sampled on real VIC — VICE's
   `vicii_sprites_set_x_position()` applies immediately OR schedules to
   a later raster position depending on whether the sprite is currently
   in display. Port that logic precisely.
2. How the expand-X flip-flop interacts with mid-line X changes —
   VICE has explicit handling in `vicii-sprites.c`.
3. What `sprite_wrap_x` is (NTSC-only?) and whether JaC64 (PAL-only at
   the moment?) needs it.

All three are answered in `vicii-sprites.c` lines 1228-1310.

## File inventory after refactor

New:
- `com/dreamfabric/jac64/SpriteSequencer.java`
- `com/dreamfabric/jac64/RasterChangeQueue.java`

Modified:
- `com/dreamfabric/jac64/C64Screen.java` (drawSprites replaced, setIOByte
  sprite cases queue changes, clock() calls new code)

Unchanged:
- `CPU.java`, `MOS6510Core.java`, `VICConstants.java`, etc.

## Related memory

- [project_krestage3_probe_decoded.md](../../.claude/projects/-Users-joakimeriksson-work-JaC64/memory/project_krestage3_probe_decoded.md) — probe behavior
- [project_krestage3_refactor_scope.md](../../.claude/projects/-Users-joakimeriksson-work-JaC64/memory/project_krestage3_refactor_scope.md) — why patches didn't work
- [reference_vice_comparison.md](../../.claude/projects/-Users-joakimeriksson-work-JaC64/memory/reference_vice_comparison.md) — test harness setup
