# Cycle-stepped CPU/VIC refactor — full plan

After today's careful comparison work, here's the concrete path to
make JaC64 cycle-exact like VICE viciisc. Multi-day work; this doc
captures everything we've learned so the next session can land it
cleanly.

## What we know works

1. **Bus-level cycle accuracy** — JaC64's `fetchByte`/`writeByte` each
   do `cycles++` + `schedule(cycles)`, so VIC sees individual cycles.
2. **CIA1+CIA2 timer accuracy** — `cia-timer-oldcias.prg` passes 8/8.
3. **IRQ-line latching** with branch-no-page-cross delay matches
   VICE's `interrupt_check_irq_delay` (`maincpu.c:484`).
4. **Sprite collision IRQ on 0→non-zero transition** matches
   `vicii-cycle.c:428`.

## What's wrong (root cause)

**JaC64 has TWO concurrent off-by-N misalignments:**

### (a) Write order

VICE's `STORE_ABS` (`6510core.c:651`):
```c
CLK_INC()                  // vicii_cycle with OLD reg
STORE(addr, value)         // write happens
```

JaC64's `writeByte` (current):
```java
cycles++
chips.performWrite(...)    // write happens
schedule(cycles)           // vicii_cycle with NEW reg
```

VICE: 1-cycle delay between write and VIC seeing it.
JaC64: 0-cycle delay (write same-cycle visible).

### (b) Case dispatcher cycle numbering

JaC64 case 0 vbeam++ + SprDma0(3) ≈ VICE cycle 1's actions.
But JaC64 case 16 c-access for col 0 ≠ VICE Phi2(15) c-access for col 0.

Anchor by sprite events: case N = cycle N+1.
Anchor by c-access: case 16 maps to VICE cycle 17 (= col 2 c-access in VICE).

**2-cycle offset in c-access vs sprite anchor.**

These two misalignments **cancel out** for the Krestage 3 banner DEC trick:
- (a) JaC64's write at cycle N visible to VIC at cycle N (vs VICE: cycle N+1)
- (b) JaC64's check_R fires at case-X (= "VICE cycle X+1") which is
  shifted from where VICE actually fires it
- Net: behavioural equivalence by coincidence

When we fix (a) only (schedule-before-store), the cancellation breaks
and banner regresses. Both must be fixed together.

## The refactor plan

### Phase 1: Cycle table redefinition (~1 day)

Replace JaC64's case-0 to case-62 dispatcher with VICE's `cycle_tab_pal`
(`vicii-chip-model.c:111`). Each cycle has explicit Phi1 and Phi2
actions. Cases 0-62 become cycles 1-63 inclusive.

For each VICE cycle action, port the corresponding JaC64 logic:
- SprPtr(N) / SprDma0(N) / SprDma1(N) / SprDma2(N) → existing sprite
  fetch code
- FetchC → existing fetchBadLineData
- FetchG → existing g-access (currently inside drawGraphics)
- ChkBrdL/R, ChkSprDma, etc. → existing border/sprite checks
- UpdateVc, UpdateRc, UpdateMcBase → existing counter updates
- Vis(N) → pixel emit positions

### Phase 2: Two-phase cycle (~2 days)

VICE has Phi1 and Phi2 within each cycle. JaC64's case is one phase.
Split each case into Phi1 and Phi2 sub-handlers. CPU activity
(reads/writes) interleaves with VIC's Phi1 fetches and Phi2 fetches
at the right phase.

### Phase 3: STORE/LOAD timing (~1 day)

With cycles properly numbered AND CPU operations placed at correct
Phi within cycle, swap `writeByte` to schedule-before-store. With both
fixes in place, VIC sees OLD reg at cycle N (during cycle-N's
actions), CPU writes at end of cycle, VIC sees NEW at cycle N+1.

This matches VICE STORE_ABS exactly.

### Phase 4: Validation pass (~1 day)

Re-run all test ROMs:
- ✅ cia-timer-oldcias.prg (8/8 should still pass)
- 🎯 irq-ack-vicii.prg RASTER + SS-COL (target 8/8, no entry-cycle dep)
- 🎯 vicii_reg_timing.prg (target all OPEN BORDER positions match ref)
- 🎯 fetchsplit.prg (target BBBB on first row)
- ✅ Krestage 3 banner stripes (must not regress)
- ✅ Krestage 3 FLI beast scene
- ✅ lets_scroll_it bitmap+text (must not regress)

### Phase 5: Cleanup (~1 day)

- Remove flags introduced as bandaids (jac64.viceMemBus,
  jac64.cAccessShift, jac64.dd00BankLatch)
- Remove rolling IRQ latch infrastructure (PHASE_A_IRQ_LATCH) since
  cycle-stepped CPU produces the right behaviour naturally
- Update CYCLE_ALIGNMENT.md / VICE_PORT_PLAN.md to reflect ship state

## Total: ~5 days focused work

Worth doing because afterwards JaC64 has a strong claim to "VICE
cycle-accurate" — every test ROM passes deterministically, demos
that work in VICE work identically in JaC64.

## Why we can't ship a partial fix

Tested today (commit fa9cafc): swapping `writeByte` to schedule-before-store ALONE
gets `vicii_reg_timing` STA right but breaks Krestage 3 banner. The
two misalignments cancel, so individually fixing one breaks demos.
Both Phase 1 (cycle numbering) AND Phase 3 (write order) must be
applied together.
