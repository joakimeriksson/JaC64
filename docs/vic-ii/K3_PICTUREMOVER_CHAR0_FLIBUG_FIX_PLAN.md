# Krestage 3 Picture-Mover char0 FLI-bug — Fix Plan

Status: CHARACTERIZED, not safely fixable now (see §FINAL 2026-06-25).
Last updated: 2026-06-25.

## §REPRO-2 2026-06-25 — the test does NOT run identically in both emus (INVALID compare)

Tried the CPU-cycle diff (option 2). It exposed why the repro is invalid: the
hand-written test does NOT execute the same in JaC and VICE.
- JaC (via detSysJump → SYS $810): runs the FLI loop ($08c0) ~once/frame, ~100 IRQ
  entries / 2M cyc. Good.
- VICE (via autostart RUN): the raster IRQ RE-FIRES constantly — $08be (ldx #0 after
  the $d019 ack) executes 210,599× / 2M cyc, the FLI loop never completes. Even after
  disabling CIA IRQs, VICE IRQ-thrashes and never reaches a stable FLI.
⇒ The "JaC green vs VICE orange" left-edge difference was JaC running the FLI while
VICE ran a thrashing/partial display — DIFFERENT code paths, not an emulator-accuracy
comparison. The repro is INVALID.

**Honest endpoint:** a clean repro needs a hand-written FLI that (a) runs IDENTICALLY
under both JaC's detSysJump and VICE's autostart, (b) is cycle-STABLE (raster-locked),
and (c) doesn't IRQ-thrash. That is a real 6502 sub-project (stable raster IRQ +
cycle-exact FLI kernel + robust IRQ ack), not yet achieved. The CPU/BA-timing lead
(§REPRO) remains plausible but unproven without such a repro. test_fli_leftedge.prg
is kept as a JaC-side FLI exerciser, NOT a valid JaC-vs-VICE reference.

## §REPRO 2026-06-25 — handwritten FLI test: unstable, but exposes a CPU/BA-timing lead

Built `test_fli_leftedge.asm/.prg` (committed ee4fe84): MC-bitmap FLI, 38-col,
xscroll=7 (K3 config), no VSP trigger → VICE deterministic. Result + caveats:

- **JaC renders green/brown garbage at the FLI left column; VICE renders a clean
  uniform orange column.** A clear, deterministic JaC≠VICE left-edge difference (the
  K3-class symptom). VICE is stable here (left edge identical run-to-run).
- **BUT the hand-written FLI loop is NOT cycle-stable** — it never raster-locks
  (at $40: VICE ys=3/rc=5/no-badline; JaC ys=7→0/rc=0/badline). The 21-cyc tight
  loop + badline-steal ≠ 63 exactly, and it can't bootstrap the lock. So the loop
  DRIFTS, and JaC vs VICE drift to DIFFERENT positions.
- ⇒ The left-edge difference here is **timing-drift, not the clean render bug**.
  K3's real picture-mover FLI IS raster-locked (it's a working demo); my unstable
  loop is NOT representative of that stable case.

**The genuine NEW LEAD:** an unstable loop with IDENTICAL CPU/BA timing would drift
IDENTICALLY in both emus. JaC and VICE drift DIFFERENTLY ⇒ **JaC's CPU/BA cycle
timing in tight badline loops diverges from VICE's.** The K3 left-edge may therefore
be rooted in CPU/BA-steal cycle-timing accuracy (the documented CPU sub-cycle floor),
NOT the VIC render counters. To pursue cleanly: either bootstrap a cycle-STABLE FLI
(raster-locked) so only the render differs, OR do a direct per-instruction +
per-badline CPU-cycle diff JaC-vs-VICE in the loop (deterministic via this test).

## §FINAL 2026-06-25 — conclusive characterization (many earlier roots RETRACTED)

After exhaustive investigation, the reliable picture:

1. **The bug is real + well-defined at the leftmost char.** VICE's K3 x39-46 is
   STABLE and mostly black across runs (gray-rows@x39: VICE=0/0, JaC=132). JaC
   reveals one extra content char at x39-46; VICE's leftmost content is at x≥47.
