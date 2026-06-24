# Krestage 3 Picture-Mover char0 FLI-bug — Fix Plan

Status: diagnosed, not fixed. Targeted fixes exhausted; remaining fix is
sub-cycle VIC c-access work gated on one VICE datum.
Last updated: 2026-06-21.

Related memory: `project_colorfetchbug_caccess_2026_06_13.md` (full trace history).

---

## 0d. 2026-06-23 — ground truth: JaC foreground vs VICE background; leading suspect = dmli+1 (NEEDS d016-exact confirm)

Screenshot RGB (no markers) at leftmost char, $ee: **JaC = GRAY (149=$f lt-gray, 108=$c gray); VICE = BLACK** (D021 background). So VICE renders the FLI-prefetch cell as BACKGROUND, JaC as FOREGROUND. Scroll-aligned fetch comparison (JaC vc = VICE vc+1 label):
- gbuf: JaC vc483=$c3 = VICE vc482=$c3 — MATCH (content-start aligns)
- vbuf: both $ff — MATCH
- cbuf: JaC $c vs VICE $6 — DIFFER (prefetch colour; JaC prefetchPc byte ≠ VICE reg_pc)
- **dmli: JaC=4 at cyc18 vs VICE=3 — JaC +1** → JaC displays the NEXT cell (real $c3 content) where VICE displays the prefetch/background cell. This is the leading suspect (cell-index off-by-one at the left edge).

⚠️ CONFOUND: the JaC and VICE captures at "$19cb=$ee" had DIFFERENT d016 (JaC $16/$17 xscroll6/7 vs VICE $11 xscroll1) — $19cb=$ee spans multiple fine-scroll(d016) sub-states. So dmli=4-vs-3 may be the xscroll difference, not a true off-by-one. MUST match BOTH $19cb AND d016 before concluding. NEXT (no code yet): capture JaC and VICE at $ee AND identical d016, read the resolved leftmost pixel + dmli; if dmli still +1 at matched d016 → fix the dmli self-increment start (VicDrawCycle, vmliUnified) by one; if cbuf is the visible diff → fix prefetchPc. DON'T patch before this (two prior hypotheses — left-border, gbuf — were confounded/wrong). Repro: ARTIFACT_jac_ee.png (gray) vs ARTIFACT_vice_ee.png (black).

## 0f. 2026-06-24 — component audit: all FAITHFUL → it's a timing interaction (not a spotable logic bug)

Audited every component on the dmli path against VICE; all are faithful ports:
- vicIdleState (C64Screen:1255-1262) = verbatim VICE update_rc (rc==7→idle=1,vcbase=vc;
  !idle||bad→rc++,idle=0) + badline→idle=0 (1210). FAITHFUL.
- dmli increment (VicDrawCycle, vmliUnified) = VICE "inc when vis_en && !vborder && !idle". FAITHFUL.
- border flop timing (checkHBorderLeft CSEL cyc), drawBorder8, fetch (c+g) — all FAITHFUL.
⇒ The dmli divergence is a SUBTLE TIMING interaction — WHEN vis_en/vborder/idle
transition relative to the per-cycle dmli increment in the FLI region — not a
logic error visible by inspection. A blind patch (dmli-1, idle flip) would be a
4th confounded guess and would break the suite. STOPPED here.

**PRECISE NEXT DIAGNOSTIC (do before any code):** add vis_en + idle_state to BOTH
per-cycle traces (JaC EV-DrawCycle already has vb/dmli — add vicIdleState + visEn;
VICE: add idle_state + vis_en to PX-LATCH or a new per-cycle trace). Capture at the
mover, join on EXACT (rast,vc,d016), and tabulate per cyc: (vis_en, vborder,
idle, dmli) JaC vs VICE for ONE matched FLI top-row (rast~$33/$44). The first cyc
where (vis_en,vborder,idle) AGREE but dmli has already diverged — OR where one of
the three disagrees — pinpoints the exact transition. THEN fix that transition's
timing, gate by the dmli-join (delta→0 on the mover) AND the 139-test suite green.

## 0e. 2026-06-23 — dmli IS misaligned but NON-CONSTANT (not a 1-line fix)

