# Krestage 3: the "9-sprite" trick & "50-pixel wide sprites"

> Status (2026-05-21): the OLD V2 sprite pipeline analysis below is **superseded** by
> the cycle-exact `VicSpritePipeline` port + Phase E render-buffer (default ON since
> May 2026). Krestage 3 scroll-in + beast scenes now render cleanly. Remaining gaps
> for the "50-pixel sprite" effect documented in [Current status](#current-status).

## The technique (from CSDb release 48577)

Crossbow's 2007 entry [Krestage 3 – More Weird Stuff](https://csdb.dk/release/?id=48577)
documents the "9-sprite" trick:

> Sprite 0 fetch happens in the beginning of the right border, you can
> move this very sprite to immediately after that fetch, and it will be
> displayed again… this only works on every other line.

Concretely:

- VIC-II fetches sprite 0's three data bytes at PAL cycle 58 (Phi1/Phi2)
  + cycle 59 (Phi1/Phi2).
- Immediately after cycle 59, the CPU writes a new `$D000` value.
- The shift register is still holding the just-fetched row data, so when
  the chip later reaches the new X for sprite 0, it draws a **second**
  copy on the same scanline.
- The technique works only on alternating lines because of how DMA /
  display_bits interact on pair-lines.

The "50-pixel wide sprites" line from the release notes refers to placing
the two sprite-0 emit copies adjacent or overlapping with X-expansion:
each copy is 48 px (24 source × 2 X-expand). Place them with a 2-px overlap
or 1-px gap and you get ~50 visible pixels of contiguous sprite.

## Current status (2026-05-21)

JaC64's sprite pipeline is now the cycle-exact
[`VicSpritePipeline`](../../com/dreamfabric/jac64/VicSpritePipeline.java)
— a 494-LOC faithful port of VICE `viciisc/vicii-draw-cycle.c`. It implements:

- 24-bit shift registers per sprite (`sbufReg[8]`)
- Expand-X and MC flip-flops per sprite
- Pipeline bits: pending / active / halt
- Per-pixel processing via `trigger_sprites()` + `draw_sprites()`
- Sub-pixel state transitions at pix 2/3/4/6/7 (DMA halt, shift-register load,
  `$D01B`/`$D01D` latch, mc-bits update)
- 6569/8565 mc-bits split gated by `colorLatency` (commit `951636e`)
- In-cycle sprite-sprite/sprite-bg collision accumulation (commit `b0eeb84`)

Verified per-pixel against VICE: SP-LATCH trace (`-Djac64.traceSpLatch=true`)
on `ss-pri-mc-exp` raster $50 shows JaC64 and VICE byte-identical xpos /
active / pending / halt / sbuf progression after JaC64 vicCycle N ≡ VICE
raster_cycle N alignment. See `project_sprite_xpos_offset.md` in memory.

Plus the **Phase E render-buffer pipeline** (commits `7045afe..8aa040e`,
default ON since May 2026 via `-Djac64.vicRenderBuf=true`):
- `vicii.cregs[]` 1-cycle commit delay (cregs pipe-delay, commit `6d35ab2`)
- Resolves $D02X mid-cycle writes at the next cycle boundary, matching VICE
- Confirmed clean render of Krestage 3 scroll-in + beast scenes

Per `project_krestage3_phase_e_fix.md`:
- `docs/vic-ii/reference/krestage3_jac64_scrollin_phase_e_on.png` matches
  `krestage3_vice_scroll_in_deer_clean.png`
- `docs/vic-ii/reference/krestage3_jac64_beast_phase_e_on.png` matches
  `krestage3_vice_fli_beast_scene.png`

## Remaining gap — sprite X mid-line re-trigger

The big 9-sprite-trick mechanism (write `$D000` after sprite 0's DMA at
cyc 58 to display sprite 0 a SECOND time) requires:

1. The mid-line `$D000` write must take effect at the cycle precision
   VICE does. Per session 2026-05-20 traces, JaC64's `$D000` writes match
   VICE byte-for-byte at (rast, cyc, val). ✓
2. The sprite pipeline must re-arm sprite 0 for a second emit after the
   X write. JaC64's `sprPipeSpriteX[]` is captured at end of cycle (1-cycle
   pipe delay before the trigger comparison sees the new X).

