# irq-ack-vicii.prg — Phase 2 + 3: trace diff & root cause

Phase 2 captured the cycle-by-cycle EV-* trace for the two failing
slots (STA SS-COL slot 4, LDA SS-COL slot 5). Phase 3 walked the
first divergent event back to source. **Both failures point at the
same root cause** — a Phi1/Phi2 ordering bug for VIC-II IO writes
in JaC64.

## STA SS-COL slot 4 — full trace, side-by-side

VICE x64sc (PASS — col 4 = `-`):

```
EV-WrD019 clk=3739013 rast=$45 cyc=26 ackVal=$1 oldFlags=$81 ; handler entry
EV-WrD019 clk=3739065 rast=$46 cyc=15 ackVal=$1 oldFlags=$81 ; handler_2 entry
EV-RdD01E clk=3739168 rast=$47 cyc=55 ret=$0                ; lda $d01e
EV-BAlow  clk=3739293 rast=$49 cyc=54 sprdma=$3
EV-BAhigh clk=3739300 rast=$49 cyc=61 sprdma=$3
EV-SSCol  clk=3739347 rast=$4a cyc=45 sprCol=$3 irqStat=$84 ; SSCol FIRES
EV-WrD019 clk=3739348 rast=$4a cyc=46 ackVal=$5 oldFlags=$84 ; STA test → catches it
EV-BAlow  clk=3739356 rast=$4a cyc=54 sprdma=$3
EV-BAhigh clk=3739363 rast=$4a cyc=61 sprdma=$3
EV-WrD019 clk=3739376 rast=$4b cyc=11 ackVal=$4 oldFlags=$0  ; handler_3 ack (no-op)
EV-RdD01E clk=3739442 rast=$4c cyc=14 ret=$3
EV-WrD019 clk=3739454 rast=$4c cyc=26 ackVal=$5 oldFlags=$0  ; reset
```

JaC64 (FAIL — col 4 = `*`):

```
EV-WrD019 clk=7552280 rast=$45 cyc=28 ackVal=$1 oldFlags=$81 ; handler entry
EV-WrD019 clk=7552330 rast=$46 cyc=15 ackVal=$1 oldFlags=$81 ; handler_2 entry
EV-WrD019 clk=7552612 rast=$4a cyc=45 ackVal=$5 oldFlags=$0  ; STA test → MISSES
TVIC      clk=7552612 rast=$4a cyc=45 act=[SSCol-fire-deferred] ; SSCol fires same clk
EV-WrD019 clk=7552687 rast=$4b cyc=57 ackVal=$4 oldFlags=$84 ; handler_3 (REAL fire)
EV-WrD019 clk=7552717 rast=$4c cyc=24 ackVal=$5 oldFlags=$0  ; reset
```

**The first divergent event is the STA test write itself**:

| | VICE | JaC64 |
|---|------|-------|
| STA test cycle | $4a cyc=46 | $4a cyc=45 |
| oldFlags at STA write | $84 (SSCol set) | $0 (SSCol clear) |
| Net result | STA acks SSCol | STA misses → handler_3 fires |

In JaC64 the STA write event AND the SSCol-fire event share the same
clk (7552612, cyc=45). But the CPU's STA write reads `oldFlags=$0` —
meaning at the moment of write, the SSCol bit was NOT yet set.

In VICE the SSCol fire is one trace event earlier (clk=3739347, cyc=45)
than the STA write (clk=3739348, cyc=46). The STA reads `oldFlags=$84`
— the SSCol bit IS set when the write happens.

## Root cause — Phi1/Phi2 ordering for IO writes

VICE x64sc `c64/c64cpusc.c:47-50` `CLK_INC()`:

```c
#define CLK_INC()                                  \
    interrupt_delay();                             \
    maincpu_clk++;                                 \
    maincpu_ba_low_flags &= ~MAINCPU_BA_LOW_VICII; \
    maincpu_ba_low_flags |= vicii_cycle()
```

Order **per cycle**:

1. `interrupt_delay()` — IRQ pipeline check
2. `maincpu_clk++`
3. `vicii_cycle()` — **VIC's Phi1: c-/g-/sprite-fetch, draw, SSCol detection, IRQ source set**
4. (return from CLK_INC) — **CPU's Phi2: instruction work, including memory writes**

So at cycle N: VIC's Phi1 (including SSCol fire) runs FIRST, then CPU
writes during Phi2. A `sta $d019` at cycle N sees the irq_status that
Phi1 just produced.

JaC64 `com/dreamfabric/jac64/CPU.java:202-261` `writeByte`:

```java
protected final void writeByte(int adr, int data) {
    cycles++;
    ...
    if (VICE_MEM_BUS_SPLIT && !isIO) {
      // Phi1/Phi2 split: schedule VIC catch-up FIRST, then write.
      // ←  ONLY for non-IO!
      schedule(cycles);
    }

    if (isIO) {
      chips.performWrite(adr, data, cycles);   // ← write FIRST for IO
    } else {
      memory[windex = adr] = data;
    }

    if (VICE_MEM_MODEL && isIO) {
      schedule(cycles);   // ← VIC catches up AFTER write for IO
    } else if (VICE_MEM_MODEL && !VICE_MEM_BUS_SPLIT) {
      schedule(cycles);
    }
}
```