Joined JaC EV-DrawCycle vs VICE PX-LATCH on EXACT scroll-state (rast,vc,d016),
JaC vc-1 label. 6591 common display-states. **dmli delta (JaC−VICE): dominant +2
(1749), some +1 (46)**; rast$33 (top row) starts +1 at vc0 and GROWS (+2 by vc2).
So JaC's display index `dmli` runs AHEAD of VICE's, by a NON-CONSTANT amount →
JaC displays a later cell than VICE at the left edge, so the FLI-prefetch cell
that VICE shows as background renders as the next cell's foreground (gray) in JaC.

⇒ This is the ROOT (display-index misalignment at the prefetch left edge), but it
is NOT a clean off-by-one — it's the same hard vmli/dmli alignment problem the
vmliUnified work hit. The dmli self-increment (VicDrawCycle, jac64.vmliUnified)
is not perfectly VICE-aligned in the mover's FLI region. A naive dmli-1 would
break the suite (border-250=0 currently). The real fix = make JaC's dmli track
VICE's exactly through the FLI prefetch/late-badline transitions (where vc/dmli
advance differs). HARD; multi-step; needs the dmli increment/reset conditions
audited against VICE vicii-draw-cycle.c:308-320 for the FLI/prefetch case
specifically, with the (rast,vc,d016) dmli-join as the regression gate (delta
must →0) AND the 139-test suite staying green. NOT attempted (would be a 3rd
confounded patch). Negatives in the delta histogram (-14..-18,-38) = idle/border
or frame-collapse noise, ignore.

## 0c. ROOT CAUSE candidate 2026-06-23 — FLI-prefetch cell (gbuf hypothesis SUPERSEDED by 0d/0e)

The leftmost gray (user: "should be blue around the deer, JaC shows gray") is the
FLI-bug prefetch cell rendering FOREGROUND-gray where VICE renders BACKGROUND.
Concrete (rast $94 cyc18, $ee, EV-DrawCycle): `gbuf=$c3 vbuf=$ff cbuf=$c` →
rb=`f.f.f.f.f.f.f.21` → leftmost char emits `$f` (lt-gray). The gray comes from
**vbuf=$ff (the FLI prefetch byte)** — in bitmap mode $ff's nibbles are $f/$f =
gray. JaC's **bitmap gbuf for the prefetch cell is NON-ZERO ($c3/$cc)** so pixels
resolve to foreground (gray); **VICE's gbuf for that cell is 0** so they resolve
to background D021 (blue at the deer, black at the wolf). The gray pixel sits at
cyc18.px7 / cyc19.px0-6 (one char, shifted by xscroll=7) on ~every raster →
full-height gray leftmost column.

⇒ **FIX TARGET: the FLI-prefetch leftmost cell's G-ACCESS must read idle
($3fff→0) like VICE**, so it resolves to background, not gray foreground. This is
colorfetchbug-FAMILY (the $ff prefetch) but the GBUF/g-access side, NOT the
c-access side that jac64.vmliUnified already fixed (hence ON==OFF on crest). One
fix resolves both gray-vs-black (wolf) and gray-vs-blue (deer). NEXT: find where
JaC computes the prefetch cell's g-access (drawGraphics gbuf for the prefetch/
idle columns) and gate it to idle like VICE's vicii_fetch_idle_gfx; verify VICE's
gbuf=0 for the prefetch cell via FetchG at the matched scroll.

## 0b. ★★ DETERMINISTIC REPRO + CONFIRMED JaC BUG 2026-06-23

**Repro recipe (no frame-matching needed):**
- The mover scroll is the `$19cb` byte (BNE self-mod target). `fpsCapture` tags
  frames `_s<hex>`. The artifact is at **`$19cb = $ee`**.
- JaC: `.capture/krestage3_crest.prg`, any `_see` sweep frame (e.g.
  `sweep_0056_clk181108997_see.png`) → leftmost char column (x32-39) is a
  **full-height gray character-garbage column**. Neighbour `$ed` is clean black.
