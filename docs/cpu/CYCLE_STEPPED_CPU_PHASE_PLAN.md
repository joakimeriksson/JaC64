# Cycle-stepped CPU / Phi1–Phi2 phase alignment — work plan

Status: **PLAN** (2026-06-25). Supersedes the "atomic CPU → multi-week rewrite"
framing in earlier K3 notes. The root of the K3 picture-mover left-edge gray
bug is now deterministically confirmed, and the architecture is far closer to
cycle-accurate than previously assumed.

---

## 0. The corrected architecture picture (READ FIRST)

JaC's 6510 is **not** atomic-per-instruction. Verified in
`MOS6510Core.emulateOp()` + `CPU.java`:

- `pc` is incremented **per memory access** inside the instruction:
  `int opcode = fetchByte(pc++)`, `int p1 = fetchByte(pc & 0xffff)`,
  `adr = (fetchByte((pc++)&0xffff) << 8) + p1`, etc.
- **Every** bus access (`fetchByte`, `loadByte`, `storeByte`) advances the
  clock one cycle and clocks the VIC: `cycles++; schedule(cycles);`
  (`CPU.fetchByte` lines ~206-225, the `VICE_MEM_MODEL` path).
- BA-low (badline) stall already exists: `CPU.waitForBus(true)` spins
  `while (baLowUntil > cycles) { cycles++; schedule(cycles); }`, clocking the
  VIC during the stall while the CPU is parked on a read.

So the VIC **does** see `pc` advance one access at a time, and it **does** run
during BA-low stalls. The machinery is there.

### What is actually wrong

The defect is the **sub-cycle phase** at which the VIC samples the CPU bus,
i.e. the Phi1 vs Phi2 ordering of three things within one cycle:

1. `pc++` (CPU advances its program counter)
2. `cycles++; schedule(cycles)` (VIC runs the cycle — incl. the FLI prefetch
   c-access that reads the CPU bus byte = `memory[pc]`)
3. the actual `readMemoryAt(adr, cycles)` (CPU's own bus read)

VICE separates each cycle into **Phi1** (VIC access) then **Phi2** (CPU
access), with `reg_pc` and the data bus holding a well-defined value across the
boundary. JaC interleaves (1)/(2)/(3) in an order that is correct for normal
rendering (proven by the whole regression suite) but is **off by one sub-cycle
phase** for the case where the VIC reads the *CPU's* bus — the FLI-bug prefetch
c-access during a late badline.

### Deterministic evidence (the gate)

Repro: `test_fli_leftedge.prg` (polling FLI, no IRQ, identical under detSysJump
and VICE autostart). K3 frame at `captureAtCycle` clk 181108997, raster `$4d`,
prefetch at PC region `$19c5..$19c8`.

| cycle | JaC pc sampled | JaC cbuf | VICE reg_pc | VICE cbuf |
|-------|----------------|----------|-------------|-----------|
| 15    | `$19c5`        | `$c`     | `$19c8` (frozen) | `$6` |
| 16    | `$19c8`        | `$c`     | `$19c8` (frozen) | `$6` |
| 17    | (advancing)    | `$6`     | `$19c8` (frozen) | `$6` |

VICE's `reg_pc` is **frozen** across the three prefetch cycles (CPU already
stalled on the badline at `$19c8`); JaC's `pc` is still **advancing** through
the FLI store sequence. The prefetch reads `memory[pc]`, so JaC paints `$c`
(→ gray via the MC gbuf/vbuf decode) where VICE paints `$6` (blue).

**Caveat already proven**: forcing the cbuf alone (`prefetchStartPc=false`,
freeze) turns ~40 of 132 rows blue but leaves ~86 gray, because the visible
gray is the **vbuf nibble `$f`** selected by the **gbuf** bit-pair, and the
gbuf (g-access) is sampled with the *same* phase error. So the fix must correct
the phase for **both** the c-access (cbuf) and g-access (gbuf) the VIC performs
against the CPU bus during the late badline — one root, two symptoms.

---

## 1. Goal & success criteria

