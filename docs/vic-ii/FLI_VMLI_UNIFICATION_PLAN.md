# FLI vmli unification — staged execution plan

The vmli-pipeline refactor (FLI_CACCESS_VMLI_REFACTOR_PLAN.md §1–16) proved:
the c-access **write** model and the **pipeline** (VicDrawCycle) are correct, but
JaC carries **two divergent vmli indices** and no localized change reconciles
them. This plan executes the **full unification** to VICE's single gated `vmli`.

Created 2026-06-22. Branch base: `refactor/fli-vmli-caccess` (328ce2c).
Prereq reading: FLI_CACCESS_VMLI_REFACTOR_PLAN.md §11–16 (every proven dead end).

---

## 0. North star (VICE's model — the invariant to reproduce)

In `viciisc`, there is **one** `vmli`:
- Reset to 0 at update_vc (raster_cycle 13).
- Incremented in `vicii_fetch_graphics` (g-fetch) **only when `!idle_state`**,
  every FetchG cycle (15..54). On idle, `vicii_fetch_idle_gfx` runs — **no
  increment**.
- The **matrix-fetch** writes `vbuf[vmli]`/`cbuf[vmli]` (same index).
- The **g-fetch** reads `vbuf[vmli]`/`cbuf[vmli]` (same index), then `vmli++`.
- The **beam/pixel** advances every cycle regardless; on idle the cell is held
  (idle gfx), so the displayed cell index is the gated `vmli`, NOT a cyc count.

JaC today: `vVmli` (added, gated, == VICE vmli, in updateVicStateVic) is CORRECT
but only TRACKED; the live render uses the legacy `vmli` (UNCONDITIONAL `++` in
drawGraphics:~4499), which diverges from `vVmli` on idle/border content by a
NON-CONSTANT amount. Unification = make every consumer use the one gated index.

## 1. Success criteria (hard gates, every stage)

- **Suite:** full 139-test A/B (flag off vs on, capture @30M) — **0 regressions**
  on non-FLI tests (border/den/dma/banking/sprite/screenpos/modesplit/...).
- **FLI win:** colorfetchbug family ≤ baseline (target ≤12, ideally <48),
  fldscroll/blackmail/fetchsplit unchanged at 0.
- **Picture-mover:** deer/wolf-mover left edge matches VICE (no garbage/gray
  flicker), validated statistically via fpsCapture (d64 non-det).
- Every stage **flag-gated** (`jac64.vmliUnified`), default OFF until U6.

## 2. vmli consumer inventory (what must migrate)