- VICE: same prg, the `$ee` scroll occurs at **clk 182156453** (found via the
  EV-ChkBrdL trace which now logs `s19cb=`); screenshot there → leftmost column
  **BLACK**. char0 bright-px: JaC **124** vs VICE **6**. **It is a JaC bug, not
  faithful FLI-bug emulation** (the demo scroller literally says "move the FLI
  bug", but VICE renders the edge black; JaC does not).
- Saved: `/tmp/ARTIFACT_jac_ee.png`, `/tmp/ARTIFACT_vice_ee.png`.

**Characterisation:** leftmost char column (x32-39, C64 x24-31), FULL picture
height (img-rows ~44-226), **38-col mode (csel=0, d016=$16 → xscroll=6)**.

**RULED OUT (verified):** fetch (c-access 452/453 + g-access 372/378 match);
left-border flop TIMING (JaC AND VICE both open at rast$44 cyc17 csel=0
mainB 1→0 — identical); `drawBorder8` (faithful port incl. 38-col 7px/1px split).

**OPEN PUZZLE (next step):** at rast$44 JaC EV-DrawCycle shows `px=0` (black) at
cyc17-19, yet the rendered image shows garbage at the matching row → the px-field
semantics or the vicCycle↔screen-x mapping needs untangling (which vicCycle emits
display x32-39?). In 38-col the leftmost ~7px (C64 x24-30) that 40-col shows
should be border; JaC appears to show the leftmost char's bitmap there. Pin by:
map display x32-39 to the emitting vicCycle, dump JaC's emitted vs VICE's RESOLVED
pixel at THOSE cycles for an `$ee` row, find where JaC emits content & VICE border.
VICE tools: EV-ChkBrdL now logs `s19cb=`; PX-LATCH has vc/dmli; clk-gate via
JAC64_TRACE_CLK_MIN. JaC: `-Djac64.vicDrawTrace` EV-DrawCycle (px[8]+mb+vb+dmli+vc+d016).

## 0. ★ LOCALIZED 2026-06-23 — it's the LEFT-BORDER EDGE, not the fetch

Full deterministic JaC↔VICE trace of `.capture/krestage3_crest.prg` (added clk-gate
+ vc/d016 to VICE FetchC/FetchG/Dmli/PX-LATCH patches; JaC `vicDrawTrace` already
emits px[8]+mb+vb+dmli, added vc+d016). Frames DRIFT (different scroll at same clk),
so JOIN both traces on scroll-state (rast,vc,d016), JaC vc-1 to match VICE label.

**Fetch is CORRECT** (ruled out): char0 c-access (vbuf+cbuf) 452/453 match;
g-access (gbuf bitmap) 372/378 match. The leftmost char's char/color/bitmap are all
fetched identically to VICE. The bug is the DISPLAY→pixel stage.

**Divergence localized to the LEFT-EDGE cycles (vicCycle 13–16) at the TOP rows
rast $44–$4a (raster 68–74 = "up there") + bottom rast $f3–$f4.** Left-edge pixel
join over 94k scroll-states → 3907 leak-states, ALL clustered at cyc 13–16 / those
rasters, ~61 scroll-values each (systematic, not scroll-specific). Bidirectional:
JaC-shows-content-where-VICE-border (1240) AND JaC-border-where-VICE-content (2667).
⇒ JaC's LEFT MAIN-BORDER flip-flop opens/closes at a different vicCycle than VICE
at the top/bottom display rows (where the mover's per-line $D011/$D016 FLI tricks
move the border-compare). Matches user's report: "a row of chars that flickers,
hidden by the border, in its last 8px before the leftmost border." JaC opens the
left border at cyc17 (mb 1→0) on the mover lines — compare to VICE's open cycle and
fix the border-flop timing (checkHBorderLeft / 38-40col CSEL compare), NOT the fetch.
NEXT: dump JaC vs VICE left-border-open cycle at a scroll-matched rast $45 state.

## 1. Problem statement

During the Krestage 3 picture-mover, JaC paints a **flickering `$ff` garbage
column in the leftmost char column (char0, x40–47)** where VICE renders the
edge **black**. It is hyper-specific to fine-scroll **`$19b9`** (the
self-modified BNE-target byte at `$19cb`): char0 ≈ 78–89, while the adjacent
scrolls `$19b8` (char0 ≈ 0) and `$19bb` (char0 ≈ 2–3) are clean. As the mover
sweeps through `$19b9` for ~one frame, the user sees a flicker.

Confirmed real (scroll-matched ON/OFF: backfill ON → char0 78–89; backfill OFF
→ char0 = 0.0). VICE's fine-sweep never produces the isolated
`[bright char0][dark gap][picture]` signature.

## 2. Root cause (established)

- The garbage is the `fliLeadingPrefetch` backfill (C64Screen.java case 16,
  ~line 3381) firing because **char0 was skipped** (`col0FetchedThisLine=false`).
- char0 is skipped because the FLI loop's `$D011` (ysmooth) write makes the
  badline rise **after** JaC's **fixed** char0 c-access at case 15
  (`BADLINE_FETCH_CYCLE = 15`). VICE's matrix fetch (`vicii_fetch_matrix`,
  viciisc/vicii-fetch.c:191) runs every c-access cycle gated on `bad_line`, so
  it catches char0 at raster-cycle 14 (verified: VICE deer fetches vmli=0,
  vbuf=$0, cbuf=$0 = black; colorfetchbug starts at vmli=2).