For IO writes, JaC64 does:

1. cycles++ (now N)
2. **chips.performWrite — CPU writes first**
3. schedule(N) — **VIC catches up after, runs case-N including SSCol fire**

So at JaC64 cycle N, CPU writes BEFORE VIC's Phi1 work. The STA
clears irqFlags first; THEN VIC fires SSCol, leaving bit 2 set
again.

Effectively, JaC64's IO write of `$D019` happens during what VICE
treats as cycle N-1's Phi2, not cycle N's Phi2 — one cycle early.

## Why the previous fix (1-cycle SSCol fire defer, commit `580fa6e`) didn't work

The defer pushed JaC64's SSCol fire from cyc=44 to cyc=45 to align
with VICE's cycle-numbering (case dispatcher off-by-one). It fixed
the ABSOLUTE cycle of the fire event, but NOT the relative ordering
of fire vs CPU write within cycle 45.

After the defer:
- SSCol fires at JaC64's "end of cyc=45" (deferred)
- CPU's STA at cyc=45 still happens BEFORE the deferred fire

So even though SSCol now fires at cyc=45 (matching VICE), it still
fires AFTER the CPU's same-cycle write — which is the wrong order.

## Why this affects only 2 cells out of 48

The bug only manifests when CPU's STA $D019 lands on the SAME cycle
that VIC fires SSCol. This happens for specific timing windows:

- STA SS-COL slot 4 ($fd=4): CPU's STA lands at $4a cyc=45 in JaC64,
  same cycle as the SSCol fire.
- LDA SS-COL slot 5 ($fd=5): CPU's LDA reads $D019 at a cycle where
  the read sees pre-VIC-cycle state vs post-VIC-cycle state.

Other slots have CPU access at different cycles (later), where
ordering doesn't change the visible state.

INC and ASL in SS-COL pass don't fail because the RMW dummy-write +
final-write straddle two cycles, masking the 1-cycle ordering bug.

## Why fixing this is non-trivial — Krestage 3 dependency

The existing comment in `CPU.java:202-241` notes a previous attempt:

```java
// IO writes ($D000-$DFFF): schedule AFTER the write, so the new VIC
//   register state is observed when VIC catches up (preserves the
//   side-border-open trick fixed in 4d05dc6).
//
// Tested against Krestage 3 scroll-in and did NOT fix the right-half
// color stripes — the artifact must have a different root cause. Keeping
// the option for future experimentation; default-off so we don't change
// any other demo's timing.
//   The conflict suggests JaC64's case dispatcher numbering is off by 1
//   vs VICE's PAL cycle table — fixing that root cause would let
//   schedule-before-store work for both VIC tests AND the banner.
//   Multi-day refactor.
```

So the simple flip (schedule BEFORE for IO writes) was tried and
broke Krestage 3 banner trick. Root cause: JaC64's case dispatcher
numbers cycles off-by-one vs VICE.

A proper fix needs ONE of:

A. **Fix the case dispatcher numbering** to match VICE's `raster_cycle`,
   THEN flip schedule order. Multi-day refactor.

B. **Per-cycle Phi1/Phi2 split** in the case dispatcher: each case
   does its Phi1 work (fetch, draw, SSCol detect) before returning,
   so when the CPU writes during the same JaC64-numbered cycle, VIC
   has already done its Phi1 for that cycle.

C. **Pre-schedule on $D019 writes only** (narrow targeted fix): hook
   the write path so $D019 specifically gets schedule-before-write.
   Risk: $D019-only fix doesn't generalize to other registers; might
   break or unmask other tests.

## Phase 4 plan

Try option C first (narrow $D019 hook). If it passes irq-ack-vicii
without breaking the regression suite (Krestage 3 + sprite collision
demos), commit. If it breaks something, fall back to option B
(Phi1/Phi2 split in the case dispatcher) — that's more work but
addresses the same architectural bug for ALL VIC registers.

We will NOT pursue option A in this session (multi-day refactor scope
not justified for a 2-cell fix). The goal is to make irq-ack-vicii
pass without regressions.

## Files involved (cited)

- VICE: `c64/c64cpusc.c:47-50` — CLK_INC ordering (interrupt_delay →
  clk++ → vicii_cycle).
- VICE: `mainc64cpu.c:271-296` — STORE/STORE_DUMMY macros (memory
  write goes via _mem_write_tab_ptr, no cycle advance — that happens
  via CLK_INC in 6510dtvcore.c).
- VICE: `viciisc/vicii-cycle.c:407-455` — vicii_cycle body, the
  capture-pre-draw / fire-post-draw pattern for SSCol.
- VICE: `viciisc/vicii-mem.c:228-257` — d019_store (also our trace
  patch).
- JaC64: `com/dreamfabric/jac64/CPU.java:202-261` — writeByte(),
  current schedule-after-write for IO.
- JaC64: `com/dreamfabric/jac64/C64Screen.java:1512-1535` — d019
  store handler in JaC64.
- JaC64: `com/dreamfabric/jac64/C64Screen.java` `clock(long)` — case
  dispatcher (the off-by-one numbering vs VICE).
