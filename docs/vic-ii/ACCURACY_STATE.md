# VIC-II accuracy — current state & what's left

_Last updated: 2026-06-03_

> **✅ 2026-06-03: sprite-xpos-wrap fix (commit a1f4795) — −147 cells, 0 regressions.**
> The sprite trigger compared raster xpos (negative in the left border) against
> 9-bit sprite-X without wrapping, so high-X sprites never rendered in an
> opened side border. VICE's xpos wraps at the **PAL line width 504** (not 512;
> verified vs VICE's cycle_table). Fix: mod-504 wrap + advance the sprite
> pipeline through left-border cyc 1-11. Closed **test-136-2a (#217) 118→0,
> border-mcbm 7→0, border-bm-idle/ysh/ysh2 →0, hvborder2 8→0** — all the same
> sprite-in-border bug. Flag `jac64.sprXposWrap` default on; PAL-only.

> **⚠️ CORRECTION (2026-06-01, later):** an earlier version of this doc claimed
> the remaining residual was "70–100% phase/measurement that VICE shares" and
> that JaC was "at its accuracy floor." That was based on
> `three_way_diff.sh`'s per-pixel classifier, which is **broken** — it compares
> raw RGB with **no image alignment** (JaC screenshots are 282 rows tall vs the
> 272-row references/VICE shots) and **no palette normalization** (VICE's
> `-exitscreenshot` palette differs from the `-8565` reference palette). Its
> `jac_bug`/`triple` breakdown is meaningless. The reliable tool is
> `png_cell_diff.py` (pattern-based, title-aligned, palette-independent), used
> by `survey_drift.sh` for both columns. By that tool there ARE real,
> well-localized, **static** bugs (not phase noise) — see "Real static bugs"
> below. Do not trust the old "at floor" framing.

## Real static bugs (confirmed via png_cell_diff JaC-vs-REF)

These are static tests (no demo-phase drift possible), so the cell-diffs are
genuine rendering bugs:

| Test | cells vs REF | where | cause |
|---|---|---|---|
| colorfetchbug `bitmap` | 357 | cols 0–1, every row | FLI-bug left-edge color fetch (VICE bug #1627) |
| colorfetchbug `main` | 357 | cols 0–1 | same |
| colorfetchbug `main2` | 356 | cols 0–1 | same |
| `fetchsplit` | 40 | split boundaries (cols every 6) | mid-line `$D018` split sub-cell phase |

The colorfetchbug family: the test creates **late badlines (after cycle 14)**
every 4th line. On real hardware the first ~2 columns are suppressed by the
FLI bug and show the flat `$d800` color (mid grey); JaC renders the real `$f0`
bitmap pattern there instead. This is the documented "bitmap-family left-edge"
(cols 0–1) issue — real, but a known-hard VIC quirk touching the badline/idle
transition + FLI-bug fetch.

### VICE color/graphics fetch model (deep-dive 2026-06-01, viciisc)

The complete VICE pipeline that JaC must match (file:line in the local fork):

1. **c-access** `vicii_fetch_matrix()` (vicii-fetch.c:191), gated by
   `bad_line && cycle_may_fetch_c` (vicii-cycle.c:809):
   - normal: `vbuf[vmli] = screen[v_fetch_addr(vc)]`, `cbuf[vmli] = color_ram[vc]`
   - prefetch/FLI (`prefetch_cycles>0`): `vbuf[vmli]=$ff`, `cbuf[vmli]=ram[pc]&0xf`
   - **written at index `vmli`, addressed by `vc`**.
2. **g-access** Phi1 (vicii-cycle.c:137):
   - `!idle_state` → `vicii_fetch_graphics()`: gbuf from `g_fetch_addr(reg11_delay)`;
     **increments BOTH `vmli` and `vc`** (vicii-fetch.c:313-316).
   - `idle_state` → `vicii_fetch_idle_gfx()`: gbuf from `$3fff`/`$39ff`;
     **no increment**.
3. **display** tail of `vicii_draw_cycle` (vicii-draw-cycle.c:308-320):
   - a SEPARATE display index **`dmli`**: `vbuf_pipe0=vbuf[dmli]; cbuf_pipe0=cbuf[dmli];
     dmli++` ONLY when `!idle_state`; idle → `pipe0=0`; outside vis area → `dmli=0`.
   - 2-stage pipe (pipe0→pipe1→reg) ⇒ display lags fetch by ~2 cyc (the readme's
     "display logic recognizes it in cycle 17").

### Why JaC diverges (all coupled — this is the hard part)

- **`vmli` increments unconditionally** (C64Screen.java:4396); VICE only in the
  non-idle g-fetch.
- **`vc` increments gated on `gfxVisible`** (C64Screen.java:1158); VICE gates on
  `!idle_state`. JaC has **two idle flags** (`gfxVisible` AND `vicIdleState`)
  where VICE has **one**.
- **`dmli` is overridden to `=vmli`** every cycle (C64Screen.java:3892); VICE
  uses a separate display index with its own increment/reset rules.

### Experiments tried 2026-06-01 (ALL reverted — none landed)

- `jac64.vmliIdleGate` (gate vmli++ on gfxVisible): **no-op** on colorfetchbug.
- `jac64.viceDmli` (faithful separate `dmli` ported into VicDrawCycle, increment
  only in display, reset outside vis area, ignore external setDmli): **regressed
  bitmap 357→359, left cols 0–1 unchanged** — because the new `dmli` is decoupled
  from the phase at which JaC computes `gByte`/fetches, so it just adds a phase
  offset. Proves cols 0–1 is NOT the display index.
- `zeroColorRam` toggle: no-op (ruled out the harness flag).

### Idle-flag collapse TESTED 2026-06-01 — NO-OP on the target

Per the "collapse the two idle flags" plan, gated all three on the single
VICE-faithful `vicIdleState` (flag `jac64.viceIdleUnify`):
1. `vmli++` on `!vicIdleState` (not `gfxVisible`) — **no change** (357/357/356).
2. `vc++` on `!vicIdleState` — **no change**, screenpos still 0.
3. gByte `idleFetch` decision on `vicIdleState` (matching the pipe) — **no change**, colorsplit still 0.

**Decisive result:** on the colorfetchbug display lines `gfxVisible` and
`vicIdleState` are already EQUIVALENT — there is no divergence to collapse, and
the entire idle/index/dmli layer is **empirically ruled out** for cols 0–1
(vmli-gate, vc-gate, dmli-port, idleFetch-unify all no-ops; dmli-port even
regressed +2). All reverted to clean baseline.

**Real cause is the DATA layer:** JaC's c-access reads colour `cbyte=$0` where
VICE reads `cbuf=$e` for the same cells, and the test rewrites colour RAM
dynamically via its raster IRQ so the colour-RAM CONTENT itself diverges
between the two emulators. That is the colorfetchbug colour-latch /
suppressed-c-access behaviour (#1627) plus the dynamic-memory content — NOT an
idle-state, index, or display-pipe issue. Code is at clean baseline (no source
changes survived this investigation).

---


This is the honest, data-backed picture of where JaC64's VIC-II accuracy
stands versus VICE x64sc, and what genuinely remains to fix. Read this
before assuming a residual is a clean bug — most of the remaining cell-diff
is **not** a JaC bug.

## Headline

- **119 / 138 VICE testprogs are pixel-perfect** vs VICE.
- **6510 CPU**: byte-exact with VICE at the cycle level.
- **VIC-II state machine** (vc / vmli / rc / vcbase / idle / badline / abl):
  byte-exact with VICE on every remaining residual (proven with the
  cycle-aligned per-cycle state harness — e.g. `blackmail-ee` has **zero**
  non-`vmli` state divergences over rasters 33–50).

## 2026-06-01: idle-flag collapse landed (gfxVisible → vicIdleState)

The two idle flags were collapsed into the single VICE-faithful `vicIdleState`
(`gfxVisible` removed entirely; display state is now `!vicIdleState`
everywhere). The FSM `update_rc` is now a verbatim port of VICE
`vicii-cycle.c:705-710`. Net −62 lines in `C64Screen.java`, and the code now
mirrors VICE's single `idle_state` model.

Full 138-test A/B (baseline build vs collapsed build, vs references):
**137/138 byte-identical**, one cost — **`gfxfetch 0 → 7`** (char-row 5, col 0).
That FLI test's correct output relied on the two flags *differing* at one
transition (`vc`/`rc` followed `gfxVisible`, the pipe-clear followed
`vicIdleState`); a single variable can't reproduce that split. Accepted as a
deliberate tradeoff for the cleaner, more VICE-faithful single-variable design.
dmadelay tripwires (test1/test1-2a-10/test2-28-16), ss-pri, screenpos,
colorsplit, greydot all unchanged at 0.

NOTE: this collapse does NOT fix colorfetchbug (that's a data/colour-latch
issue, separately proven) — it's an architecture/faithfulness cleanup.

## We already have the sub-cycle pixel pipeline

Do **not** propose "build a sub-cycle / per-cycle pixel pipeline" as the path
forward — it already exists and is the active render path:

| Component | What it is |
|---|---|
| `VicDrawCycle.drawCycle()` | Port of VICE `viciisc/vicii-draw-cycle.c` `vicii_draw_cycle`, emits **8 px/cycle** |
| `VicDrawCycle.drawCyclePart1()` / `drawCyclePart2()` | **Intra-cycle (sub-cycle) split** — gfx in part1, sprites/border/colors in part2; dispatched for mid-cycle `$D016` events (`C64Screen.java:3905/3920`) |
| `cycle_flags_pipe` | VICE's 1-cycle flag pipe so the gfx that emits at cycle N reflects the flags latched the cycle before |
| `VicSpritePipeline` | Port of VICE's sprite shift/priority pipeline |
| `VICE_CYCLE_ACCESS_PHASE` (CPU) | VICE's STORE→CLK_INC write-phase model (write@C visible to `vicii_cycle` at C+1) |

The infrastructure is not the gap.

## What the 3-way diff actually shows

`tools/vice-compare/three_way_diff.sh <test>` classifies every pixel:

- `jac_bug` — VICE **and** the reference agree, JaC differs → a real JaC bug.
- `ref_drift` — JaC matches VICE, both differ from the (stale) reference.
- `triple` — all three differ → phase/measurement (the display moved).

Result on the worst residual tests (2026-06-01):

| Test | png_cell_diff (JaC-vs-VICE) | `jac_bug` (real) | `triple` (phase) |
|---|---|---|---|
| bitmap | 357 | **0** | 100% |
| fetchsplit | 132 | **50** | 81% |
| border-mcbm | 2797 | **36** | 84% |
| blackmail-ee | 597 | **150** | 71% |

**70–100% of the big numbers are `triple`** — i.e. VICE *also* fails to match
its own reference image, because these are dynamic FLI/border displays and a
single screenshot catches each emulator at a different demo phase. That is a
**measurement limitation shared by VICE**, not a bug in either emulator.

The genuinely JaC-specific residual across these is **~236 cells**, not ~3900.

## The one real remaining bug family

The `jac_bug` residual is all one thing: a **mode-dependent `vmli` / `vbuf`
read-phase** offset *inside the existing pixel pipeline*.

- The pipeline emits correctly; the **data index** (`vmli`) fed into the
  g-fetch / `drawCycle` is off by one pipeline step in **bitmap / FLI-split /
  mid-line-split** contexts (surfaces in `fetchsplit`, bitmap left-edges, and
  the FLI write-phase tests like `blackmail`).
- It is **context-dependent** — correct in text mode, off in some
  bitmap/FLI-split cases. That is why a *uniform* shift can't fix it.

### Disproven approaches (do not re-try as global shifts)

Both were built, flag-gated, A/B'd, and **reverted** this session:

- `jac64.vmliWithVc` (move `vmli++` into lockstep with `vc++`): aligned the
  EV-State `vmli` field to 0.0 divergence but made pixels **worse**
  (`screenpos` 0→3060, `bitmap` 357→512); with read-compensation `screenpos`
  recovered but `bitmap` still 357→361. `fetchsplit` unchanged.
- `jac64.vmliReadPre` (read `vbuf` at `vmli-1`, mirroring `bmmVcFetchFix`):
  `fetchsplit` **132→132 (unchanged)**, `bitmap` 357→701, `main2` 356→707,
  `screenpos` 0→2992, `greydot` 0→3048. Disaster.

Conclusion from those: the EV-State "only `vmli` diverges" is a **harmless
emission-timing offset** for text mode — the render already compensates. The
real fix must be **mode-aware** (correct read-step per video mode), not a
global `vmli` rephase. That fix has not yet been found.

## What's left to fix (prioritized, honest)

1. **Mode-aware `vmli`/`vbuf` read-phase** (the only real lever): find the
   per-mode-correct read step that fixes `fetchsplit` / bitmap-split / FLI
   *without* regressing text mode (`screenpos`/`greydot`/`colorsplit` must
   stay 0). Bounded, in existing code; tricky because it's mode-dependent.
   Worth ~a few hundred cells. Use `tools/vice-compare/cycle_align.py` +
   `fetchg_diff.py` as the per-cycle gate, and the full 138-test A/B before
   commit.
2. **Everything else** (`border-mcbm`, dynamic FLI/border, the bulk ~6000
   cells): phase/measurement that VICE shares — **not actionable**. Stop
   chasing the big png_cell_diff numbers on these.

## Tools

- `tools/vice-compare/three_way_diff.sh <test>` — REF/JaC/VICE classification.
- `tools/vice-compare/survey_drift.sh [tests…]` — suite table (JaC-vs-REF and
  JaC-vs-VICE; the JaC-vs-VICE column is what's actionable).
- `tools/vice-compare/cycle_align.py` — phase-aligned per-cycle EV-State diff
  (aligns frames by per-raster ys/abl fingerprint; defeats demo-phase drift).
- `tools/vice-compare/fetchg_diff.py` — data-layer diff aligned by
  `$D018`-per-raster fingerprint (EV-FetchG addr/data). Note: VICE prints
  pre-bank addr, JaC post-bank — compare effective addresses.
