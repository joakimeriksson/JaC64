# Plan V3 — Final Path to Krestage 3 Probe Pass

## State before this plan

Phases A–D of `SPRITE_REFACTOR_PLAN_V2.md` landed:
- VICE-accurate raster_x formula
- Lambda-based sorted change queue
- CPU-write cycle-offset compensation
- Full port of `d01c_store` MC-bug computation

Current `$D01E` on probe = `$00`. No regression.

## Key insight from deep analysis

Internal sprite X values (register + X_OFFSET = $20):

| Sprite | Internal X | Range (expanded) | Notes |
|---|---|---|---|
| S0 | 0x90 | 0x90-0xBF | window: 0x190-0x1BF when MSB flipped |
| S1 | 0xC0 | 0xC0-0xEF | |
| S2 | 0x1A0 | 0x1A0-0x1CF | |
| **S3** | **0x170** | **0x170-0x19F** | **In repeat-pixel zone** |
| S4 | 0x120 | 0x120-0x14F | |
| S5 | 0xF0 | 0xF0-0x11F | |
| S6 | 0x60 | 0x60-0x8F | |
| S7 | 0x30 | 0x30-0x5F | |

**Sprite 3 at X=0x170 permanently sits in the expanded repeat-pixel zone** (0x16a–0x1a7 for sprite 3). This is TRUE from probe start, no writes needed. Sprite 3 emits 7 extra pixels past 0x19F into sprite 2's range (0x1A0+).

**This alone should fire bits 2 and 3** of `$D01E`. Phase F's port of `draw_hires_sprite_expanded` (including the repeat-pixel logic at vicii-sprites.c:564-583) gives us `$D01E = 0x0c` as a verifiable intermediate.

## Plan

### Step 1 — Port `draw_hires_sprite_expanded` with repeat-pixel logic

**Goal**: `$D01E = 0x0c` on probe read.

**Files**: new `C64Screen.drawSpriteExpandedHires(int n)` or new `SpriteRenderer.java` class.

**Sub-tasks**:
1. Port constants:
   ```java
   static final int SPRITE_X_OFFSET = 0x20;
   static final int[] SPRITE_EXPANDED_REPEAT_START = {0x13a, 0x14a, 0x15a, 0x16a, 0x17a, 0x18a, 0x19a, 0x1aa};
   static final int[] SPRITE_REPEAT_END          = {0x177, 0x187, 0x197, 0x1a7, 0x1b7, 0x1c7, 0x1d7, 0x1e7};
   static final int[] SPRITE_REPEAT_BEGIN        = {0x16b, 0x17b, 0x18b, 0x19b, 0x1ab, 0x1bb, 0x1cb, 0x1db};
   ```
   (Pre-computed from VICE: `0x157+X_OFFSET+n*0x10` etc., all with PAL X_OFFSET=0x20.)

2. Port render function working on `mem[]` (pixel buffer) and `collissionMask[]` directly:
   ```java
   void renderHiresSpriteExpanded(int n, int[] data) {
     int spriteBit = 1 << n;
     int spriteX = sprites[n].x + SPRITE_X_OFFSET;
     int sprmsk = (DOUBLING_TABLE[data[0]] << 16) | DOUBLING_TABLE[data[1]];
     int size = 48;
     boolean mustRepeatPixels = false;
     int repeatPixel = 0;

     if (spriteX > SPRITE_EXPANDED_REPEAT_START[n]
         && spriteX < SPRITE_REPEAT_END[n]) {
       size = SPRITE_REPEAT_BEGIN[n] - spriteX;
       mustRepeatPixels = size > 0;
       if (mustRepeatPixels && size < 33) {
         sprmsk = sprmsk >>> (32 - size);
         repeatPixel = sprmsk & 1;
         for (int i = 0; i < 7 && size < 32; size++, i++) {
           sprmsk = (sprmsk << 1) | repeatPixel;
         }
       }
     }
     // ... render 48 pixels (or size if repeat), update collmask
   }
   ```

