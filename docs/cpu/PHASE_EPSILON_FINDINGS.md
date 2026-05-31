# CPU Refactor Phase ε — BA-Low Hypothesis Test

## Hypothesis
Phase γ identified that JaC64 fires raster IRQ at line 60 with
`cyc=27` vs VICE's `cyc=29`. Hypothesized BA-low stall duration during
badline at line 59 (YSCROLL=3 → badline) was 2 cycles short.

## Test
Swept `BA_BADLINE` constant in `VICConstants.java` across values
53, 54, 55, 56, 57, 58.

| BA_BADLINE | colorsplit cells | irq-ack-vicii |
|---|---|---|
| 53 | 2676 | PASS |
| 54 (default) | 2676 | PASS |
| 55 | 2676 | PASS |
| 56 | 2676 | PASS |
| 57 | 2676 | PASS |
| 58 | 2676 | PASS |

**Zero impact on output for any value.**

## What this tells us
The BA-low DURATION constant (when CPU resumes after badline)
doesn't drive the 2-cycle drift. Possible reasons:

1. **The handler's reads don't intersect with BA-low cycles.**
   colorsplit's IRQ handler at line 59 executes set_raster which is
   short. Most accesses might be writes (pass through BA-low) or hit
   non-BA-low cycles.

2. **The bug is in BA-low START timing, not end.** If JaC64 asserts
   BA-low at a slightly different cycle than VICE, that affects when
   the FIRST CPU stall happens.

3. **The bug isn't in BA-low at all.** It might be in:
   - IRQ exit timing (RTI cycle count).
   - The `lda $d012 / eor $d012 / beq` stable-raster trick at the end
     of irq_stable2 handler — exact cycle the EOR happens may differ.
   - CPU branch-cycle behavior for the BEQ.

## What didn't work
Phase α: trace parity setup. ✅ Showed equivalent IRQ-delay paths.
Phase β: DELAY_COUNTER flip. ✅ No regression but no improvement.
Phase γ: VIC raster IRQ trace. ✅ Identified 2-cycle drift at post-
   badline IRQs.
Phase ε: BA_BADLINE constant tweak. ❌ No effect.

## Conclusion
The 2-3 cycle raster-IRQ drift between JaC64 and VICE is NOT in:
- IRQ entry sequence cycle count.
- IRQ delay mechanism.
- BA-low end cycle (`BA_BADLINE` constant).

It IS likely in one of:
- IRQ exit (RTI cycle count, P/PC restore timing).
- The stable-raster trick's `eor $d012 / beq` cycle accounting.
- BA-low ASSERTION clk (when ba_low first becomes true).
- Sub-cycle CPU access phase (Phi1/Phi2 ordering).

## Reaching the goal
Closing the gap requires:
1. **Per-cycle CPU trace** comparison between JaC64 and VICE at the
   specific handler instructions. Show which instruction's cycle
   count differs.
2. **Trace EV-IrqService AND every PC** in a narrow window around
   line 59→60 transition. Diff exact clk for each instruction.
3. **Identify the divergent instruction** — likely one specific
   opcode taking different cycles in the two emulators under BA-low.

Estimated work: 2-3 more days. Multi-session.

## Status
Phase γ identified WHERE the drift is (post-badline handler).
Phase ε tested ONE hypothesis (BA_BADLINE) and it didn't fix it.

The actual fix requires deeper trace-level instruction comparison
in the handler region — work I'm pausing here rather than
speculating further.

## Session deliverables
- Plan + 4 findings docs committed.
- Trace infrastructure verified in both emulators.
- Clear evidence VIC pipeline is byte-perfect.
- Clear evidence CPU-side has 2-3 cyc drift at post-badline IRQ.
- Concrete next-session start: trace-level instruction comparison
  in colorsplit handler at line 59→60 transition.
