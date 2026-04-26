# JaC64 → VICE-architecture port plan

## Goal
Bring JaC64's CPU/VIC/CIA emulation architecture in line with VICE
viciisc so that tests like `irq-ack-vicii.prg`, `fetchsplit.prg`, and
`irqdelay-cia*.prg` pass deterministically. Side effects: Krestage 3
scroll-in renders correctly, banner stripes work without hacks, and
flicker disappears.

## Empirical evidence the architecture is wrong

Running `irq-ack-vicii.prg` twice with the same flags shows:
```
Run 1 row 0: | ******  ******  ******  AAAA..         |
Run 2 row 0: | ***-**  ***-**  ******  AAAA..  RASTER |
```

**Same input, different output.** This is a determinism failure that
no register-handler patch can fix. The architecture itself produces
slightly different cycle alignment per run.

## VICE's architecture (the target)

### 1. Cycle-stepping main CPU
- `maincpu_clk` advances by **exactly 1** per CPU cycle.
- Each instruction is broken into per-cycle steps.
- Memory access (read/write) happens at the EXACT cycle of that
  instruction step, not batched at end-of-instruction.
- IRQ check happens at instruction boundaries, but the IRQ delivery
  timing is itself cycle-precise via the alarm system.

### 2. Alarm-based event scheduling
- Future events (raster IRQ, CIA timer fire, sprite DMA, etc.)
  scheduled via `alarm_set(clk + delta)`.
- Each cycle: CPU calls `alarm_check(maincpu_clk)`. Any alarms whose
  time has passed fire their handler.
- IRQ line transitions happen WITHIN the cycle that fires the alarm,
  not at end-of-instruction.

### 3. VIC-II as cycle-table state machine
- `vicii-chip-model.c:cycle_tab_pal` enumerates Phi1/Phi2 actions for
  every cycle 1-63 of every line. Each action is one of: SprPtr(N),
  SprDma{0,1,2}(N), Refresh, FetchC, FetchG, Idle, plus per-cycle
  flags (BaFetch, BaSprX, ChkBrdL/R, UpdateVc, UpdateRc, etc.).
- `vicii_cycle()` runs once per CPU cycle, dispatches Phi1 and Phi2
  actions from this table.
- Pixel rendering pipeline is also per-cycle: `draw_graphics8()`
  emits 8 pixels with explicit per-pixel mode/color latching at
  pixels 4 and 6.

### 4. IRQ-source register handlers are TRIVIAL
```c
// d019_store: 4 lines
vicii.irq_status &= ~((value & 0xf) | 0x80);
vicii_irq_set_line();
```
All RMW dummy-write timing emerges from the cycle-stepped CPU calling
the store twice with old/new values at the correct cycles.

## JaC64's architecture (current, divergent)

### 1. Instruction-stepping CPU
- `cycles += instruction_cycles` after the WHOLE instruction.
- Memory accesses inside the instruction all share the same
  `cpu.cycles` count (which has already been updated to end-of-
  instruction by the time `chips.performWrite` runs).
- No fine-grained Phi1/Phi2 separation; everything in one CPU cycle
  is treated as atomic.

### 2. No alarm system
- Raster IRQ fires in `case 0` of the per-cycle case dispatcher.
- CIA timers tick on read/write of CIA regs and at periodic update
  points.
- No cycle-precise alarm queue. Events bunched into per-line case
  handlers.

### 3. VIC-II as case dispatcher (close to VICE but not identical)
- 63 cases (0-62) for the line. Each case runs once per CPU cycle.
- C-access for column K is at case 16+K (two cycles late vs VICE's
  Phi2(15+K)).
- Per-case work is well-defined but missing Phi1/Phi2 separation.

### 4. IRQ-source register handlers had ad-hoc workarounds
- $D019 had `isRmwDummyWrite()` special-case + `handleLateRasterIrq
  Acknowledge()` quirk.
- Decimal-instead-of-hex literals in sprite-collision IRQ raise
  (`irqFlags |= 82` instead of `0x02`).

## Concrete bug list (in order of impact)

| # | Bug | Location | VICE behaviour |
|---|---|---|---|
| 1 | Per-frame timing variance | CPU instruction-step model | Cycle-step model is deterministic |
| 2 | Decimal IRQ flag literals | C64Screen.java:1769,1774 | Use bit values 0x02/0x04 only |
| 3 | $D019 RMW special-cases | C64Screen.java:1357 | Trivial 4-line handler — RMW emerges from CPU |
| 4 | C-access cycle off by 2 | Cases 16+K | Phi2(15+K) — 1 cycle earlier on a Phi-aware model |
| 5 | $DD00 bank-switch timing | C64Screen.java:1474 | Phi1/Phi2 latch via gluelogic alarm |
| 6 | Sprite collision detection | C64Screen.java:3315ff (V2) | Per-cycle pixel rendering |
| 7 | No 2-cycle gbuf pipeline delay | drawGraphicsVice | Pipe0→Pipe1→Reg in draw_graphics8 |
| 8 | $D012 read accuracy | C64Screen.java:1004 | Reads vbeam at exact CPU cycle |

## Phase A REVISED: JaC64 already cycle-steps at bus level

After investigation, JaC64 is NOT pure instruction-stepping. Each
`fetchByte()` and `writeByte()` already does `cycles++` and
`schedule(cycles)` (CPU.java:140,151,177). For an N-cycle instruction
that does N memory accesses (most 6502 ops), VIC sees each cycle
individually.

**The actual non-determinism source identified empirically**:

WITHIN a single JVM run, irq-ack-vicii.prg captures across 10 frames
ALL show identical row 0:
```
0: | ***-**  ***-**  ******  AAAA..         |
0: | ***-**  ***-**  ******  AAAA..         |
... (all 10 identical)
```

