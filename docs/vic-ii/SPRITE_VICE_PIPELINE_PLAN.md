# Sprite cycle-exact pipeline — restart plan

> Status: WIP. Commit `51ae943` lands the foundation behind
> `-Djac64.viceSprPipe=true` (default OFF). Default-off behavior is
> unchanged — all current passing tests still pass.

## Context (read first)

JaC64's current V2 sprite pipeline (`renderMcSpriteExpanded`,
`renderHiresSprite*`, `drawSpritesV2`, `RasterChangeQueue`) was ported
from VICE's **fast / inaccurate** `vicii/vicii-sprites.c` span renderer.

But our reference comparison is `x64sc` (MOS8565), which uses VICE's
**cycle-exact** `viciisc/vicii-draw-cycle.c`. The two have completely
different architectures:

- Fast (`vicii-sprites.c`): renders entire 24-pixel sprite spans at
  once via `MCSPR_TABLE` / `SPRITE_DOUBLING_TABLE` lookups. Mid-line
  $D01D (expand-X) / $D01C (multicolor) transitions are *not* tracked
  pixel-by-pixel — that's why ss-exp-unexp-mc and friends diverge.
- Cycle-exact (`vicii-draw-cycle.c`): per-pixel shift register with
  `sbuf_expx_flops`, `sbuf_mc_flops`, `sprite_pending/active/halt_bits`
  pipeline. Mid-line transitions latched at specific sub-cycle pixels
  (`$D01D` and `$D01B` at pixel 6; `$D01C` 8565-style also at pixel 6).

Diff comparison against `VICE-testprogs/VICII/spritesplit/references/
*-8565.png` confirms the architectural mismatch:

| Test | Legacy V2 | New (this branch) | Δ |
|------|-----------|-------------------|---|
| ss-exp-unexp-mc | 10.10% | 4.50% | −55% |
| ss-pri-mc-exp | 9.65% | 5.53% | −43% |
| ss-unexp-exp-mc | 8.21% | 3.77% | −54% |
| ss-pri-mc | 7.10% | 3.96% | −44% |
| ss-xpos | **0.23%** | **4.15%** | **+18×** |
| ss-hires-color | 1.19% | 3.17% | +2.7× |

Architecture wins on complex tests (proves the port is correct);
basic-rendering bugs cause simple-test regression. Plus
`irq-ack-vicii` regresses (collision IRQ ordering).

## What's already built

### `com/dreamfabric/jac64/ViceSpritePipeline.java` (new, 357 lines)

Faithful port of `vicii-draw-cycle.c` lines 88-532. State, helpers,
and per-cycle entry point all match VICE 1:1 with source-line
citations in comments.

Public API:
```java
// Per-cycle inputs (set by caller before drawCycle8):
viceSprPipe.checkSprDisp        // true at VICE cycle 58 = JaC64 case 57
viceSprPipe.spritePtrDma0       // true at SprPtr/SprDma0 cycle
viceSprPipe.spriteDma1Dma2      // true at SprDma1/SprDma2 cycle
viceSprPipe.spriteDmaNum        // 0..7, sprite being fetched (or -1)
viceSprPipe.spriteDisplayBits   // mask of dma'd sprites
viceSprPipe.reg1bPipe           // $D01B
viceSprPipe.reg1cPipe           // $D01C
viceSprPipe.reg1dPipe           // $D01D
viceSprPipe.currentSpriteX[]    // sprites[s].x
viceSprPipe.currentSpriteData[] // 24-bit data (loaded into sbufReg at pixel 4)
viceSprPipe.priBuffer[]         // foreground-priority per pixel

viceSprPipe.drawCycle8(xpos);    // runs 8 pixels of draw_sprites8

// Outputs:
viceSprPipe.outColorCode[i]      // 0 transparent / 1 D025 / 2 D027+s / 3 D026
viceSprPipe.outSprite[i]         // winning sprite or -1
viceSprPipe.outSpriteSpriteColl[i]  // SS-COL mask for this pixel
viceSprPipe.outSpriteBgColl[i]   // SB-COL mask for this pixel
```

### `C64Screen.drawSpritesViceCycle()` (new, ~120 lines)

Integration: replaces `drawSprites()` body when `viceSprPipe=true`.
Sets pipeline inputs from existing state, runs `drawCycle8`, paints
outputs into `mem[]` and `collissionMask[]`. Hooks
`Sprite.readSpriteData()` to populate `currentSpriteData[]`.

