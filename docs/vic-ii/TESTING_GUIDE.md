# JaC64 vs VICE testing guide

How to run irq-ack-vicii.prg (and similar VIC-II tests) on both emulators,
diff their behavior, and find the divergent instructions/cycles.

## Prerequisites

- VICE x64sc binary at `/Users/joakimeriksson/work/vice-emu/vice/src/x64sc`
- JaC64 built at `/Users/joakimeriksson/work/JaC64/build/libs/JaC64.jar`
- Test programs at `/Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg`
- VICE has trace patches in `6510dtvcore.c`, `viciisc/vicii-cycle.c`, `viciisc/vicii-mem.c`,
  `viciisc/vicii-irq.c` that emit `EV-*` events to `$JAC64_TRACE_FILE` and
  per-instruction PC trace to `$JAC64_PC_TRACE_FILE`. Patches archived in
  `tools/vice-trace-patches/`.

## Run JaC64 with the test

```sh
cd /Users/joakimeriksson/work/JaC64

./gradlew jar

# Basic run, dump screen RAM. Use the Gradle-built jar, not stale in-place
# classes from `java -cp .`.
java -Djac64.headless=true -Djac64.warp=true -Djac64.dumpScreen=true \
     -Djac64.dumpScreenFile=/tmp/jac64_result.txt \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg

# Inspect result
grep "row 0[0-5]" /tmp/jac64_result.txt
```

Expected good result (slots 0-5 all match VICE):
```
row 00: ... ***-**  ***-**  ******  aaaa..        (RASTER)
row 03: ... ***-**  ******  ******  dddd..  SS-COL
```

The old 47/48 symptom was row 03 LDA SS-COL with 5 d's (`ddddd.`) where VICE
has 4 (`dddd..`). If that returns, first verify the run is using
`build/libs/JaC64.jar`; stale `java -cp .` classes can reproduce old results.

## Closed path: load/autostart

Do not investigate load, autostart, boot phase, synthetic `SYS`,
`pauseAtCycle`, PRG-vs-D64 behavior, or disk timing for the
`irq-ack-vicii` failure. This path is ruled out.

Evidence from 2026-05-02:

- JaC64 direct PRG/headless/warp run produces row 03 `DDDDD.` and `$D020=$2`.
- JaC64 D64 `LOAD`/`RUN` path produces the same row 03 `DDDDD.` and `$D020=$2`.
- The bad screen cell comes from the running IRQ test window:
  `$0b5b: LDA $D019` reads `$f4`, then `$0b76` stores `$84` into row 03,
  column 29.

VICE `-autostart` remains useful as a way to launch the reference emulator.
It is not evidence that JaC64's load path is involved, and it is not a fix
direction for this bug.

## Run VICE with the test

```sh
# IMPORTANT: -autostartprgmode 1 enables Inject mode (= write PRG into memory).
# Without this, x64sc black-screens.
# This is only a VICE reference-run mechanism; do not treat autostart/load as
# a JaC64 root-cause candidate for this bug.
JAC64_TRACE_FILE=/tmp/vice_events.log \
JAC64_PC_TRACE_FILE=/tmp/vice_pc.log \
timeout 90 /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
   -warp -limitcycles 50000000 -autostartprgmode 1 \
   -autostart /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg \
   -exitscreenshot /tmp/vice_screen.png

# Inspect result via screenshot
# (read /tmp/vice_screen.png in a viewer or via Read tool)
```

VICE produces:
- `/tmp/vice_events.log` — EV-* event stream (~45MB)
- `/tmp/vice_pc.log` — per-instruction PC trace (~65MB, 2.4M lines)
- `/tmp/vice_screen.png` — final framebuffer (visual verification)

## JaC64 trace flags (cheat sheet)