BETWEEN JVM runs (separate process invocations), output varies:
```
Run 1 row 0: ******  ******  ******  AAAA..        (FAIL)
Run 2 row 0: ***-**  ***-**  ******  AAAA..  RASTER (PASS)
```

Same input PRG, same flags, different output across processes.

The variance traces to **Thread.sleep-based autostart timing** in
JaC64.java:268,299,325 and TestRaster.java:379,395,425:

```java
while (!scr.ready()) { Thread.sleep(100); }     // race
reader.readPGM(name, -1);                        // inject AT some cycle
Thread.sleep(10);  ... Thread.sleep(3000);       // more races
cpu.enterText("RUN~");                           // injects at non-det cycle
```

Each Thread.sleep(N) yields for ≥N ms, returning at OS scheduler's
discretion. Different runs return after different real-time delays
which translate to different EMULATED cycle counts when injection
happens. The PRG starts running at varying cpu.cycles values across
runs, and the test's IRQ-ack chain has just enough sensitivity to
cycle-zero alignment that the result varies.

### Phase A REVISED — Deterministic injection harness (small, doable)

Replace Thread.sleep-based polling with a cycle-precise injection:

1. Add `cpu.runUntilCycle(long target)` — busy-wait or pause until
   `cpu.cycles == target`.
2. In TestRaster, after `initEmulator()`:
   - `runUntilCycle(2_000_000)` (~2 emulated seconds — kernal idle)
   - `reader.readPGM(path, -1)`
   - Detect SYS address from BASIC stub
   - `cpu.jumpToSubroutine(sysAddr)` (deterministic, sets known CPU
     state)
3. Re-run irq-ack-vicii. Within-run determinism + across-run
   determinism = test result is now stable.

After this, all subsequent emulator-correctness work has a stable
test loop. The deeper cycle-stepping refactor is only needed if some
test case STILL produces non-deterministic results AFTER the
injection harness is fixed.

### Phase A FULL (deferred, only if needed)
- Refactor `MOS6510Core.emulateOp()` to advance EXACTLY 1 cycle per
  call. Each opcode becomes a state machine with `instructionPhase`
  variable. Memory accesses placed at the correct cycle within
  instruction.
- Multi-day refactor touching ~150 opcode handlers. Defer until we
  see test cases that prove it's needed beyond the injection fix.

### Phase B: Alarm-based event scheduling
- Replace the ad-hoc `rasterIrqClock` field with an alarm queue
  similar to VICE's `alarm_set(clk + delta)`.
- Raster IRQ scheduled via alarm.
- CIA timer fire scheduled via alarm.
- IRQ line transitions happen at alarm-fire time, not at case 0.

### Phase C: $D019 / IRQ-source handler simplification
- (DONE for $D019 — partial.) Apply same simplification to $D01A,
  $DC0D, $DD0D.
- Verify against `irq-ack-vicii.prg` (RASTER + SS-COL must pass) and
  `irqdelay-cia*.prg`.

### Phase D: Phi1/Phi2 cycle table for VIC
- Port VICE's `cycle_tab_pal` into JaC64. Each cycle has Phi1 and
  Phi2 actions.
- Move c-access from current "case 16+K all-at-once" to "case 15+K
  Phi2 c-access, case 16+K Phi1 g-access". Closes the 2-cycle
  alignment gap.
- Border checks (ChkBrdL/R) at correct cycles.

### Phase E: Glue-logic alarm for $DD00
- Port VICE's `c64gluelogic.c` discrete-vs-custom-IC distinction.
- Bank-switch effect via alarm at `clk + 1` (matches real 6569).
- Validates against `fetchsplit.prg` (BBBB column boundary).

### Phase F: Sprite pipeline finalization
- Existing partial sprite port (Phase A-J in tasks #23-35) plus the
  per-cycle pixel rendering needed for irq-ack-vicii's SS-COL test
  to pass (currently fails — sprCol never sets reliably).

### Phase G: Pipeline delays for FLI / scroll-in
- 2-cycle gbuf pipeline delay (Pipe0 → Pipe1 → Reg).
- 1-cycle reg11_delay for BMM bit in g_fetch_addr.
- Per-pixel color register pipeline (cregs[]).

## Validation suite (run after each phase)

| Test ROM | Phase that should pass it |
|---|---|
| `irq-ack-vicii.prg` (RASTER) | C |
| `irq-ack-vicii.prg` (SS-COL) | F |
| `fetchsplit.prg` | A + D + E |
| `irqdelay-cia1.prg` | A + B + C |
| `irqdelay-cia2.prg` | A + B + C |
| `Krestage 3 banner stripes` | already passes |
| `Krestage 3 FLI beast` | already passes |
| `Krestage 3 scroll-in` | A + D + E + G |
| `lets_scroll_it` (regression) | should always pass |

## Estimate

| Phase | Effort | Independently shippable? |
|---|---|---|
| A | 5-10 days | No (foundation) |
| B | 2-3 days | Yes (after A) |
| C | <1 day (mostly done) | Yes |
| D | 2-3 days | Yes (after A) |
| E | 1-2 days | Yes (after A+B) |
| F | 3-5 days | Yes (after F dependencies) |
| G | 2-3 days | Yes (after D) |

Total: **2-3 weeks of focused work** to get fully VICE-aligned.

The single biggest correctness win is **Phase A** (cycle-stepping CPU)
— without it, every subsequent fix shows non-deterministic results.
The current $D019 simplification (Phase C partial) was the right
direction but limited by the instruction-stepping CPU underneath.
