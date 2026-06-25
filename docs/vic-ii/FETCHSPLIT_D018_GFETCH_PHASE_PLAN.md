# fetchsplit / $D018 g-access sub-cycle write-phase fix — detailed plan

Status: PLAN (not started) 2026-06-18. Target: fetchsplit 132 JaC-vs-VICE → ~0.
Cross-refs: docs/cpu/SUBCYCLE_REFACTOR_PLAN.md (this is its "Phase 3" made
concrete for $D018), memory project_fetchsplit_dd00_bank_split_2026_05_28,
project_vmli_vbuf_refactor_design_2026_06_01 (rejected global-phase attempts),
docs/vic-ii/WORKPLAN.md (every fix cites a VICE line + a divergent trace).

---

## 0. Problem — precise statement

fetchsplit issues **bursts of `sta $d018` mid-line** (fetchsplit.asm:482-492,
532-540, …), ~every 6 cycles, switching the **charset base (CB bits)** so each
6-column region fetches its glyphs from a different charset. The screen (char
codes) and color RAM are unchanged across the switch, so:

- The **132 diff cells sit at the split boundary columns 4,10,16,22,28,34**
  (every 6), in 3 repeating bands (char-rows 2-6/10-14/18-22 = the test's
  8-row config period).
- Pixel extraction (char-row 4, col 10): **color/index matches VICE**
  (palette RGB differs but png_cell_diff is shape-based), but the
  **character-bitmap glyph differs** — VICE keeps the old charset's glyph
  through col 10 and switches at col 11; **JaC switches at col 10 (one column
  early)**.

### What it is NOT (ruled out with evidence)
- **NOT state-machine:** `cycle_trace.sh fetchsplit --rast 75-82` →
  `total field-divergences: 0` (vc/vmli/rc/addresses all match VICE).
- **NOT a whole-cycle fetch-source delay:** tried `vicBankFetchDelay2`
  (jac64.gBankDelay2), `vicMemFetchDelay2` feeding the g-fetch
  (jac64.gMemDelay2), and the existing `vicFetchDelay` — **all three no-ops
  (132 unchanged).** A whole-cycle delay can only move the switch ±1 whole
  column; it never lands on VICE's phase.
- **NOT the vmli/vbuf phase:** prior `vmliWithVc` attempt left 132→132 and
  regressed text mode (project_vmli_vbuf_refactor_design).

⇒ The residual is the **sub-cycle phase** at which a `$D018` write becomes
visible to the **g-access**. This is the same class as the broader sub-cycle
write-phase, but isolated to one register: `$D011`/`$D017`/DEN write-phase is
already correct (modesplit/d017/den all 0 vs VICE); only `$D018`→g-access is
off by one column.

---

## 1. VICE model (viciisc, cited)

`viciisc/vicii-fetch.c`:
- `vicii_fetch_graphics()` (256): `addr = g_fetch_addr(reg11/reg11_delay)`;
  `data = fetch_phi1(addr); vicii.gbuf = data;` — **the g-access is a PHI1
  fetch.**
- `g_fetch_addr()` charset base = `((vicii.regs[0x18] & 0xf0) << 6)` (159) and
  char/bitmap base from `regs[0x18] & 0x8/0xe` (169-172) — read from the
  **CURRENT `regs[0x18]`**.
- The `addr_from`/`addr_to` mixing (267-288) is the **`$D011` BMM-mode**
  RAM→CharROM LSB latch (`reg11_delay` vs `regs[0x11]`), NOT `$D018`. JaC's
  `mixPhi1FetchAddress` mirrors exactly this (RAM→CharROM only).

