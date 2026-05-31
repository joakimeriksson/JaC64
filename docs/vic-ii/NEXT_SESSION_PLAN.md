# Next-session plan — VIC pixel-level mode-pipe trace work

**Context:** Phase K iter#1-16 captured the easy wins (8606 → 7937 cells across PAL suite, -7.8%). Iter#16 proved CPU sub-cycle timing is already correct (`$D011` writes commit at identical `(line, cyc)` in both emulators on modesplit). The remaining 6294-cell floor on `colorsplit/modesplit/vicii_reg_timing/screenpos` is in **VIC pixel-level rendering**, specifically how `ViceDrawCycle.drawGraphics8` applies mid-cycle register changes at the pixel-4 rising / pixel-6 falling latches.

**DO NOT:**
- Touch the CPU model. CPU timing is verified correct.
- Touch the badline FSM (iter#10-11). It's a clean drop-in for legacy.
- Touch the `vc++` FetchG-window logic (iter#13). Window is right.
- Touch the 1-cycle sprite delay in C64Screen. Verified correct (iter#14).
- Pursue Phi1/Phi2 CPU split. Verified unnecessary (iter#16).

## Goal

Find the pixel-level divergence between JaC64's `ViceDrawCycle.drawGraphics8` and VICE's `vicii-draw-cycle.c:draw_graphics8` when a mid-cycle register write changes the mode mid-render. Target test: **modesplit** (985 cells, localized at mode-transition column boundaries cols 0, 1, 8, 14-17, 26, 32, 33, 38).

## Concrete first steps

### Step 1 — Add pixel-resolution trace in both emulators (30 min)

In `ViceDrawCycle.drawGraphics8` (around lines 472-503), add an optional per-pixel trace gated by `-Djac64.tracePxLatch=true`:

```java
private void drawGraphics8(int cycleFlags) {
  // ...existing code...
  for (int pix = 0; pix < 8; pix++) {
    drawGraphics(pix);
    if (TRACE_PX_LATCH && rasterLine == TARGET_LINE) {
      log("PX-LATCH line=" + rasterLine + " cyc=" + rasterCycle
          + " pix=" + pix + " regs11=$" + Integer.toHexString(regs0x11)
          + " regs16=$" + Integer.toHexString(regs0x16)
          + " vmode11Pipe=$" + Integer.toHexString(vmode11Pipe)
          + " vmode16Pipe=$" + Integer.toHexString(vmode16Pipe)
          + " emitted=$" + Integer.toHexString(emittedColor));
    }
  }
}
```

Mirror trace in VICE `viciisc/vicii-draw-cycle.c` `draw_graphics_pal` (around line 196). Gate by `JAC64_TRACE_FILE_PXLATCH` env var. Format must be byte-identical for diff.

### Step 2 — Capture both emulators on modesplit line 60 (15 min)

Run modesplit on both. Filter trace for line=60. Compare side-by-side.

```bash
# VICE
JAC64_TRACE_FILE_PXLATCH=/tmp/vice_pxl.trace x64sc -warp ...

# JaC64 (aligned phase)
java -Djac64.tracePxLatch=true ... TestRaster modesplit.prg
```

### Step 3 — Find first pixel that diverges (30 min)

Diff the two traces. Look for first cyc/pix where `vmode11Pipe`, `vmode16Pipe`, or `emitted color` differs. That's the bug.

Likely candidates (in order of probability):
1. **`vmode11Pipe` rising-edge OR timing**: VICE may use `(regs0x11 & 0x60) >> 2` from a delayed snapshot, not live regs0x11.
2. **`vmode16Pipe` MCM update timing**: maybe latched at different pixel.
3. **`vmode16Pipe2` lag**: 1-cycle delayed for MC flop reset — verify lag direction.
4. **`gbufMcFlop` reset on MCM 0→1**: pixel 7 in JaC64 vs different pixel in VICE.

### Step 4 — Surgical fix and verify (30 min)

Change ONE thing at a time. Re-run modesplit. Check cell-diff drops without regressing other tests. Repeat.

### Step 5 — Same method for other tests (1-2 hours each)

After modesplit improves: apply same trace-and-diff to colorsplit (1428), vicii_reg_timing (945). Expected: each test has its own specific bug at slightly different sub-pixel point.

## Expected outcomes

**Realistic per-session:**
- 300-800 cells total improvement on a successful fix
- One narrow bug found and fixed
- Documented for memory

**Stretch:**
- 2000 cells if a single fix addresses multiple tests (e.g., common mode-pipe latch bug affecting modesplit + vicii_reg_timing + colorsplit)

## Tools already in place

- `tools/vice-compare/batch_diff.sh` — PAL-only per-variant suite measurement
- `JAC64_TRACE_FILE_STATE` / `-Djac64.traceVicState` — per-cycle FSM state trace (commit b58a1bb)
- `JAC64_TRACE_VICE=1` + `VICE-D011W` — register-write trace (already in VICE patch)
- `JAC64_PC_TRACE_FILE` + `cpu_diff.py` — instruction-level diff (commit d52660d/76e6800)

## What success looks like

Suite total drops from 7937 to ≤7500 cells. Memory note `project_cpu_subcycle_floor.md` updated with the actual root cause (not the rejected Phi1/Phi2 hypothesis). New memory note `project_mode_pipe_pixel_latch.md` documenting the fix.

## Backup target — if mode-pipe yields nothing

Sprite pipeline trace dive (project_sprite_residual_findings.md):
- ss-* combined 887 cells
- Already documented as needing 2-3 iters of focused work
- Methodology: VICE state trace in `draw_sprites`, mirror in `ViceSpritePipeline.drawSprites`, diff per-pixel sbufReg/sbufPixelReg
- Win estimate: 500 cells achievable

## Reference: known-correct subsystems (cite these to avoid relitigating)

- CPU sub-cycle timing (iter#16, commit d4aa5e7): `$D011` writes commit at identical `(line, cyc)` vs VICE
- Badline FSM (iter#10-11): zero-regression VICE-faithful replacement
- vc++ FetchG window (iter#13): raster_cycle 15-54 when gfxVisible
- vBorder cyc-1 commit (iter#7): mirrors viciisc/vicii-cycle.c:549
- DEN unconditional latch at FIRST_DMA_LINE (iter#11)
- Sprite 1-cycle output delay (iter#14): removing regresses ~860 cells
- `control1FetchDelay` mirrors VICE `reg11_delay`
- Fetch address combine `(regs[11] | (reg11_delay & 0x20))` matches VICE
