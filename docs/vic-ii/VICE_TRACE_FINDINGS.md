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

## 2026-04-26 update — SSCol fire ordering audit

### What VICE actually does (verified)

`viciisc/vicii-cycle.c:407-455` (vicii_cycle):
1. `next_vicii_cycle()` increments `vicii.raster_cycle`.
2. Phi1 fetch.
3. `can_sprite_sprite = (vicii.sprite_sprite_collisions == 0)` — capture
   pre-draw "armed" state.
4. `vicii_draw_cycle()` paints pixels, may set `sprite_sprite_collisions`.
5. Clear-collisions (deferred from `$D01E` read).
6. `if (can_sprite_sprite && vicii.sprite_sprite_collisions)`
   → `vicii_irq_sscoll_set()` — set bit 2 in `irq_status`, raise IRQ.

CPU side (`6510dtvcore.c` and `c64/c64cpusc.c:47`):
- `LOAD; CLK_INC()` pattern. CLK_INC() is
  `interrupt_delay(); maincpu_clk++; vicii_cycle()`.
- LOAD reads memory at the PRESENT `maincpu_clk` BEFORE the next CLK_INC.
- The vicii_cycle for the CURRENT raster_cycle has already run (during
  the previous CLK_INC that brought us to this clk). The vicii_cycle for
  the NEXT raster_cycle has NOT yet run.

So when a CPU LOAD of `$D019` fires, the trace prints
`vicii.raster_cycle = N` where N is the cycle that JUST finished (whose
`vicii_cycle` work, including any SSCol fire, is already visible).

### What JaC64 does

`CPU.java:147-181 fetchByte`:
- `cycles++` (now N+1)
- `schedule(cycles)` runs `C64Screen.clock(N+1)`
- LOAD reads memory.

Identical ordering: LOAD at `cycles=N+1` reads state AFTER `clock(N+1)`.

### Where the 1-cycle drift comes from

JaC64 case dispatcher paints sprite-at-X=$F8 in case **44**.
VICE `vicii_draw_cycle` paints sprite-at-xpos=248 during raster_cycle **45**.
BA-low traces confirm cycle conventions otherwise match (cyc 54-60 for
sprite-0 DMA on the same physical line). So JaC64's collision detection
fires the IRQ one physical cycle earlier than VICE.

Mirroring VICE end-of-cycle pattern (capture `sprColCanFire` pre-draw,
fire post-draw) plus a **1-cycle defer** of the bit-2 set realigns
JaC64's SSCol fire absolute clock with VICE's:

| | JaC64 fire cyc | VICE fire cyc |
|---|---|---|
| no defer | 44 | — |
| 1-stage | 45 | **45** ✓ |
| 2-stage | 46 | — |

### Slot table (JaC64 with 1-stage defer vs VICE)

| Slot | VICE cyc/ret | JaC64 1-stage cyc/ret |
|------|--------------|-----------------------|
| 1 | 43 / $70 | 44 / $70 |
| 2 | 44 / $70 | 45 / $f4 |
| 3 | 45 / $f4 | 45 / $f4 |
| 4 | 46 / $f4 | 47 / $f4 |
| 5 | 47 / $f4 | 47 / $f4 |
| 6 | 48 / $f4 | 49 / $f4 |

Display: VICE = `DDDD..`, JaC64 = `DDDDD.`.

### Remaining drift to investigate (NOT to be patched as another defer)

JaC64's LDA cycles cluster (44, 45, 45, 47, 47, 49) instead of VICE's
uniform (43→48). The total slot span is 5 cycles in both, so total IRQ
handler latency matches; the IRREGULAR spacing means SOMETHING in the
test's per-slot delay path takes 0 or 2 cycles where it should take 1.

Likely candidates:
- Branch-taken-no-page-cross IRQ delay quirk
  (`OPCODE_DELAYS_INTERRUPT`) handled differently per slot.
- RMW-on-`$D019` dummy-write semantics (tests 2-3 for STA/INC/ASL).
- IRQ handler's `jmp ($c000)` indirect jump cycle count.

The 2-stage defer was rejected as a hack: it OVER-compensates for the
sprite-paint offset to the point where the IRQ pipeline state itself
shifts, masking the slot-spacing drift rather than fixing it.

## Tools available

- `/Users/joakimeriksson/work/vice-emu/vice/src/x64sc` — patched VICE
- JaC64's `-Djac64.traceVicCycle=true -Djac64.traceVicCycleFile=PATH`
- Both emit `EV-*` lines to a file via `JAC64_TRACE_FILE` env var.
- VICE: `EV-RdD019/WrD019/RdD01E/SSCol/BAlow/BAhigh` working.
- JaC64: `EV-RdD019/WrD019` (need WrD019 added; only RdD019 currently)
  + `act=[SSCol-fire-eoc]` per cycle + `act=[BA-SPR0/1]` per cycle.