2. **NOT the FLI-prefetch/vc-phase class.** colorfetchbug (the deterministic FLI-
   prefetch test) is ALREADY ~perfect vs VICE (~1-4 cells/variant). vicii_reg_timing
   and the FLI/border suite are all 0 vs VICE. So the K3 gray is a DISTINCT bug:
   the **content position at the 38-col(csel=0) + xscroll + scrolling-bitmap-FLI
   left edge** — JaC's leftmost displayed cell is one char too far LEFT vs VICE.
3. **The border timing is PROVABLY CORRECT.** Delaying the 38-col left border one
   cycle (`csel0BorderLate`) DOES hide the gray (x39→0, leftmost→x47 = VICE), but
   it REGRESSES vicii_reg_timing 0→14 vs VICE (deterministic). So cyc17 is the right
   border cycle; the delay is a symptom-mask (matches the 2026-05-25 reverted
   hideColumnDelay attempt, vicii_reg_timing +35). REVERTED.
4. **VICE's K3 run is NON-DETERMINISTIC** (VSP-bug RNG: 18632 px/frame vary between
   identical runs) → per-cycle K3 comparison is invalid. All earlier per-cycle
   roots here (vcbase 119-vs-120 §0h, dmli, draw-vs-fetch) are RETRACTED as
   VICE-jitter-confounded. The vc-unification refactor (S1 shadow counter validated
   0-div on colorfetchbug+screenpos, committed) does NOT help — colorfetchbug is
   already correct (VC_TIMING_UNIFICATION_REFACTOR_PLAN.md §0).

**Why not safely fixable now:** the real defect (content one char too far left for
scrolling-bitmap-FLI + xscroll) has (a) NO deterministic repro — every deterministic
FLI/border/xscroll test is already 0 vs VICE, and (b) a NON-deterministic VICE
reference. The only working symptom-mask regresses a perfect deterministic test.
A real fix needs a deterministic minimal repro (a static 38-col + xscroll=N +
bitmap-FLI test prg exhibiting the one-char-left content shift) to even validate
against — that construction is the prerequisite, not more per-cycle K3 tracing.

(Earlier sections §0..§9 below are superseded; kept for history.)

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

## 0h. ★★★ 2026-06-24 — per-physical-cycle verification: visEn/dmli ALIGNED; gray is the vbuf/gbuf PIPELINE-PHASE at the border edge (leading-g-fetch hypothesis TESTED → no-op)

Did the §0g per-physical-cycle verification at $ee (JaC deterministic clk 181108997,
VICE clk 182274389+, rast $4d FLI-prefetch line). Added visEn to VICE PX-LATCH
(this session) + VICE EV-FetchG-VICE (already existed). Aligned JaC↔VICE by the
content anchors **vc + dmli** (mapping-independent), confirming JaC vicCycle N ≡
VICE raster_cycle N+1.

**Findings, in order of certainty:**
1. **visEn ALIGNED, dmli ALIGNED.** JaC cyc14 (vc=119,dmli=0,visEn=1) ≡ VICE cyc15
   (vc=119,dmli=0,visEn=1). §0e/0g concerns (dmli, draw-vs-fetch visEn timing) are
   CLOSED — both aligned by content.
2. **The gray is a GBUF (g-access) divergence at dmli=0 and dmli=1.** At the left
   edge VICE gbuf=0 (→ background $21); JaC gbuf=non-zero ($42 stale at dmli=0,
   $13 at dmli=1) (→ foreground gray/$f). Both in MC-bitmap. Confirmed §0c.
3. **EXACT mechanism (EV-FetchG addr sequences, $4d):**
   - VICE g-fetch order: `$63b2`(0), `$63ba`($13), `$63c2`(0), … — vmli=0 @rc15
     reads **vc=118 → $63b2 → 0**.
   - JaC  g-fetch order: `$63ba`($13), `$63c2`(0), … — col0 @cyc15 reads
     **vc=119 → $63ba → $13**.
   ⇒ **JaC's 40 g-fetches are shifted ONE CELL LATE: they cover VICE's vmli 1..40
   (vc 119..158) and MISS VICE's leading vmli=0 (vc 118, $63b2=0)**, adding a
   harmless extra cell at the right (vmli≥40 dropped). The leftmost displayed cell
   (dmli=0) therefore shows the skipped cell's right-neighbour ($13) = gray, where
   VICE shows the real leading cell (0) = background.
