# VIC-II vc/vmli/rc/vcbase + badline-idle Unification — Full Refactor Plan

Status: PLAN (designed 2026-06-25, not started). Supersedes the targeted-patch era
for the FLI left-edge / vc-phase class. This is the "§9 refactor" referenced
throughout `K3_PICTUREMOVER_CHAR0_FLIBUG_FIX_PLAN.md` §0h.

Related: `FLI_CACCESS_VMLI_REFACTOR_PLAN.md` (earlier c-access-only attempt),
`CYCLE_STEPPED_REFACTOR_PLAN.md`, `K3_PICTUREMOVER_CHAR0_FLIBUG_FIX_PLAN.md`
(the diagnosis that motivates this), `WORKPLAN.md` (every fix cites a VICE line +
a divergent trace).

---

## 1. Why a refactor (the wall, proven by measurement)

JaC's VIC has accumulated **per-test compensations** on top of a video-matrix
counter model that is NOT VICE-faithful. Each compensation fixed one test and
left the model internally inconsistent. The K3 picture-mover left-edge gray made
the inconsistency visible, and 7 targeted fixes were disproven by measurement
(K3 plan §0h). The decisive finding:

- On the FLI **late badline** (the mover's per-line `$D011` ysmooth trick), JaC's
  `vc` increments **39 times instead of 40** (the cyc15 `vc++` is skipped because
  idle is cleared one cycle late). So `vcbase` (captured at rc==7) is **one low
  (119 vs VICE 120)** and PROPAGATES through the whole FLI picture → the c-access
  reads colors one cell early = "right pixels, wrong colors" + left-edge gray.

The two structurally-sound fixes each break a DIFFERENT load-bearing test,
because `vc` is coupled to their tuning:

| Fix | K3 | Breaks |
|---|---|---|
| capture-time `vcbase +1` | static OK | live scroll flicker (vcbase toggles with scroll) |
| source cyc15 `vc++` | static OK, stable | screenpos +3062, colorfetchbug +180–203 |

⇒ The compensations (`bmmVc = vc-1`, c-access `vcBase+col`, `gFetchIdx = vVmli-1`,
the self-incrementing `dmli`, the late idle-clear) cannot be locally adjusted.
They must be **removed together** and replaced by VICE's single coherent model.

### The compensations to eliminate (each is a symptom)
- **`bmmVcFetchFix` = `(vc-1)`** (C64Screen ~3873) — exists because the g-fetch
  "runs AFTER finishCycleVic post-incremented vc". Symptom of doing vc++ at the
  wrong point.
- **c-access uses `vcBase + col`** (writeCAccess ~1748) instead of `vc`.
- **`gFetchIdx = vVmli - 1`** and the **`vmli` vs `vVmli` duality** (two matrix
  indices) — VICE has ONE `vmli`.
- **`vmliCol0Backfill`** (writes `vVmli-1` AND `vVmli` at col0) — patches the
  c-access starting one cycle late.
- **`fldPrefetchShift`** (writes c-data for column k while processing k-1).
- **late idle-clear**: JaC clears `idle_state` at cyc15 (in check_badline) AFTER
  the cyc15 `vc++` check; VICE clears it such that the cyc15 `vc++` sees it false.

---

## 2. Target model — VICE viciisc, ported verbatim

One coherent counter set, driven by the per-cycle chip-model table (JaC already
ports the table: `VicDrawCycle.PAL_6569_CYCLE_TABLE`). VICE sources to mirror:

- **`vicii-cycle.c`**
  - `update_vc` @ VICII_PAL_CYCLE(14): `vc = vcbase; vmli = 0; if (bad_line) rc = 0;`
  - `update_rc` @ VICII_PAL_CYCLE(58): `if (rc==7){ idle=1; vcbase=vc; } if(!idle||bad){ rc=(rc+1)&7; idle=0; }`
  - `check_badline` every cycle: `bad = allow_bad_lines && (line&7)==ysmooth`;
    `if (bad) idle = 0;`
  - `prefetch_cycles`: reset `3+1` when `!ba_low`, decrement while `ba_low`.
  - `cycle_phi1_fetch`: at FETCH_G cycles, `gbuf = idle ? fetch_idle_gfx() : fetch_graphics()`.
- **`vicii-fetch.c`**
  - `vicii_fetch_matrix` (Phi2, FETCH_C cycles): `vbuf[vmli] = prefetch ? 0xff : screen[vc];`
    `cbuf[vmli] = prefetch ? bus : colorRAM[vc];`  (NO vmli++ here)
  - `vicii_fetch_graphics` (Phi1, FETCH_G cycles): `addr = g_fetch_addr(vc, vbuf[vmli], reg11_delay);`
    `gbuf = fetch(addr);` **then `vmli++; vc = (vc+1)&0x3ff;`**  ← the single
    increment point. Both matrix and graphics read `vc`/`vmli` BEFORE this bump.
- **`vicii-draw-cycle.c`** — the pipe (already a faithful port in `VicDrawCycle`):
  `gbuf_pipe0 = vis_en&&!vborder ? gbuf : 0`; `vbuf_pipe0 = vis_en&&!vborder&&!idle ? vbuf[dmli] : 0`;
  load `*_reg = *_pipe1` at `i == xscroll`; `dmli++` when `vis_en&&!vborder&&!idle`.

**Invariant the refactor must establish:** `vc == vcbase + vmli` at all times, and
the g-fetch, c-access, and vcbase-capture ALL read the same `vc`/`vmli` (no -1, no
vcBase+col, no vVmli). The cyc15 increment is never skipped — `idle` is cleared by
`check_badline` BEFORE the increment, exactly as VICE orders it.

---

## 3. Acceptance suite (build FIRST — S0)

The lesson from §0h: single-frame capture passes while live fails. The suite must
be **dynamic + multi-test + live**. Gate every stage on ALL of:

1. **K3 scroll-gate** — `tools/vice-compare/k3_scroll_gray.py` (built). Target: the
   gray-bug colors (light-gray 149 / gray 108) at x39 drop to ~legit-floor across
   ALL 153 frames; frame-to-frame same-scroll instability not worse than baseline
   (+≤5%). EXTEND it to also score vs a VICE per-scroll reference if feasible.
2. **cycle_align** (`cycle_trace.sh`) — per-(rast,cyc) vc/vmli/rc/vcbase/idle MUST
   match VICE 0-divergences on: screenpos, fetchsplit, colorfetchbug, blackmail,
   a K3 FLI line. (Widen VICE EV-State raster gate via `JAC64_STATE_LO/HI` — it's
   currently $30–$40; the field already supports the env override.)
3. **Deterministic per-test A/B** (`captureAtCycle=30M`, png_cell_diff) — HARD gate:
   - screenpos = 0 (currently 0)
   - colorfetchbug family ≤ 48 (bitmap 6, main 7, main2 7, main3/4 14)
   - blackmail-ee / blackmail-fixed = 0
   - fetchsplit = 0
   - fldscroll 20/21/22/29/2A/2B, modesplit, colorsplit, vicii_reg_timing,
     greydot, rmwtest, ss-* — unchanged
   - full 139-test sweep: 0 regressions
4. **Live MCP check** — Krestage 3 picture-mover: left edge blue/black not gray,
   scroll smooth (no cell-jump). Rebuild `build/libs/JaC64.jar`, reconnect MCP.

Tripwire: if ANY static-screen test moves by 1 cell at any stage, an invariant is
broken — STOP and fix the invariant, do not tune.

---

## 4. Staged migration (flag-gated parallel path)

Land behind `-Djac64.viceVcModel` (default OFF) so the old compensated path stays
intact and A/B-able at every step. NEVER edit the live path until S6.

### S1 — Parallel counter core ✅ DONE + VALIDATED (2026-06-25)
Implemented `updateShadowCounters()` (C64Screen) — VICE vicii_cycle order verbatim
(phi1 g-fetch vc++/vmli++ gated on prev-cycle idle → check_badline → update_vc →
update_rc), shadow vars `vc2/vmli2/rc2/vcbase2/idle2`, frame reset vcbase2=vc2=0 at
vbeam==0, gated `-Djac64.viceVcModel` (default OFF). EV-State traces the shadow when
the flag is on.
**GATE PASSED — cycle_align (shadow) vs VICE = 0 field-divergences on BOTH:**
- colorfetchbug (FLI late-badline): **0** (legacy 984), frame fingerprint 0.0
- screenpos (normal line): **0** (legacy 2038)
**Render-neutral:** K3 + fetchsplit 0-cell change with the flag on (shadow only).
⇒ The VICE-faithful single counter matches VICE per-cycle where the legacy's
compensations could not. The refactor premise is PROVEN; S2–S4 are now mechanical
switches of the fetches onto vc2/vmli2 with hard A/B gates.

### S2 — c-access on the unified counter
Switch `writeCAccess` to `screen[vc2]` / `colorRAM[vc2]` indexed by `vmli2`
(remove `vcBase+col`, the col0 backfill, fldPrefetchShift). prefetch decision from
the unified `prefetch_cycles`.
**Gate:** colorfetchbug ≤ 48, blackmail = 0, fetchsplit unchanged, screenpos = 0,
K3 static $ee gray → ~0. (c-access is the "wrong colors" layer — this is where the
deer colors realign.)

### S3 — g-access on the unified counter
Switch the g-fetch to `g_fetch_addr(vc2, vbuf[vmli2], reg11_delay)` (remove
`bmmVc=vc-1`, `gFetchIdx=vVmli-1`). The single vc++ point eliminates the -1.
**Gate:** fetchsplit = 0 (the bmmVc test — the decisive one), modesplit = 0, K3
gate drops, NO pixel shift on static bitmap tests.

### S4 — display pipe (dmli) on the unified counter
Reconcile `VicDrawCycle.dmli` with `vmli2` (VICE's dmli == vmli at the pipe load).
Remove the self-increment/setDmli duality.
**Gate:** greydot = 0, ss-* unchanged, K3 left-edge pairing correct (gbuf $98 pairs
real vbuf, the §0h pixel test).

### S5 — full validation + iterate
Run the ENTIRE acceptance suite (§3) with `viceVcModel=true`. Iterate on
divergences using cycle_align (each must cite a VICE line). Expected wins beyond
K3: colorfetchbug may drop BELOW 48 (same root); fetchsplit/blackmail stay 0.

### S6 — flip default + delete compensations
Only after S5 is fully green: flip `viceVcModel` default ON, then DELETE the dead
legacy path and all the compensation flags (`bmmVcFetchFix`, `vmliCol0Backfill`,
`fldPrefetchShift`, `vVmli`, `fliVcCyc15Inc`, `fliVcbaseLateComp`, ...). Rebuild
jar, live-verify, update memory.

---

## 5. Risk register

| Risk | Mitigation |
|---|---|
| Re-times the whole VIC; many tests shift | Parallel shadow path (S1) proves counter correctness BEFORE any pixel moves; flag-gated A/B at every stage |
| screenpos depends on vc++ order | S1 cycle_align gate on screenpos = 0-divergences is the first hard gate |
| colorfetchbug=48 / blackmail=0 are real-HW-tuned, not VICE | Keep both refs; if unified model diverges from real-HW where it matches VICE, that is a SEPARATE chip-model question — flag it, don't force |
| Live scroll regressions invisible to single-frame | §3.1 scroll-gate + §3.4 live MCP are mandatory per-stage |
| Endless iteration | Each stage has a SHORT gate list; if a stage can't go green in ~2 sessions, the model port is wrong — re-read the VICE source, don't tune |

---

## 6. Code map (where each change lands)

- **C64Screen.java**
  - counter core: lines ~1186–1263 (vc++, check_badline, update_vc@13, update_rc@57)
  - c-access: `writeCAccess` ~1690–1769; `fetchBadLineData` ~1624–1672 (backfill/shift)
  - g-access: ~3811–3894 (`isFetchG`, `bmmVc`, gByte)
  - fields: `vc/vcBase/rc/vmli/vVmli` @264–275, `vicIdleState` @303,
    `BADLINE_FETCH_CYCLE=15` @46, `prefetchCycles` @583
  - dmli wiring to VicDrawCycle: ~4015–4039
- **VicDrawCycle.java** — pipe + dmli: `dmli` @185, pipe feed ~597–638,
  draw decode `drawGraphics` ~508–555, cycle table @89–153
- **VICE refs** — `vicii-cycle.c` (update_vc/update_rc/check_badline/prefetch),
  `vicii-fetch.c` (fetch_matrix/fetch_graphics/g_fetch_addr), `vicii-draw-cycle.c`
  (pipe). Trace tooling: EV-State, EV-FetchC/FetchG, PX-LATCH (all patched, incl.
  visEn/vbufReg added this session).

---

## 7. Effort & sequencing

- S0 (suite): ~0.5 session (gate exists; widen VICE EV-State, wire live check).
- S1 (shadow counter + cycle_align proof): ~1 session — THE critical gate.
- S2–S4 (c/g/dmli switch, each A/B'd): ~1 session each.
- S5 (full suite iterate): ~1–2 sessions.
- S6 (flip + delete): ~0.5 session.
**Total ~5–6 focused sessions.** Highest value: S1 — once the shadow counter
matches VICE per-cycle on screenpos AND a K3 FLI line simultaneously, the rest is
mechanical switching with hard gates. If S1 can't achieve that, the whole premise
(VICE model fixes all) is re-examined before touching render.

## 8. Definition of done

- K3 picture-mover left edge: blue/black (not gray), scroll smooth (live MCP).
- K3 scroll-gate: gray-bug colors at floor, instability ≤ baseline+5%.
- screenpos=0, colorfetchbug≤48, blackmail=0, fetchsplit=0, 139-test 0 regressions.
- All compensation flags + the legacy counter path DELETED; one VICE-faithful model.