- **CPU instruction timing is identical** JaC↔VICE (STA $19c2 cyc6,
  STY $19c5 cyc10, STX $19c8 cyc14 in both). It is **not** a mistimed
  instruction. The `$D011` write→effect lag is +1 in both. The divergence is
  purely the **char0 c-access cycle** (JaC fixed case15 vs VICE variable cyc14).

## 3. Ruled out (do NOT re-try)

| Attempt | Result |
|---|---|
| Disable backfill (`fliLeadingPrefetch=false`) | char0 → black BUT colorfetchbug 7→357 |
| Prefetch color via `instructionStartPC` (blackmail fix) | tested at $19b9: **no effect** (char0 still 78–89) |
| CPU instruction-timing hunt | timings identical; nothing to fix |
| `riseCyc` / `idle` / `ba_low` / `prevChar0` gates | cases observationally identical in JaC's model |

## 4. Open question — MUST resolve first (Phase 0)

At scroll `$19b9`, does VICE:
- **(A)** real-fetch char0 (vmli=0 present in FetchC, vbuf/cbuf = black), or
- **(B)** prefetch char0 (vmli=0 absent → `$ff` + bus color, rendered dark)?

This decides the whole fix direction. We have VICE's deer FetchC at one scroll
(real-fetch); we need it **at the `$19b9` mover scroll** specifically.

---

## 5. Phased plan

### Phase 0 — Get the decisive VICE datum (no deterministic boot needed)

1. Run VICE (`JAC64_TRACE_FILE_FETCHC` + `JAC64_PC_TRACE_FILE`) over the
   picture-mover window. Match frames by the `$19cb` scroll byte (BNE target),
   **not** absolute clk — sidesteps the d64 boot non-determinism.
2. Find a raster line where VICE's loop is at BNE-target `$19b9` and dump the
   first matrix-fetch cycle + vmli + vbuf/cbuf for char0.
3. Classify as (A) real-fetch-black or (B) prefetch-dark.

Exit criteria: we know VICE's char0 mechanism at `$19b9`.

### Phase 1A — if VICE REAL-FETCHES char0 (likely)

Goal: make JaC fetch char0 at the boundary scroll like VICE, i.e. give JaC's
char0 c-access the same cycle-flexibility VICE's matrix fetch has, without
disturbing the load-bearing `BADLINE_FETCH_CYCLE`.

Approach (least-risk → most-faithful):
1. **Late-rise re-fetch (surgical):** if the badline rises at case 16 (one
   cycle after the case-15 char0 fetch was skipped), and VICE would still have
   c-accessed char0 at its cycle, perform a *real* char0 c-access at case 16
   (instead of the `$ff` backfill) — but ONLY when the VICE model says char0 is
   in the real-fetch window (`prefetch_cycles == 0` for cell 0), NOT in the
   colorfetchbug case (`prefetch_cycles > 0` → keep `$ff`). This requires
   porting VICE's `prefetch_cycles` counter as the gate (it already exists in
   C64Screen ~line 3037 — verify it is correct at cell 0).