## Bugs to fix (priority order)

### Bug 1: `priBuffer` source is wrong

**Symptom:** simple sprite tests (ss-xpos, ss-hires-color) regress
heavily.

**Root cause:** I'm reading `(collissionMask[pixelX] & 0x100) != 0`
as the foreground-priority bit. But `collissionMask[pixelX]` is set
by `drawSprites()` itself — by the time the *next* sprite cycle
reads it, sprites from prior cycles have OR'd in their bits, not
just background.

**Fix:** find where graphics rendering writes the foreground bit
into mem/collissionMask, and feed that bit directly into
`priBuffer[i]` BEFORE the sprite cycle runs. Search:

```bash
grep -n "0x100\|FOREGROUND\||=  *0x100\|setForeground" \
  com/dreamfabric/jac64/C64Screen.java
```

The graphics path at lines 3050-3200 (`drawText`, `drawBitmap`,
`drawHiresMC`, `drawMCBitmap`) is where the bit is set. Need a
dedicated `priBuffer8[]` filled at that point, distinct from
`collissionMask` (which is multiply written and not idempotent).

### Bug 2: SS-COL IRQ flow

**Symptom:** `irq-ack-vicii` border = red FAIL.

**Root cause:** existing code has a `sprColCanFire` / `sprColFirePending`
end-of-cycle pipeline (matches VICE `vicii-cycle.c:407-455`). My new
integration writes `sprCol |= ssColl` per pixel, bypassing that
pipeline. The collision IRQ then fires at the wrong cycle.

**Fix:** Don't OR `sprCol` directly. Instead, accumulate sprite-sprite
hits in a temporary mask, and at end of `drawSpritesViceCycle()`,
follow the existing pattern:

```java
// Roughly, look at how the V2 path handles SS-COL:
//   private boolean sprColCanFire = true;
//   private boolean sprColFirePending = false;
// New code should set sprCol via the same machinery, so the IRQ
// fires at the same Phi2 boundary as before.
```

Search `sprColFirePending` in `C64Screen.java` for the existing flow.

### Bug 3: `outSprite` set but `outColorCode == 0`

**Symptom:** spritesplit ss-xpos regression, sprites half-rendered.

**Root cause:** When sprite is hidden behind foreground priority,
VICE still sets `active_sprite = s` and `collision_mask |= m` but
*does not* paint to render_buffer. My pipeline does this correctly
(see `drawSprites(i)` lines 254-269 of ViceSpritePipeline.java),
but the integration in `drawSpritesViceCycle()` treats `s >= 0` as
"sprite drew here" and OR's into collissionMask — which then makes
it look like a foreground pixel for *next* sprite. Loop.

**Fix:** Only OR into `collissionMask[pixelX]` if `code != 0`.
When the sprite is hidden, do not modify the mask.

### Bug 4: `sprite_x_pipe` latching timing

**Symptom:** mid-line $D000 writes (Krestage 3 9-sprite trick) may
still not work. Need explicit verification.

**Reference:** VICE `vicii-draw-cycle.c:459-465`:
```c
static DRAW_INLINE void update_sprite_xpos(void) {
    int s;
    for (s = 0; s < 8; s++) {
        sprite_x_pipe[s] = vicii.sprite[s].x;
    }
}
```

This is called at the *end* of `draw_sprites8()`. My pipeline does
the same. But: it samples `currentSpriteX[s]` which I set from
`sprites[s].x` *at the start* of `drawSpritesViceCycle()`. If the
CPU writes $D000 mid-cycle, my snapshot might be stale.

**Fix:** Sample `sprites[s].x` inside `drawCycle8` at end (in the
pipe-update step), not at start. This requires either:
- Pass `Sprite[]` to drawCycle8, or
- Call `viceSprPipe.currentSpriteX[s] = sprites[s].x` at the end of
  `drawSpritesViceCycle` (before `drawCycle8` returns) — wait, no,
  drawCycle8 is what reads them. Restructure so the pipe update
  reads from a callback/array that's live.

Actually simplest: inside `drawCycle8`, replace:
```java
for (int s = 0; s < 8; s++) spriteXPipe[s] = currentSpriteX[s];
```
with a call to a `xPosUpdater` lambda or just defer the update by
having the integration call `viceSprPipe.commitXPipe()` after the
cycle.

