# FLI c-access vmli-pipeline refactor — full plan

Goal: make JaC's VIC-II c-access (color-matrix fetch) cycle-exact with VICE's
`viciisc` so the FLI left edge matches on dynamic content (Krestage 3
picture-mover), without regressing the FLI tests that currently pass.

Status: designed, not started. Root cause confirmed with deterministic traces.
Owner notes / history: `project_colorfetchbug_caccess_2026_06_13.md`,
`K3_PICTUREMOVER_CHAR0_FLIBUG_FIX_PLAN.md`.
Created 2026-06-21.

---

## 0. TL;DR

JaC fetches the color/char matrix **by display column** (`col = vicCycle-15`,
only when `badLine`), plus a `char0/char1 = $ff` backfill hack. VICE fetches
**by `vmli`** in a pipeline where `vmli`/`vc` advance every display cycle and
`vbuf[vmli]` is written only on a `bad_line` cycle (prefetch `$ff` while
`prefetch_cycles>0`, else real). On a **late badline** (FLI), the two models
diverge at the leftmost cells: VICE leaves the cells whose `vmli` already passed
**stale** and resumes at the current `vmli`; JaC can't represent "resume at
current vmli", so its leading cells are wrong (the col0 gray/garbage flicker).

The fix = port VICE's vmli pipeline, behind a flag, staged, suite-gated.

## 1. Success criteria

- **Hard gates (must hold at every stage):**
  - colorfetchbug family: main 7, bitmap 6, main2 7, main3/4 14 (≈48) — no increase.
  - fldscroll-20/21/22/29/2A/2B = 0; blackmail-ee/fixed = 0; fetchsplit = 0.
  - Full 139-test A/B (HEAD vs branch, capture @30M): **0 regressions**.
- **Win condition:**
  - Krestage 3 picture-mover left edge stable and matching VICE: leftmost char
    shows the correct prefetch band / stale content (no bright or gray
    flickering column) across the deer- and wolf-mover scrolls.
  - colorfetchbug ideally improves below 7 (the residual is the same pipeline phase).

## 2. Root cause (confirmed, deterministic)

colorfetchbug rast $48 (deterministic PRG, captured @30M):
- **VICE**: first c-access **cyc16, vmli=2**, `vbuf=$ff cbuf=$a`; `vc = vcbase + vmli`.
  vmli/vc advance every display cycle; `vbuf[vmli]` written only on bad_line.
- **JaC**: `col = vicCycle-15`, fetched at case15+ when `badLine`; `char0/char1`
  `$ff` backfill at case16; `fldPrefetchShift` writes `col+1`.

Picture-mover (non-deterministic d64): same mechanism, but the **stale** value
the skipped leading cells should keep is real picture content (black/blue), not
the `$ff` that colorfetchbug's continuous FLI leaves. JaC's backfill forces
`$ff` (→ bright/gray flicker) instead of keeping the right stale value, OR
`col0StaleHold` keeps stale when VICE actually prefetches `$ff` — neither is
right because JaC lacks the per-cell vmli/prefetch state.

CPU instruction timing is **identical** JaC↔VICE in the mover loop (verified:
STA $19c2 cyc6 / STY $19c5 cyc10 / STX $19c8 cyc14 both). So this is purely the
VIC c-access pipeline, NOT a CPU-timing bug.

## 3. VICE reference model (viciisc — replicate exactly)

`vicii-cycle.c` per `vicii_cycle()` (one system clock):
1. `cycle_phi1_fetch` → **g-fetch** (`vicii_fetch_graphics`): reads `vbuf[vmli]`,
   builds the 8 pixels, then **`vmli++`, `vc++`** (vc wraps at 0x3ff).
2. `check_badline()` (line 643, gated by `allow_bad_lines`): `bad_line =
   (raster_line & 7) == ysmooth`; if set, `idle_state = 0`.
3. `update_vc` at raster_cycle 13 (`cycle_is_update_vc`): `vc = vcbase; vmli = 0;
   if bad_line: rc = 0`.
4. BA + `prefetch_cycles` (lines 745-805): `ba_low = bad_line && fetch_ba`; if
   `ba_low` count `prefetch_cycles` down (start 3+1=4), else reset to 4.
5. **matrix-fetch** (line 813, `bad_line && cycle_may_fetch_c`):
   `vicii_fetch_matrix()` (vicii-fetch.c:191):
   - `if (prefetch_cycles) { vbuf[vmli]=0xff; cbuf[vmli]=ram_base_phi2[reg_pc]&0xf; }`
   - `else { vbuf[vmli]=fetch_phi2(v_fetch_addr(vc)); cbuf[vmli]=color_ram[vc]; }`