2. **Faithful matrix-fetch port (correct, larger):** replace the
   case15/case16 + backfill HACK with VICE's `vicii_fetch_matrix` model — a
   per-c-access-cycle fetch gated on `bad_line`, using `prefetch_cycles` to
   choose `$ff`+bus-color vs real colorRAM. This is the documented "refactor"
   but is the genuinely correct model and would subsume colorfetchbug,
   blackmail, fldscroll, fetchsplit into one mechanism.

Pick (1) if Phase 0 shows the difference is a single boundary cell driven by
`prefetch_cycles`; escalate to (2) only if (1) cannot separate the cases.

### Phase 1B — if VICE PREFETCHES char0 dark

Then both emus skip char0 and the difference is the rendered byte/color.
- We already disproved `instructionStartPC` color. So investigate whether
  VICE's `$ff` prefetch char renders **black** because of mode/`$D016`/`$D018`
  state at that cell (e.g. the bitmap byte vs the color), not the cbuf nibble.
- Compare JaC's vicCharCache[0]=`$ff` render path vs VICE's at the same cell.
  The fix would be in how `$ff` char data is colored/rendered at the FLI left
  edge, not the backfill color source.

### Phase 2 — Validation (mandatory, every variant flag-gated)

Run the existing harness for EACH candidate, flag-gated, default-off until proven:
1. **colorfetchbug family** must stay: main 7, bitmap 6, main2 7, main3/4 14
   (family ≈ 48). ANY increase = reject.
2. **fldscroll** 20/21/22/29/2A/2B, **blackmail** ee/fixed (= 0),
   **fetchsplit** (= 0) — all must stay 0.
3. Full 139-test `/tmp/backfill_ab.sh`-style A/B (HEAD vs candidate, capture
   @30M): require 0 regressions.
4. **K3 visual check:** fpsCapture deer-mover 282–284M; char0 at `$19b9` must
   drop from ~80 toward VICE's value (Phase 0 target) while $19b8/$19bb stay
   unchanged.

### Phase 3 — (optional tooling) Deterministic d64 boot

NOT required for the fix (user-declined), but useful if Phase 0/2 alignment
proves painful. Mirror the PRG `detSysJump` pattern: inject `RUN` at a fixed
emulated cycle instead of TestRaster's wall-clock `Thread.sleep` polling
(TestRaster.java ~489–520). Makes d64 runs reproducible scroll@cycle.

---

## 6. Risk register

| Risk | Mitigation |
|---|---|
| Touching `BADLINE_FETCH_CYCLE`/c-access breaks colorfetchbug/fldscroll/blackmail | Flag-gate every change; Phase 2 A/B is a hard gate; never change the constant directly — gate the *extra* char0 fetch on `prefetch_cycles` |
| `prefetch_cycles` in JaC not VICE-faithful at cell 0 | Verify against VICE FetchC before relying on it as the gate |
| Faithful refactor (1A.2) regresses many FLI tests | Land behind a flag; keep the old path; A/B both |
| d64 non-determinism muddies Phase 0/2 | Match by `$19cb` scroll byte, not clk |

## 7. Success criteria