`viciisc/vicii-mem.c:274`: `vicii.regs[0x18] = value` on the CPU write (at the
write's **Phi2**). `vicii.c:86-104` maintain `vaddr_chargen_value_phi1/phi2`.

**Net VICE timing:** a `$D018` write commits `regs[0x18]` at Phi2 of cycle N;
the g-access at Phi1 of cycle N+1 reads it. The charset base used to render a
given column is therefore the `$D018` value as of the **Phi1 of that column's
g-access cycle** — i.e. reflecting writes up to and including the previous
cycle's Phi2.

---

## 2. JaC model (C64Screen.java, cited)

- `drawGraphics()` (4385): `pipeVByte = vicCharCache[drawVmli]`;
  `from = fetchCharMemoryIndex + pipeVByte<<3`, `to = charMemoryIndex + …`;
  `position = mixPhi1FetchAddress(from, to)`; `data = memory[position + rc]`.
- `fetchCharMemoryIndex = charMemoryIndexFor(fetchBank, fetchMem)` where
  `fetchMem = vicMemFetchDelay` — a **whole-cycle** (1-cycle) delayed snapshot
  of `$D018` mem bits (updated once/cycle at C64Screen.java:3971).
- `$D018` write (2261): `vicMem = data`; `charMemoryIndex` recomputed (2665).

**GAP:** JaC delays the charset base by a *whole cycle* (`vicMemFetchDelay`).
VICE reads the charset base at the **g-access Phi1**. JaC has no Phi1/Phi2
split for `$D018`, so the whole-cycle snapshot can't place the charset switch
at the correct sub-cycle column — it lands one column off, and the only knobs
available shift it by whole columns (hence all delay experiments were no-ops
or ±1-col, never matching).

---

## 3. OPEN QUESTION to resolve FIRST (direction)

There is a genuine contradiction to nail before any code:
- Pixel evidence: JaC switches **early** (col 10 vs VICE col 11).
- Yet VICE reads `regs[0x18]` **current** while JaC uses a **delayed**
  snapshot — which naively predicts JaC would be *late*, not early.

The resolution is that JaC's `drawGraphics` reads `vicCharCache[drawVmli]`
(the char *code*) at a render-cycle offset that interacts with the charset-base
snapshot; the *net* column where the new charset appears depends on both. This
**must** be pinned by an address trace (Phase 0) — guessing the direction and
coding the wrong sign would regress every `$D018` split (all FLI).

---

## 3b. Phase-0 EXECUTION findings 2026-06-18 (CORRECTS §2 and the rejected levers)

Started executing Phase 0. Two discoveries that change the plan:

1. **The active render path is `VicDrawCycle` (a separate class), NOT
   `drawGraphics`/`drawGraphicsVic`.** C64Screen.java:606 "VicDrawCycle is now
   the PRIMARY render path (default ON)" (`useVicFullPipeline = !legacyPaint`,
   default true). The g-fetch that feeds it is at **C64Screen.java:~3757**
   (`gByte = memory[fetchAddr]`, BMM `fetchAddr = vicBase + ((vc-1)&0x3ff)*8 +
   rc` with the landed `bmmVcFetchFix`), gated by `isFetchG` (PHI1_FETCH_G,
   vicCycle 16..55), then `vicDrawCycle.setGbuf(gByte)` (3874).
   ⇒ **The earlier "all levers ruled out" experiments (gBankDelay2/gMemDelay2
   in `drawGraphics`) edited DEAD CODE** — `drawGraphics` returns early under
   `useVicFullPipeline`. So those no-ops proved nothing; the real path was
   never probed. **The fix possibility is REOPENED** in the VicDrawCycle path.

2. **`cycle_align` does NOT compare the fetched gByte** — its FIELDS are
   `vc,vmli,rc,vcbase,idle,bad,abl,ys` (cycle_align.py:23). So "0 divergences"
   confirms only the state machine; whether gByte matches VICE is still open.

3. **Capture-timing caveat:** an EV-FetchG trace over clk 7.0–7.12M (the
   detSysJump window) shows the test's TEXT setup screen (d018=$14 constant,
   addr=CharROM $1d107, d011 BMM bit clear) — NOT the bitmap split pattern.
   The survey captures a LATER frame via `-Djac64.captureOnDone=true`. **Phase 0
   must trace at the captureOnDone frame**, not the fixed 7M window, or it sees
   the wrong screen. Also: in that early frame `$D018` does not change mid-line
   NOR per-raster — so the split mechanism must be re-characterized at the
   correct (captureOnDone) frame before assuming "mid-line $D018".

### 3c. Phase-0 round 2 (2026-06-18) — PREMISE FALSIFIED + tooling gap exposed

Traced at the deterministic capture frame and found:

4. **fetchsplit renders in TEXT mode, NOT a $D018/bitmap split.** At the
   captured frames `d018=$14` CONSTANT and `d011=$9b` (BMM bit clear) on every
   raster/cycle. The colored "data rows" in the reference PNG are colored TEXT
   characters, not bitmap. **The entire "$D018 charset-base sub-cycle phase"
   premise of this plan (§0-§2) is FALSIFIED.** If there is a real bug it is a
   TEXT-mode char-code render phase (which `vicCharCache[vmli]` index renders at
   which column), seen earlier as the glyph switching 1 col early at a screen
   char-code transition — NOT $D018.

