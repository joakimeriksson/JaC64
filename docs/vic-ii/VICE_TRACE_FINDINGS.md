# VICE x64sc trace patch — comparison findings

## Setup

VICE patched at:
- `viciisc/vicii-cycle.c`: `EV-SSCol`, `EV-BAlow`, `EV-BAhigh` events
- `viciisc/vicii-mem.c`: `EV-RdD019`, `EV-WrD019`, `EV-RdD01E` events

Build: `PATH="/opt/homebrew/opt/bison/bin:$PATH" make -j4` from
`/Users/joakimeriksson/work/vice-emu/vice/`.

Run: `JAC64_TRACE_FILE=/tmp/vice.trace x64sc -warp -limitcycles N
-autostartprgmode 1 -autostart /tmp/irqack.prg`

ROMs needed at `~/.local/share/vice/C64/`.

## Findings on irq-ack-vicii.prg

### What matches between VICE and JaC64

| Event | VICE | JaC64 | Match |
|-------|------|-------|-------|
| SSCol fire cycle | line $4A cyc 45 | line $4A cyc 45 | ✅ |
| BA-low duration per sprite-line | 7 cycles (cyc 54 → cyc 61) | 7 cycles | ✅ |
| Sprite DMA cycle window | line $49 cyc 54-60, $4A cyc 54-60 | same | ✅ |

### What differs

**JaC64's test code runs ~30 cycles FASTER than VICE's.**

- VICE LDA $D019 (irq_ack_test4) lands at line $4A cyc 41-46 (uniform)
- JaC64 LDA $D019 lands at line $49 cyc 59 → $4A cyc 2 (irregular gaps)

Mapping iter → cycle:
| Iter ($fd) | VICE LDA cyc | JaC64 LDA cyc |
|-----------|-------------|--------------|
| 6 | $4A cyc 41 | $49 cyc 59 |
| 5 | $4A cyc 42 | $49 cyc 61 |
| 4 | $4A cyc 43 | $49 cyc -1 (= boundary) |
| 3 | $4A cyc 44 | $4A cyc 0 |
| 2 | $4A cyc 45 | $4A cyc 1 |
| 1 | $4A cyc 46 | $4A cyc 2 |

JaC64 LDAs are uniformly ~40 cycles earlier per frame.

### Implication

The dominant remaining bug is NOT in the VIC-II — it's in the
CPU's cycle accounting for some instruction in the IRQ handler
hot path.

The cycle-eating chain from `irq_handler_2` entry to LDA $D019:
1. `irq_handler_2` body (~80 cycles): waste loops, $D012 manipulation,
   $D01E read, vector patching
2. `firstdelay` jsr (delay table-driven ~50-60 cycles)
3. Test slot delay (~25-30 cycles)
4. `jmp ($c000)` indirect jump (5 cycles)
5. `irq_ack_test4`: `sei; lda $d019` (2 + 4 = 6 cycles)

If JaC64 is 30 cycles faster in this 200+-cycle chain, ONE of:
- An RMW instruction (DEC/INC/ASL/ROR) has wrong cycle count
- The `dex; bne` loop iterations are off
- The `jsr/rts` cycle count differs
- IRQ entry happens 30 cycles earlier (latency mismatch)

### Next concrete step

Add per-instruction cycle counters in BOTH emulators for the test
code's hot path. Diff per-PC cycle deltas. The PC where JaC64
falls behind VICE points to the wrong opcode timing.

## Tools available

- `/Users/joakimeriksson/work/vice-emu/vice/src/x64sc` — patched VICE
- JaC64's `-Djac64.traceVicCycle=true -Djac64.traceVicCycleFile=PATH`
- Both emit `EV-*` lines to a file via `JAC64_TRACE_FILE` env var.
- VICE: `EV-RdD019/WrD019/RdD01E/SSCol/BAlow/BAhigh` working.
- JaC64: `EV-RdD019/WrD019` (need WrD019 added; only RdD019 currently)
  + `act=[SSCol-fire-eoc]` per cycle + `act=[BA-SPR0/1]` per cycle.
