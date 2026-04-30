# Phase 10.F: VICE CPU/VIC ordering attempt — REJECTED

## Hypothesis

Per Phase 10.E, JaC64's `fetchByte` runs `cycles++` BEFORE `schedule(cycles)`,
so VIC sees `cpu.cycles = N+1` when processing cycle N+1. VICE's CLK_INC runs
`maincpu_clk++` (= bump to N+1), then `vicii_cycle()` at the new clk. At a
LOAD, VICE reads with `maincpu_clk = N` (BEFORE CLK_INC) and runs vicii_cycle
AFTER. Pattern proposed:

```java
schedule(cycles + 1);          // chips.clock(N+1) — VIC at new cycle
int val = readMemoryAt(adr, cycles + 1);   // read at clk N+1
cycles++;                      // counter catches up
```

Gated behind `-Djac64.viceCpuVic=true`.

## Empirical result (REJECTED)

irq-ack-vicii.prg with `viceCpuVic=true`:

```
Baseline (47/48):
  row 00 INC RASTER: ***-**       (matches VICE ref)
  row 03 LDA SS-COL: ddddd.       (1 cell mismatch — slot 5 = the failing cell)

viceCpuVic=true (46/48):
  row 00 INC RASTER: **-***       (2 cells mismatch — dash at slot 3 instead of 4)
  row 03 LDA SS-COL: dddd..       (matches VICE ref — slot 5 fixed!)
```

**Net: 1 → 2 cells worse.** Same shuffling trap as Phase 10.C autostart shift
and Phase 10.E read-old-clk. Reverted; only the `readMemoryAt(adr, forCycles)`
helper remains as a behavior-neutral refactor of the inline read code.

## Why the targeted bug fixes but a NEW one appears

The proposed change makes `setIRQLow` (called from inside `chips.clock`) see
`cpu.cycles = N` instead of `N+1`, so `irqCycleStart = cycles + IRQ_DELAY`
becomes `N+2` instead of `N+3`. That moves IRQ service 1 cycle EARLIER. For
slot 5 LDA SS-COL — where JaC64 reads the SSCol flag 1 cycle "too late" —
this fixes the read by shifting handler entry 1 cycle earlier. But for INC
RASTER row 00, where the dash position depends on the ABSOLUTE cycle the INC
ACK lands at, the same earlier-handler-entry shifts the dash 1 slot left.

Same as autostart phase shift: a global 1-cycle phase change shuffles which
cells pass without netting a fix.

## What this rules in

The targeted bug (slot 5 LDA SS-COL) IS sensitive to a 1-cycle CPU/VIC
ordering shift — confirming the Phase 10.D conclusion that the failing cell
needs JaC64's IRQ service to land 1 cycle earlier (or the read at
$D019 to happen 1 cycle earlier). But applied uniformly, the shift breaks
INC RASTER row 00, so the fix has to be **differential** — only certain
events need the shift, not all of them.

## Phase 10.D's two bugs (still unresolved)

Recap from `IRQ_PHASE10D_IRQ_SERVICE.md`:

1. **Line 69 raster IRQ** services at `irq_clk + 4` in JaC64 vs `+ 2` in
   VICE. JaC64 is 2 cycles LATER.
2. **Handler chain** (line 69 IRQ service → line 70 IRQ service) is 62
   cycles in JaC64 vs 64 in VICE. JaC64 is 2 cycles SHORTER.

Net at handler_2's `lda $d012`: +2 - 2 + 1 = +1 cycle. The two bugs almost
cancel.

Any global phase shift moves both bugs in the same direction, which doesn't
fix the cancellation — it just exposes one bug or the other in different
cells.

## Path forward

The fixes need to be targeted:

- **Bug 1**: line-69 IRQ delivery is 2 cycles late. Investigate why JaC64's
  JMP-loop boundary lands 2 cycles later than VICE's at the same `irq_clk`.
  Per-instruction cycles match (Phase 9.1), so the boundary mod-3 alignment
  must come from earlier divergence — boot/autostart, KERNAL ROM flow,
  or a specific instruction's timing inside the test program's setup.
- **Bug 2**: handler chain is 2 cycles short. Suspect: `irqEnableDelayOps`
  after CLI (JaC64 sets to 1; VICE may effectively delay 2 cycles longer
  via `OPINFO_ENABLES_IRQ` interaction with the next instruction's IRQ
  service window).

Each must be diagnosed and fixed independently. Empirical-only chasing
(testing flag combinations and counting cells) doesn't make progress —
each fix must cite VICE source + a divergent EV-* trace event.
