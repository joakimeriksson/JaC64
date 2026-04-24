# Krestage 3: the "9-sprite" trick and how it breaks JaC64's V2 pipeline

> Status (2026-04-24): active investigation. Four visible artifacts on the
> Krestage 3 beast scene all trace back to mid-line register-write
> timing drift in JaC64's V2 sprite pipeline. Task #39.

## The technique (from CSDb release 48577)

Crossbow's 2007 entry [Krestage 3 – More Weird Stuff](https://csdb.dk/release/?id=48577)
documents a technique in the comments:

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

## Symptoms in JaC64 before a fix

All symptoms observed on the beast scene (~44 s after RUN):

1. **Ladder on KRESTAGE banner** — sprites 6 & 7 have sprite-data
   row 0 = `$FFFFFF` (solid), sprites 0–5 have row 0 = `$000000`
   (blank). They share Y=`$FA`, so both groups activate on the same
   line, but SPR6/7 emit pixels one scan line earlier than SPR0–5,
   yielding a 1-line staircase. In VICE the two groups are visually
   aligned — almost certainly because Crossbow updates screen-RAM
   sprite pointers (`$07FE`/`$07FF`) *between* VICE cycle 7 (VICE's
   `SprPtr(6)` fetch) and VICE cycle 8 (where JaC64 reads the
   pointer inside its single-cycle `readSpriteData()`). JaC64 picks
   up the *old* "solid bar" pointer; VICE picks up the *new* letter
   pointer.

2. **Solid grey side borders** — in VICE the banner's grey bar has
   the stripe pattern continuing all the way across, including the
   left/right border pixels (0–31 and 352–383). In JaC64 those
   ranges are solid grey. Not caused by border opening — caused by
   multiplex failing: the demo moves sprites into border X positions
   but the V2 pipeline doesn't re-queue the draw at the new X when
   `$D000` lands mid-line.

3. **CREST logo flicker** — 36 pixels differ between some frame
   pairs (confirmed by diff of frames 3↔4, 4↔5, 5↔6 on a fresh
   capture). Scattered across y=16-23, x=102-327 — exactly the
   multiplex positions of the sprites that build the CREST letters.
   Intermittent because the JaC64 clock jitters ±1 VIC cycle on the
   mid-line `$D000` write; when it lands at the "correct" cycle the
   multiplexed copy renders, otherwise it doesn't.

4. **Ghost K behind the real K (older builds)** — sprite 0 multiplex
   to `$200` wraps to screen X=8. In the pre-wrap-fix build
   (`SPRITE_WRAP_X=0x200`) this produced a visible ghost copy of K
   in the left border. Commit `be09bca` corrected wrap to `0x1F8`
   and my current trace no longer shows sprite writes at pixelX<55.

## Root cause — common to all four

JaC64's V2 sprite pipeline buffers mid-line register writes
(`$D000`–`$D010`, sprite pointer RAM) in a `RasterChangeQueue` and
drains them at their raster_x position. But the drain timing is off
by a cycle relative to VICE's cycle-exact model:

- VICE's `vicii-sprites.c:draw_sprites_partial` applies each register
  change at the *exact* VIC cycle the CPU wrote it, using the
  `Phi1`/`Phi2` half-cycle split.
- JaC64's single-cycle `readSpriteData()` reads sprite pointer +
  all three data bytes atomically, so any CPU write that happens in
  the window between VICE's `SprPtr(N)` (Phi1) and its
  `SprDma0(N)` (Phi2) is applied differently by the two emulators.
- Similarly `$D000` writes queued at raster_x X are drained at X in
  JaC64, but the resulting sprite-reposition interacts with the
  shift-register state at a boundary that's half a VIC cycle off.

## Fix direction

1. Split `readSpriteData()` into separate pointer-fetch and data-fetch
   phases matching VICE's Phi1/Phi2.
2. Re-read sprite pointer on any mid-line screen-RAM write to
   `$07F8+i` that lands between the sprite's Phi1 pointer-fetch and
   its Phi2 data-fetch.
3. Verify `drawSpritesV2`'s partial-render drains register changes
   at raster_x matching VICE's `pixel_accu` stepping.
4. Extend the `RasterChangeQueue` to honor Phi1/Phi2 ordering when
   multiple writes land in the same cycle.

Estimated scope: 1-2 days, guided by side-by-side VICE trace output
of the same scene.

## Source pointers

- VICE cycle-exact sprite fetch: `../vice-emu/vice/src/viciisc/vicii-fetch.c`
  (`sprite_dma_cycle_0`, `vicii_fetch_sprite_dma_1`, `sprite_dma_cycle_2`).
- VICE partial-render orchestration: `../vice-emu/vice/src/vicii/vicii-sprites.c`
  (`draw_sprites_partial`, `draw_hires_sprite_expanded` etc — referenced
  throughout JaC64's V2 port).
- JaC64 V2 entry points: `C64Screen.java` around lines 2244
  (`renderSpriteV2Span`) and 2550 (`loadSequencerData`).
- JaC64 queue: `RasterChangeQueue.java`.
- JaC64 sprite pointer fetch: `Sprite.readSpriteData()` in
  `C64Screen.java` around line 3229.

## Related memory

- `reference_krestage3_csdb.md` — the CSDb release page with the
  9-sprite-trick comment thread.
- `project_fli_fix.md` — prior fix (rc/idle_state) that got the FLI
  beast image rendering at all.
- `project_sprite_pipeline_v1.md` — V2 pipeline initial status.