| Flag | Effect |
|------|--------|
| `-Djac64.warp=true` | Run as fast as possible (always set for tests) |
| `-Djac64.dumpScreen=true -Djac64.dumpScreenFile=PATH` | Dump screen RAM to PATH at exit |
| `-Djac64.tracePcCycles=true` | Per-instruction PC trace (PC, op, cyc, clk) |
| `-Djac64.tracePcStart=N -Djac64.tracePcEnd=N` | Window for PC trace (cpu.cycles) |
| `-Djac64.tracePcFile=PATH` | PC trace destination (default stderr) |
| `-Djac64.traceIrqService=true` | Trace IRQ delivery (`EV-IrqService`) |
| `-Djac64.traceIrqServiceStart=N -Djac64.traceIrqServiceEnd=N` | IRQ trace window |
| `-Djac64.traceVicCycle=true` | Per-cycle VIC trace (TVIC, EV-Rd*, EV-Wr*, EV-LineInc) |
| `-Djac64.traceVicCycleStart=N -Djac64.traceVicCycleEnd=N` | VIC trace window |
| `-Djac64.traceVicCycleFile=PATH` | VIC trace destination |
| `-Djac64.injectAtCycle=N` | Legacy harness control; do not use as an irq-ack fix direction |
| `-Djac64.detSysJump=false` | Legacy harness control; do not use as an irq-ack fix direction |
| `-Djac64.baTrace=true -Djac64.baTraceFile=PATH` | BA-low events (very verbose, ~500MB) |

## VICE trace events

Both emulators emit the same EV-* event format for diffing:

| Event | Meaning |
|-------|---------|
| `EV-IrqService clk=N pc_pushed=$X irqClkStart=Y` | CPU enters doInterrupt for IRQ at clk N |
| `EV-RasterIrq clk=N rast=L cyc=C pc=$X` | VIC fires raster IRQ |
| `EV-SSCol clk=N rast=L cyc=C sprCol=B irqStat=S` | Sprite-sprite collision IRQ fires |
| `EV-RdD019 clk=N rast=L cyc=C ret=$V pc=$X` | CPU reads $D019 |
| `EV-WrD019 clk=N rast=L cyc=C ackVal=$V oldFlags=$O newFlags=$N` | CPU writes $D019 |
| `EV-RdD012 clk=N val=V line=L cyc=C pc=$X` | CPU reads $D012 (raster register) |
| `EV-LineInc clk=N from=A to=B [rastCyc=C / lastLine=N]` | Line transition (raster_line++) |
| `EV-BAlow / EV-BAhigh clk=N rast=L cyc=C sprdma=B` | BA-low events |
| `TVIC clk=N rast=L cyc=C bl=B baU=N vmli=V vc=V rc=R act=[...]` | Per-cycle JaC64 VIC summary |
| `PC=$X op=$O clk=N` (VICE) / `PC=$X op=$O cyc=C clk=N` (JaC64) | Per-instruction trace |

### Convention difference (CRITICAL)

VICE labels clk DURING the access (= maincpu_clk before next CLK_INC).
JaC64 labels cpu.cycles AFTER cycles++ (= post-increment for the access).

For the SAME PHYSICAL CYCLE, JaC64's reported value is +1 vs VICE's. This
shows up in `cyc-within-line` reporting:
- VICE EV-RdD019 cyc=43 = same physical cycle as JaC64 EV-RdD019 cyc=44.

When diffing, normalize by subtracting 1 from JaC64's cyc OR adding 1 to
VICE's. Or just compare ABSOLUTE cycle counts (`clk - line_start` vs
`cpu.cycles - lastLine` differ by 1 for same physical moment).

## Running both and diffing

### Step 1: Capture traces from both

```sh
# JaC64 with full PC trace (reasonable window — full run is ~20M cycles)
java -Djac64.warp=true -Djac64.tracePcCycles=true \
     -Djac64.tracePcStart=7800000 -Djac64.tracePcEnd=8000000 \
     -Djac64.tracePcFile=/tmp/jac64_pc.log \
     -Djac64.traceIrqService=true \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg

# VICE
JAC64_TRACE_FILE=/tmp/vice_events.log \
JAC64_PC_TRACE_FILE=/tmp/vice_pc.log \
timeout 90 /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
   -warp -limitcycles 50000000 -autostartprgmode 1 \
   -autostart .../irq-ack-vicii.prg
```