6. `update_rc` at raster_cycle 57.

KEY consequences:
- `vmli`/`vc` advance EVERY display cycle (in g-fetch), independent of bad_line.
- On a late badline, by the time `bad_line` is true the first matrix-fetch
  writes `vbuf[current vmli]` — cells `0..vmli-1` keep their **previous-line**
  value (stale). That stale value is whatever the last line wrote there.
- `vbuf[vmli]` is written the cycle BEFORE cell `vmli` is displayed (g-fetch
  reads `vbuf[vmli]` then `vmli++`, matrix-fetch writes the post-inc `vbuf[vmli]`).
  This is the 1-cycle pipeline `fldPrefetchShift` partially models.
- The prefetch byte is `$ff` with color = the CPU Phi2 bus byte
  (`ram_base_phi2[reg_pc]`), which during a BA stall is the frozen `reg_pc`
  (= JaC `cpu.getInstructionStartPC()`).

## 4. JaC current model (what to replace)

`C64Screen.java`:
- `updateVicStateVic` (~1143): `bad_line` FSM, `vc` (++ cyc15-54 if !idle),
  `vmli=0` at cyc13, `rc`, `prefetch_cycles` (~3037 countdown).
- c-access dispatch (case 15/16/17/default/54, ~3319-3454):
  `fetchBadLineData(col)` with `col = vicCycle-15`, gated on `badLine`.
- `fetchBadLineData` (~1605) → `writeCAccess(col)` (~1632): `prefetchCycles>0`
  → `vicCharCache[col]=$ff`, `vicColCache[col]=memory[prefetchPc]&0xf`; else real
  `memory[fvm+vcBase+col]` / `colorRAM[vcBase+col]`. `fldPrefetchShift` writes
  `col+1` (and col 0 directly).
- backfill (~3398): `char0/char1 = $ff` when `!col0FetchedThisLine`
  (col0StaleHold gate, default true).
- `handleBadLineStart` (~1586): `if (!wasVisible) { vc=vcBase; vmli=
  badLineFetchStartColumn; }`.
- display read: `drawGraphicsVic` reads `vicCharCache[displaycol]` / `vicColCache`.

Problems: write index is `col` not `vmli`; no per-cell stale (the whole-array
char/col caches are overwritten or backfilled); the late-badline resume column
is ad-hoc (`badLineFetchStartColumn`).

## 5. Staged implementation (flag-gated parallel path `jac64.vmliCAccess`)

Build the new path behind a flag, default OFF, so the current behavior is never
touched until the new path passes all gates. Flip the default only at S4.

### S1 — vmli/vc tracking (no behavior change)
- Add a VICE-faithful `vmli`/`vc` that advance every display cycle (cyc14-53),
  reset at cyc13 (`vc=vcbase; vmli=0`), wrap vc at 0x3ff.
- Do NOT change any fetch/render yet. Add a trace (reuse EV-FetchC) and verify
  the new `vmli`/`vc` match VICE's `JAC64_TRACE_FILE_FETCHC` `vmli`/`vc` per
  (rast,cyc) on colorfetchbug (deterministic). Gate: identical vmli/vc.

### S2 — vmli-indexed matrix fetch (the core change), flag-gated
- When `jac64.vmliCAccess`: on each cycle `cyc in [fetch range] && badLine`,
  write `vicCharCache[vmli]`/`vicColCache[vmli]`:
  - `prefetchCycles>0` → `$ff`, color = `memory[cpu.getInstructionStartPC()]&0xf`.
  - else → real `screenRAM[vc]` / `colorRAM[vc]`.
- Do NOT clear/backfill the caches; cells whose `vmli` is never reached keep
  their previous-line value (TRUE stale). Remove the col0/col1 backfill on the
  new path (the stale handles it).
- Keep the OLD path for `!jac64.vmliCAccess`.
- Gate: with the flag on, colorfetchbug must be ≤7. Expect it to NOT yet be 7
  until S3 fixes the read phase — that's OK, S3 is where it converges.

### S3 — reconcile display read phase (the hard part)
- `drawGraphicsVic` reads `vicCharCache[k]` for display cell k. With S2 writing
  by `vmli` and the g-before-c pipeline, the read index vs write index has a
  1-cell phase. This is what regressed 7→1239 in the naive attempt.