Audit `grep -n vmli C64Screen.java VicDrawCycle.java` and classify each as
FETCH-index (write/read of vbuf/cbuf/gbuf) vs DISPLAY-index (beam/pixel/sprite):
- **Write (c-access):** writeCAccess / fetchBadLineData (already on vVmli in S2').
- **gbuf fetch:** C64Screen ~3810 (`vicCharCache[vmli]` → bitmap addr).
- **vbuf/cbuf pipe:** `dmli` (C64Screen ~3983 → VicDrawCycle:615 `vbuf[dmli]`).
- **legacy increment:** drawGraphics ~4499 (`vmli++` unconditional — the divergence).
- **drawGraphicsVic / drawGraphics dead bodies:** vicCharCache[vmli] reads
  (4217/4519/4667) — dead under useVicFullPipeline but migrate for correctness.
- **sprite pipeline:** any vmli use in drawSprites*/VicSpritePipeline.
- **retroactive paint / border:** vmli/dmli use in drawColorsVic, border paint.
- **traces / debug:** harmless, update for clarity.
Output of U0 = a checklist table (file:line, role, migration action).

## 3. Stages

### U0 — Inventory + per-cycle ground truth (no code change)
- Produce the consumer table (§2).
- Trace, on ONE run each, the per-cycle (vicCycle, vVmli, legacy vmli,
  display-column, idle) on: a TEXT test (screenpos), a BORDER test (border-250),
  an FLI test (colorfetchbug). Tabulate the legacy-vmli vs vVmli divergence and
  WHERE it occurs (idle/border cycles). This is the map the migration must honor.
- Gate: a clear per-cycle table showing exactly which cycles diverge and why.

### U1 — Single gated index, dual-run verification
- Keep BOTH indices live. Under `jac64.vmliUnified`, route the gbuf fetch AND
  dmli through `vVmli` (gated) — but FIRST prove, per-cycle vs VICE FetchC, that
  `vVmli` reproduces VICE's vmli on ALL three test types (text/border/FLI), incl.
  the idle-hold. If `vVmli` already matches VICE everywhere → the divergence is
  purely the legacy-vmli consumers; if not, fix `vVmli`'s gating/cycle first.
- Gate: `vVmli` == VICE vmli per (rast,cyc) on screenpos+border-250+colorfetchbug.

### U2 — Migrate the READ path (gbuf + vbuf/cbuf pipe) to gated vmli
- Under the flag, gbuf fetch reads `vicCharCache[vVmli]`; `dmli = vVmli`.
- CRITICAL (the §16 failure): the display-column↔cell-index mapping must honor
  the idle-hold. VicDrawCycle already idle-gates the pipe load (vicii-draw-cycle
  .c:313) and advances pixels per cycle — verify the pipe DELAY (pipe0→pipe1)
  combined with `dmli=vVmli` yields the correct display column on BOTH normal
  (vVmli tracks beam) and idle/border (vVmli holds) cycles. If a fixed offset is
  needed it must come from the pipe depth, NOT a per-line constant (§16 proved
  constants fail). Likely needs the gbuf/vbuf/cbuf pipe depths audited so the
  one gated index + pipe delay = VICE exactly.
- Gate: border-250 / screenpos / modesplit back to their OFF values (0 / ~87 /
  baseline) AND colorfetchbug improves. This is the make-or-break stage.

### U3 — Retire the legacy unconditional vmli increment
- Once U2 holds, make the legacy `vmli++` (drawGraphics:4499) GATED on `!idle`
  under the flag (or replace reads of `vmli` with `vVmli`). Verify no consumer
  relied on the unconditional advance (the U0 inventory drives this).
- Gate: full 139-test A/B still 0 regressions.

### U4 — Migrate sprite / border / retroactive consumers
- Repoint any remaining vmli/dmli users (sprite pipe, border paint, retroactive)
  to the unified index. Most are display-side and may already be correct via the
  beam; audit each against U0's table.
- Gate: sprite tests (spriteenable, spritecrunch, ss-*) unchanged; border family
  unchanged.

### U5 — Retire the col-indexed write + backfill + col0StaleHold
- With the unified gated index, the c-access write is naturally VICE-faithful
  (vbuf[vVmli], prefetch_cycles for $ff, skipped cells stale). Remove the
  fldPrefetchShift, the $ff backfill, and col0StaleHold (no longer needed — the
  stale is now correct).
- Gate: colorfetchbug at its new floor; fldscroll/blackmail/fetchsplit 0;
  picture-mover left edge correct (fpsCapture statistical check vs VICE).

### U6 — Flip default + cleanup
- Full 139-test A/B 0 regressions + picture-mover visual clean → flip
  `jac64.vmliUnified` default true; remove the dual-index scaffolding and the
  dead drawGraphics/drawGraphicsVic bodies' vmli handling. Keep the flag one
  release for rollback.

## 4. Validation harness (exists)

- 139-test A/B: `/tmp/vmli_ab.sh` (flag off vs on, @30M, png_cell_diff).
- deterministic per-cycle: JaC `-Djac64.traceVicCycle` (EV-FetchC/EV-FetchG) vs
  VICE `JAC64_TRACE_FILE_FETCHC`, align by (rast,cyc) — colorfetchbug anchor.
- 3-way cell diff: /tmp/tw2.py (ON/OFF/REF per (row,ri,col)) — pinpoints
  regressions/wins per cell (used in §13).
- picture-mover: `-Djac64.fpsCapture*` scroll-tagged frames, match VICE by the
  `$19cb` byte (d64 non-det → statistical).

## 5. Risk register

| Risk | Mitigation |
|---|---|
| U2 display mapping wrong → whole-suite shift (the §16 failure) | U2 is the gate; iterate the pipe-depth/offset against U0's per-cycle table, NOT constants |
| A consumer relies on unconditional vmli advance | U0 inventory enumerates every consumer before U3 touches the increment |
| Sprite/border interactions | U4 isolates them; per-family gates |
| FLI improves but picture-mover (d64) can't be cycle-validated | statistical fpsCapture + deterministic suite as the hard gate |
| Large surface → hard to bisect a regression | each stage flag-gated + committed separately; A/B after each |

## 6. Rollback

All under `jac64.vmliUnified` (default off) until U6. Each stage is a separate
commit on the branch; a failing gate reverts that stage only. Master's default
path and `col0StaleHold` (e3ce8ad) are untouched throughout — zero shipping risk.

## 8. BREAKTHROUGH (2026-06-22) — VICE has TWO indices; model nailed

Digging into VICE src (vicii-fetch.c + vicii-draw-cycle.c) overturned the
"one gated vmli" premise. VICE has **two** indices, and JaC must reproduce both:

1. **`vmli` (fetch)** — vicii-fetch.c. Matrix-fetch (FetchC) writes `vbuf[vmli]`
   starting **raster_cycle 14** (vmli=0 → vbuf[0]); g-fetch reads `vbuf[vmli]`
   then `vmli++`. So at cyc N: write index = N-14.
2. **`dmli` (display)** — vicii-draw-cycle.c:309-320. SELF-incremented inside
   the draw-cycle (`vbuf_pipe0=vbuf[dmli]; dmli++` when vis_en && !vborder &&
   !idle), reset to 0 outside the visible area. NOT fed from vmli.

**Decisive trace datum** (added EV-Dmli to VICE, border-250): **`dmli == vmli-1`
every cycle** (cyc15: dmli=0,vmli=1; cyc16: dmli=1,vmli=2; …). The g-fetch reads
`vbuf[vmli]` BEFORE its `++`, so bitmap and color both reference cell `vmli-1`.

### JaC's bug (not "index duality" — a wrong dmli + a missed cyc14 write)
- JaC set `dmli = legacy vmli` externally; correct = **self-increment** like VICE.
- JaC's gbuf read used legacy vmli; correct = **vVmli-1** (= dmli).
- JaC's c-access starts at **cyc15** (BADLINE_FETCH_CYCLE), not cyc14, so a
  `vbuf[vmli]` write skips **vbuf[0]** (VICE wrote it at cyc14) → border garbage.

### The fix (jac64.vmliUnified), all verified:
- **VicDrawCycle**: `dmli` self-increments + resets (VICE 311-319).
- **C64Screen gbuf read (3810)**: `vicCharCache[vVmli-1]` (= dmli).
- **C64Screen c-access write**: `writeCAccess(vVmli)` (VICE write-leads-read-by-1
  — this is what makes colorfetchbug exact) PLUS at column 0 also
  `writeCAccess(vVmli-1)` to cover the vbuf[0] VICE wrote at cyc14.
- **External setDmli** suppressed when unified (let VicDrawCycle self-increment).

### Results (capture @30M, vs 8565 refs):
| test | off | on |
|---|---|---|
| colorfetchbug main | 7 | **1** |
| colorfetchbug bitmap | 6 | **1** |
| colorfetchbug main2 | 7 | **1** |
| colorfetchbug main3 | 14 | **4** |
| colorfetchbug main4 | 14 | **4** |
| border-250 | 0 | **0** |
| den01-48-0 | 0 | **0** |

colorfetchbug family 48→11, border/den unchanged. Beats shipping col0StaleHold.
Full 139-test A/B = the gate (running). Dead ends that led here: write=vVmli
alone (border 182), write=vVmli-1 alone (FLI 177) — only the double-write holds both.

## 7. Execution checklist

- [ ] U0 consumer table + per-cycle divergence map (text/border/FLI)
- [ ] U1 vVmli == VICE vmli verified on all three test types
- [ ] U2 gbuf+dmli on gated index; border/screenpos/modesplit back to baseline (GATE)
- [ ] U3 legacy vmli increment gated; 139-test A/B 0 regress
- [ ] U4 sprite/border/retroactive migrated; per-family gates
- [ ] U5 retire col-write/backfill/col0StaleHold; colorfetchbug floor + picture-mover clean
- [ ] U6 139-test A/B 0 regress + picture-mover visual → flip default, cleanup
