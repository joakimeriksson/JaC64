# Phase 10.E: VICE-pattern read timing fix attempt

## Hypothesis tested

JaC64's `fetchByte` does `cycles++` THEN read (= read at NEW clk after
chips.clock). VICE's `FETCH_OPCODE` and `LOAD()` macros read at CURRENT
clk BEFORE `CLK_INC` advances. This 1-cycle ordering difference is
exactly the slot-5 cyc=60 vs cyc=59 delta found in Phase 10.D.

## Fix tested: `-Djac64.vicReadOldClk=true`

Modified `fetchByte` to read at OLD clk first, then `cycles++` and
`schedule(cycles)`:

```java
protected final int fetchByte(int adr) {
    if (VICE_READ_OLD_CLK) {
        waitForBus(true);
        int val = doRead(adr);   // read at CURRENT cpu.cycles
        cycles++;
        sampleIrqLine();
        schedule(cycles);
        return val;
    }
    // ... legacy ...
}
```

## Result: WORSE — 1 cell fail → 2 cells fail

```
Without fix (baseline): 1 failed cell (slot 5 LDA SS-COL = ddddd. instead of dddd..)
With vicReadOldClk:    2 failed cells:
  - row 0 col 5 (STA RASTER slot 5: actual=`-` vs reference=`*`)
  - row 3 col 5 (STA SS-COL slot 5: actual=`-` vs reference=`*`)
```

The fix DID move slot 5 LDA SS-COL to passing (cyc-at-LDA matched
VICE's 59). But it BROKE slot 5 of STA tests in BOTH testsets because
the changed read timing destabilizes handler_2's `lda $d012; cmp $d012;
beq` idiom — JaC64's BEQ outcome shifts in different frames than
VICE's, causing handler_3 to NOT fire when it should.

## Why this is a non-fix (same trap as Phase 9.2 / autostart shift)

The slot-5 cell failure is a SYMPTOM of cycle-phase misalignment.
Shifting the read timing moves the cyc value at LDA $D012 (= matches
VICE) but propagates the shift into handler_2's BEQ outcome (= breaks
test cells where BEQ outcome matters).

For the fix to ACTUALLY work without regressing, JaC64's CPU must be
at the SAME cycle phase as VICE in ALL frames simultaneously, including:
1. `lda $d012` cyc value (= VICE 58/59).
2. BEQ outcome (= VICE pattern, 18/50 taken).
3. Handler chain entry NOP boundary (= VICE pushes $ad3 or $ad4 in matching frames).

All three conditions must hold simultaneously. Currently fixing one
breaks others.

## What this confirms

Per-instruction CPU cycles match (Phase 9.1). Frame periods match
(Phase 9.A). Line transitions match after lineAlign (Phase 10.B).
But CPU memory access PHASE within cycle differs: JaC64 reads at
NEW clk, VICE reads at OLD clk.

This is a fundamental CPU/VIC ordering difference. To fix without
regression requires:
1. ALSO changing `writeByte` to write at OLD clk (= remove the
   existing `vicMemBus`/`vicD019Phi2` carve-outs).
2. Restoring handler_2 BEQ stability through some other mechanism.

## Phase 10.F plan

If time permits, try the FULL VICE-pattern: read AND write at OLD clk
across all memory accesses. This is a much bigger change but matches
VICE's CLK_INC/access semantics precisely. Risk: many demos break.

If not, accept 47/48 as the achievable limit and document Phase 10.E
as confirming the EXACT cycle-accuracy bug source (= JaC64's read-at-
NEW-clk vs VICE's read-at-OLD-clk pattern). The 1-cycle delta has a
specific cause now, even though fixing it requires architectural
changes beyond a simple flag toggle.
