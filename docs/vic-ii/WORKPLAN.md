# Cycle-accuracy debugging — way of working

A repeatable loop for tracking down JaC64-vs-VICE timing divergences
without ending up with hacks. Read this BEFORE patching the emulator
in response to a failing test or visual artifact.

## Core principle

> **Every emulation fix must cite the VICE source line that
> motivates it, AND a trace event where JaC64 was diverging from
> VICE at that exact point.**

A patch that makes a test pass without satisfying both is a
hack. The 2-stage SSCol fire defer (rejected 2026-04-26) is the
canonical cautionary example: it passed a test by shifting the
IRQ pipeline state, not by porting a real VICE behavior.

## The loop

```
   1. Pick smallest failing test
            ↓
   2. Capture sync trace (both emulators, same .prg, same flags)
            ↓
   3. Find first divergent EV-* event
            ↓
   4. Open VICE source at that event — what does it do?
            ↓
   5. Open JaC64 source at the same event — what does it do?
            ↓
   6. Patch JaC64 to match VICE (with source citation in comment)
            ↓
   7. Re-run trace — divergence gone? If yes, commit. If no, GOTO 3.
            ↓
   8. Run regression suite (other demos that previously worked)
```

## Step-by-step playbook

### 1. Pick smallest failing test

Prefer Lorenz/VIC-II micro-tests over full demos:
- One scene, one frame, deterministic.
- Documented PASS pattern (e.g., `DDDD..` for `irq-ack-vicii.prg`
  LDA SS-COL).
- Smaller cycle window to compare.

If a demo is artifact-y but no micro-test reproduces it, write a
minimal `.asm` repro first.

### 2. Capture sync trace

Both emulators emit `EV-*` events in identical format (timestamps
clock-relative). Trace patches live in:
- VICE: `viciisc/vicii-cycle.c`, `viciisc/vicii-mem.c`,
  `6510dtvcore.c` (per-PC). Build:
  `PATH="/opt/homebrew/opt/bison/bin:$PATH" make -j4` from
  `/Users/joakimeriksson/work/vice-emu/vice/`.
- JaC64: `-Djac64.traceVicCycle=true
  -Djac64.traceVicCycleFile=PATH`,
  optionally `-Djac64.tracePcCycles=true`.

Use `JAC64_TRACE_FILE` env var for VICE; same env var convention
in both for symmetry.

Window: trace 100K-200K cycles around the suspected event. Wider
windows make diffs unreadable; narrower windows miss context.

### 3. Find first divergent EV-* event

Diff procedure (manual today, scripted later — see Tooling backlog):
1. Pick an anchor event both emulators MUST agree on (e.g.,
   raster IRQ fire at line $4A, first BA-low transition).
2. From the anchor, walk forward event-by-event.
3. The first event with different fields (cyc, ret, sprdma, etc.)
   is the divergence point.

Common pitfall: comparing absolute clk values. Don't — VICE and
JaC64 boot at different clk because of different autostart
sequences. Use line-relative cyc.

### 4. Open VICE source at that event

VICE has well-isolated cycle handlers. Useful entry points:
- `viciisc/vicii-cycle.c` — the per-cycle dispatcher.
- `viciisc/vicii-mem.c` — register reads/writes.
- `viciisc/vicii-irq.c` — IRQ source set/clear.
- `viciisc/vicii-fetch.c` — c-/g-/sprite-fetches.
- `viciisc/vicii-draw-cycle.c` — pixel pipeline.
- `c64/c64cpusc.c` — CLK_INC, FETCH_OPCODE.
- `6510dtvcore.c` — opcode bodies.
- `mainc64cpu.c` — check_ba, IRQ delay.

Read the ENTIRE function the event lives in. The JaC64 mistake
mode is patching a single line based on a line-grep hit while
missing surrounding logic that re-orders the operation.

### 5. Open JaC64 source at the same event

Roughly:
- `C64Screen.java` `clock(long)` — per-cycle dispatcher (the
  case-0..62 switch). VIC register R/W in same file.
- `MOS6510Core.java`, `CPU.java` — CPU dispatch + memory bus.
- `MOS6510Core.java:130-160` — IRQ line / delay handling.
- Sprite pipeline — V2 path under `-Djac64.newSprites`.

Look for:
- **Cycle-numbering convention mismatches.** Some JaC64 code uses
  case-N = VICE-(N+1); other code uses case-N = VICE-N. The
  inconsistency is the bug — search for `case [0-9]` AND grep
  any neighboring source citation comments.
- **Order-of-operations.** VICE's `vicii_cycle` body has a
  specific sequence (Phi1 fetch → can_*_capture → draw → clear →
  fire → end-of-line/start-of-line → border → sprite_logic →
  graphics_logic → BA logic). JaC64's `clock()` should mirror.