**Goal**: align the CPU↔VIC sub-cycle phase so the VIC samples the CPU bus
(`pc` / data) at the VICE-equivalent Phi1/Phi2 point, making the FLI-bug
prefetch read the *frozen* stalled-fetch byte.

**Primary gate (deterministic, must pass)**
- `test_fli_leftedge.prg`: at raster `$4d`, the 3 prefetch cells' cbuf all read
  `$6` (frozen), matching VICE. (cycle-trace, no rendering needed)
- K3 / `crest.prg` at captureAtCycle 181108997: leftmost FLI column (x≈39)
  renders blue/black, gray-row count 132 → 0 via `k3_scroll_gray.py`.

**Regression gate (must not lose ground)**
- 16-test 8565 survey: total cell-diff vs REF must not increase. Current
  documented anchors that are at/near 0 and MUST stay there: fetchsplit (0),
  blackmail-ee/fixed (0), fldscroll-2A/2B (0), colorfetchbug family (48),
  den10-48-2, border-250/251/252 (0), screenpos, greydot, modesplit.
- Lorenz CPU suite: 231 passing tests must stay passing (CPU-timing risk).
- irq-ack-vicii.prg 48/48, cia-timer tests: the IRQ-delay-after-steal and
  `waitForBus` cycle-steal interact with this code — must stay green.

Every change is **flag-gated, default OFF**, until the regression gate is
clean, then flipped per the project's flag convention.

---

## 2. Why this is tractable (not a rewrite)

The cycle engine exists. We are **re-phasing**, not rebuilding:

- No new instruction-stepping state machine. `emulateOp()` stays.
- The change is localized to (a) the order of `pc++` vs `schedule()` vs the
  read inside `CPU.fetchByte`/`storeByte`, and (b) what value the VIC's
  prefetch c/g-access samples for "the CPU bus byte" during a BA-low badline.
- It is gated and measured against a deterministic single-frame repro, so we
  get a tight edit→trace loop (seconds), not a render-diff guessing game.

Risk is real but bounded: the same `fetchByte`/`waitForBus` path drives the
Lorenz CPU-timing and IRQ-ack results, so phase edits can regress those. That's
why every step has a CPU-timing checkpoint.

---

## 3. Phased plan

### Phase 0 — Instrumentation lock-in (½ day)
Make the gate cheap and repeatable before touching behavior.
- [ ] Commit `test_fli_leftedge.asm/.prg` and `k3_scroll_gray.py` (deterministic
      repro + metric) if not already in-tree.
- [ ] Add a single `cycle_trace.sh fli-leftedge --rast 4c-4e` recipe that emits,
      per cycle: `pc`, `instructionStartPC`, `baLowUntil`, the VIC's sampled
      prefetch byte, cbuf, gbuf, vbuf. Mirror the VICE side
      (`reg_pc`, `ram_base_phi2[reg_pc]`, c/g sampled byte).
- [ ] Capture the **baseline** JaC-vs-VICE per-cycle table for rast `$4c-$4e`
      and the 16-test survey totals. This is the before-picture every later
      step diffs against.

### Phase 1 — Characterize the phase, pick the model (1 day)
Decide the exact Phi1/Phi2 contract, on paper, against VICE source.
- [ ] In `viciisc/vicii-cycle.c` + `vicii-fetch.c`, pin down: at a late badline,
      which clock half does the c-access fire, and what address/data bus value
      it latches. Confirm it is `ram_base_phi2[reg_pc]` with `reg_pc` = the
      stalled instruction-fetch PC (frozen because BA gated the CPU off).
- [ ] In `maincpu.c`/`6510core.c`, confirm the rule that **stalls the CPU**
      (BA low for ≥3 cycles AND the next access is a read) and that on stall
      `reg_pc` does **not** advance.
- [ ] Map those two facts onto JaC's `cycles++/schedule/read` ordering and write
      down the **one** invariant we must enforce:
      *"When the VIC clocks a cycle in which it samples the CPU bus, the pc/data
      it sees must equal the pc/data the CPU will use on its next un-stalled
      access — i.e. the frozen stalled-fetch PC, not a mid-step pc."*

