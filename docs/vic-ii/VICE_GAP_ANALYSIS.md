# VICE vs JaC64 VIC-II Implementation Gap Analysis

## Status update (post-implementation of gaps #1 + #2)

| Gap | Status | Impact on irq-ack-vicii |
|-----|--------|------------------------|
| #1 — $D01E read deferred clear | ✅ Implemented (`d2002b7`) | No visible change |
| #2 — SSCol fire end-of-cycle (was per-pixel) | ✅ Implemented (`b428d68`) | No visible change |
| #3 — Sprite painting cycle alignment | Pending | Likely needed |
| #4 — $D019 write/read same-cycle timing | Pending | Likely needed |
| #5 — IRQ delivery latency match | Pending | Likely the dominant issue |

**Empirical finding:** The dominant gap appears to be #5. Cycle math:

- SSCol fires at line $4A cycle 44 (sprite at X=$f8 = column 28 = cycle 44)
- LDA $D019 across 6 test slots happens at cycles 42-47 of line $4A
- For VICE pattern (slots 1-4 = $84, slots 5-6 = $0), the IRQ ack
  must complete BETWEEN cycles 45 and 46 of line $4A — i.e. ack
  happens within ~2 cycles of SSCol fire.
- JaC64 IRQ delivery is 7-9 cycles (IRQ_DELAY=2 + ~3 cycle CPU
  instruction wait + 7 cycle interrupt entry). Ack happens at
  ~cycle 53+ of line $4A. By then, all 6 slot LDAs have already
  read.
- Result: JaC64 reads $84 at all 6 slots → DDDDDD vs reference
  DDDD..

To match VICE the IRQ delivery latency must drop to ~1-2 cycles.
This requires deeper investigation of VICE's `interrupt_ack_irq`
+ `maincpu_set_irq_clk` machinery and the precise CLK-relative
fire moment in `vicii_irq_sscoll_set`.

**Test Status:** `irq-ack-vicii.prg` currently fails (red border) in JaC64; passes in VICE.

**Failure Symptom:** Sprite-sprite collision IRQ ($D01E register) shows extra pending bits when read in slot 4 of the SS-COL test. JaC64 reports `$84,$84,$84,$84,$84,$84` (all positions flagged); VICE reports `$84,$84,$84,$84,$00,$00` (first 4 positions only).

---

## Executive Summary: Single Most Critical Fix

If you can only fix ONE issue to drive `irq-ack-vicii.prg` to pass, prioritize:

1. **$D01E Read Defers Collision Clear by 1 Cycle** (vicii-mem.c:520-535 vs C64Screen.java:1160)  
   JaC64 clears `sprCol = 0` immediately on read; VICE defers via `vicii.clear_collisions = 0x1e` → applies at START of NEXT vicii_cycle() (vicii-cycle.c:414-418). This deferral is **critical** for irq-ack-vicii: if $D01E is read at cycle N, collisions detected at cycle N+1 should still see `sprCol==0` at the time JaC64 checks `wasZero`, but because JaC64 cleared it at N, it incorrectly fires the IRQ again. VICE doesn't fire because can_sprite_sprite (sampled at cycle N's Phi1, before the clear) = false at cycle N+1.

2. **Sprite-Sprite Collision Transition Detection** (C64Screen.java:2894-2908 vs vicii-cycle.c:407-433)  
   JaC64 checks `wasZero = (sprCol == 0)` then sets bit and fires IRQ. But sprCol is now non-zero for ALL future reads in slot 5-6 of the same LDA $D019 cycle. VICE latches the transition state at the moment collision is detected (can_sprite_sprite), applies the clear deferred to next cycle, ensuring a fresh 0→non-zero check per pixel/cycle.

3. **IRQ Status Latch Timing After $D019 Write** (C64Screen.java:1485 vs vicii-mem.c:227-230)  
   Both clear bits the same way (`irq_status &= ~((value & 0x0f) | 0x80)`), but JaC64's per-cycle dispatch may not align with VICE's Phi2 timing. Verify the CPU write commits BEFORE the VIC's next vicii_cycle() runs the clear logic.

---

## Gap Analysis by Priority