- **Pipeline registers.** VICE has `xxx_pipe`, `reg11_delay`,
  `vbuf_pipe0_reg` etc. that latch values 1 cycle before use.
  Missing these in JaC64 = mid-line write timing bugs.

### 6. Patch JaC64 to match VICE

Required in the patch:
- A comment citing the VICE source: `// Port of
  viciisc/vicii-cycle.c:407-455 (...)`.
- If the patch is a compensation (not a 1:1 port — like the
  1-cycle SSCol fire defer), explain WHAT it compensates for and
  WHY 1:1 isn't possible right now.

Forbidden:
- Magic constants without source citation ("defer by 2 cycles"
  with no VICE line saying that).
- Patches that work by side-effect (like the rejected 2-stage
  defer altering IRQ pipeline state).
- Patches without a feature flag if the change has cross-demo
  blast radius. New behaviors that change ALL demos go behind
  `-Djac64.somethingNew=true` until proven safe.

### 7. Re-run trace — divergence gone?

If yes:
- Run the failing test screenshot harness.
- Verify the SAME cycle-relative event now matches VICE
  byte-for-byte.
- Commit.

If no:
- The first divergence may have been a SYMPTOM of an earlier
  divergence the trace window missed. Widen the window upward
  (lower cycle start) and find an EARLIER divergent event. Patch
  that first.
- Or the patch is wrong. Revert and re-read VICE.

### 8. Regression sweep

After every cycle-accuracy patch, re-run the canonical demo
corpus (see backlog). At minimum:
- Krestage 3 intro + scroll-in + beast scene.
- A multi-sprite demo (sprite collision).
- A FLI demo.
- Lorenz `irq-ack-vicii.prg` and any other Lorenz tests
  previously passing.

A patch that fixes test X but breaks demo Y is not yet shippable
— investigate Y before merging.

## Tooling backlog

Concrete tools that would shrink the loop. Build when the next
cycle-accuracy bug surfaces — premature scaffolding rots.

### `tools/cycle_diff.sh` (highest leverage)

Runs both emulators on a .prg, captures EV-* traces, normalizes
timestamps to line-relative, prints first N divergent events.
Inputs: `.prg`, anchor event regex, cycle window. Output:
side-by-side diff.

### EV-* event format spec

Single doc listing every event both emulators emit, with field
contract. Currently scattered between the patch sites. Without
this, schema drift is the first cause of false-positive diffs.

### `docs/vic-ii/TEST_CORPUS.md`

Table: test program → status (pass/fail/partial) → failing event
class (e.g., "SSCol slot $b5e returns $f4 where VICE returns
$70") → date last verified.
Update on every cycle-accuracy commit. Catches silent
regressions early.

### Trace patch sync

VICE patches live in our local fork
(`/Users/joakimeriksson/work/vice-emu/`). They're not version
controlled with JaC64. A `tools/apply_vice_patches.sh` that
applies the JaC64 trace patches against an upstream-fresh VICE
checkout would let us re-bisect VICE versions if needed.

## Anti-patterns to call out

These have all happened in this project:

- **"Test passes, ship it."** Always verify the underlying
  trace event matches VICE, not just the screenshot.
- **Cascading defers.** Adding a 2nd cycle of delay because the
  1st didn't fix it usually means the 1st was wrong, not too
  small. Revert and re-read VICE.
- **Patching the symptom file.** A bug in `C64Screen.java` is
  often actually in `MOS6510Core.java` (CPU/VIC ordering) or in
  the case dispatcher's drawSprites column-mapping. Read both
  sides before editing.
- **Multiple feature flags toggled in one trace.** Always isolate
  one variable at a time: `-Djac64.X=true` alone, then
  `-Djac64.X=true -Djac64.Y=true`. Don't compare runs that differ
  in 3+ flags.

## Quick reference — common commands

```bash
# Build VICE x64sc
PATH="/opt/homebrew/opt/bison/bin:$PATH" \
  make -j4 -C /Users/joakimeriksson/work/vice-emu/vice/

# Run VICE with trace
JAC64_TRACE_FILE=/tmp/vice.trace \
  /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
    -warp -limitcycles 8000000 -autostartprgmode 1 \
    -autostart /tmp/test.prg

# Build + run JaC64 with trace
./gradlew jar
java -Djac64.warp=true \
  -Djac64.traceVicCycle=true \
  -Djac64.traceVicCycleStart=N -Djac64.traceVicCycleEnd=M \
  -Djac64.traceVicCycleFile=/tmp/jac64.trace \
  -Djac64.testRasterTime=15 \
  -cp build/libs/JaC64.jar TestRaster /tmp/test.prg
```

## Updating this doc

When you discover a new way the loop broke down, add an
anti-pattern. When you build a new tool, move it from "backlog"
to "available" with usage. This doc is the playbook — keep it
sharp.