The current 1-cycle pipe handles MOST mid-line X moves correctly.
The remaining residual (visible in `ss-xpos`: 88 cells off VICE's
reference floor) traces to sprite trigger timing at the second-display
boundary — specifically when sprite 0 needs to re-trigger after the
DMA at cyc 58. See `project_krestage3_scrollin_open.md` for details.

## How to test Krestage 3 today

```bash
# Extract the demo PRG once:
# (see /Users/joakimeriksson/work/JaC64/.capture/krestage3_crest.prg)

# JaC64 with current defaults (vicRenderBuf=true, cregs pipe-delay):
java -Djac64.warp=true -Djac64.captureFrames=130 \
     -cp . TestRaster /tmp/krestage3_crest.prg

# Compare scroll-in (~frame 80) and beast scene (~frame 100) against:
# - docs/vic-ii/reference/krestage3_vice_scroll_in_deer_clean.png
# - docs/vic-ii/reference/krestage3_vice_fli_beast_scene.png
```

## Source pointers (current pipeline)

- VICE per-pixel sprite source of truth:
  `vice-emu/vice/src/viciisc/vicii-draw-cycle.c` (`draw_sprites8`,
  `trigger_sprites`, `update_sprite_xpos`)
- VICE sprite fetch: `vice-emu/vice/src/viciisc/vicii-fetch.c`
  (`vicii_fetch_sprite_pointer`, `vicii_fetch_sprite_dma_1/2`)
- JaC64 sprite pipeline:
  `com/dreamfabric/jac64/VicSpritePipeline.java` (entire file)
- JaC64 dispatcher hand-off:
  `com/dreamfabric/jac64/C64Screen.java` (`advanceSpritePipeline`,
  `drawCyclePart1`/`drawCyclePart2` order)
- JaC64 render-buffer / cregs pipeline:
  `com/dreamfabric/jac64/VicDrawCycle.java` (`drawColors8`, `drawColors6569`,
  `drawColors8565`)

## Related memory

- `reference_krestage3_csdb.md` — CSDb release page (the 9-sprite comment)
- `project_krestage3_phase_e_fix.md` — vicRenderBuf=true win (May 10)
- `project_sprite_xpos_offset.md` — SP-LATCH-verified per-pixel match
- `project_idle_gfx_fetch_3fff.md` — idle gfx fetch fix (sprite-priority 1392→0)
- `project_vis_en_cyc56_fix.md` — VIS_EN cyc 56 fix (suite -500 cells)

---

# OLD V2-pipeline analysis (superseded by VicSpritePipeline)

> The text below described the pre-port V2 sprite pipeline and its
> `RasterChangeQueue`-based approach. Both have been replaced by the
> cycle-exact port. Kept here for historical context.

JaC64's V2 sprite pipeline buffered mid-line register writes
(`$D000`–`$D010`, sprite pointer RAM) in a `RasterChangeQueue` and
drained them at their raster_x position. But the drain timing was off
by a cycle relative to VICE's cycle-exact model:

- VICE's `vicii-sprites.c:draw_sprites_partial` applied each register
  change at the *exact* VIC cycle the CPU wrote it, using the
  `Phi1`/`Phi2` half-cycle split.
- JaC64's single-cycle `readSpriteData()` read sprite pointer +
  all three data bytes atomically, so any CPU write that happened in
  the window between VICE's `SprPtr(N)` (Phi1) and its
  `SprDma0(N)` (Phi2) was applied differently by the two emulators.

That analysis is no longer relevant — the V2 pipeline has been wholesale
replaced.