### 1. Sprite-Sprite Collision IRQ Pipeline (CRITICAL)

**Issue:** JaC64 fires SS-COL IRQ immediately when sprCol transitions 0→non-zero; VICE defers both the collision LATCHING and the clear CLEARING by 1 cycle via state variables.

**VICE Implementation (vicii-cycle.c:407-433):**
```c
/* Line 407-408: Sample collision state at Phi1 START, before any draw */ 
can_sprite_sprite = (vicii.sprite_sprite_collisions == 0);

/* Line 411: vicii_draw_cycle() may set vicii.sprite_sprite_collisions during pixels */
vicii_draw_cycle();

/* Line 414-418: AFTER draw, if $D01E was read, clear is deferred */
switch (vicii.clear_collisions) {
    case 0x1e:
        vicii.sprite_sprite_collisions = 0;  /* Clear happens NOW, at Phi1 end */
        vicii.clear_collisions = 0;
        break;
}

/* Line 428: Fire IRQ only if can_sprite_sprite AND new collision */
if (can_sprite_sprite && vicii.sprite_sprite_collisions) {
    vicii_irq_sscoll_set();
}
```

**JaC64 Implementation (C64Screen.java:2889-2908):**
```java
// During sprite painting (mid-pixel, multiple times per cycle)
boolean wasZero = (sprCol == 0);
sprCol |= tmp & 0xff;
if (wasZero) {
    irqFlags |= 0x04;
    updateVicIrqLine();
    if ((irqMask & 4) != 0) {
        setIRQ(VIC_IRQ);
    }
}

// $D01E read clears immediately
case 0xd01e:
    val = sprCol;
    sprCol = 0;  // <-- BUG: immediate clear, not deferred
    return val;
```

**The Problem:**  
When slot 4's `lda $d019; and #$87; sta $fb` reads $D019 in line $4a cycle ~42-47, the LDA reads $D019 across 3 cycles:
- Cycle 42: fetch opcode $a9 (lda immediate)
- Cycle 43: fetch immediate byte (= old d019 value)
- Cycle 44: commit read; $D019 is sampled

In JaC64, cycle 44's read of $D019 clears sprCol immediately. If a collision fires at cycle 45-46, JaC64 sees `wasZero = false` (because the clear already happened), fires the IRQ, and **keeps the flag set** for all future reads in positions 5-6.

