# VICE CPU/IRQ Subsystem Code Review

Reference source: `/Users/joakimeriksson/work/vice-emu/vice/src/`

## VICE's IRQ flow (cycle-exact c64sc CPU)

### Layer 1: VIC raster IRQ assertion
`viciisc/vicii-cycle.c:536-543` inside `next_vicii_cycle()`:
```c
if (vicii.raster_line == vicii.raster_irq_line) {
    if (!vicii.raster_irq_triggered) {
        vicii_irq_raster_trigger();
        vicii.raster_irq_triggered = 1;
    }
}
```

`viciisc/vicii-irq.c:58-62`:
```c
void vicii_irq_raster_set(CLOCK mclk) {
    vicii.irq_status |= 0x1;
    vicii_irq_set_line_clk(mclk);
}
```

`viciisc/vicii-irq.c:47-56`:
```c
static inline void vicii_irq_set_line_clk(CLOCK mclk) {
    if (vicii.irq_status & vicii.regs[0x1a]) {
        vicii.irq_status |= 0x80;
        maincpu_set_irq_clk(vicii.int_num, 1, mclk);
    }
    ...
}
```

`maincpu_set_irq_clk` sets `cs->irq_clk = mclk` (in `interrupt.h:174`).

### Layer 2: CLK_INC at each cycle tick
`c64/c64cpusc.c:47-51`:
```c
#define CLK_INC()                                  \
    interrupt_delay();                             \
    maincpu_clk++;                                 \
    maincpu_ba_low_flags &= ~MAINCPU_BA_LOW_VICII; \
    maincpu_ba_low_flags |= vicii_cycle()
```

Order: `interrupt_delay()` runs **BEFORE** `maincpu_clk++`. `vicii_cycle()` runs **AFTER** `maincpu_clk++`. So when vicii_cycle sets irq_clk = maincpu_clk, it's the POST-INC value.

### Layer 3: interrupt_delay (mainc64cpu.c:97-110)
```c
inline static void interrupt_delay(void) {
    while (maincpu_clk >= alarm_context_next_pending_clk(...)) {
        alarm_context_dispatch(...);
    }
    if (maincpu_int_status->irq_clk <= maincpu_clk) {
        maincpu_int_status->irq_delay_cycles++;
    }
    if (maincpu_int_status->nmi_clk <= maincpu_clk) {
        maincpu_int_status->nmi_delay_cycles++;
    }
}
```

**Each CLK_INC increments `irq_delay_cycles` if irq was already asserted** (`irq_clk <= maincpu_clk`).

### Layer 4: Per-instruction check
`mainc64cpu.c:690-710`:
```c
inline static int interrupt_check_irq_delay(interrupt_cpu_status_t *cs, CLOCK cpu_clk) {
    unsigned int delay_cycles = INTERRUPT_DELAY;  /* = 2 */
    if (OPINFO_DELAYS_INTERRUPT(*cs->last_opcode_info_ptr)) {
        delay_cycles++;  /* branch delay +1 */
    }
    if (cs->irq_delay_cycles >= delay_cycles) {
        if (!OPINFO_ENABLES_IRQ(*cs->last_opcode_info_ptr)) {
            return 1;
        } else {
            cs->global_pending_int |= IK_IRQPEND;
        }
    }
    return 0;
}
```

INTERRUPT_DELAY = 2 (`interrupt.h:39`). Branch-taken adds +1.

### Layer 5: DO_INTERRUPT (6510dtvcore.c:354)
When `interrupt_check_irq_delay` returns 1, DO_INTERRUPT runs:
1. `LOAD_DUMMY(reg_pc); CLK_INC()` — 2 dummy reads (cycles 1-2 of IRQ entry).
2. `LOAD_DUMMY(reg_pc); CLK_INC()`.
3. DO_IRQBRK macro at `6510dtvcore.c:314-349`:
   - `PUSH(pc_hi); CLK_INC()` — cycle 3.
   - `PUSH(pc_lo); CLK_INC()` — cycle 4.
   - `PUSH(status); CLK_INC()` — cycle 5.
   - `addr = LOAD(vec_lo); CLK_INC()` — cycle 6.
   - `addr |= LOAD(vec_hi) << 8; CLK_INC()` — cycle 7.
   - `JUMP(addr)` — set new PC.