### Step 2: Find iter N's window

Find iter N's IrqService events to get clk windows:

```sh
# JaC64 IrqService
grep "EV-IrqService" /tmp/jac64_pc.log | head -20

# VICE IrqService
grep "EV-IrqService" /tmp/vice_events.log | head -20
```

### Step 3: Extract instruction sequences for window

```sh
# JaC64 chain for iter 2 (handler_2 IrqService → iter 3 handler_2 IrqService)
awk '$3+0 >= 7886470 && $3+0 <= 7906126' /tmp/jac64_pc.log > /tmp/jac64_iter2.log

# VICE same window (clk values)
awk -F'clk=' '$2+0 >= 4033894 && $2+0 <= 4053551' /tmp/vice_pc.log > /tmp/vice_iter2.log
```

### Step 4: Diff to find divergence

```sh
# Quick: count of (PC, cyc) pairs
diff <(awk '{print $1, $3}' /tmp/jac64_iter2.log | sort | uniq -c) \
     <(awk -F'clk=' '...' /tmp/vice_iter2.log | sort | uniq -c)

# Per-PC cycle totals
awk '{... sum cyc by PC ...}' /tmp/jac64_iter2.log
```

## Likely divergence candidates

### 1. IRQ delivery / running pipeline drift

The former 47/48 failure was in the already-running IRQ/VIC/CPU pipeline, not
in load or launch. If it regresses, compare from stable runtime anchors such
as `irq_handler`, `irq_handler_2`, and the `irq_ack_test4` `$0b5b: LDA $D019`
probes.

**Symptom:** Line-69 IRQ service timing and later handler return points drift
relative to VICE. That drift changes whether `$0b5b: LDA $D019` sees the
sprite-sprite collision IRQ bit on the final SS-COL slot.

**To verify:**
```sh
# Check IrqService trace
grep "EV-IrqService" /tmp/jac64_pc.log | head -2
grep "EV-IrqService" /tmp/vice_events.log | head -2
# Compute service - irq_clk for each
```

Relevant implementation areas:
- VICE `irq_delay_cycles` dispatch rules.
- BA-steal interaction in `maincpu_steal_cycles()`.
- CLI/SEI opcode-enable and opcode-disable handling.
- sprite DMA BA timing around line `$4a`.
- per-iteration drift between `irq_handler_2` returns and the next
  `irq_ack_test4` probe.

### 2. Sprite paint cycle (case 43 vs case 44 vs raster_cycle 45)

JaC64's `drawSpritesLegacy()` paints sprite-at-X=$F8 at JaC64 case 44 (=
xPos=264 entry). VICE paints at raster_cycle 45.

Check via the `sprColCanFire` capture vs sprColFirePending fire timing.

**To verify:**
```sh
# Look for SSCol fire events in both traces
grep "SSCol-fire\|EV-SSCol" /tmp/jac64_pc.log /tmp/vice_events.log
# Compare cyc-within-line (note JaC64 cyc N == VICE cyc N-1 for same physical moment)
```

### 3. Per-iteration cycle drift

VICE adds consistent +1 cycle per test iteration (= 19657 inter-iter delta).
JaC64 alternates 19656/19657/19658 (= irregular). Per-instruction cycles
match VICE (Phase 9.1 verified) — drift is from path differences:
- `lda ($fe),y` page-cross (rare).
- BA-low timing for sprite DMA (sprite Y/X evolves per iter).
- Handler_3 RTI return point ($ad2 vs $ad3 vs $ad4).

**To verify:**
```sh
# Inter-iter LDA $D019 deltas
grep "EV-RdD019" /tmp/jac64_pc.log | awk '/pc=\$b5e/' | awk '{print $2}' \
  | awk -F'=' '{
      if (prev) print "delta=" ($2 - prev);
      prev = $2
    }'
```

### 4. CPU/VIC ordering (Phi1/Phi2)

Now structurally correct (Phi2 hook implemented in commit 301a12d). All
Phase 10.E/F attempts to "fix" CPU/VIC ordering shuffle cells without
helping slot-5.

