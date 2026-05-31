# Cycle-stepped CPU/VIC refactor — REVISED plan

After re-reading VICE c64cpusc.c carefully (and on user
challenge: *"are you really sure that VICE do that?"*), the
earlier plan was based on a wrong reading of VICE.

## What VICE actually does

VICE's c64cpusc is **instruction-stepped, not cycle-stepped
inside an instruction**.

- `CLK_INC()` (defined ONLY in c64cpusc.c:47) is the only thing
  that calls `vicii_cycle()`. It bumps `maincpu_clk++` and runs
  one VIC cycle.
- `CLK_ADD(clock, amount)` (6510core.c:114 fallback) is just
  `clock += amount`. It does **not** call `vicii_cycle`.
- `CLK_INC()` appears **only inside `FETCH_OPCODE`**
  (c64cpusc.c:131-176). Every other macro (STORE_ABS,
  STORE_ABS_X, LOAD_IND_Y, DCP_IND_Y, DEC, INC, etc.) uses
  `CLK_ADD`.

Example — `DCP_IND_Y(addr)`:
```c
CLK_ADD(CLK, 2);          // clock += 2, VIC unchanged
LOAD_DUMMY(...);
CLK_ADD_DUMMY(CLK, 1);    // clock += 1, VIC unchanged
LOAD(tmp_addr);
CLK_ADD(CLK, 1);          // clock += 1, VIC unchanged
CLK_ADD_DUMMY(CLK, 1);    // clock += 1, VIC unchanged
DUMMY_STORE_ABS_RMW(...);
STORE_ABS(tmp_addr, tmp, 1);   // CLK_ADD(1) + STORE
```

So VIC is up to **6 cycles behind** clock during this
instruction body. VIC catches up at the NEXT instruction's
`FETCH_OPCODE` (which calls `CLK_INC` 2-3 times back-to-back).

## Implications for write order

For `STA $D016`:
1. FETCH_OPCODE: 3× CLK_INC → clock=3, vicii.raster_cycle=3
2. STORE_ABS: CLK_ADD(1); STORE → clock=4, raster=3 (lagging),
   register $D016 written at clock=4
3. Next FETCH_OPCODE: LOAD opcode (clock=4); CLK_INC → clock=5,
   raster=4 → vicii_cycle for raster=4 runs with $D016 already
   committed.

**Net result**: VIC's cycle-4 actions see the new register value
— exactly what JaC64 does today (`cycles++; performWrite;
schedule(cycles)`).

So **no write-order bug exists in JaC64**. The earlier theory
was wrong, and the schedule-before-store experiment correctly
broke Krestage 3 banner stripes (commit `fa9cafc` reverted).

## What's actually wrong then?

The remaining test ROM failures (`vicii_reg_timing` OPEN BORDER
WITH STA: 5 wrong positions, `irq-ack-vicii` SS-COL last 2 chars
`DDDDDD` vs ref `DDDD..`) point at **cycle dispatch
numbering**, not write order:

- JaC64 `case 0` does vbeam++ + SprDma0(3), VICE
  `vicii_cycle()` at raster_cycle=1 does
  `vicii_cycle_end_of_line` + `vicii_cycle_start_of_line` +
  raster_line++.
- JaC64 c-access at `case 16` for column 0; VICE c-access at
  `raster_cycle=15` Phi2 for column 0.
- 1-2 cycle drift in WHICH register reads/writes match WHICH
  VIC fetch.

Plus: **VIC register reads during instruction body** — in VICE,
a `LDA $D012` inside e.g. `LDA ($zp),Y` reads at clock=N when
vicii.raster_cycle=N-K (lagging by K). The returned $D012
reflects the OLDER VIC state. JaC64 schedules vicii_cycle on
every memory access, so JaC64 returns the CURRENT VIC state.
This is a real divergence but only matters for code reading
$D011/$D012/etc. mid-instruction, not for the demos in our
test set.

## Revised plan

### Phase 1: Cycle-table alignment (~1-2 days)

Match JaC64's `case N` to VICE's `raster_cycle = N+1` (or
N, TBD by careful comparison). For each VICE cycle, port the
exact action set from `vicii-chip-model.c:111` (`cycle_tab_pal`).

Tools:
- VICE trace: `x64sc -tracecpu -tracevicii` to capture
  per-cycle Phi1/Phi2 actions on a known input.
- JaC64 instrumented dispatcher: log `case N` actions and
  compare side-by-side.

Targets:
- `vicii_reg_timing` OPEN BORDER WITH STA: align cycle numbers
  so STA $D011 lands at the cycle where VIC commits the value.
- Krestage 3 banner: must NOT regress.

### Phase 2: Phi1/Phi2 split (~optional, only if needed)

If Phase 1 doesn't fix all remaining failures, split each cycle
into Phi1 (VIC fetch) and Phi2 (CPU memory access). This is the
only way to make $D018 mid-cycle write timing match VICE's
single-cycle resolution.

This is heavy work and may break demos. Only proceed if
Phase 1 alone leaves test failures.

### Phase 3: VIC register read freshness (~half day)

For maximum VICE compatibility on demos that read $D011/$D012
mid-instruction, **delay** the schedule() call on VIC register
reads to match VICE's lagging behavior. This is contrary to
intuition (we want fresh reads) but matches VICE.

Skip unless we hit a demo that needs it.

### Phase 4: Validation (~1 day)

- ✅ cia-timer-oldcias.prg (8/8)
- 🎯 irq-ack-vicii.prg RASTER (currently 4/4) + SS-COL (target 4/4)
- 🎯 vicii_reg_timing.prg (target all OPEN BORDER positions match)
- 🎯 fetchsplit.prg
- ✅ Krestage 3 banner stripes
- ✅ Krestage 3 FLI beast
- ✅ lets_scroll_it bitmap+text

### Phase 5: Cleanup (~half day)

Same as before — remove flag-gated experiments, document
decisions taken.

## Total: ~3 days (down from 5)

The write-order rework was unnecessary — that saves 1-2 days.
What remains is cycle-table alignment, which is the legitimate
divergence between JaC64 and VICE.

## Why the user's question mattered

Pushing back on *"is that what VICE do?"* prevented us from
applying a write-order rewrite that would've broken Krestage 3
to "fix" a non-issue. The DCP_IND_Y example (which the user
shared) is the clearest evidence: VICE bumps clock by 5 via
CLK_ADD without ever calling vicii_cycle, then does STORE_ABS
which adds one more CLK_ADD + STORE. VIC catches up later.

Lesson for next time: read the macros from leaf (CLK_ADD) up,
not from the top down. The single-line `CLK_ADD` definition
contradicts what STORE_ABS appears to do.