Total IRQ entry: 7 cycles.

## JaC64's current IRQ flow

### Layer 1: VIC raster IRQ assertion (C64Screen.java:1150-1180)
```java
private void triggerRasterIrq(long irqClock) {
    if ((irqFlags & 0x1) != 0 && irqTriggered) return;
    irqFlags |= 0x1;
    if ((irqMask & 1) != 0) {
        irqFlags |= 0x80;
        irqTriggered = true;
        setIRQ(VIC_IRQ);  // → InterruptManager.setIRQ → cpu.setIRQLow(true)
    }
}
```

### Layer 2: setIRQLow (MOS6510Core.java:142-163)
```java
public void setIRQLow(boolean low) {
    if (low) {
        if (!irqRequested) {
            irqRequested = true;
            IRQLow = true;
            irqDelayCycles = 0;
            irqCycleStart = VICE_IRQ_DELAY_COUNTER
                ? cycles
                : cycles + IRQ_DELAY - (IRQ_ASSERT_PRE_INCREMENT ? 1 : 0);
        }
    }
}
```

With default flags off: `irqCycleStart = cycles + 2`.

### Layer 3: Per-cycle increment (counter mode, MOS6510Core.java:244-248)
```java
protected final void viceInterruptDelayBeforeClockInc() {
    if (VICE_IRQ_DELAY_COUNTER && IRQLow && irqCycleStart <= cycles) {
        irqDelayCycles++;
    }
}
```

Called from `CPU.fetchByte` / `CPU.writeByte` BEFORE `cycles++`. Only fires when counter mode is ON.

### Layer 4: Per-instruction check (MOS6510Core.java:471-477)
```java
boolean irqAllowedByStatus = !disableInterupt || (VICE_SEI_IRQ_WINDOW && lastOpcodeDisablesIrq);
boolean irqDelayReady = VICE_IRQ_DELAY_COUNTER
    ? (IRQLow && irqDelayCycles >= IRQ_DELAY + (branchDelaysIrq ? 1 : 0) && irqEnableDelayOps == 0)
    : (IRQLow && cycles >= irqCycleStart + (branchDelaysIrq ? 1 : 0) && irqEnableDelayOps == 0);
```

Default: cycles >= irqCycleStart (= assert + 2).

### Layer 5: doInterrupt (MOS6510Core.java:277-312)
```java
private final void doInterrupt(int adr, int status) {
    fetchByte(pc);       // dummy 1
    fetchByte(pc + 1);   // dummy 2
    push((pc & 0xff00) >> 8);
    push(pc & 0x00ff);
    push(status);
    interruptInExec++;
    pc = fetchByte(adr);
    pc |= fetchByte(adr + 1) << 8;
}
```

Each call advances `cycles` by 1 (via fetchByte / push→writeByte). Total: 7 cycles. ✓ matches VICE.

## Comparison: where does JaC64 differ from VICE?

### Same:
- IRQ entry sequence (7 cycles, same op order). ✓
- VIC raster IRQ trigger condition (raster_line == raster_irq_line, latched). ✓
- INTERRUPT_DELAY = 2. ✓
- Branch-delay (+1). ✓
- IRQ_DELAY mechanism (counter or cycle-based). ✓

### Different / suspect:
1. **Counter increment site**:
   - VICE: `interrupt_delay()` runs at TOP of CLK_INC (= BEFORE clk++ AND before vicii_cycle).
   - JaC64: `viceInterruptDelayBeforeClockInc()` runs at TOP of fetchByte/writeByte cycle work (similar logic).
   - **Subtle**: VICE's interrupt_delay also dispatches alarms FIRST. JaC64 doesn't.