- Match VICE: g-fetch reads `vbuf[vmli]` BEFORE `vmli++`, matrix-fetch writes
  AFTER. So cell k is DISPLAYED from `vbuf[k]` which was WRITTEN at the previous
  cell's cycle. Implement the read at `vmli` pre-increment, write at `vmli`
  post-increment (or equivalently shift one path by 1 and verify against the
  deterministic colorfetchbug EV-FetchC vmli/vbuf/cbuf).
- Gate: colorfetchbug back to 7 (or lower) ON the new path. Iterate the ±1
  phase until the deterministic c-access bytes match VICE cell-for-cell.

### S4 — validate + flip default
- Full 139-test A/B (HEAD vs branch). Hard gate: 0 regressions, colorfetchbug
  ≤7, fldscroll/blackmail/fetchsplit unchanged.
- Picture-mover visual: fpsCapture deer- and wolf-mover windows; left-edge
  gray/garbage must be gone (leftmost char stable, matching VICE's band).
  Validate STATISTICALLY (many frames) since the d64 boot is non-deterministic.
- If all green: flip `jac64.vmliCAccess` default to true; retire the old
  col-indexed path + the col0StaleHold/backfill hacks. Keep the flag for one
  release as a rollback.

## 6. Validation harness (exists)

- colorfetchbug single: `java -Djac64.captureAtCycle=30000000
  -Djac64.captureFile=… colorfetchbug/main.prg` then `png_cell_diff.py` vs ref.
- 139-test A/B: `/tmp/backfill_ab.sh` style (base build vs branch build,
  capture @30M, png_cell_diff, report wins/regressions).
- deterministic c-access compare: JaC `-Djac64.traceVicCycle` (EV-FetchC
  col/vmli/vc/vbyte/cbyte) vs VICE `JAC64_TRACE_FILE_FETCHC` (vmli/vc/vbuf/cbuf),
  align by (rast, vc). colorfetchbug is the deterministic anchor.
- picture-mover: `-Djac64.fpsCapture*` (scroll-tagged frames), match VICE by the
  `$19cb` BNE-target scroll byte (NOT clk — d64 boot non-deterministic).

## 7. Risk register

| Risk | Mitigation |
|---|---|
| vc/vmli read-vs-write phase wrong → colorfetchbug explodes (7→1239) | S3 is isolated; iterate ±1 phase against deterministic EV-FetchC bytes before any suite run |
| Regress fldscroll/blackmail/fetchsplit (load-bearing prefetch/BADLINE_FETCH_CYCLE) | flag-gated parallel path; old path untouched until S4 green; A/B gate |
| Stale handling wrong on scene transitions (vbuf holds wrong prev value) | verify against VICE FetchC where vmli0 is/ isn't c-accessed; the stale source is the last line that wrote that vmli |
| Picture-mover non-deterministic → can't cycle-match | validate statistically + rely on deterministic suite as the hard gate; optionally add deterministic d64 boot (S0, below) |
| Sprite/idle/border interactions with the new fetch | keep finishCycleVic/draw order; only change the matrix-fetch write index + stale |

## 8. Optional S0 — deterministic d64 boot (de-risks picture-mover validation)

Not required (suite is the hard gate) but makes the picture-mover reproducible
for cycle-exact validation. Mirror the PRG `detSysJump` pattern: type `RUN` at a
fixed emulated cycle instead of TestRaster's wall-clock `Thread.sleep` polling
(`TestRaster.java` ~489-520). Then JaC reproduces the same scroll@cycle and the
picture-mover can be diffed cell-for-cell against VICE. User previously declined
this for diagnosis; reconsider for refactor validation.

## 9. Rollback

All work on a branch behind `jac64.vmliCAccess` (default false). If S4 doesn't
go green, the branch is abandoned with zero impact on master (default path
unchanged). The col0StaleHold commit (e3ce8ad) stays as the interim behavior
(or flip its default to false to revert to baseline) until the refactor lands.

## 10. Execution checklist

- [ ] S1: vmli/vc tracking + EV-FetchC trace; vmli/vc match VICE on colorfetchbug
- [ ] S2: vmli-indexed matrix fetch behind flag; stale kept; backfill off (flag path)
- [ ] S3: read/write phase reconciled; colorfetchbug ≤7 on flag path (deterministic bytes match VICE)
- [ ] S4: 139-test A/B 0 regress; picture-mover visual clean; flip default; retire old path