- K3 `$19b9` char0 matches VICE (black / VICE's value), flicker gone.
- colorfetchbug family ≤ 48, all listed FLI tests unchanged, 139-test A/B
  zero regressions.
- Change is flag-gated, default decided only after green A/B.

## 8. Tooling reference (all exists)

- VICE: `JAC64_TRACE_FILE_FETCHC` (c-access), `JAC64_TRACE_FILE_D011`
  (EV-WrD011 cyc/pc), `JAC64_PC_TRACE_FILE` (+CLK_LO/HI) in 6510dtvcore.c.
- JaC: `-Djac64.tracePcCycles`, `-Djac64.traceVicState` (ys/bad per cycle),
  `-Djac64.traceD011W`, `-Djac64.traceBackfill` (riseCyc/prevChar0 — was added
  & reverted; re-add from memory), `-Djac64.fpsCapture*` (scroll-tagged frames).
- Match JaC↔VICE by the `$19cb` BNE-target byte (fine-scroll signature).

---

## 9. Sub-cycle refactor design (2026-06-21, user approved)

The targeted heuristics (`col0StaleHold`, instructionStartPC color) all just
recolor/reshape the same artifact — confirmed by user (gray flicker still
present "as soon as it becomes FLI"). The real fix is to port VICE's
**vmli-indexed c-access pipeline**.

### Model mismatch (deterministic, colorfetchbug rast $48)
- VICE: first c-access cyc16 **vmli=2** vbuf=$ff cbuf=$a; `vc = vcbase + vmli`;
  vmli/vc advance every display cycle (in vicii_fetch_graphics), vbuf[vmli]
  written only when bad_line (vicii_fetch_matrix), prefetch $ff while
  prefetch_cycles>0. Skipped cells (vmli not reached) keep stale vbuf/cbuf.
- JaC: column-indexed `col = vicCycle-15`, fetched only at case15+ when
  badLine; backfill hack forces char0/char1=$ff. No "resume at current vmli".
  → on a late badline JaC has no clean way to leave the right leading cells
  stale, so it either backfills $ff (wrong when stale should be real content)
  or holds stale (wrong when VICE prefetches $ff). That's the col0 gray/garbage.

### VICE pipeline order (vicii-cycle.c / vicii-fetch.c) — must replicate
1. g-fetch (vicii_fetch_graphics) every cycle: reads vbuf[vmli], THEN vmli++/vc++.
2. matrix-fetch (vicii_fetch_matrix) when bad_line: writes vbuf[vmli] (post-inc)
   = $ff+bus-color if prefetch_cycles>0 else colorRAM[vc]/screenRAM[vc].
   → vbuf[vmli] for cell k is written the cycle BEFORE k is displayed
   (the 1-cyc pipeline JaC's fldPrefetchShift partially addresses).

### Staged plan (validate colorfetchbug==7 + 139-test A/B after EACH stage)
- S1: introduce a true vmli/vc pair that advances every display cycle (not
  col=cyc-15), decoupled from the case dispatch. Keep current render reading
  unchanged; just verify vmli/vc track VICE (compare EV-FetchC vmli/vc).
- S2: write vicCharCache/ColCache indexed by vmli (not col), gated on badLine,
  with prefetch_cycles deciding $ff vs real. Remove the col0/col1 backfill.
  Skipped cells keep stale. ← the core change; colorfetchbug must stay 7.
- S3: reconcile the display read index (drawGraphicsVic) with the vmli write
  index (the vc/vmli phase that regressed 7→1239 in the naive attempt — get
  the phase exactly right by matching VICE's g-before-c order).
- S4: validate picture-mover visually (left edge stable, matches VICE) +
  full FLI suite (fldscroll/blackmail/fetchsplit unchanged).

### Validation prerequisite note
The picture-mover itself is non-deterministic in JaC (harness wall-clock d64
boot), so validate the picture-mover STATISTICALLY (many fpsCapture frames,
left-edge gray/garbage must drop) + rely on the deterministic suite (colorfetchbug
/fldscroll/blackmail/fetchsplit) as the hard regression gate.

## 0g. 2026-06-24 — dmli "+1" was a CYC-NUMBERING ARTIFACT; real lead = draw-vs-fetch phase

Added idle+visEn to EV-DrawCycle, idle to PX-LATCH; per-cycle compare at rast$33.
JaC visEn turns on cyc14 (loads vbuf[0]), dmli=1 by cyc15. VICE first dmli-load at
cyc15. BUT JaC vicCycle N = VICE raster_cycle N+1, so JaC cyc14 == VICE cyc15 —
**dmli is ALIGNED, the "+1/+2" from the vc-join was the vc-label + cyc-numbering
offsets compounding.** dmli RULED OUT (a dmli-1 patch would've been a 4th wrong fix
+ broken the suite). Real remaining lead: VICE fetches char0 BEFORE drawing it
(FetchC rc14 → draw rc15); JaC's writeCAccess lands at cyc15(=VICE rc16) while the
dmli read is at cyc14(=VICE rc15) → JaC draws the leftmost cell ~1 VICE-cyc before
its c-access writes it, so on FLI-prefetch lines the draw reads STALE vbuf. VERIFY
per physical cycle (align JaC cyc N ↔ VICE cyc N+1) at a matched FLI prefetch row
BEFORE patching. Pattern this session: every confident single-cause patch (left-
border, gbuf, dmli) was confounded — only careful per-physical-cycle verification
holds. Trace tooling ready (idle/visEn in both).
