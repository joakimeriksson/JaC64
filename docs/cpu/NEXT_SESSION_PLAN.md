# Next Session Plan — CPU Sub-Cycle Alignment (2-3 Weeks)

## Context
VIC pipeline is **complete and byte-perfect to VICE per cycle** (commit
`244bcb3` and follow-ups). The remaining cell-diff residual on
colorsplit (+1302), rmwtest (+533), and ss-* sprite tests reflects
**per-cycle CPU-VIC interleaving phase** that requires building diff
infrastructure first, then iterating fixes.

Previous session attempted single-shot fixes (Phases α-ι); only Phase
η produced a verified VICE-aligned change (deferred RMW dummy-write
raster IRQ by 1 cycle). Cell-diff metric didn't move because the
test program is steady-state synchronized to raster IRQ — single
1-cycle fixes don't cross the visible boundaries on captured frames.

## The blocker
The remaining drift can ONLY be found by **per-cycle diff** between
JaC64 and VICE running the same test program. Single-PC-trace
comparison is insufficient (PCs match; cycle counts per instruction
match in spot checks; drift accumulates across thousands of
instructions in a way that's invisible without instruction-by-
instruction sequence alignment).

## Phase J — Per-cycle diff infrastructure (1 day)

**Goal**: tool that aligns JaC64 and VICE traces by instruction
SEQUENCE NUMBER (not by clk), reports first divergent state.

**Steps**:

1. **Extend VICE trace** (`6510dtvcore.c` at `jac64_pc_trace` site,
   line ~1843):
   ```c
   fprintf(jac64_pc_trace,
       "I=%llu PC=$%x op=$%x clk=%llu A=$%x X=$%x Y=$%x SP=$%x P=$%x "
       "rast=%u cyc=%u ba=%u\n",
       (unsigned long long)instr_counter++,    // monotonic instr seq num
       jac64_pre_pc & 0xffff,
       lastop & 0xff,
       (unsigned long long)jac64_pre_clk,
       reg_a, reg_x, reg_y, reg_sp, reg_p,
       vicii.raster_line, vicii.raster_cycle,
       (maincpu_ba_low_flags != 0));
   ```
   Add file-static `instr_counter` initialized to 0.

2. **Extend JaC64 trace** (`MOS6510Core.java` PC trace at line ~1103):
   ```java
   tracePcOut.println("I=" + instrCounter++
       + " PC=$" + Integer.toHexString(prePC & 0xffff)
       + " op=$" + Integer.toHexString(memory[prePC & 0xffff] & 0xff)
       + " clk=" + (cycles - cyc) // start clk
       + " A=$" + Integer.toHexString(acc & 0xff)
       + " X=$" + Integer.toHexString(x & 0xff)
       + " Y=$" + Integer.toHexString(y & 0xff)
       + " SP=$" + Integer.toHexString(s & 0xff)
       + " P=$" + Integer.toHexString(getStatusByte() & 0xff)
       + " rast=" + vbeam
       + " cyc=" + (cycles - lastLine)
       + " ba=" + (cpu.baLowUntil > cycles ? 1 : 0));
   ```
   Add field `long instrCounter = 0`.

3. **Build alignment tool** (`tools/vice-compare/cpu_diff.py`):
   - Read both trace files.
   - Skip past boot phase (find first matching PC sequence post-autostart).
   - From that point, walk instruction-by-instruction.
   - For each I=N pair, check: do A/X/Y/SP/P match? Does cycle-count
     of this instruction match? Do raster_line/cycle match?
   - Report FIRST divergence with full context.

4. **Validate**: run on colorsplit. Verify the tool finds A=X=Y=SP=P
   sync (= same instruction stream) but drift in cycle-count or
   raster_cycle.

**Acceptance**: tool reports concrete first-divergent instruction
with both emulators' state side-by-side.

## Phase K — Iterative cycle-by-cycle fixes (1-2 weeks)

For each divergence found by Phase J:

1. Read VICE source for the relevant opcode handler.
2. Compare to JaC64's `MOS6510Core.java` implementation.
3. Identify exact cycle-count or phase difference.
4. Apply targeted fix.
5. Run `irq-ack-vicii` test — must remain 48/48.
6. Run Phase J diff again — find NEXT divergence.
7. Iterate until first divergence is far enough downstream that
   colorsplit cell-diff drops.

**Expected divergence candidates**:
- **CIA timer underflow clk**: VICE's CIA has subtle timing for
  TIMERA/B underflow vs JaC64's. Could shift IRQ assertion clk.
- **Specific opcode cycle**: e.g., RMW absolute INC (cycle 6 write
  timing), LDA $D012 (sub-cycle read of raster), branch-taken
  pipeline (cycle 3 vs 4 on page cross).
- **BA-low stall on read**: JaC64's `waitForBus` may stall reads at
  different cycle phase than VICE's `vicii_steal_cycles`.

**Validation per fix**:
```bash
# irq-ack-vicii must pass
java -cp ... TestRaster /path/to/irq-ack-vicii.prg
# Cell-diff trend (some tests may regress, some improve — push through
# per user direction)
/tmp/sweep_g.sh
```

## Phase L — Final validation (3 days)

After ~5-10 fix iterations in Phase K, run full VICE testprogs
suite (80+ tests) to check broad improvement + cross-test regression.

Adjust as needed.

**Exit criteria**:
- Pipeline cell-diff total ≤ legacy 3210 (= net improvement).
- irq-ack-vicii still 48/48.
- No catastrophic regressions in other test classes.

## Tools preserved from previous session
- VICE x64sc with EV-* trace patches at `/Users/joakimeriksson/work/vice-emu/`.
- JaC64 trace flags: `-Djac64.tracePcCycles=true -Djac64.tracePcFile=...`,
  `-Djac64.traceIrqService=true`, `-Djac64.traceVicCycle=true`, etc.
- Phase A-η findings documenting the path so far.
- `tools/vice-compare/png_cell_diff.py` for visual diff.
- `/tmp/sweep_g.sh` for 9-test sweep.

## Estimated effort
- Phase J: 1 day.
- Phase K: 5-10 iterations × 1-2 days = 1-2 weeks.
- Phase L: 3 days.
- **Total: 2-3 weeks of focused work**.

## Why this can't be compressed into a single chat session
Each Phase K iteration requires:
- ~30 min to run trace + run diff tool.
- ~1 hour to read VICE source + compare to JaC64 code.
- ~1 hour to apply + test fix.
- ~30 min to verify irq-ack-vicii.

Single-session attempts to skip the diff infrastructure (which
previous session tried in Phases ε/η/θ/ι) result in speculative
changes that don't visibly improve cell-diff — because the actual
divergence is in cycle-counting subtle that isn't visible without
the diff tool.

## Session deliverables expected
After Phase J + ~3 Phase K iterations (= 1 week):
- ≥1 visible cell-diff improvement on colorsplit/rmwtest.
- Diff infrastructure committed in `tools/vice-compare/cpu_diff.py`.
- 3-5 focused per-cycle CPU fixes in `MOS6510Core.java`.

After full Phase J+K+L (= 2-3 weeks):
- Pipeline cell-diff TOTAL ≤ legacy baseline.
- All findings documented for long-term maintainability.

## Honest disclaimer
This plan represents what's REALISTICALLY needed. The "deep
multi-week work" must follow this pattern:
1. Build infrastructure.
2. Iterate guided by diff data.
3. Validate broadly.

Skipping (1) and trying to fix (2) without data = speculative
changes that produce no visible improvement. Previous session
proved this empirically across Phases ε/η/θ/ι.
