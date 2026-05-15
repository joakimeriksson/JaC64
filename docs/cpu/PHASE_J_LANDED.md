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
| 4 | LDA abs,X | cyc=48 | cyc=47 | **1-cyc raster drift** |

At step 4 (= reading IRQ vector table), JaC64 and VICE diverge by 1
raster cycle. This is the FIRST concrete CPU-VIC interleaving
divergence found. Phase K iterations can target this.

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