2. **Default `VICE_IRQ_DELAY_COUNTER` flag**:
   - OFF by default in JaC64. Falls back to `cycles >= irqCycleStart` comparison.
   - The cycle-based comparison should be equivalent to counter-based mathematically — but only if irqCycleStart was set at EXACTLY assert_clk + 2.
   - VICE's irq_clk is set inside vicii_cycle, AFTER CLK_INC's clk++ ran. So irq_clk = post-inc clk.
   - JaC64's irqCycleStart = (cycles at triggerRasterIrq) + 2. At time of triggerRasterIrq, what is `cycles`?

3. **`cycles` value at triggerRasterIrq in JaC64**:
   - Called from C64Screen.clock(cycles), which is called from CPU.schedule(cycles), which is called from fetchByte AFTER `cycles++`. So `cycles` in chips.clock IS the post-inc value.
   - JaC64: irqCycleStart = post-inc-cycles + 2.
   - VICE: irq_clk = post-inc-clk. Counter increments per CLK_INC. After 2 CLK_INCs, irq_delay_cycles = 2. Check fires.
   - **Equivalent timing**, BUT...

4. **`vicii_cycle()` SETS BA-low for NEXT cycle in VICE**:
   - `c64cpusc.c:51`: `maincpu_ba_low_flags |= vicii_cycle()` — vicii_cycle returns BA-low flags for the NEXT CPU cycle.
   - JaC64's `chips.clock(cycles)` doesn't return BA-low flags this way. BA-low handling is separate via `waitForBus`.

5. **`OPINFO_DISABLES_IRQ` (SEI delay window)**:
   - VICE: SEI takes effect AFTER its own boundary, so an IRQ that would have been checked before SEI completes IS still allowed to fire if it was already pending.
   - JaC64: `VICE_SEI_IRQ_WINDOW` flag handles this. Currently ON by default.

6. **`OPINFO_ENABLES_IRQ` (CLI re-enable delay)**:
   - VICE: when CLI executes, the IRQ delay starts AFTER CLI, not at CLI.
   - JaC64: `irqEnableDelayOps` handles this. Default behaviour unclear.

### Most likely root cause
The 1-cycle drift in IRQ entry comes from one of:
- `cycles` value mismatch between when JaC64 sets `irqCycleStart` and when VICE sets `irq_clk`.
- `irqDelayCycles` counter not being incremented at the right moments when DELAY_COUNTER is on.
- An accumulating drift from CIA timer IRQ assertion timing (not measured yet).

## Phase β fix plan

### Step 1 — Make DELAY_COUNTER mode the default and match VICE exactly
1. Flip default `VICE_IRQ_DELAY_COUNTER = true`.
2. Verify `irqDelayCycles` increments at EVERY cycle tick (= per `fetchByte`/`writeByte` access), not just per "memory access". VICE's `interrupt_delay()` runs at EVERY CLK_INC unconditionally.
3. Verify the initial `irqDelayCycles = 0` set at `setIRQLow(true)` happens BEFORE the next CLK_INC.

### Step 2 — Validate irq-ack-vicii after each change
- Run irq-ack-vicii after each step. Must stay 48/48.

### Step 3 — Audit `OPINFO_*` semantics
- Make sure `lastOpcodeDisablesIrq` / `irqEnableDelayOps` map correctly to VICE's `OPINFO_DELAYS_INTERRUPT` / `OPINFO_ENABLES_IRQ` / `OPINFO_DISABLES_IRQ`.

### Step 4 — Test on colorsplit
- After CPU changes, re-run colorsplit cell-diff. Target: ≤ legacy 1376.

### Step 5 — If still drift: trace CIA timer IRQ assertion (Phase γ scope).

## Files to edit (in Phase β execution)
- `com/dreamfabric/jac64/MOS6510Core.java` — IRQ entry / delay logic.
- `com/dreamfabric/jac64/CPU.java` — verify `viceInterruptDelayBeforeClockInc` fires at right places.

## Risk
- **irq-ack-vicii regression** (48/48 today). Must re-test after EVERY change.
- The `VICE_IRQ_DELAY_COUNTER=false` path is the current default and battle-tested. Flipping it can regress other tests.
- Safe approach: gate the new default behind a feature flag for one commit, run full sweep + irq-ack-vicii, then flip default if clean.
