# Phase J — Per-Cycle CPU Diff Infrastructure LANDED

## What's in place

### VICE side (in `/Users/joakimeriksson/work/vice-emu/`)
- `6510dtvcore.c` jac64_pc_trace block extended to emit:
  ```
  I=N PC=$xxxx op=$xx clk=NNN A=$xx X=$xx Y=$xx SP=$xx P=$xx
      rast=N cyc=N ba=N
  ```
  Gated by `CALLER == e_comp_space` so only main CPU emits (not drive).
- Accessor helpers added:
  - `vicii-irq.c`: `vicii_get_raster_line()`, `vicii_get_raster_cycle()`.
  - `c64cpusc.c`: `maincpu_ba_low_flags_get()`.
- VICE rebuilt: binary at `/Users/joakimeriksson/work/vice-emu/vice/src/x64sc`.

### JaC64 side
- `MOS6510Core.java`'s `TRACE_PC_CYCLES` block emits matching format
  with `jac64InstrCounter` (monotonic) + full CPU state.
- Compiles to `/tmp/jac64-build`.

### Diff tool
- `tools/vice-compare/cpu_diff.py` aligns by anchor PC, walks
  instruction-by-instruction, reports first N divergences.

## How to use

```bash
# Generate VICE trace
JAC64_PC_TRACE_FILE=/tmp/vice_pc.trace \
    /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
    -warp -limitcycles 4000000 \
    -autostartprgmode 1 -autostart /path/to/test.prg

# Generate JaC64 trace
java -Djac64.tracePcCycles=true -Djac64.tracePcFile=/tmp/jac64_pc.trace \
    -Djac64.tracePcStart=0 -Djac64.tracePcEnd=8000000 \
    -Djac64.warp=true -Djac64.framesToCapture=3 -Djac64.captureOnDone=true \
    -cp /tmp/jac64-build:/path/to/JaC64 TestRaster /path/to/test.prg

# Diff anchored at test-program PC (e.g., SYS entry $0815)
python3 tools/vice-compare/cpu_diff.py /tmp/vice_pc.trace /tmp/jac64_pc.trace \
    --ignore-op --alignment-pc 0x815 --max-divergences 20
```

## First validation findings on colorsplit

Anchored at PC=$0815 (SYS entry):

| Step | Instr | VICE state | JaC64 state | Diff |
|---|---|---|---|---|
| 0 | SEI | A=0 X=0 Y=0 SP=$f6 P=$20 rast=122 cyc=38 | (same) P=$24 cyc=38 | P (I flag inherited from BASIC autostart) |
| 1 | LDA #$35 | A=$00 cyc=40 | A=$35 cyc=40 | A (pre-state captured at different sub-point) |
| 4 | LDA abs,X | cyc=48 | cyc=47 | **1-cyc raster drift** (later found = trace bug) |

## Phase K iter #1 — trace bug fix

The "1-cyc raster drift" was traced to a bug in VICE's Phase J patch.
The original patch captured `jac64_pre_clk = CLK` BEFORE `FETCH_OPCODE`,
but read `vicii_get_raster_cycle()` AFTER `FETCH_OPCODE`. Since
FETCH_OPCODE runs 2–3 CLK_INCs (each of which bumps both maincpu_clk
and vicii.raster_cycle), the reported `cyc` was 2–3 ahead of `clk`.

Fix: capture `jac64_pre_rast` and `jac64_pre_cyc` BEFORE FETCH_OPCODE.
After the fix, per-instruction clk Δ and cyc Δ match exactly between
JaC64 and VICE for all 7 steps after SYS entry. (JaC64's side was
already correctly pre-fetch.)

Other observations:
- VICE is **non-deterministic at autostart** — across runs the PC=$0815
  entry happens at different `rast` values (90, 167, 280, …). The
  test program self-syncs via raster IRQ, so cell output is the same;
  but trace alignment by absolute clk/rast is unreliable for boot
  comparison.
- Remaining diff at $84c (program's idle JMP loop):
  - `P=$30` (VICE) vs `P=$20` (JaC64): VICE keeps B-flag in `reg_p`;
    JaC64 derives it. **Cosmetic** — B has no effect on execution
    outside BRK / PHP / PLP push semantics.
  - 4-cycle static `cyc` offset: artifact of VICE non-deterministic
    autostart, not a real drift.

## Caveat
JaC64's `op=$%x` trace reads `memory[prePC]` directly — for ROM
addresses, memory[] holds RAM values (0). Use `--ignore-op` flag.
This is cosmetic; PC sequence and A/X/Y/SP/P state are correct.

## Validation
- VICE binary builds clean with new format string (`strings | grep "I=%llu"` confirms).
- JaC64 compiles clean.
- Diff tool runs cleanly with synchronized traces.
- Concrete first divergence found: 1-cyc raster drift at PC=$081c.

## What enables Phase K
With this infrastructure, Phase K can:
1. Run diff for a test.
2. Find first concrete divergence (which CPU state field, which instruction).
3. Read VICE source for that opcode handler.
4. Compare to JaC64's `MOS6510Core.java` implementation.
5. Apply targeted fix.
6. Re-run diff. Find next divergence. Iterate.

Each iteration ~1-2 hours instead of hours of speculative changes.
Phase K is genuinely tractable now that Phase J is in place.