In VICE, the read sets `clear_collisions = 0x1e`, defers the clear to the start of the **next** vicii_cycle(), and uses `can_sprite_sprite` (sampled at cycle 44's Phi1) to gate the IRQ. If a collision fires at cycle 45-46, `can_sprite_sprite` is **already false** (set at cycle 44, before the clear), so no new IRQ fires.

**Files and Lines:**
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-mem.c:520-535` ($D01E read handler)
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-cycle.c:407-433` (collision latch & IRQ logic)
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java:1160` (immediate clear)
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java:2889-2908` (transition check)

**Classification:** CRITICAL — blocks `irq-ack-vicii.prg` pass.

**Fix Strategy:**
1. Add a `clearCollisionsDeferred` state variable (0 = none, 0x1e = sprite-sprite, 0x1f = sprite-background).
2. On $D01E read: set `clearCollisionsDeferred = 0x1e`, return current `sprCol`, do NOT clear.
3. At the start of the next `clock()` cycle (or within the dispatcher's case 0 pre-logic), apply the deferred clear: `if (clearCollisionsDeferred == 0x1e) sprCol = 0; clearCollisionsDeferred = 0;`
4. When checking collision transitions, sample the "was zero" state BEFORE the pixel-by-pixel checks, similar to VICE's `can_sprite_sprite`.

---

### 2. Sprite Painting & DMA Activation Timing (IMPORTANT)

**Issue:** JaC64 activates sprite DMA and painting at cycles 54-55; VICE's check_sprite_dma runs at cycles 55-56, check_sprite_display at cycle 58. The 1-cycle offset affects when sprites start rendering and can generate collisions.

**VICE Implementation (vicii-cycle.c:496-513):**
```c
/* Check sprite DMA (Cycles 55 & 56 on PAL) */
if (cycle_is_check_spr_dma(vicii.cycle_flags)) {
    check_sprite_dma();    /* Enables DMA and sets mcbase=0 */
}

/* Check sprite expansion (Cycle 56) */
if (cycle_is_check_spr_exp(vicii.cycle_flags)) {
    check_exp();
}

/* Check sprite display (Cycle 58 on PAL) */
if (cycle_is_check_spr_disp(vicii.cycle_flags)) {
    check_sprite_display();  /* Sets sprite_display_bits if DMA & Y-match */
}
```

**JaC64 Implementation (C64Screen.java:2222-2262):**
```java
case 54:
    for (int i = 0; i < 8; i++) {
        if (sprite.enabled && sprite.y == (ypos & 0xff)) {
            sprite.nextByte = 0;
            sprite.dma = true;        // <-- Cycle 54
            sprite.expFlipFlop = true;
            sprite.painting = true;   // <-- BUG: too early
        }
    }
    break;

case 55:
    // ChkBrdR0 handled here
    break;

case 56:
    // ChkBrdR1 handled here
    break;

case 57:
    // Sprite painting starts here
    break;

case 58:
    // Sprite painting continues
    break;
```

**The Problem:**  
JaC64 sets `sprite.painting = true` at cycle 54, but VICE's `check_sprite_display()` runs at cycle 58. If the test expects sprites to NOT be painting yet at cycles 55-57, JaC64 will generate collisions 1-3 cycles too early.

**Files and Lines:**
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-cycle.c:496-513` (sprite checks)
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-cycle.c:62-79` (check_sprite_display logic)
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java:2222-2262` (cases 54-58)

**Classification:** IMPORTANT — may affect collision timing in other tests.

**Fix Strategy:**  
Verify that `sprite.painting` is NOT set until case 58, or adjust the collision checks to account for the 4-cycle pipeline delay. Confirm with per-cycle trace whether JaC64's sprite painting actually starts at cycle 54 or 58.

---

### 3. IRQ Delivery Latency to CPU (IMPORTANT)

**Issue:** JaC64's IRQ arrives at the CPU with a 2-3 cycle latency (set in MOS6510Core.java:30-31); VICE similarly defers via maincpu_set_irq(). However, the exact cycle when the CPU samples IRQLow may differ.

**JaC64 Implementation (MOS6510Core.java:30-31, 140-154):**
```java
public static final int IRQ_DELAY = 2;
public static final int IRQ_RELEASE_DELAY = 3;

public void setIRQLow(boolean low) {
    if (low && !irqRequested) {
        irqRequested = true;
        IRQLow = true;
        irqCycleStart = cycles + IRQ_DELAY;  /* 2-cycle latency */
        traceIrqLine("IRQ-ASSERT");
    }
}
```

**VICE Implementation (interrupt.c, mainc64cpu.c):**  
VICE also defers IRQ via `maincpu_set_irq()` with similar latency. The exact cycle depends on when the CPU's interrupt check (Phase A) reads the IRQ pin.

**The Problem:**  
If JaC64's IRQ latency doesn't match VICE's, the CPU may sample the interrupt at a different cycle, leading to mis-timed instruction execution or delayed RTI in the handler.

**Files and Lines:**
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/MOS6510Core.java:30-31, 140-154`
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java:761-763` (setIRQ call)
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-irq.c:36-45` (vicii_irq_set_line)

**Classification:** IMPORTANT — affects interrupt timing but may not block `irq-ack-vicii.prg` if latency is consistent.

**Fix Strategy:**  
Ensure JaC64's IRQ_DELAY and VICE's latency match. If VICE uses 1-cycle delay, adjust JaC64 to IRQ_DELAY=1.

---

### 4. $D019 Read/Write Timing (CRITICAL)

**Issue:** When CPU's STA $D019 commits, does the VIC see the write in the same cycle or the next? JaC64's `writeByte()` calls `schedule()` which runs the dispatcher **before** the write is visible to the VIC.

**JaC64 Implementation (CPU.java, C64Screen.java:1475-1487):**
```java
writeByte(0xd019, data) {
    cycles++;
    performWrite(0xd019, data);    // irqFlags &= ~((data & 0x0f) | 0x80)
    schedule(cycles);              // VIC sees updated irqFlags
    // updateVicIrqLine() called in performWrite
}
```

**VICE Implementation (vicii-mem.c:227-230):**
```c
inline static void d019_store(uint8_t value) {
    vicii.irq_status &= ~((value & 0xf) | 0x80);
    vicii_irq_set_line();
    // Clear is applied immediately, IRQ line updated
}
```

Both JaC64 and VICE apply the clear immediately (same-cycle). However, JaC64's dispatch order may differ.

**Files and Lines:**
- JaC64: `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java:1475-1487` ($D019 write handler)
- VICE: `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-mem.c:227-230` (d019_store)

**Classification:** CRITICAL — if the dispatcher runs BEFORE the write is committed, collision flags set at the same cycle won't be cleared.

**Fix Strategy:**  
Verify that `updateVicIrqLine()` is called AFTER the `irqFlags &= ~(...)` operation in the same cycle, ensuring the clear takes effect before any new collisions are latched.

---

### 5. Collision Transition vs. Latching (CRITICAL)

**Issue:** VICE latches collisions into `sprite_sprite_collisions` continuously during draw_cycle(), but only fires an IRQ if `can_sprite_sprite` (= 0 at Phi1) AND collision is detected at Phi1. JaC64 checks the transition inside the pixel loop, allowing multiple IRQ fires per pixel.

**VICE Implementation (vicii-cycle.c:407-433):**
```c
can_sprite_sprite = (vicii.sprite_sprite_collisions == 0);  // Line 407
vicii_draw_cycle();  // May set sprite_sprite_collisions
// ...
if (can_sprite_sprite && vicii.sprite_sprite_collisions) {  // Line 428
    vicii_irq_sscoll_set();  // Fire ONCE per cycle
}
```

**JaC64 Implementation (C64Screen.java:2889-2908, called per pixel):**
```java
// Called inside drawSpritesV2Span(), potentially many times per cycle
if (wasZero) {
    irqFlags |= 0x04;
    updateVicIrqLine();
}
```

**The Problem:**  
JaC64 may call this collision check many times in a single cycle (once per pixel in the 8-pixel span), potentially setting the IRQ multiple times. VICE fires only once per cycle, at the Phi1 edge.

**Classification:** CRITICAL — contributes to extra IRQ flags in irq-ack-vicii.prg.

**Fix Strategy:**  
Move the collision checks to case 0 or a dedicated pre-draw phase, sample `can_sprite_sprite` once per cycle (at cycle start), and fire the IRQ only if the condition holds, not per-pixel.

---

## Summary: Implementation Roadmap

| Gap | Priority | Severity | Fix Estimate |
|-----|----------|----------|--------------|
| $D01E read defers clear | 1 | CRITICAL | 2-3 hrs |
| Sprite painting start cycle | 2 | IMPORTANT | 1-2 hrs |
| Collision transition logic | 3 | CRITICAL | 2-3 hrs |
| IRQ latency alignment | 4 | IMPORTANT | 1 hr |
| $D019 write timing | 5 | CRITICAL | 1-2 hrs |

**Recommended Fix Order:**  
1. Implement deferred $D01E clear (fixes the core irq-ack-vicii issue)
2. Align sprite DMA/painting cycles with VICE
3. Refactor collision checks to fire once per cycle, not per pixel
4. Validate $D019 write timing in the dispatcher
5. Test with irq-ack-vicii.prg and iteratively validate

---

## References

- **JaC64 Main VIC:** `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/C64Screen.java`
- **JaC64 CPU:** `/Users/joakimeriksson/work/JaC64/com/dreamfabric/jac64/MOS6510Core.java`
- **VICE VIC-II (cycle-exact):** `/Users/joakimeriksson/work/vice-emu/vice/src/viciisc/`
  - Main cycle: `vicii-cycle.c`
  - Memory access: `vicii-mem.c`
  - IRQ logic: `vicii-irq.c`
  - Drawing: `vicii-draw-cycle.c`
  - Collision: `vicii-cycle.c:407-433`
- **Test:** `/Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/irq-ack-vicii.asm`
- **Test Slot 4 Reference:** Line 315-318 (SS-COL test, reference $84,$84,$84,$84,$00,$00)