### Phase 2 — Freeze the stalled-fetch PC (core fix) (1–2 days)
The actual behavior change. Flag: `jac64.cpuBusPhaseFix` (default OFF).
- [ ] In `CPU.waitForBus(true)`: when a read stalls on BA-low, record the
      **stalled fetch address** (the `adr` the CPU is parked on) into a new
      field, e.g. `cpu.busFrozenPc`, and mark `busFrozen=true`. Clear it when
      the stall ends and the read completes.
- [ ] Thread `adr` into `waitForBus` (currently it only takes `isRead`) so the
      frozen value is the *actual* parked address, not a guess from `pc`.
- [ ] In `C64Screen.writeCAccess` (prefetch path) and the g-access path: when
      the VIC samples the CPU bus during a late badline, use
      `cpu.busFrozen ? cpu.busFrozenPc : cpu.pc` instead of
      `getInstructionStartPC()`/`pc`. This makes all prefetch cells read the
      single frozen byte → matches the VICE table in §0.
- [ ] **Critical**: this only works if JaC's CPU actually *stalls* at `$19c8`
      by cyc15 like VICE. From §0, JaC's pc is still at `$19c5` at cyc15 — i.e.
      JaC's CPU is ~1 instruction *behind* VICE entering the badline. So Phase 2
      likely needs Phase 3 to land first or jointly.

### Phase 3 — Align stall *onset* (the harder half) (2–4 days)
Why JaC reaches the badline one instruction behind VICE.
- [ ] Trace the FLI loop body (`lda d018tab,x / sta D018 / lda d011tab,x /
      sta D011 / 3×nop / inx / cpx / bne`) cycle-by-cycle in both emulators
      from the top of the line. Find where the ±1 instruction of skew is
      introduced — candidates: the badline BA-low onset cycle (`fliBaLowFix`
      window `vicCycle 11-54`), the write-vs-read stall asymmetry (writes don't
      stall, reads do), or the `$D011` write that *sets* the badline landing a
      half-cycle off.
- [ ] The likely lever: the cycle at which `baLowUntil` is armed relative to the
      CPU's store of `$D011`. If JaC arms BA-low one cycle late, the CPU sneaks
      one extra access in before stalling. Re-phase the badline arm point to
      match VICE's `check_badline` timing (which already lives in the shadow
      counters / `vicePreRcUpdateRc` work).
- [ ] Re-run the §0 table after each tweak; target: JaC `pc` frozen at `$19c8`
      across cyc15-17.

### Phase 4 — g-access (gbuf) phase (1–2 days)
Close the ~86 residual gray rows that cbuf alone can't.
- [ ] Apply the same frozen-bus rule to the **g-access** the VIC performs for
      the prefetch cell (the bitmap byte). Confirm against VICE whether the
      late-badline g-access reads `$3fff`/idle or the frozen CPU bus, and which
      gbuf bit-pair then selects vbuf `$f`.
- [ ] Verify the MC decode end-to-end: cbuf `$6` + correct gbuf → cell paints
      `D021` (blue) / vbuf, not gray.

### Phase 5 — Regression hardening & flag flip (1–2 days)
- [ ] Full 16-test + 41-test 8565 survey A/B (flag OFF vs ON). Net must be ≤ 0.
- [ ] Lorenz CPU Disk1/Disk2 re-run (legal+illegal, 231 tests).
- [ ] irq-ack-vicii 48/48, cia-timer-newcias, ackcia3.
- [ ] K3 full-demo visual spot-check at the known scenes (deer↔wolf seam,
      9th-sprite, beast). 
- [ ] If clean: flip `jac64.cpuBusPhaseFix` (and any Phase-3 sub-flag) default
      ON, document in MEMORY.md, commit with VICE source citations per WORKPLAN.

---