### 5. Raster IRQ trigger timing

Currently in `clock()` (Phi1). Cannot move to clockPhi2 without breaking
LDA RASTER row 00 (Phase 10.G demonstrated). Stay put.

## Reproduction recipe for slot-5 root cause analysis

```sh
# 1. Capture JaC64 PC trace for iter 2 chain
java -Djac64.warp=true -Djac64.tracePcCycles=true \
     -Djac64.tracePcStart=7886470 -Djac64.tracePcEnd=7906126 \
     -Djac64.tracePcFile=/tmp/jac64_iter2.log \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg

# 2. Capture VICE PC trace for matching window
JAC64_PC_TRACE_FILE=/tmp/vice_pc.log \
JAC64_TRACE_FILE=/tmp/vice_events.log \
timeout 90 /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
   -warp -limitcycles 50000000 -autostartprgmode 1 \
   -autostart .../irq-ack-vicii.prg

# 3. Extract iter 2 chain from VICE
awk -F'clk=' '$2+0 >= 4033894 && $2+0 <= 4053551' /tmp/vice_pc.log > /tmp/vice_iter2.log

# 4. Diff PC-by-PC cycle counts
diff <(awk '{gsub(/op=\$[0-9a-f]*/,""); print}' /tmp/vice_iter2.log | ...) \
     /tmp/jac64_iter2.log
```

The key diff is in the handler tail NOPs ($ad2, $ad3) and JMP loop $aae.

## Documents tracking the slot-5 hunt

- `IRQ_ARCHAEOLOGY.md` — historical context, pre-Phi2
- `IRQ_PHASE9A_MEASUREMENT.md` — frame period verification
- `IRQ_PHASE10A_LINE_PHASE.md` — line transition alignment fix (lineAlign)
- `IRQ_PHASE10C_PER_FRAME.md` — historical phase-shift attempt (rejected; do not repeat)
- `IRQ_PHASE10D_IRQ_SERVICE.md` — first identification of 2 near-canceling bugs
- `IRQ_PHASE10E_READ_TIMING.md` — read-old-clk attempt (failed)
- `IRQ_PHASE10F_VICE_CPU_VIC.md` — viceCpuVic attempt (failed)
- `IRQ_PHASE10G_PHI2_RESULT.md` — Phi2 architecture stabilizes the historical phase-shift experiment
- `IRQ_PHASE10H_VICE_DIRECT.md` — direct VICE comparison reveals per-iter cycle drift
- `IRQ_PHASE10I_INSTRUCTION_DIFF.md` — per-instruction cycles match for iter 1
- `IRQ_PHASE10J_SPRITE_PAINT_CYCLE.md` — sprite paint cycle alignment finding
- `IRQ_PHASE10K_DEFER_TRADEOFF.md` — 1-cycle defer fixes iter 2, breaks iter 3
- `IRQ_PHASE10L_FINAL_DIAGNOSIS.md` — JMP loop mod-3 root cause (current understanding)
- `CYCLE_MAPPING.md` — JaC64 case ↔ VICE raster_cycle mapping table

## Other test programs

```sh
# CIA timer (currently passing, baseline check)
java -Djac64.warp=true -Djac64.dumpScreen=true \
     -Djac64.dumpScreenFile=/tmp/cia.txt \
     -cp build/libs/JaC64.jar TestRaster \
     /Users/joakimeriksson/work/VICE-testprogs/CIA/cia-timer/cia-timer-newcias.prg

# Other interrupt tests (worth running for regression check)
ls /Users/joakimeriksson/work/VICE-testprogs/interrupts/cia-int/*.prg
ls /Users/joakimeriksson/work/VICE-testprogs/interrupts/irqnoack/*.prg

# Lorenz CPU tests (sprite-independent, run as regression for CPU work)
ls /Users/joakimeriksson/work/VICE-testprogs/general/Lorenz-2.15/

# Krestage 3 / FLI / sprite tests (visual)
# (need to set up appropriate .prg or .d64 files)
```
