# VICE x64sc trace patches

Patches that make VICE x64sc emit `EV-*` trace events compatible
with JaC64's `-Djac64.traceVicCycle` output. Used for cycle-by-cycle
comparison between the two emulators (see `docs/vic-ii/WORKPLAN.md`).

## What's here

- `vice_trace_patches.diff` — unified diff against upstream VICE
  source. Touches 11 files (mostly small `fprintf` blocks gated on
  env vars).

## Touched files

| File | What |
|---|---|
| `src/viciisc/vicii-cycle.c` | `EV-SSCol`, `EV-BAlow/high`, `EV-LineInc` events |
| `src/viciisc/vicii-mem.c` | `EV-RdD019`/`WrD019`/`RdD01E`/`WrD01B`/`WrD021`/`WrSprX`/`WrD01D` register-access events |
| `src/viciisc/vicii-draw-cycle.c` | `EV-DrawCycle` per-cycle render trace; `GREYDOT`, `VICE-DRAW-S0`, `SP-LATCH` events |
| `src/viciisc/vicii-irq.c` | `EV-RasterIrq` event |
| `src/viciisc/vicii-chip-model.c` | optional cycle-table dump |
| `src/6510dtvcore.c` | per-instruction PC + register trace (`JAC64_PC_TRACE_FILE`) |
| `src/c64/c64memsc.c` | RAM-init dump, `$01` ZP store trace, ZP read/write trap, generic write trap, mem-at-clk dump |
| `src/c64/c64mem.c` | non-SC variant (vestigial — x64sc uses `c64memsc.c`) |
| `src/c64/c64cpusc.c`, `src/mainc64cpu.c` | `memmap_mem_store` write trap |
| `src/6510core.c` | `INC zp` opcode trace (non-SC; x64sc uses dtvcore) |

## Env vars

| Var | Effect |
|---|---|
| `JAC64_TRACE_FILE=<path>` | Most `EV-*` events. |
| `JAC64_PC_TRACE_FILE=<path>` | Per-instruction PC stream. |
| `JAC64_TRACE_FILE_DRAW=<path>` | `EV-DrawCycle` (per-cycle render). |
| `JAC64_TRACE_DRAW_CLK_LO=N` / `_HI=N` | clk window for EV-DrawCycle (use to keep output small). |
| `JAC64_TRACE_FILE_GREYDOT=<path>` | grey-dot fire trace. |
| `JAC64_TRACE_FILE_SPLATCH=<path>` | per-pixel sprite-pipeline trace. |
| `JAC64_TRACE_FILE_PXLATCH=<path>` | per-pixel graphics-pipeline trace. |
| `JAC64_TRACE_VICE=1` | enable `VICE-DRAW-S0` sprite-0 sbuf trace. |
| `JAC64_TRACE_LINE_LO=N` / `_HI=N` | line gating for SP-LATCH / pxlatch / sprite traces. |
| `JAC64_TRAP_WRITE_ADR=0xNN` | log any store to that ZP / RAM addr. |
| `JAC64_TRAP_WRITE_CLK_LO=N` / `_HI=N` | clk window for write trap. |
| `JAC64_TRAP_READ_ADR=0xNN` | log any ZP read from that addr. |
| `JAC64_TRAP_READ_CLK_LO=N` / `_HI=N` | clk window for read trap. |
| `JAC64_TRAP_INC_ZP=1` | log every INC zp opcode dispatch. |
| `JAC64_DUMP_RAM=1` | dump initial RAM[$B400..$B43F] etc. at boot. |
| `JAC64_DUMP_AT_CLK=N` | one-shot RAM + pport.data dump at first ram_store ≥ clk N. |
| `JAC64_TRAP_PORT_WRITES=1` | log every ZP $00/$01 store. |

## Setup

Assumes a VICE source checkout at
`/Users/joakimeriksson/work/vice-emu/`. Adjust paths if your fork
lives elsewhere.

```bash
cd /Users/joakimeriksson/work/vice-emu/
git apply /Users/joakimeriksson/work/JaC64/tools/vice-trace-patches/vice_trace_patches.diff
PATH="/opt/homebrew/opt/bison/bin:$PATH" make -j4
```

ROMs needed at `~/.local/share/vice/C64/` (copy from
`vice/data/C64/`).

## Run with tracing

```bash
JAC64_TRACE_FILE=/tmp/vice.trace \
  /Users/joakimeriksson/work/vice-emu/vice/src/x64sc \
    -warp -limitcycles 8000000 \
    -autostartprgmode 1 -autostart /tmp/test.prg
```

`-autostartprgmode 1` = inject the .prg via memory rather than
disk emulation; required for the trace patches to capture events
from cycle 0.

## Re-syncing if VICE upstream changes

If you pull a new VICE version and the diff stops applying:

1. Re-apply manually using the existing patched source as
   reference. The patches are simple — each adds a small
   `fprintf` block guarded by a `getenv` check.
2. Regenerate the diff:
   ```bash
   cd /Users/joakimeriksson/work/vice-emu/vice/
   git diff src/6510core.c src/6510dtvcore.c src/c64/c64cpusc.c \
            src/c64/c64mem.c src/c64/c64memsc.c src/mainc64cpu.c \
            src/viciisc/vicii-chip-model.c src/viciisc/vicii-cycle.c \
            src/viciisc/vicii-draw-cycle.c src/viciisc/vicii-irq.c \
            src/viciisc/vicii-mem.c \
     > /Users/joakimeriksson/work/JaC64/tools/vice-trace-patches/vice_trace_patches.diff
   ```

## Why these live in the JaC64 repo, not VICE's

VICE upstream wouldn't accept these — they're debug-only
fprintfs gated on env vars. Keeping the diff in JaC64 means the
trace harness travels with the code that consumes it, and they
survive even if the local VICE fork is rebuilt or lost.