4. **Why static screens are immune / why it's FLI-only:** the missing leading
   g-fetch leaves dmli=0's gbuf STALE from the previous line. On a static screen
   the leading cell's content is identical line-to-line, so stale==correct (no
   visible bug). FLI changes rc/content per line, so the stale leading cell is
   wrong → the K3 left-edge gray. Also explains why fetchsplit's bmmVc tuning
   (`bmmVcFetchFix`, vc-1) never exposed it.

**LEADING-G-FETCH FIX TESTED → NO-OP (0 cells). Hypothesis (3) was the wrong layer.**
Implemented `jac64.fliLeadingGfetch` (extra g-fetch at vicCycle 14 so dmli=0 reads
VICE's leading cell). It DID make JaC's gbuf FEED match VICE cell-for-cell at $4d
(cyc14 gbuf $42→0, and the whole feed JaC cyc14=0,15=$13,16=0 ≡ VICE cyc15=0,
16=$13,17=0). BUT the rendered PNG was **byte-identical (0/8000 cells)** — the gray
SURVIVED. Reverted (kept only the VICE visEn tooling). Lesson re-confirmed: a
VICE-faithful sub-fix that doesn't move pixels is the wrong layer.

**WHY the no-op (deeper, corrected root):** The gray at the displayed left edge is
NOT the missing leading fetch. Both emus g-fetch `$63ba=$13` for the vc≈119 cell.
The gray = JaC RENDERS `$13`'s bits as foreground over **vbuf=$ff** (→ `$f` gray)
+ **cbuf=$c** at the leftmost VISIBLE pixels (cyc18, after the 38-col border opens
at cyc17 + xscroll=7), where VICE at the content-aligned position emits background
`$21` / black `$0` (vbuf there = `$0`, real-fetched). So the divergence is the
**relative PHASE between (a) the gbuf shift-register content, (b) the vbuf/cbuf
matrix value, and (c) the border-flop reveal** at the FLI left edge — i.e. the
deep **vmli/dmli/vbuf pipeline-phase** (the same wall as `vmliUnified` / §0e),
NOT a single missing fetch. Specifically: at the first VISIBLE cell, JaC's vbuf is
still the `$ff` FLI-prefetch byte while VICE's is the real `$0` — so the gbuf bits
that do appear paint `$ff`'s `$f` nibble (gray) instead of `$0` (black/invisible).

**NEXT (do NOT patch on confidence — the last 4 hypotheses all confounded):** pin,
per physical cycle at $4d, which (vc-cell, pipe-stage) supplies the vbuf/cbuf AND
the gbuf for the FIRST border-revealed pixel (cyc18.px? at xscroll7), in BOTH emus.
The fix must make JaC's vbuf at that pixel be VICE's `$0` (real) not `$ff` (prefetch)
— i.e. it is a c-access prefetch-EXTENT / vbuf-pipe-phase issue at the border edge,
likely only addressable by the vmli c-access pipeline refactor (§9), validated by
the 139-test suite + colorfetchbug==48 as the hard gate.

Trace tooling added this session: visEn in VICE PX-LATCH (vicii-draw-cycle.c). The
`fliLeadingGfetch` flag was reverted (no-op).

### 0h continued — PNG ground-truth + flag refutations (2026-06-24 cont.)

Stopped trusting internal vc/vmli labels (they mislead) and measured the FINAL
rendered PNGs (JaC deterministic $ee @181108997 vs VICE @182274389). Both 384px
wide. Left-edge-x histogram (first non-black px per row):
- **JaC: 132 rows have content starting at x=39** (a full-height strip), and the
  strip is a full **8px char (x39–46)**.
- **VICE: NO rows start at x=39** (earliest ≥41, picture body ≥52); x39–46 is
  black/border on every picture row.
⇒ The gray is **one extra leftmost CHAR** that JaC displays and VICE does not.

**Refuted by direct test (PNG unchanged / strip still present):**
- `fliLeadingGfetch=true` → 0/8000 cell change (no-op).
- `bmmVcExtraShift=1` (g-fetch vc−2) → shifts the WHOLE bitmap one cell (66% of
  cells change) and the gray just MOVES to the next cycle — not a uniform vc error.
- `vmliCol0Backfill=false` → strip persists (149 rows). NOT the c-access backfill.
- `vmliUnified=false` → strip persists (132 rows). NOT the unified read-index.

**Mapping-resolved mechanism (the one consistent story):** JaC's leftmost DISPLAYED
cell is **vc119** (g-fetch `$63ba`=`$13`, gbuf bits set → `$ff` vbuf paints `$f`
gray); VICE's leftmost displayed cell is **vc120** (gbuf=0 → `$ff` stays background).
Both leftmost cells have vbuf=`$ff` (prefetch); the ONLY difference is the gbuf
(content) of the cell that lands at the first border-revealed pixel: JaC is one
content-cell EARLIER than VICE. No single existing flag moves this boundary — it is
the relative phase between (gbuf shift-reg) ↔ (border-flop reveal at csel=0/xscroll=7)
↔ (the FLI per-line vc/rc), i.e. the genuine vmli/dmli/vc left-edge alignment that
needs the c-access pipeline refactor (§9), NOT a targeted patch. This is the 5th+
single-cause hypothesis disproven by measurement — the discipline holds: the K3
left-edge is NOT a one-liner; it co-resolves with the §9 vmli pipeline refactor.

**For the §9 refactor, the precise acceptance test is now concrete:** JaC's leftmost
displayed cell on FLI lines must be vc120 (gbuf 0), not vc119 — i.e. the strip at
PNG x39–46 must go black, matching VICE, while colorfetchbug stays 48 and the
139-test suite is green.

### 0h continued (2) — PIXEL-LEVEL ROOT: gbuf/vbuf pipes pair OFF-BY-ONE (2026-06-24)

Added `vbufReg`/`cbufReg`/`dmli` to BOTH PX-LATCH traces (JaC VicDrawCycle.pxLatchTrace
+ VICE vicii-draw-cycle.c) and compared the gbuf SHIFT REGISTER pixel-by-pixel at
$ee rast $4d. Decisive:
- **The gbuf shift register is BYTE-IDENTICAL** JaC↔VICE: same sequence
  `$26,$4c,$98,$30,$60,$c0,$80,$0` (JaC ~6px ahead in cyc.pix, but identical values).
  ⇒ the g-access is NOT the bug.
- **Aligning on gbuf=`$98` (same bitmap cell, both dmli=3):**
  JaC `vbufReg=$ff cbufReg=$c → emitted $f` (gray); VICE `vbufReg=$0 cbufReg=$0 →
  emitted $0` (black). **Same gbuf cell, different vbuf.** JaC pairs the content
  gbuf with the PREVIOUS cell's `$ff` prefetch vbuf; VICE pairs it with the real
  `$0`. ⇒ JaC's **vbuf pipe is one cell BEHIND its gbuf pipe** vs VICE.

**Two targeted fixes implemented + TESTED + REVERTED (both wrong layer):**
1. `fliPrefetchBackfillDec` — decrement prefetchCycles when the col0 backfill
   double-writes (it skips one VICE per-cell pc decrement). This DID make the
   c-access VICE-faithful (col3 became real `$0` not `$ff` prefetch, prefetch extent
   3 not 4 — verified in EV-FetchC). BUT: (a) K3 PNG **unchanged** (0/8000 — the
   displayed cell reads a piped index the extent-fix doesn't touch), and (b) it
   **REGRESSED blackmail-ee by 160 cells** (blackmail is currently PERFECT vs
   real-HW). So the prefetch EXTENT is already correct as-is; changing it is a pure
   regression. REVERTED.
2. `fliLeadingGfetch` / `bmmVcExtraShift` (earlier) — also no-op / shifts whole
   bitmap. REVERTED.

**Conclusion (pixel-confirmed):** the K3 gray is NEITHER g-access NOR the c-access
prefetch-extent. It is the **relative PHASE between the gbuf pipe and the vbuf pipe**
— in JaC the vbuf the renderer pairs with a given gbuf cell is one cell stale
(the prefetch `$ff` instead of the real `$0`). The gbuf/vbuf load together at
i==xscroll (faithful), so the drift is UPSTREAM: the g-fetch index (`vVmli-1`) and
the self-incrementing display `dmli` (drives vbuf[dmli]) are not in the VICE phase
through the FLI prefetch. Fixing this is exactly the §9 vmli/dmli unification and is
**entangled with blackmail** (the extent is shared) — confirming it is NOT a
targeted patch. Trace tooling now in place (vbufReg in both PX-LATCH) makes the
acceptance test directly measurable: at gbuf=`$98`/dmli=3, JaC's vbufReg must become
`$0` (matching VICE) WITHOUT moving blackmail off 0.

### 0h continued (3) — TRUE ROOT: FLI line vc is +2 vs VICE (normal lines +1) (2026-06-24)

Final round of measurement + disproven knobs. The pipe is internally CONSISTENT;
the cells are at the wrong vc.

- `dmliFromGfetch` (drive display dmli = g-fetch index vVmli-1): **0/8000 no-op** →
  proves the vbuf read index ALREADY equals the g-fetch index. gbuf and vbuf are
  read at the SAME index. So the pairing logic is fine; the INDEX→vc mapping is off.
- Per-vc g-fetch DATA is identical JaC↔VICE (vc119=$13, vc120=0, …); JaC simply
  never fetches VICE's leading vc118. Per-cell vbuf prefetch: JaC prefetches
  vc119–122, VICE vc120–122. **JaC's whole FLI left edge is shifted one vc LEFT:
  JaC's leftmost displayed cell is vc119, VICE's is vc120.**
- Root: at $4d cyc15 JaC vc=120 / VICE vc=118 → **JaC vc is +2**; on a NORMAL
  badline ($33) JaC vc=1 / VICE vc=0 → **JaC vc is +1**. The standard +1 is
  compensated by `bmmVcFetchFix`=(vc-1); the EXTRA +1 on FLI late-badlines is
  uncompensated → leftmost cell lands on vc119 (content) instead of vc120,
  rendering FLI content where VICE shows the leading/background cell.
- `bmmVcExtraShift=1` (vc-2 globally) cancels the extra +1 but shifts EVERY BMM
  line (66% cells) — the extra +1 is FLI-late-badline-specific, not global.

**DEFINITIVE root cause:** JaC's vc/vcbase counter is one too high specifically on
the FLI late-badline (the same late-badline that makes colorfetchbug=48 and forces
the col0 backfill). It is NOT g-access, NOT prefetch-extent, NOT dmli, NOT the
pipe-pairing — all internally consistent. It is the **vc/vcbase phase on the late
badline**. The fix is to make JaC's vc on the FLI late-badline match VICE's (one
lower), which is the §9 vmli/vc/vcbase unification — and it MUST stay neutral on
normal lines (where the +1 is already compensated) and on blackmail (different
badline phase). This unifies the K3 left-edge with the colorfetchbug-48 residual:
they are the same FLI-late-badline vc-phase bug.

**Knobs disproven this session (all measured):** fliLeadingGfetch (no-op),
bmmVcExtraShift (shifts all), fliPrefetchBackfillDec ungated (regresses blackmail
-160), fliPrefetchBackfillDec gated pc>=3 (neutral but K3 no-op), vmliCol0Backfill
off (worse, 149), vmliUnified off (no change), dmliFromGfetch (no-op). 7 dead ends.
All reverted; only the vbufReg PX-LATCH trace addition kept.

### 0h continued (4) — EXACT PINPOINT: vcbase=119 (JaC) vs 120 (VICE) on the FLI late-badline

JaC EV-State at $ee $4d (clk 181095591+):
```
cyc12 vc=159 vmli=40 rc=2 vcbase=119   (end of $4c)
cyc13 vc=119 vmli=0  rc=2 vcbase=119   (vc reset to vcbase)
cyc14 vc=119 vmli=0  rc=2 vcbase=119 bad=0
cyc15 vc=120 vmli=0  rc=2 vcbase=119 bad=1   (badline rises LATE here)
cyc16 vc=121 vmli=0  ...
cyc17 vc=122 vmli=1
cyc18 vc=123 vmli=2
```
VICE vcbase reconstructed from FetchC (matrix vmli0 reads vc=vcbase): **VICE vcbase=120**.
JaC **vcbase=119** → one too LOW. So JaC's leftmost matrix cell = vc119, VICE's = vc120.

(VICE's own EV-State is raster-gated to $30–$40, so it doesn't cover $4d directly;
widen that window in vicii-cycle.c + rebuild to confirm vcbase=120 live. The FetchC
reconstruction already establishes it.)

Also note: JaC's `vc` increments every cycle from cyc14 while `vmli` (old counter)
lags to cyc17 — vc and vmli are decoupled through the late badline (vc = vcbase + ?,
vmli separate). The g-fetch/c-access use vVmli = vc-vcbase, so they ride the vc that
started one too low.

### 0h continued (7) — SOURCE vc++ fix regresses screenpos + colorfetchbug (the wall) (2026-06-25)

Took on the source fix: do the skipped cyc15 vc++ at its source (jac64.fliVcCyc15Inc)
so vc counts 40 consistently every frame (no scroll-toggle).
- STATIC: fully fixes $ee (132→7, vcbase matches VICE 80/120/160/200) — same as the
  capture-hack but at the source.
- STABLE: frame-to-frame same-scroll instability unchanged (BASE avg 3788 vs FIX
  3861, +2%) — does NOT introduce the cell-jump flicker the capture-hack did.
- SCROLL-GATE: 12425→11055 (cyc15-only) / →10457 (broadened to cyc15-54). Partial —
  but the gate metric over-counts (the deer has legit gray fur at x39); the gray
  bug colors specifically dropped (light-gray 149: 2074→1002).
- ❌ REGRESSES: **screenpos +3062, colorfetchbug bitmap/main/main2/3/4 +180/203/179/
  185/185.** The late-badline DETECTION (idle-true at the cyc15..54 vc++ check) also
  fires at the NORMAL top-of-screen display entry (screenpos's idle→display
  transition) and alters colorfetchbug's tuned FLI rendering (currently 48). So the
  vc++ change is NOT FLI-mover-specific.

⇒ DEFINITIVE WALL: the vc COUNT is coupled to the tuning of screenpos (the vc++
order is the documented screenpos fix), colorfetchbug (FLI prefetch, =48), and
blackmail (=0, real-HW). Every targeted vc fix breaks one of them OR the live
scroll. The two viable-looking fixes both fail:
  - capture-time vcbase +1: correct value, but toggles with scroll → live flicker.
  - source cyc15 vc++: consistent + stable, but regresses screenpos + colorfetchbug.
The K3 left-edge can only be fixed by the COORDINATED §9 refactor that re-derives
vc/vcbase/vmli/rc + the badline-idle timing to VICE's model and re-tunes
screenpos+colorfetchbug+blackmail+fetchsplit TOGETHER (so the bmmVc/vc++ compensations
become unnecessary). That is the multi-session refactor, not a flag. All experiments
reverted to default-off; gate (k3_scroll_gray.py) + lateBadlineThisLine tracking +
both flags (default-off) kept for that refactor.

### 0h continued (6) — scroll-sweep GATE built; -1 is a single PROPAGATING capture error (2026-06-25)

Built the dynamic gate the single-frame capture lacked:
`tools/vice-compare/k3_scroll_gray.py` — runs fpsCapture over the 180–183M sweep
(153 frames, scrolls $d4–$ef) and counts per-frame "leftmost-char gray" rows
(x∈[39,46]) + variance. **Baseline: total_gray_rows=12425, all 153 frames gray,
mean 81, max 156.** This is the regression gate (a good fix → ~0 across ALL frames,
low variance; the vcbase-hack's frame-to-frame toggle would show as high variance).

Using the gate, a SECOND targeted fix was tried + REVERTED:
- `fliCaccessLateShift` (shift ONLY the c-access read +1 on lateBadline lines, leave
  vcBase/vc propagation alone, to avoid the scroll-failure): **0/8000 no-op.** Why:
  the -1 vcbase error is captured ONCE (at the FLI-entry $43 rc==7) and PROPAGATES
  to every line below (each line's vc=vcBase at cyc13). The bulk "wrong colors" are
  propagated, not per-line-late — so a per-line c-access shift (which only fires on
  the few actually-late lines) can't touch them. The vcbase-hack changed 67% of
  cells precisely BECAUSE it propagated; the c-access shift doesn't, so it's inert.

⇒ Refined understanding: the fix MUST correct the vc COUNT at the source (the
skipped cyc15 vc++) so vcbase is captured correctly AND consistently every frame.
The vcbase-hack got the value right but toggled with scroll (the late-badline
DETECTION shifts as the mover's $D011 writes move the badline timing) → flicker.
The source cause is JaC's idle/badline clearing one cycle LATE vs VICE (so the
cyc15 vc++ — gated on prev-cycle idle — is skipped); VICE clears idle at cyc14.
This is the colorfetchbug §2 badline-timing root. Fixing it consistently (idle/
badline rises at VICE's cycle on the FLI $D011 trick) makes vc count 40 always,
vcbase correct always, no toggle — but it touches the load-bearing badline FSM, so
it must be gated against k3_scroll_gray (→0, low variance) AND the 139-test suite
AND a LIVE MCP scroll check. Left default-off; gate + lateBadlineThisLine tracking
kept for the next attempt.

### 0h continued (5) — vcbase fix WORKS STATICALLY, BREAKS LIVE SCROLL (2026-06-25)

Implemented `jac64.fliVcbaseLateComp`: detect the skipped vicCycle-15 vc++ on the
late badline (idle still true at the vc++ check) and add the missing increment at
the rc==7 vcbase capture. STATIC results (.capture/krestage3_crest.prg $ee frame):
- vcbase now matches VICE EXACTLY across the whole picture ($43–$b3, all OK; was
  -1 from $4b down).
- K3 gray strip 132 → 7 rows (residual: top 6 rows + 1).
- ZERO suite regressions (A/B ON-vs-OFF = 0 on blackmail-ee/fixed, all 5
  colorfetchbug, fldscroll, fetchsplit, screenpos, greydot, modesplit, colorsplit,
  vicii_reg_timing). 67% of K3 cells changed = the WHOLE picture's colors
  realigning to the correct vcbase (the "right pixels / wrong colors" the user saw
  across the deer, not just the strip).

**BUT — LIVE (MCP, full demo scrolling) REGRESSED: scroll failure + flicker, and
the leftmost gray PERSISTS.** Root of the regression: the capture-time +1 is NOT
address-neutral (vcbase sets the NEXT line's vc start), and worse, the late-badline
trigger (cyc15 vc++ skip) MOVES as the picture-mover scrolls (its per-line $D011
writes shift the badline timing), so the +1 toggles on/off frame-to-frame → the
picture jumps one cell per frame = scroll failure. REVERTED to default OFF (jar
rebuilt; baseline restored 0/8000).

⇒ The vcbase=119-vs-120 root is CONFIRMED, but a capture-time patch can't fix it.
The real fix must make the vc COUNTING correct CONSISTENTLY across all scroll
states — i.e. don't skip the cyc15 vc++ on the late badline in the first place
(so vc is right throughout the line AND the addresses follow), which is the §9
vc/vmli unification, validated DYNAMICALLY (live scroll must stay smooth) not just
on one captured frame. Add a deterministic multi-frame/scroll-sweep gate before
the next attempt — single-frame capture is insufficient (it passed; live failed).

**THE FIX (designed, for §9):** make JaC's vcbase capture on the FLI late-badline
match VICE (one higher, 120 not 119). vcbase is set in the update_rc/vicIdleState
path (rc==7 → vcbase=vc). On the FLI single-line char rows the late $D011 badline
makes JaC capture vcbase one cycle early/low. The fix MUST be neutral on normal
lines (vcbase already correct there) and on blackmail (different badline phase) —
gate on the late-badline condition and validate with: K3 PNG x39–46 → black,
colorfetchbug ≤ 48 (likely IMPROVES — same root), blackmail/fldscroll/fetchsplit
unchanged, 139-test green. This is the precise, measurable §9 target; it is a
vcbase-capture change, NOT any of the 7 knobs above.

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

## §REPRO-3 2026-06-25 — WORKING deterministic repro: BA-steal-timing root PROVEN

Rewrote test_fli_leftedge as POLLING (no IRQ thrash) — now runs identically in JaC
(detSysJump) and VICE (autostart): both execute the FLI loop ($08a4) 190×/frame.
The deterministic divergence:
- **VICE: 29 cyc/iteration, ba=0** — mid-line $D011 writes do NOT trigger a BA-steal;
  the loop never raster-locks.
- **JaC: drifts 31→ then LOCKS at 63 cyc/iter** — its $D011 writes DO trigger a
  BA-steal that paces the loop to one-per-line (bad=1 every line in EV-State).
⇒ ROOT (deterministic, finally): JaC and VICE disagree on whether a mid-line $D011
badline-trigger pulls BA low (steals CPU cycles). JaC steals/locks; VICE doesn't.
This is the CPU/BA-steal-timing root of the K3 picture-mover render difference — the
picture-mover's FLI relies on this exact timing, so the two emus' different steal
behavior → different render at the FLI left edge.

CONCRETE FIXABLE TARGET (deterministic): make JaC's BA-steal on a mid-line $D011
badline trigger match VICE — i.e. the FLI loop should run 29 cyc/iter (no lock) in
JaC too, OR (if real HW locks) confirm VICE is the one that's wrong. The repro
(test_fli_leftedge.prg) is the gate: JaC's per-iteration delta must match VICE's.
Relevant JaC code: setBaLowUntil / the BADLINE BA-low window (C64Screen, the
"BADLINE-*" setBaLowUntil calls + handleBadLineStart BA timing).

## §REPRO-4 2026-06-25 — root = CPU/BA SUB-CYCLE floor (VICE confirmed correct)

User confirms VICE is correct for the K3 demo. So JaC's behavior (the gray; locking
the FLI test where VICE doesn't) is the bug. Characterization of WHY:
- In the FLI test JaC self-LOCKS (BA-steal engages, ~34 BA-low cyc / 63-cyc line);
  VICE free-runs at 29 cyc/line (no steal, no lock).
- BUT JaC's BA-steal is CORRECT on a STABLE FLI: colorfetchbug renders ~1-4 cells vs
  VICE (near-perfect). If JaC's BA-steal were grossly wrong, colorfetchbug would
  diverge. It doesn't.
⇒ The test (and K3) diverge because they are TIMING-SENSITIVE: a SUB-CYCLE (≈1-cycle)
JaC-vs-VICE difference tips the marginally-stable FLI loop into a lock in JaC but not
VICE. On stable, non-marginal FLI (colorfetchbug) the same sub-cycle difference is
absorbed and the render matches. So the K3 left-edge is rooted in the **CPU/BA
sub-cycle timing floor** (project_cpu_subcycle_floor), NOT a gross BA-steal or VIC
render error — which is exactly why every deterministic-suite-validated fix attempt
this session was either a no-op or a regression: the suite CANNOT see it.

**Fix path (toward VICE):** the deep CPU sub-cycle (Phi1/Phi2) timing work. NOT a
targeted patch — the deterministic testprog suite is already 0 vs VICE on every
FLI/border/timing test, so it provides no gate for a sub-cycle fix. A future clean
gate would be a hand-written FLI tuned to lock in BOTH JaC and VICE (stable +
diverging) so only the render differs; test_fli_leftedge locks in JaC only and is the
starting point. Until the sub-cycle floor is addressed, the K3 left-edge is a known,
characterized, low-ROI residual.

## §REPRO-5 2026-06-25 — DEFINITIVE: JaC == VICE on the deterministic FLI (no fixable target)

Full per-iteration CPU-cycle diff of the FLI loop, JaC vs VICE: **ZERO divergence
across all 189 iterations (X=0..188, every delta identical).** JaC's CPU/BA/VIC
timing matches VICE exactly on this test — no timing bug.

The render "difference" (JaC green/brown stripes vs VICE pink) is NOT a bug:
- It's the FLI-bug cell color = the prefetch BUS BYTE = memory[frozen-PC-during-BA-low]
  & 0xf. That PC depends on the loop PHASE, which both emus vary frame-to-frame
  (VICE's PX-LATCH over a frame: 48024 cells, none non-gray in the sampled frame —
  its stripes are phase-dependent too). The captures caught different phases.
- The gray field is pure PALETTE (JaC color-12 RGB 108 vs VICE 148; same index).
- Counts matched exactly (gray 7836=7836, black 180=180, accent 1344=1344) = same
  data; only the phase-dependent stripe color differed.

⇒ Where it can be measured deterministically, JaC ALREADY equals VICE. The actual
K3 left-edge difference is below deterministic reproducibility: the closest
deterministic FLI (this test) shows JaC==VICE, and K3 itself renders
NON-DETERMINISTICALLY in VICE (VSP bug). There is no stable, verifiable target to
fix toward — "100% VICE" is undefined for a bug VICE doesn't render the same twice.
The K3 left-edge is a CLOSED investigation: characterized, real visually, but not a
fixable/verifiable defect with current tooling.