## 4. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Phase edit regresses Lorenz CPU-timing | CPU-timing checkpoint after Phases 2,3,5; keep flag OFF until green |
| `waitForBus` cycle-steal interacts with IRQ-delay-after-steal (`vicInterruptDelayAfterSteal`) | re-run irq-ack-vicii + ackcia3 each step; the frozen-pc field is read-only to that path |
| Fixing K3 regresses other FLI (blackmail/colorfetchbug) which have *their own* prefetch paths | those use `writeCAccess`/backfill too — A/B them explicitly; they're at 0/48 today |
| Phase 3 skew turns out to be deeper than badline-arm timing | fall back to Phase-2-only (cbuf) as a documented partial; record the residual |
| "Frozen pc" wrong for non-FLI badlines (normal text/bitmap) | gate the frozen-bus read to the *late-badline prefetch* path only (the `prefetchCycles>0` case), not all c-accesses |

---

## 5. Effort estimate

Realistic: **1.5–2.5 weeks** of focused work, not "multi-week rewrite."
- Phase 0–1: ~1.5 days (mostly tracing, no behavior change)
- Phase 2: 1–2 days
- Phase 3: 2–4 days (the genuine unknown — stall-onset skew)
- Phase 4: 1–2 days
- Phase 5: 1–2 days

The single biggest uncertainty is Phase 3 (why JaC enters the badline one
instruction behind VICE). If that resolves cleanly, the rest is mechanical and
well-gated. If Phase 3 is intractable, Phase 2 still lands a documented partial
(cbuf correct, ~40 rows fixed) behind a flag.

---

## 6b. PHASE 0 RESULTS (2026-06-25) — two structural corrections

Phase 0 ran against the deterministic K3 frame (`krestage3_crest.prg`,
`captureAtCycle=181108997`). Findings — **two of them change the plan**:

### Correction 1 — the synthetic repro is INVALID
`test_fli_leftedge.prg` **never triggers the prefetch path** (`prefetchCycles`
stays 0; trace shows only `EV-FetchG`, zero `src=PREFETCH`). It forces *normal*
badlines, not the *late* badlines that produce the FLI-bug leading-cell
prefetch. Its gray left edge is ordinary MC bitmap content (vbuf `$1a`/cbuf
`$0c`), not the K3 divergence. **Action: redesign the test so the `$D011`
YSCROLL write lands at cyc ~14–54 (late), or rely on the K3 frame as the gate.**
Until redesigned, the K3 deterministic frame is the only valid gate.

### Correction 2 — the prefetch cbuf is only ~27% of the bug
At x=39, the K3 frame has **146 non-black rows**. Freezing the prefetch PC
(`prefetchStartPc` true vs false) changes **only 40 of them**; **86 rows are
gray independent of the prefetch cbuf**, and the two sets **interleave
per-raster**. So:
- The c-access/cbuf fix (Phases 2–3) addresses ~40 rows.
- **The g-access/gbuf-vbuf path (Phase 4) is the MAJORITY (86 rows), not a
  cleanup.** Phase 4 must be treated as co-equal with Phases 2–3, and the 86
  rows' mechanism characterized before committing to a single fix.

### Confirmed (the c-access half)
Per-cycle, raster `$94` (representative; identical $94→$ef):
| col | cyc | prefCyc | `instructionStartPC` (default) cbyte | `cpu.pc` (pcfalse) cbyte | VICE (frozen reg_pc=$19c8) |
|-----|-----|---------|------|------|------|
| 0,1 | 15  | 3 | `$19c5` → `$c` (gray) | `$19c8` → `$6` (blue) | `$6` |
| 2,3 | 16/17 | 2/1 | `$19c8` → `$6` | `$19c9` → `$2` | `$6` |

CPU trace at the same clk: I=3 `STY $19c5` (cyc11-14), I=4 `STX $19c8` op=`$86`
at cyc15 with **ba=1**. VICE freezes `reg_pc=$19c8` across cyc15-17 (CPU halted
on the badline); **JaC's `cpu.pc` advances `$19c8`→`$19c9` during cyc15-17** —
the CPU is *not* frozen through the badline c-access window. Neither
`prefetchStartPc` mode matches VICE because both sample a *moving* pc; VICE
samples a *frozen* one. **The fix is to freeze the stalled-fetch PC for the
whole badline window (Phase 2/3), not to pick a better single pc source.**

