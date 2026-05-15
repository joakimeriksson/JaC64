# VICE Pipeline — Next-Phases Plan (Phase 11→17)

## Goal

Close the +3099-cell suite delta between `-Djac64.viceFullPipeline=true`
and flag-off baseline. Stretch: pipeline output ≤ flag-off cells on
all 9 baseline tests, with the door open to dropping legacy paint.

## Current state recap (commit landed)

- `ViceDrawCycle.java` skeleton + faithful per-cycle port of
  `vicii-draw-cycle.c` (drawGraphics, drawSprites overlay,
  drawBorder8, drawColors8 6569+8565 with update_cregs).
- Dispatcher hand-off in `C64Screen.clock()` runs every VIC cycle.
- `-Djac64.viceFullPipeline=true` flag + configurable
  `-Djac64.viceShift=-8` paint base.
- Flag-off path: **byte-identical** to legacy across all 10 phases ✓
- Wins: ss-hires-color **PERFECT (0)**, rmwtest **-711**, ss-pri/ss-xpos
  significantly improved.
- Regressions: greydot, colorsplit, videomode1/2 (cycle-granular
  cregs commit vs legacy's retroactive paint sub-cycle compensation).

## Known facts from investigation

1. **VICE itself has cycle-granularity**: `vicii-mem.c:323` sets
   `vicii.last_color_reg` from CPU write — no sub-cycle position
   captured. `update_cregs` commits at start of `draw_colors8` of
   the SAME cycle.
2. **JaC64 CPU↔VIC clock**: CPU `performWrite` at clk-N → `schedule(cycles)`
   where `cycles` carries the post-write counter (CPU.java:381). So
   the Screen.clock running AFTER the write sees the new pendingFromCpu.
3. **Pipeline differs from legacy by 6081 px on greydot**; only 372 px
   where pipeline is closer to VICE ref than legacy. Pipeline output
   IS the problem, not legacy overlay.
4. **No suffix vs -8565 references**: VICE generates the default
   `.prg.png` from PAL 6569; `-8565.png` from PAL HMOS.

## Revised sequencing (after VICE source review)

**Key insight from VICE source review:**

1. `vicii.cycle_table[63]` is BUILT at init from chip-model
   descriptors (`vicii-chip-model.c:813`). It encodes 32-bit cycle
   flags including VISIBLE_M, FETCH_G, FETCH_C, sprite-DMA, border
   checks, UpdateVc/Rc. ~500 LOC.

2. `vicii.gbuf` is set in `vicii_fetch.c:229,265` during specific
   fetch cycles, consumed by `draw_graphics8` at the NEXT cycle.
   JaC64's inline `gbuf = memory[...]` at hand-off captures the
   CURRENT cycle's gByte — likely 1-cycle off VICE.

3. `vicii.last_color_reg` (set by CPU writes in `vicii-mem.c:323`)
   is CYCLE-granularity, NOT sub-cycle. Confirms that Phase 13
   "sub-cycle precision" misnomer — VICE's reference image is
   cycle-granular too, so my pipeline should already match VICE.
   The regression must be CYCLE-LEVEL drift, not sub-cycle.

**Sequence revision**: Move trace-parity to FIRST so the rest are
data-driven not speculative. Renumbered.

## Phase plan

### Phase A — Trace-event parity setup  (1d, low risk) ★ DO FIRST
(Was Phase 13 in original plan)

**Why first**: Without trace data, Phases B/C/D are speculative.
WITH trace data, the first divergent EV-* event is a CONCRETE bug
to fix. Avoids whack-a-mole sweeps.

**Steps**:
1. Verify VICE x64sc patches are in sync. Per
   `reference_vice_local_fork.md`, patched binary at
   `/Users/joakimeriksson/work/vice-emu/`. Test build:
   `cd ~/work/vice-emu && make` (~5 min).
2. In `ViceDrawCycle.java`, add trace-emission gated by
   `Boolean.getBoolean("jac64.viceDrawTrace")`. Events to emit:
   - `EV-DrawColors8` per cycle: cregs[$21], pixelBuffer[0..7],
     thisCycleLastReg, dbufOffset, vicCycle.
   - `EV-DrawGraphics8` per cycle: gbuf, vbufReg, cbufReg,
     vmode11Pipe, vmode16Pipe, xscrollPipe, renderBuffer[0..7].
3. Patch VICE x64sc with matching `printf` calls at the same lines
   (`vicii-draw-cycle.c:659,229`). Use the same event format as
   existing JaC64 trace patches.
4. Run BOTH on greydot.prg with `TRACE_VIC_CYCLE_START/END` bracketing
   ~10 cycles of visible-line activity. Output to files.
5. Use `tools/vice-compare/trace_diff.py` (or write a small alignment
   shim) to identify first divergent event.

**Acceptance**: List of 5-10 first divergent events catalogued with
cycle, field, jac64-value, vice-value. This document directly drives
the priority of Phases B-F.

**Rollback**: trace emission is gated by flag; no code change to
hot path. Safe by construction.

---

### Phase B — Drop legacy paint when pipeline is on  (~2h, low risk)
Tasks #48.

**Why**: Architectural cleanliness. Pipeline already overwrites the
legacy result; the legacy work is wasted CPU. Cleaner isolation
makes Phase 12+ bugs easier to chase.

**Steps**:
1. In `C64Screen.clock()` case dispatcher: gate `drawGraphics`,
   `drawBackground`, `drawSprites`, `finishCycleVice`,
   `applyD02xCurrentCycleColor` calls behind `if (!useViceFullPipeline)`.
2. Keep all *state* updates outside the guard (`vc++`, `vmli++`,
   `xPos += 8`, `mpos += 8`, badline data fetches, BA gating).
3. Run sweep. Expect zero pixel change (validation: the existing
   overlay is byte-identical to standalone-pipeline output).

**Acceptance**: Re-run 9-test sweep with -Djac64.viceFullPipeline=true.
Numbers should be **identical** to current Phase 10 baseline (6309).
A regression here = pipeline coverage gap → critical to fix BEFORE
moving on.

**Failure mode**: If pipeline doesn't fully cover visible pixels,
black holes appear in the output. Easy to spot. Fallback: restore
gating, narrow coverage gap to specific cycle range (e.g., add
back drawBackground for cycles 13-15 only).

**Rollback**: revert the gate. Pipeline path unchanged from
current state.

---

### Phase C — gbuf fetch-phase alignment  (~1d, medium risk)
Tasks #49.

**Why**: My pipeline computes `gbuf` inline at hand-off time using
JaC64's CURRENT-cycle `vc/rc/vicBase`. VICE's `vicii.gbuf` is set
INSIDE `vicii-fetch.c:229,265` during cycle-N's fetch_g, then
consumed in cycle-(N+1)'s `draw_graphics8` (via `gbuf_pipe0_reg`).
Likely 1-cycle phase off.

**VICE references**: `vicii-fetch.c:228-230`, `vicii-cycle.c:419-428`.

**Steps**:
1. Add a `viceGbufNext` field in C64Screen that's SET at the
   appropriate cycle (cycle (N) for column (N-16), matching VICE's
   g-access at Phi1(16+K)).
2. Replace the inline gbuf calc in the hand-off with `viceGbufNext`.
3. Verify badline-vs-idle distinction. VICE reads gbuf as 0 (idle
   pattern) when not in display state.

**Acceptance**: Per Phase A trace, gbuf values match VICE byte-by-byte
for badline rows. Suite sweep: greydot/colorsplit deltas reduce.

**Failure mode**: gbuf reads wrong byte → visible glitches in
display area. Easy to spot.

**Rollback**: Revert to inline fetch.

---

### Phase D — Mode-bit (regs[0x11], regs[0x16]) pipe phase  (~1d, medium risk)
Was Phase 9 attempt. Re-examine after Phase A trace.

**Why**: Mid-line $D011/$D016 writes (ECM/BMM/MCM toggles) drive
videomode/modesplit/colorsplit tests. Pipeline reads regs at
end-of-cycle but VICE's draw_graphics8 latches at pixels 4/6/7 of
SAME cycle as CPU write. Need to verify alignment.

**Steps**:
1. From Phase A trace: where do mode bits diverge?
2. If divergence is "I'm 1 cycle late" — pipe regs0x11/regs0x16
   through cycle_flags_pipe equivalent (read at start of drawCycle
   from a snapshot taken at end of previous cycle's CPU).
3. If divergence is "wrong pixel position within cycle" — verify
   pixel 4/6/7 latching ordering vs VICE source.

**Acceptance**: videomode1/2 and colorsplit deltas reduce per trace
findings. Specific target: videomode1 ≤ 50, videomode2 ≤ 100
(currently +226, +268 vs flag-off baseline 0, 8).

**Rollback**: revert pipe staging if trace shows it makes things
worse.

---

### Phase E — Border state machine full port  (~1d, low risk)
NEW task.

**Why**: My `drawBorder8` is a simplified port. VICE has multi-cycle
border state transitions for CSEL/RSEL edges (vicii-draw-cycle.c:540-580).

**Steps**:
1. Read full VICE drawBorder8 + the chip-model encoding of
   `vicii.main_border` transitions.
2. Replace my simplified `borderState` int with VICE's exact state
   machine. Currently I sync via setBorderState() but the transition
   logic is JaC64's simplified version.
3. Cover CSEL=0 pixel-7 split AND vborder transition cases.

**Acceptance**: ss-pri delta ≤ 0 (currently +18). No regression
on border-edge tests.

---

### Phase F1 — cycle_flags bake-in (static)  (~1d, low risk) ★ TRACTABLE
Split from original Phase 16.

**Why**: Full chip_model_init in VICE is 844 lines of C with
descriptor tables for 7 chip variants. Tractable shortcut: extract
the PAL 6569 `cycle_table[63]` values at VICE init time, bake into
Java as a static `int[]` constant.

**Steps**:
1. Add `printf` to VICE's `vicii_chip_model_init` to dump
   `cycle_table[0..62]` after init. Save to file.
2. Convert to Java `static final int[] PAL_6569_CYCLE_TABLE = {...};`
   in ViceDrawCycle. ~63 int values.
3. Use the mask constants (VISIBLE_M, FETCH_G, etc.) from
   vicii-chip-model.h — port the #defines as Java constants.
4. Replace `setCycleFlags(vicCycle 15..54 ? VIS_EN_M : 0)` call
   in C64Screen with
   `setCycleFlags(PAL_6569_CYCLE_TABLE[vicCycle])`.

**Acceptance**: cycle_flags values match VICE byte-for-byte per
Phase A trace. ss-pri/spritesplit edge cases improve.

**Failure mode**: Wrong bit positions → mass visual corruption.
Mitigation: gate behind `-Djac64.viceCycleTable=true` sub-flag,
default off until validated.

**Rollback**: Flip flag back to coarse VIS_EN_M gate.

---

### Phase F2 (stretch) — Full chip_model_init dynamic port  (~2d, high risk)
Optional after F1 lands.

**Why**: For runtime 6569↔8565↔6567 chip switching, need the full
`vicii_chip_model_init` machinery. Without F2, PAL 6569 only.

**Steps**: Port `vicii-chip-model.c` (lines 600-844) — descriptor
structs + builder. Tractable but tedious. Defer until F1 validated
and use-case for runtime switching is clear.

---

### Phase G — Replace legacy paint entirely  (~1-2d, low risk if A-F1 land)
Was queued part of Phase 11.

**Why**: Once pipeline is at-parity with VICE, legacy path
(drawGraphics/drawGraphicsVice/drawColorsVice + retroactive paint
helpers) is dead weight. Removing simplifies the codebase and
eliminates the dual-truth confusion.

**Steps**:
1. Make `-Djac64.viceFullPipeline=true` the default.
2. Delete drawGraphics, drawGraphicsVice, drawColorsVice,
   drawBorderVice, drawBackground, applyD02xCurrentCycleColor,
   applySpriteColorCurrentCycle, the cregs[] in C64Screen
   (already redundant with ViceDrawCycle.cregs[]).
3. Update tests + memory notes.

**Exit criteria**: ~1500-2000 LOC removed from C64Screen.java. All
tests pass at the new baseline.

## Validation rhythm

After EACH phase:
1. Compile clean.
2. Run 9-test sweep (`/tmp/sweep2.sh`), compare to previous totals.
3. Verify flag-off byte-identical with `diff` check on greydot frame.
4. Commit with co-authored line per project convention.
5. Update `project_vice_full_pipeline_phase1_5a.md` memory note.

## Effort estimate (revised, trace-first sequence)

| Phase | Days | Risk | Cumulative | Drop task # |
|---|---|---|---|---|
| **A** — Trace parity setup ★ | 1.0 | low | 1.0 | new |
| **B** — Drop legacy overlay | 0.25 | low | 1.25 | #48 |
| **C** — gbuf fetch phase | 1.0 | med | 2.25 | #49 |
| **D** — Mode-bit pipe | 1.0 | med | 3.25 | (from #46) |
| **E** — Border state full port | 1.0 | low | 4.25 | new |
| **F1** — cycle_flags bake-in ★ | 1.0 | low | 5.25 | new |
| **G** — Replace legacy entirely | 1.5 | low | 6.75 | #48 part 2 |
| F2 (stretch) — Full chip_model_init | 2.0 | high | 8.75 | — |

**Core total: ~6.75 days** (≈1.5 weeks calendar with overhead).
**With F2 stretch: ~8.75 days** (≈2 weeks).

Cleaning up the old task IDs:
- Drop #50 ("Phase 13: Sub-cycle precision") — VICE source review
  shows VICE itself is cycle-granular, so sub-cycle wasn't the gap.
- Renumber #48 → Phase B, #49 → Phase C, etc. (or keep numbers,
  just rename subjects).

## Test gating per phase

Each phase must satisfy these gates before merging:

| Gate | Mechanism |
|---|---|
| **Compile clean** | `javac -d /tmp/build -sourcepath .` |
| **Flag-off byte-identical** | `diff` greydot frame 5 vs reference |
| **9-test sweep regression** | `/tmp/sweep2.sh`; total cell-diff trend |
| **Trace alignment (post Phase A)** | First N events match VICE |
| **Memory note updated** | `project_vice_full_pipeline_phase1_5a.md` |
| **Commit landed** | Single-phase commit with co-author line |

## Stretch goals (post-phase-17)

- NTSC chip-model support (currently PAL only).
- 8565 color_latency=false default with grey-dot working correctly
  in greydot test.
- VSP attack / FLI bug edge cases.
- spritesplit/spritefetchbug tests improving from current baselines.
- VICE pixel-for-pixel match on the full PAL VICII testprogs suite
  (currently 9 tests sampled; the corpus has 80+).

## Decision points for user before kicking off

1. **Are intermediate regressions during Phase A-F1 acceptable?**
   You previously said yes ("ignore regressions before we are
   done"). Confirming for the new push.

2. **Default flag state when Phase G lands?** Switching the default
   from legacy → pipeline is a bigger semantic change than per-phase
   commits. Prefer to land it as its own atomic flip with its own
   test sweep. Want me to leave G off-by-default until you flip it
   manually?

3. **VICE x64sc trace patches in sync?** Per
   `reference_vice_local_fork.md`, patches archived at
   `tools/vice-trace-patches/`. Phase A re-applies if drifted. Should
   I `cd ~/work/vice-emu && git status` to check before Phase A starts?

4. **Stretch goal F2 (full chip_model_init)?** Adds NTSC/8565
   support but doubles risk. Default: defer to a separate session
   after F1 is stable. Want to include in this push?

5. **Scope of "fully VICE-like"?** Target options:
   - **(a)** 9-test sweep total ≤ flag-off (3210). Strict; requires
     all phases to convert.
   - **(b)** Each individual test ≤ flag-off for that test.
     Stricter; some tests like rmwtest already beat flag-off, but
     greydot might never if VICE's 8565 emission differs from
     legacy's bgColor-COLOR_DELAY semantics.
   - **(c)** Match VICE byte-for-byte on at least N tests
     (currently 1: ss-hires-color). Pure VICE-correctness metric;
     ignores tests where flag-off has its own JaC64-specific bugs.

   Default: (a). Confirm or pick another.

## Suggested kickoff order

1. Read this plan. Approve or amend.
2. Update task list: drop #50, rename #48/#49 to match Phase B/C.
3. Phase A first (1d, low risk, high information yield).
4. Re-evaluate sequence based on Phase A trace findings — phases
   B-F1 may reorder.