5. **TOOLING GAP (the actual blocker): the deterministic trace frame ≠ the
   survey's pixel-diff frame.** Deterministic captures at clk 7099000 show the
   BASIC "READY." setup screen (rast $5b = char codes $12,$5,$1,$4,$19,$2e =
   "READY"); captures at 12M/25M/50M show blank. NONE reproduces the survey's
   full colored-text test pattern (which the warp-loop `captureFrames` capture
   does catch). So `cycle_trace.sh`'s "field-divergences 0" for fetchsplit
   compared the SETUP screen, not the pattern — i.e. the prior cycle-level
   "state matches VICE" claim for fetchsplit is UNFOUNDED (wrong frame). And
   the per-pixel 132 diff (survey) and any cycle trace are currently measuring
   DIFFERENT frames.

**⇒ The prerequisite is no longer "pin the $D018 phase" — it is a TOOLING task:
make the cycle/g-fetch trace capture the SAME frame the pixel-diff scores.**
Until the survey-frame and the trace-frame are the same screen, every
root-cause claim about fetchsplit (this plan's, and the memory's) is built on
mismatched frames and cannot be trusted. Options: (a) make the pixel-diff use
a deterministic `captureAtCycle` frame (and find the clk that shows the
pattern), or (b) make the trace fire during the warp-loop `captureFrames`
capture and tag the captured frame's clk. Solve this FIRST; it likely explains
why every targeted lever has been a no-op (they were evaluated on the wrong
frame's pixels).

### 3d. Tooling gap FIXED 2026-06-18 (commit 7559ca9)

Root cause of the frame mismatch: **JaC reaches the fetchsplit display pattern
~4x later than VICE** — VICE shows it by clk ~7.1M, JaC only by ~30M. So
`cycle_trace.sh`'s hardcoded JaC capture at 7099000 grabbed JaC's BASIC
"READY." setup screen, and the per-pixel survey (warp-loop, ~36M) caught the
pattern — different frames.

Fix landed:
- **Deterministic pattern-frame handle:** `captureAtCycle=30000000` (default
  inject, detSysJump) produces a JaC PNG **byte-identical (0 cells) to the
  survey's warp-loop capture**, and scores the same **132** vs VICE. So 30M is
  a reproducible handle on the exact frame the pixel-diff scores.
- **cycle_trace.sh** now takes `JAC_CAP` (decoupled from VICE `LIMIT`); with
  `JAC_CAP=30000000` it captures JaC's pattern frame (verified 0-diff vs
  survey). Committed 7559ca9.