### Revised effort note
Phase 4 is now the long pole (86/146 rows), not Phase 2. Recommend reordering:
characterize the 86-row mechanism (Phase 1+4 merged) *first*, since it dominates
and may share the same frozen-bus root as the 40-row c-access case.

---

## 6c. PHASE 1 RESULTS (2026-06-25) — the bug is DEER-specific gbuf decode, not "wrong prefetch"

Compared JaC vs VICE on the actual K3 picture-mover (`krestage3_crest.prg`).
Three findings that **further correct** the plan:

### Finding A — JaC's WOLF == VICE's WOLF (methodology validated)
The picture-mover slides two FLI pictures (a wolf and a deer). On the **wolf**:
- JaC (clk 189M) and VICE (clk 185M) BOTH use normal badline cadence (~every 8
  lines: `$33,$3b,$41,$4b,$53…`), near-zero prefetch, clean left edge.
- They agree. So the emulators are consistent on the wolf — the bug is **not**
  general FLI handling.

### Finding B — the bug is DEER-specific, and BOTH emulators prefetch there
On the **deer** (JaC's bug frame = the deterministic 181108997 capture):
- **VICE deer** (clk ~150M): **453 prefetch cells, 170 per-line FLI rasters**
  (`$4b,$4c,$4d,$4e,$4f,$50,$51…` consecutive). VICE *does* per-line FLI + the
  FLI-bug prefetch on the deer.
- **JaC deer**: also per-line FLI + prefetch.
- ⇒ The earlier "JaC wrongly enters the prefetch path" framing is **WRONG**.
  Both emulators prefetch on the deer; the prefetch itself is correct/expected.
  **The bug is in how JaC *renders/decodes* the prefetch cells**, not whether it
  prefetches.

### Finding C — the gray is the gbuf decode of `vbuf=$ff`
In MC bitmap mode the prefetch cell sets `vbuf=0xff` (VICE does this too —
`vicii_fetch_matrix` line 194, identical). The cell color is then chosen by the
**gbuf** (g-access bitmap byte) bit-pairs: `00`→D021, `01`→vbuf hi `$f`,
`10`→vbuf lo `$f`, `11`→cbuf. So wherever the prefetch cell's gbuf has a `01`/`10`
pair, the pixel = `$f` = **light gray** — that is the 86 cbuf-independent gray
rows. The 40 cbuf-driven rows are the `11`-pair pixels. **The fix must make the
prefetch cells' gbuf (g-access) match VICE** so the pairs land on `00`
(background) where VICE's do — i.e. this is fundamentally a **g-access / gbuf**
problem (Phase 4), with the c-access/cbuf (Phases 2-3) a minority.

### Blocker for the decisive measurement — frame alignment
The deer is **transient in JaC** (~1 frame-second, slides fast at ~181-182M) but
**stable in VICE** (~15 frame-seconds, 145-160M). The slide timing differs, so a
raw clk pick won't put both at the same scroll position. The decisive
gbuf-cyc15-17 comparison needs **scroll-aligned deer frames** via the
`cycle_align.py` fingerprint harness (YSCROLL/abl). That alignment is the
immediate next task before any code change.

### Revised plan ordering (supersedes §6b)
1. **Align** JaC-deer (181108997) ↔ VICE-deer (some clk in 145-160M) with
   `cycle_align.py`.
2. **Compare gbuf** at the prefetch cells (cyc15-17, cols 0-3) — find why JaC's
   g-access yields `01/10` pairs where VICE's yields `00`. This is the 86-row
   majority and the real root.
3. Then the cbuf/PC-freeze work (40 rows) as the secondary fix.
The CPU Phi1/Phi2 phase work (original Phases 2-3) is now demoted to the
*minority* fix; **the g-access/gbuf alignment is the primary target.**

---

## 6d. PHASE 1b RESULTS (2026-06-25) — root localized to bmmVcFetchFix, blocked on demo-sync

Direct JaC↔VICE g-access comparison on the deer (via `EV-FetchG`/`EV-FetchG-VICE`):

### The 86-row gray IS the BMM g-fetch address (`bmmVcFetchFix`)
At every leading cell, **vc/rc/d018 are IDENTICAL** between JaC and VICE, but the
fetch ADDRESS differs by exactly one cell (8 bytes):
- JaC col0: `bmmVc=(vc-1)` → addr `$63b8` → reads real bitmap `$d1` → MC pairs
  `01/10` → vbuf `$f` = **gray**.
- VICE vmli0: uses `vc` → eff `$63c0` → reads `0` → pairs `00` → **background**.

`C64Screen.java:~3969` applies `bmmVc=(vc-1)` (the `bmmVcFetchFix`, default true).
**Test: `-Djac64.bmmVcFetchFix=false` collapses the deer left edge 146→21 gray
rows (x=39 column 132→7).** So the -1 is the direct cause of the 86-row majority.

### But the -1 is REQUIRED by fetchsplit (static, valid comparison)
On fetchsplit (a STATIC test → frames align): JaC vc = VICE vc **+1** (vcdiff=1)
at every cell → the -1 is correct there (it undoes JaC's pre-`g-fetch` cyc15
vc++ at `C64Screen.java:1252`, which runs in `updateVicStateVic` before the
fetch). Removing it would re-break fetchsplit (185→132 regression per
[[project_fetchsplit_dd00_bank_split_2026_05_28]]).

### Why they differ — JaC's vcBase is one-low on the deer
- fetchsplit: JaC vcBase = VICE vcbase (0=0) → vc one-ahead → -1 right.
- deer: JaC vcBase=**119**, VICE vcbase=**120** → vc in-sync at fetch → -1 wrong.

So the deer's -1 over-correction is downstream of a **vcBase that is one-low**.
The existing FLI vcbase-compensation flags (`fliVcbaseLateComp`,
`fliVcCyc15Inc`) gate on `lateBadlineThisLine`, but **that flag is 0 on the
deer** (traced: idle=0, vc++ runs normally at cyc15) — so they don't fire. The
deer is a NORMAL badline by JaC's classification yet its vcBase is one low.

### BLOCKER — demo-phase desync (the deer is dynamic, not aligned)
The deer is a *sliding* picture. VICE HOLDS it static (146-158M identical
frames); JaC only ever shows it mid-SLIDE (~181-182M, frame-unstable; no static
hold period found). So JaC-deer and VICE-deer are at **different demo moments**,
and the vcBase=119-vs-120 may be a scroll/phase artifact rather than proof the
deer needs no -1. **Cannot confirm the fix without a scroll-aligned deer
reference** — and the slide-vs-hold mismatch may defeat even `cycle_align.py`.
This is the real wall now: JaC and VICE traverse the picture-mover with
different timing (JaC slides through where VICE holds), pointing at a higher-level
**scroll/slide-timing divergence** upstream of the per-cell vcBase question.

### Status / next options
1. Determine WHY JaC's vcBase is one-low on the deer at the *correct* aligned
   position — requires aligned EV-State traces (slide-vs-hold makes this hard).
2. Investigate the macro slide-timing desync (JaC slides through the deer while
   VICE holds it) — this is likely the same family as the deer↔wolf seam work
   ([[project_fldscroll_prefetch_pipeline_2026_05_31]]) and may be the true root.
3. Do NOT flip `bmmVcFetchFix` globally (regresses fetchsplit). A conditional
   needs a scroll-stable signal that distinguishes deer-FLI from fetchsplit-BMM;
   `lateBadlineThisLine`/`idle`/`badLine` all fail to (traced).

The g-access fetch (`bmmVcFetchFix`) is now the confirmed *locus*; the open
question is the correct vcBase, gated behind the demo-sync wall.

---

## 6. First concrete action

Phase 0: wire the `cycle_trace.sh fli-leftedge --rast 4c-4e` recipe and capture
the baseline per-cycle JaC↔VICE table + 16-test survey totals. Everything else
diffs against that. No behavior change in step 1 — measure, then move.
