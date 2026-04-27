# VICE x64sc trace patches

Patches that make VICE x64sc emit `EV-*` trace events compatible
with JaC64's `-Djac64.traceVicCycle` output. Used for cycle-by-cycle
comparison between the two emulators (see `docs/vic-ii/WORKPLAN.md`).

## What's here

- `vice_trace_patches.diff` — unified diff against an upstream VICE
  source tree. Touches `src/viciisc/vicii-cycle.c`, `vicii-mem.c`,
  and `src/6510dtvcore.c`.

## What the patches emit

Each event line is whitespace-separated `KEY=VALUE` pairs prefixed
with `EV-<name>`. Both emulators emit the same schema.

| Event | Source file | Triggered by |
|-------|-------------|--------------|
| `EV-SSCol` | vicii-cycle.c | sprite-sprite collision IRQ fire |
| `EV-BAlow` / `EV-BAhigh` | vicii-cycle.c | BA line transitions |
| `EV-RdD019` | vicii-mem.c | CPU read of `$D019` (incl. PC) |
| `EV-WrD019` | vicii-mem.c | CPU write of `$D019` |
| `EV-RdD01E` | vicii-mem.c | CPU read of `$D01E` |
| `PC=$xxx op=$xx clk=N` | 6510dtvcore.c | per-instruction (when `JAC64_PC_TRACE_FILE` set) |

All events go to the file pointed to by `$JAC64_TRACE_FILE`. The
per-PC stream uses `$JAC64_PC_TRACE_FILE` (separate file).

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
   git diff src/viciisc/vicii-cycle.c \
            src/viciisc/vicii-mem.c \
            src/6510dtvcore.c \
     > /Users/joakimeriksson/work/JaC64/tools/vice-trace-patches/vice_trace_patches.diff
   ```

## Why these live in the JaC64 repo, not VICE's

VICE upstream wouldn't accept these — they're debug-only
fprintfs gated on env vars. Keeping the diff in JaC64 means the
trace harness travels with the code that consumes it, and they
survive even if the local VICE fork is rebuilt or lost.