Remaining tooling limitation: when JaC (30M) and VICE (7.1M) reach the pattern
at very different clks, the EV-State **fingerprint alignment can still mismatch**
(observed: it matched JaC's pattern frame to a VICE vmli=0 / non-display frame).
The robust path is a **direct (rast,cyc) g-fetch comparison** — both sides show
the SAME stable pattern, so rast R/cyc C is the same screen position; diff
EV-FetchG (JaC@30M) vs EV-FetchG-VICE (VICE@7.1M) by (rast,cyc). Needs VICE's
FETCHG trace window (vicii-fetch.c:299, currently rast $30-$40) widened to the
diff band + x64sc rebuild.

**Also note (possible separate bug):** JaC taking 30M vs VICE 7.1M to reach the
pattern is a 4x test-setup-execution difference worth investigating on its own
(timing/wait-loop), independent of the 132 render diff.

**Revised Phase 0 (do this first):**
- a. Make the trace fire at the same frame the survey captures: either honor
  `captureOnDone` in the trace run, or find the clk of the captured frame
  (instrument the capture) and window the trace there.
- b. At that frame, dump EV-FetchG (JaC, line 3760, already exists) over the
  diff-band rasters; determine the REAL split mechanism (mid-line $D018?
  per-raster? bank? bitmap-data content?).
- c. Capture VICE EV-FetchG-VICE (widen its rast window in vicii-fetch.c:299
  to cover the band; rebuild x64sc) at the aligned frame.
- d. Diff gByte/addr per (rast,cyc). If gByte matches → bug is in VicDrawCycle
  pixel emission (its gbuf-pipe phase); if gByte differs → bug is in the
  line-3757 fetch (vicBase/vc/rc timing). THIS dichotomy is the Phase-0 output
  that decides where Phase 1 operates.

## 4. Phase 0 — pin the exact phase (prereq, ~1 day) [SUPERSEDED by §3b revised]

- Enable `EV-GFXADDR` (JaC, C64Screen.java:4462) and the VICE `FETCHG` trace
  (vicii-fetch.c:290, env `JAC64_TRACE_FILE_FETCHG`) over a single `$D018`
  burst (a raster in the diff band, e.g. rast 77).
- Diff per-(rast,cyc) the **g-access effective address** JaC vs VICE.
- Output the exact rule: at the burst, for cycle C, VICE's charset base =
  f(regs[0x18] @ which write), JaC's = g(vicMemFetchDelay). Identify the
  cycle/column of first divergence and the **sign**.
- Gate: do not proceed to Phase 1 until the address trace reproduces the
  1-column glyph shift and names the exact phase rule.

## 5. Phase 1 — `$D018` Phi1/Phi2 split for the charset base (~2-3 days)

- Add a Phi1-phase charset-base value: on a `$D018` write, store into a
  "phi2" holding value; at the Phi2→Phi1 cycle boundary, promote phi2→phi1.
  The g-access (`drawGraphics`/`writeCAccess` as the trace dictates) reads the
  **phi1** charset base instead of `vicMemFetchDelay`.
- This mirrors VICE: "g-access at Phi1 sees `$D018` as of the previous Phi2."
  Replace the whole-cycle delay only for the g-fetch *charset base*; leave the
  screen base (VM bits, c-access) alone — c-access timing already matches
  (color is correct).
- Compose with `mixPhi1FetchAddress` (the `$D011` RAM→CharROM latch) — verify
  the two phase models don't double-count.
- **Flag-gated** `-Djac64.d018Phi1Gfetch` (default OFF until validated).

## 6. Phase 2 — validate / generalize (~1-2 days)

- If the trace shows the screen base (VM bits) also needs it, apply the same
  Phi1/Phi2 split to the c-access videoMatrix; otherwise leave it.
- Confirm the BMM (`drawGraphicsVic` gbuf-pipeline) path is unaffected
  (fetchsplit is text-mode; bitmap-mode $D018 splits should also improve or
  stay neutral).

## 7. Risk & validation matrix

The change touches the **shared text-mode g-fetch path** — the same path whose
global phase-shifts previously regressed `greydot`/`screenpos`. Mitigation:
the phi1 charset base **equals the current value whenever `$D018` is static**,
so static screens must be bit-identical. VERIFY that invariant in code AND
empirically:
- Full **cycle_align A/B** over fetchsplit (target: field-divergences stay 0,
  pixel diff 132→~0).
- **16-test survey** must stay 133 *minus* fetchsplit's gain, every other test
  unchanged (esp. greydot=0, screenpos=0, modesplit=0, colorsplit=0).
- `d011h*`, `d017-54/57`, `den01-48-*`, `colorsplit` unchanged.
- A **$D018-split demo**: Krestage 3 FLI + picture-mover (already fixed — must
  not regress).
- Tripwire: if ANY static-screen test moves by even 1 cell, the phi1==current
  invariant is broken — stop and fix the invariant, do not tune.

## 8. Effort & risk

- Phase 0: ~1 day. Phase 1: ~2-3 days. Phase 2/validate: ~1-2 days.
  **Total ~1 week focused.**
- Risk: **medium-high** — shared FLI-critical path; wrong-sign phase regresses
  all `$D018` splits. BUT bounded (one register's sub-cycle phase), precisely
  targeted, and protected by the phi1==current-when-static invariant +
  exhaustive A/B. The Phase-0 trace is the gate that removes the direction
  risk before any code lands.

## 9. Why this is worth doing (not niche)

`$D018` mid-line splits ARE FLI / per-line graphics — core demo technology.
The demo-critical instances (Krestage 3 FLI, picture-mover) were already fixed
via `bmmVcFetchFix`/`fldscroll`, but those addressed the BMM g-fetch vc and the
c-access prefetch; the **text-mode `$D018` charset-base sub-cycle phase** is the
remaining unmodeled piece, and fetchsplit is its torture test. Fixing it closes
the last real VIC-vs-VICE state+data divergence in the anchor suite.
