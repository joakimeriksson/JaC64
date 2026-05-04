# irq-ack-vicii 48/48 fix record

## Current status

JaC64 now runs the test headlessly with warp enabled and no desktop audio
driver. Repro command:

```sh
java -Djac64.headless=true -Djac64.warp=true \
  -Djac64.captureFrames=1 -Djac64.dumpScreen=true \
  -Djac64.dumpRows=5 \
  -Djac64.dumpScreenFile=/tmp/jac64_irq_ack_result.txt \
  -cp build/libs/JaC64.jar TestRaster \
  /Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.prg
```

Current expected result:

```text
row 03: ***-**  ******  ******  DDDD..
row 04: ***-**  ******  ******  DDDD..  SS-COL
$D020=$5 $D7FF=$0
```

The former mismatch was one cell in `irq_ack_test4`: `LDA $D019` saw the
sprite-sprite collision IRQ bit for one more slot than VICE. That is now fixed
by the default-on VICE CPU access phase plus collision IRQ visibility delay.

Important build note: the authoritative run path is `./gradlew jar` followed
by `-cp build/libs/JaC64.jar`. The repo has old in-place `.class` artifacts;
running `java -cp . TestRaster ...` can report stale 47/48 results unless
those classes are rebuilt too.

## Forbidden path: load/autostart

Load path is closed. Do not investigate PRG-vs-D64 loading, autostart,
RUN/SYS entry, `pauseAtCycle`, disk-load timing, synthetic `SYS`, boot
phase, or launch alignment for this bug again.

Evidence from 2026-05-02:

- Direct PRG/headless/warp run produces row 03 `DDDDD.` with `$D020=$2`.
- D64 `LOAD`/`RUN` path produces the same row 03 `DDDDD.` with `$D020=$2`.
- The bad cell is written while the already-running test is in
  `irq_ack_test4`; `LDA $D019` at opcode PC `$0b5b` reads `$f4` at line
  `$4a`, cycle 45 for the failing slot.

This failure is inside the IRQ/VIC/CPU pipeline after the program is
running. Future work must start from that running timing window, not from
how the program got into memory or how control reached `$080d`.

## What this is not

- Not audio or warp related. Headless warp reproduces the same stable result.
- Not load/autostart related. PRG direct execution and D64 `LOAD`/`RUN`
  reproduce the identical failing cell.
- Not a missing test source problem. The source is available at
  `/Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.asm`.
- Not a simple `$D019` read/write ordering fix. Earlier global timing shifts
  fixed this LDA cell but broke RASTER/STA cells.
- Not fixed by running VIC Phi2 during BA waits in a quick experiment; the
  screen output was unchanged.

## Working diagnosis

VICE and JaC64 agree on the important local semantics:

- VICE `CLK_INC()` runs interrupt delay, increments `maincpu_clk`, then runs
  `vicii_cycle()`.
- VICE `$D01E` read defers collision clear to a later VIC cycle.
- VICE collision IRQ fire happens after sprite drawing in `vicii_cycle()`.
- VICE `$D019` write clears `irq_status` bits with
  `irq_status &= ~((value & 0xf) | 0x80)`.

JaC64 mirrors many of these with the current Phi1/Phi2 split, but the core
CPU/VIC access contract still differs from VICE:

- VICE x64sc performs the CPU memory access first, then `CLK_INC()` calls
  `interrupt_delay()`, increments `maincpu_clk`, and runs `vicii_cycle()`.
- JaC64 `fetchByte()` currently checks BA, increments `cycles`, runs
  `schedule(cycles)`, reads memory, then runs `clockPhi2(cycles)`.

That hybrid model makes CPU reads happen after the main VIC `clock()` work for
the same labeled cycle, but before the hand-ported Phi2 events. The remaining
failure is exactly in that boundary: the failing `LDA $D019` reaches line
`$4a` one cycle later relative to the collision IRQ fire than VICE for the
same slot. Global one-cycle shifts fix this cell but break other rows, so the
fix must port the access-phase contract rather than moving a single event.

2026-05-02 follow-up trace sharpened this further:

- For the bad slot, the PC stream from `irq_handler_2` to `$0b5b` is aligned
  with VICE.
- VICE `LDA abs` performs `LOAD($D019)` after the 3-byte
  `FETCH_OPCODE` prefetch, at instruction start + 3 cycles.
- JaC64's generic `fetchByte(adr)` increments before sampling, so the same
  `$D019` data read happens at instruction start + 4 cycles.
- A broad access-before-clock prototype moved the read to cycle 44, but it
  also changed upstream IRQ/flag state and regressed an SS-COL STA cell. This
  confirms the fix cannot be a simple global `fetchByte()` reordering.
- A narrower opcode-body access prototype (`jac64.viceBodyAccessPhase=true`)
  also moved toward VICE's `LOAD/STORE` shape but still produced row 03
  `****-*  ******  ******  DDDDD.`. Adding the global IRQ pre-increment made
  the LDA group `DDDD..` but broke the RASTER/STA groups again. This keeps the
  same conclusion: partial phase ports and global IRQ shifts only move the
  mismatch around.

2026-05-03 result:

- `jac64.viceCycleAccessPhase=true` changes CPU reads/writes to VICE's
  `access at current clock, then CLK_INC()` shape. This moves the bad
  `LDA $D019` sample to raster cycle 44, but the read still returned `$f4`
  because the sprite-sprite collision IRQ flag was set at the same clock.
- Direct `EV-SSColFire` tracing showed JaC64 set `$D019` bit 2 at
  `clk=7886762 rast=$4a cyc=44`, immediately before the `LDA $D019` at the
  same clock. VICE's passing behavior requires this flag to become visible one
  cycle later for this slot.
- The passing candidate is the pair now enabled by default:
  - `jac64.viceCycleAccessPhase=true`
  - `jac64.viceCollisionIrqDelay=true`
- Opt-outs remain for A/B testing:
  - `-Djac64.viceCycleAccessPhase=false`
  - `-Djac64.viceCollisionIrqDelay=false`

The active root-cause area is the running IRQ/VIC/CPU pipeline: CPU access
ordering relative to `vicii_cycle()`, IRQ delay counting, BA-steal interaction,
CLI/SEI opcode-enable behavior, sprite DMA timing, and per-iteration drift
after the test is already executing.

## Fix plan

1. Keep the headless test harness change.
   This makes the VICE-style timing work reproducible without an audio device
   or Swing window.

2. Add one more trace layer before changing timing code:
   - `EV-IrqService` should include current raster line/cycle and pushed PC.
   - `$D019` reads should include the opcode PC, not only `cpu.pc` after
     operand fetch.
   - screen writes to `$0400,y` in this test should log `$fd`, `$fb`, and the
     stored value so each result slot is unambiguous.

3. Compare JaC64 and VICE from the running IRQ windows only:
   - first `irq_handler` entry after the test has initialized.
   - first `irq_handler_2` entry.
   - `irq_ack_test4` `LDA $D019` at opcode PC `$0b5b` for all six SS-COL slots.
   - screen writes from `$0b76` so each `SS-COL` result cell is tied to the
     `$D019` value that produced it.

4. Find the first post-initialization pipeline divergence. Candidate areas:
   - CPU read/write ordering relative to `schedule(cycles)` and `clockPhi2()`.
   - VICE `irq_delay_cycles` dispatch rules.
   - BA-steal interaction in `maincpu_steal_cycles()`.
   - CLI/SEI opcode-enable and opcode-disable handling.
   - sprite DMA BA timing around the `$4a` collision line.
   - per-iteration cycle drift between `irq_handler_2` returns and the next
     `irq_ack_test4` probe.

5. Implemented fix:
   - CPU memory accesses now have a default-on VICE-style phase option:
     `LOAD/STORE` at the current CPU clock, then `CLK_INC()` runs VIC work for
     the next clock. This avoids the old "increment before sampling" behavior
     that made opcode-body reads one cycle late.
   - Sprite-sprite and sprite-background collision IRQ flag setting now has a
     default-on extra Phi2 readiness stage. Collision detection still records
     the collision immediately; only the IRQ flag visibility moves to the next
     Phi2. This is not a `$D019` read special-case and applies to both collision
     IRQ sources.
   - The old behavior remains available with
     `-Djac64.viceCycleAccessPhase=false` and
     `-Djac64.viceCollisionIrqDelay=false`.

6. Reject local hacks:
   - Do not patch the test program.
   - Do not investigate or tune load/autostart/boot/launch behavior.
   - Do not special-case `irq-ack-vicii` or a single `$D019` read slot.
   - Do not use global IRQ assertion shifts (`irqAssertPreIncrement`) as a fix;
     those only trade one passing cell for another failing cell.
   - Do not use stale `java -cp .` runs as evidence; rebuild with Gradle and
     run `-cp build/libs/JaC64.jar`, or rebuild the in-place classes first.

## Acceptance

The target output is:

```text
row 03: ***-**  ******  ******  DDDD..
$D020=$5 $D7FF=$0
```

After that, rerun the focused regression set:

- `irq-ack-vicii.prg`
- CIA timer/new-CIA IRQ tests already used in this tree
- Krestage 3 / side-border regression screenshot
- `fetchsplit.prg`
- nearby VICE-testprogs interrupt tests under `irqnoack`

2026-05-03 candidate verification:

- `irq-ack-vicii.prg`: PASS, `$D020=$5 $D7FF=$0`, row 03
  `***-**  ******  ******  DDDD..`.
- `./test_raster.sh .../irq-ack-vicii.prg`: PASS after switching the script to
  `./gradlew` and the jar classpath.
- `java -cp . TestRaster .../irq-ack-vicii.prg`: PASS only after rebuilding
  the root/package `.class` artifacts; stale classes previously reproduced the
  old 47/48 symptom.
- `cia-timer-newcias.prg`: PASS, `$D020=$5 $D7FF=$0`.
- `fetchsplit.prg`: unchanged from baseline in this tree (`$D020=$e`,
  `$D7FF=$ff`).
- `irqnoack/ackraster.prg`, `ackcia.prg`, `ackcia3.prg`: unchanged from
  baseline in this tree.
- `irqnoack/ackcia2.prg`: still PASS.
- `test-demos/krestage3.d64`: smoke run boots and captures without crashing;
  longer visual comparison still recommended.