3. Replace `drawSpritesV2`'s per-pixel sequencer call with one
   `renderHiresSpriteExpanded()` call per sprite per line (at line
   end, or at sprite's X-match cycle).

4. **Verify**: Krestage 3 probe should now read `$D01E = 0x0c`.

**Estimate**: 3–4 hours.

### Step 2 — Port `draw_hires_sprite_normal`

Sprites 0, 1, 2, 4, 5, 6, 7 aren't X-expanded in the probe, but normal-mode sprites are used in Let's Scroll It and other demos. Port for completeness and regression safety.

**Verify**: Let's Scroll It renders identically to legacy.

**Estimate**: 2 hours.

### Step 3 — Investigate why sprite 0 at 0x190 should render

The $D010 write window gives sprite 0 internal X=0x190 for ~104 pixels. For bit 0 to fire, sprite 0 must emit at that X.

**Diagnostic plan**:
1. Add logging in my V2 drainQueue: when $D010 changes sprite X, log the new X and current raster_x.
2. Add logging in render: when sprite fires `renderHiresSpriteExpanded`, log sprite X at render time.
3. Run probe, check: was sprite 0's x = 0x190 ever the value AT the time render was called?

If NO → render timing issue: sprite 0's x gets reset by write 2 BEFORE the partial-render covering 0x190 runs.

If that's the case, VICE's partial render model needs porting: render sprites BETWEEN each raster_change, not at line end. See `raster-line.c:522-538`:
```c
xs = 0;
for each change at xe:
  draw_sprites_partial(xs, xe - 1)
  apply change
  xs = xe
draw_sprites_partial(xs, end)
```

Port this as: when processing queued changes in drainQueueAt, call
a partial-render between each change point.

**Verify**: Krestage 3 probe reads `$D01E = 0x0d` (bits 0, 2, 3).

**Estimate**: 4 hours (port partial render orchestration).

### Step 4 — Find bit 1 (sprite 1 collision)

This is the remaining mystery. Likely candidates:
1. **Pixel-repeat for sprite 0 at X=0x190**: my earlier analysis said
   size = 0x16b - 0x190 = negative → no repeat. But with a 7-pixel
   repeat extension, sprite 0 would emit at 0x190-0x1C6, still not
   overlapping sprite 1 (0xC0-0xEF).
2. **$D01C writes during brief probe window trigger MC-bug for sprite 1**:
   if sprite 1 is mid-display (raster in 0xC0-0xEF range) when $D01C
   writes happen, MC-bug activates. Check timing.
3. **Sprite 0 at 0x190 interacts with sprite 5 at 0xF0**: no overlap
   directly, but MC-bug on one might extend.
4. **Specific VIC-II quirk I haven't identified**: VICE source has many
   corner cases in `vicii-sprites.c`.

**Diagnostic**: run VICE x64sc with monitor tracing $D01E reads at
the probe's final read. See what value actually arrives. Compare to
our `$D01E = 0x0d`.

**Command** (next session):
```
x64sc -warp -autostart test-demos/krestage3.d64 \
      -monlog /tmp/vice_probe.log \
      -moncommands /tmp/probe.mon
```
where `probe.mon` contains:
```
watch store $D01E $D01E
watch load $D01E $D01E
cont
```

**Estimate**: 3 hours (setup + analysis + fix).

### Step 5 — Regression + Phase E, G, H

After probe passes:
- Port `d01d_store` with x_shift (Phase E)
- Align DMA cycles with VICE (Phase G)
- Verify collision semantics (Phase H)

Then strip legacy path (Phase I), verify vs VICE traces (Phase J).

**Estimate**: 5 hours.

## Total remaining estimate

| Step | Work | Hours |
|---|---|---|
| 1 | `draw_hires_sprite_expanded` port | 3–4 |
| 2 | `draw_hires_sprite_normal` port | 2 |
| 3 | Partial-render orchestration | 4 |
| 4 | VICE trace for bit 1 + fix | 3 |
| 5 | E/G/H/I/J cleanup | 5 |
| **Total** | | **17–18 hours** |

## Checkpoints / verification matrix

| After step | `$D01E` | Let's Scroll It | Other demos |
|---|:---:|:---:|:---:|
| Current (Phase D done) | `$00` | renders | untested |
| Step 1 | `$0c` | renders | — |
| Step 2 | `$0c` | identical to legacy | — |
| Step 3 | `$0d` | identical | — |
| Step 4 | `$07` → **PROBE PASSES** | identical | some improve |
| Step 5 | `$07` | identical | broad regression run |

## Commit sequence

Each step = separate commit. If step 3 or 4 gets stuck, steps 1–2 still
ship and improve V2 regardless.

## Fallback if probe still won't pass

If after step 4 the probe still fails, use VICE's exact monitor dump
of $D01E during the probe on a real-C64-verified build, then match
JaC64 to that behavior specifically — even if it means porting more
VICE source than currently listed.

The ultimate verification: if trace-diff against VICE x64sc (Phase J)
matches cycle-by-cycle at the probe PC range, correctness is
established regardless of probe pass/fail.