## Verification protocol (after each fix)

```bash
# Build
export JAVA_HOME="/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew jar

# 1. Default-OFF regression — must stay clean
for test in \
  /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg \
  /Users/joakimeriksson/work/VICE-testprogs/CIA/cia-timer/cia-timer-newcias.prg \
  /Users/joakimeriksson/work/VICE-testprogs/VICII/spriterestart/spriterestart.prg \
  /Users/joakimeriksson/work/VICE-testprogs/VICII/spritecollisions/sprite-sprite-hi-hi.prg
do
  java -Djac64.headless=true -Djac64.warp=true -Djac64.captureFrames=1 \
    -cp build/libs/JaC64.jar TestRaster "$test" 2>&1 \
    | grep "Test complete:"
done

# 2. Pipeline-ON regression — must reach PASS on all
for test in [...same...]; do
  java -Djac64.viceSprPipe=true -Djac64.headless=true -Djac64.warp=true \
    -Djac64.captureFrames=1 -cp build/libs/JaC64.jar TestRaster "$test" 2>&1 \
    | grep "Test complete:"
done

# 3. Visual diff — must drop to <1% per test
mkdir -p /tmp/jac64_pipe
for prg in /Users/joakimeriksson/work/VICE-testprogs/VICII/spritesplit/*.prg; do
  name=$(basename "$prg" .prg)
  java -Djac64.viceSprPipe=true -Djac64.headless=true -Djac64.warp=true \
    -Djac64.captureFrames=2 -cp build/libs/JaC64.jar TestRaster "$prg" >/dev/null 2>&1
  cp /tmp/jac64_test_frame_001.png /tmp/jac64_pipe/${name}.png
done
for ref in /Users/joakimeriksson/work/VICE-testprogs/VICII/spritesplit/references/*-8565.png; do
  name=$(basename "$ref" .prg-8565.png)
  result=$(python3 tools/vice-compare/png_cell_diff.py "$ref" \
    "/tmp/jac64_pipe/${name}.png" 2>&1 | grep "Total cell-diffs")
  echo "$name => $result"
done
```

## Acceptance criteria for flipping default ON

- [ ] All 4 bugs above resolved
- [ ] Pipeline-ON regression: irq-ack-vicii / cia-timer / spriterestart /
      sprite-sprite-* all PASS (border = green)
- [ ] All 17 spritesplit tests: cell-diff < 1% vs MOS8565 reference
- [ ] Krestage 3 beast scene: visual smoke-check matches VICE banner
      (no staircase artifact)
- [ ] After verification: remove flag, delete legacy V2 sprite path
      (~600 lines: renderMcSpriteExpanded, renderHiresSpriteExpanded,
      renderMcSpriteNormal, renderHiresSpriteNormal,
      renderMaskedPixels, renderMcMaskedPixels, drawSpritesV2,
      drawSpritesV2Tail, renderSpriteV2Span, renderSpritesV2Span,
      MCSPR_TABLE, SPRITE_DOUBLING_TABLE,
      SPRITE_*_REPEAT_* constants)

## Files to read first when restarting

1. `docs/vic-ii/SPRITE_VICE_PIPELINE_PLAN.md` (this doc)
2. `docs/vic-ii/sprite-staircase-investigation.md` (background on the
   architectural mismatch + the cycle-table reference)
3. `docs/vic-ii/krestage3-nine-sprite-trick.md` (the demo case driving
   this refactor)
4. `com/dreamfabric/jac64/ViceSpritePipeline.java` (the new pipeline)
5. `C64Screen.drawSpritesViceCycle()` ~ line 3220 (the integration)
6. `vicii-draw-cycle.c:88-532` for VICE source (under
   `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/`)

## Key source citations in the new pipeline

- `triggerSprites` ↔ `vicii-draw-cycle.c:318-340`
- `drawSprites(i)` ↔ `vicii-draw-cycle.c:342-430`
- `drawCycle8` ↔ `vicii-draw-cycle.c:469-532`
- `updateSpriteMcBits8565` ↔ `vicii-draw-cycle.c:442-449`

## Time estimate

~2-3 hours of focused work to fix the 4 bugs + verify. Then
another 1-2 hours to remove the legacy V2 code and lock the new
pipeline as default-on.
