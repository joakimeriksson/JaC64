# Deep Analysis: Why bit 1 of $D01E Differs VICE vs JaC64

## The question

Krestage 3's probe reads `$D01E` and expects `AND $07 == $07` (sprites 0, 1, 2 all collided). On x64sc (cycle-exact VICE) the probe passes; on JaC64 V2 without assist, `$D01E = $0d` — bit 1 missing.

## Probe setup re-stated precisely

From `$74C0`:
```
  S0: reg X=$70 Y=$48
  S1: reg X=$A0 Y=$48
  S2: reg X=$80 Y=$48
  S3: reg X=$50 Y=$48
  S4: reg X=$00 Y=$48
  S5: reg X=$D0 Y=$48
  S6: reg X=$40 Y=$48
  S7: reg X=$10 Y=$48
  $D010=$1C, $D011=$1B, $D015=$FF, $D017=$00, $D01D=$FF
  Sprite data bytes all $FF.
```

VICE's internal sprite X (register + X_OFFSET with PAL X_OFFSET=$20):
```
  S0 $C0 ... wait this is register X raw. Internal is +0x20.
  S0 internal: $70+$20=$90, range [$90,$BF]
  S1 internal: $A0+$20=$C0, range [$C0,$EF]
  S2 internal: $180+$20=$1A0, range [$1A0,$1CF]
  S3 internal: $150+$20=$170, range [$170,$19F]
  S4 internal: $100+$20=$120, range [$120,$14F]
  S5 internal: $D0+$20=$F0,  range [$F0,$11F]
  S6 internal: $40+$20=$60,  range [$60,$8F]
  S7 internal: $10+$20=$30,  range [$30,$5F]
```

**S1 and S5 are ADJACENT**: S1 ends at $EF, S5 starts at $F0. A 1–2 pixel extension of S1 (or backward-shift of S5) creates S1+S5 overlap → bit 1 fires.

## The probe's sequence of writes (observed cycles on JaC64)

From my earlier exec trace on line 81 (vbeam=81):
```
  cyc 23-27: STA $D01C = $01
  cyc 29-33: STA $D01C = $02
  cyc 35-39: STA $D01C = $00
  (NOP)
  cyc 43-47: STA $D010 = $1D   ← sprite 0 MSB=1
  (...delay...)
  cyc 14-18 on line 82: STA $D010 = $1C  ← sprite 0 MSB back to 0
  (...320-cycle delay...)
  LDA $D01E     ← probe read
```

## VICE's MC-bug condition (vicii-mem.c:714)

```c
sprite_x = (vicii.regs[2 * i] | (vicii.regs[0x10] & b ? 0x100 : 0)) + vicii.screen_leftborderwidth - 0x20;
// For PAL: sprite_x = register_x (since screen_leftborderwidth == 0x20)

if (sprite_x < raster_x && sprite_x + (x_exp ? 48 : 24) >= raster_x) {
    // MC-bug computation
}
```

So MC-bug fires for sprite N when `sprite_x < raster_x <= sprite_x + 48` (expanded).

**For sprite 1** (internal X=160, range [160, 208)):
- Write 1 ($D01C=$01) at some raster_x — if raster_x in (160, 208], S1 fires.
- Write 2 ($D01C=$02) — same condition.
- Write 3 ($D01C=$00) — same condition.

**For sprite 1 write 2 (bit 1 change 0→1, HIRES→MC)** — this is where delayed_shift can be 1:

From VICE vicii-mem.c:717-723:
```c
if (x_exp) {
    delayed_load = sprite_x % 2;       // =160%2=0
    delayed_shift = ((sprite_x & 1) == ((sprite_x >> 1) & 1) ? 1 : 0);
                                        // (0==0) → 1
}
delayed_pixel = 6 - delayed_load;      // =6
```

**If write 2 fires MC-bug for S1**: `mc_bug = (delayed_shift << 1) | delayed_load = 2`.

## VICE's MC rendering with delayed_shift=1 (vicii-sprites.c:783-791)

```c
if (delayed_shift) {
    ptr += 2;               // sprite start shifts 2 pixels RIGHT
    sptr += 2;
    trim_size += 2;         // sprite renders 2 MORE pixels at the end
    mcsprmsk <<= 1;
    collmsk = (collmsk << 2) | ...;
    data0 = (data_ptr[0] << 1) | (data_ptr[1] >> 7);
    data1 = (data_ptr[1] << 1);
    sprmsk = ...;
}
```

**Net effect for data=$FF with delayed_shift=1**:
- Sprite renders at X+2..X+49 (50 pixels instead of 48)
- Sprite 1 would emit at $C0+2..$C0+51 = $C2..$F1
- Sprite 5 at $F0-$11F: overlap at $F0, $F1 → **bit 1 AND bit 5 fire!**

## So the question reduces to: does write 2's MC-bug fire for sprite 1?

**Condition**: `sprite_x=160 < raster_x` when write 2 runs.

`raster_x` at write 2 depends on the cycle sampling model:
```
cyc 32 (write cycle):  rasterX(32) = (32-17)*8 + 0x20 = 152
cyc 33 (post-write):   rasterX(33) = (33-17)*8 + 0x20 = 160
cyc 34 (next cycle):   rasterX(34) = (34-17)*8 + 0x20 = 168
```

**Condition check**: `160 < rasterX`:
- If rasterX = 152: 160<152 FALSE → **skip MC-bug**
- If rasterX = 160: 160<160 FALSE (strict <) → **skip**
- If rasterX = 168: 160<168 TRUE → **fire MC-bug**

So bit 1 fires **if and only if raster_x at write time is ≥ 161 (= cycle 34+ worth of raster_x)**.

## Where does JaC64 currently sample raster_x?

In `C64Screen.writeRasterX()`:
```java
private int writeRasterX() {
    return currentRasterX - 8;
}
```

`currentRasterX` is computed in `clock()` at the start of each VIC tick. JaC64's CPU does `cycles++` inside `writeByte` BEFORE calling `schedule()` which calls `chips.clock(cycles)`. So when setIOByte runs, `currentRasterX = rasterX(write_cycle + 1)`.

For write 2 at CPU cycle 32:
- CPU cycles++ → 33.
- clock(33). currentRasterX = rasterX(33) = 160.
- setIOByte runs. writeRasterX returns 160 - 8 = 152.

**So my JaC64 samples raster_x = 152. 160 < 152 FALSE. MC-bug skipped for S1.**

## Where does VICE sample raster_x?

VICE's x64sc CPU model advances `maincpu_clk` throughout the CPU's cycle-exact execution. For a 4-cycle STA abs, maincpu_clk advances by 4 total. The store function is invoked on the last cycle (the write cycle).

VICE's `VICII_RASTER_X(VICII_RASTER_CYCLE(maincpu_clk))` samples the current `maincpu_clk` at the write handler. VICE's convention: maincpu_clk is the clock AFTER the current cycle has been consumed. So at store time, clk = write_cycle + 1.

- clk at store time ≈ 33 (for write cycle 32).
- VICE raster_x = rasterX(33) = 160.
- Condition 160<160 = FALSE. **Skip** — same as my JaC64.

Wait, this says VICE would ALSO skip... but VICE passes the probe!

## Revisiting: maybe the probe cycle timing differs in VICE vs JaC64

I traced the probe cycles in JaC64. The cycle offsets I computed are from JaC64's own execution. **In VICE, the probe code might run at different cycle offsets**, because:

1. **Different boot state**: VICE's autostart + BASIC loader may leave CPU at different raster position when probe code starts.
2. **Different cycle-accuracy in CPU**: x64sc's cycle-exact CPU may insert bus-contention cycles that JaC64 doesn't.
3. **Different initial sprite rendering**: VICE may start sprite display on a slightly different line, shifting probe timing.

**If VICE's write 2 happens at cycle 34 or later** (instead of my JaC64's cycle 32), rasterX at that point ≥ 168, MC-bug fires for S1, bit 1 fires.

## Secondary hypothesis: delayed_pixel in MC-bug affects queueing

Even if MC-bug fires at rasterX=152 (impossibly early), VICE queues the mc_bug change at `raster_x + delayed_pixel`. For delayed_pixel=6, queue position = 158. The change applies when rendering reaches pixel 158 — which is BEFORE sprite 1's display window (160). So mc_bug has effect on entire sprite 1 rendering.

Thus the MC-bug TIMING (when written to sprite state) is before sprite display starts, so sprite 1 uses mc_bug=2 for its ENTIRE display. If mc_bug fires at write 2, sprite 1 renders with delayed_shift=1, emits 50 pixels, collides with S5.

## Test plan

1. **Empirically verify**: change my `writeRasterX` from `currentRasterX - 8` to various alternatives (no offset, +8, +16). Run probe. Check if `$D01E` naturally becomes `$27` (bits 0, 1, 2, 5) or similar showing bit 1 fires.

2. **Trace the exact cycle of probe writes in VICE x64sc**: requires monitor session. At write 2 of $D01C, log `VICII_RASTER_CYCLE(maincpu_clk)` to see the cycle value.

3. **Compare cycle-exactness**: JaC64's CPU may differ from x64sc by 1-2 cycles per instruction due to:
   - Different BA stall modeling
   - Different badline handling
   - Missing cycle exact VIC interactions

## Differences between VICE x64sc and JaC64 that could affect this

| Aspect | VICE x64sc | JaC64 |
|---|---|---|
| CPU cycle model | `maincpu_clk` advanced per cycle; store sampled at clk after write | `cycles` incremented before schedule; store sampled at clk = write_cycle+1 |
| Raster_x formula | `(cycle-17)*8 + screen_leftborderwidth` | Same (PORTED) |
| $D01C store timing | Raster_x sampled at cycle of store | My `-8` offset makes it sample cycle of store - 1 |
| Sprite fetch cycles | Documented in vicii-fetch.c | Similar but possibly off by 1-2 cycles |
| BA/BBA bus stalls | Fully modeled | Partial (sprite DMA only) |
| MC-bug condition | `sprite_x < raster_x` strict | Same (PORTED) |
| MC-bug delayed_pixel | Applied to queue position | Same (PORTED) |

## Most likely answer

**My `writeRasterX = currentRasterX - 8` is incorrect.**

Removing the -8 offset (using `currentRasterX` directly) would make raster_x at write 2 equal to 160, which still fails the strict `<` condition. So we'd need `currentRasterX + 8` (= 168 at write 2) for bit 1 to fire.

This suggests VICE actually samples raster_x at cycle **AFTER** the write completes, not at the write cycle itself. Which maps to my `currentRasterX + 8` if `currentRasterX` is sampled at the write cycle.

BUT — my currentRasterX is already sampled at write_cycle+1 (due to JaC64's CPU advancing cycles first). So `currentRasterX` should already equal VICE's sample. That gives rasterX=160, condition 160<160 still fails.

**Unless**: VICE's CPU model advances clk TWICE for some instruction sequences (e.g., during bus stall or badline). That would make VICE's raster_x at write 2 be 168 while mine is 160.

## Action: check write 2's actual cycle in VICE via monitor

1. Run VICE x64sc with Krestage 3.
2. In monitor: `watch exec 7465 7465` (break at LDA before STA $D01C #2).
3. Continue a few cycles. At the STA $D01C instruction: check `maincpu_clk % 63` (cycle within line).
4. Compare to JaC64's cyc 32.

If VICE's cycle for write 2 is **34 or later**, that explains bit 1 — and suggests JaC64's CPU is running 2+ cycles ahead of VICE by the time it reaches that point in the probe (due to missing bus stalls or similar).

## Alternative hypothesis: badline side-effect

The probe runs in display area (lines 73-93, Y=$48). Badlines can steal CPU cycles.

- Badline triggers when `vbeam == (ctrl1_yscroll | 0x??...)` for specific y-scroll values. `$D011=$1B` gives yscroll=3. Badline on lines where (vbeam & 7) == 3.
- Line 81: 81 & 7 = 1. Not badline.
- Line 82: 82 & 7 = 2. Not badline.
- Line 83: 83 & 7 = 3. Badline!

So line 83 is badline. The probe's 320-cycle delay crosses line 83. Badline steals 40 cycles of CPU time (AEC stall for character fetch). This could shift actual sprite rendering by up to 40 cycles on that line.

In JaC64 V2, is the badline stall modeled cycle-accurate? Checking... JaC64 does have bad line handling (C64Screen.java), with `setBaLowUntil`. But it may not EXACTLY match VICE's model.

**Mismatch in badline cycle count could shift probe write cycles by a few cycles** between JaC64 and VICE. Over the 320-cycle delay, even 1 cycle of drift could make the difference between write 2 firing MC-bug or not.

## Summary of candidate root causes

1. **CPU cycle model off by 1-2 cycles** — raster_x at store time differs between VICE and JaC64.
2. **Badline cycle count off** — over the probe's 320-cycle delay, accumulated drift.
3. **BA stall modeling incomplete** — sprite DMA stalls partly modeled; other bus conflicts may drift.
4. **writeRasterX -8 offset WRONG** — should be 0 or +8 to match VICE.

## Concrete next diagnostics

1. **Run exec trace with wider range** and save CYCLE counts per PC. Compare first-principles against what VICE would produce.

2. **Remove writeRasterX -8 offset, test**. If bit 1 fires → fixed.

3. **Add writeRasterX +8 offset, test**. If bit 1 fires → fixed.

4. **Build VICE with binary monitor, capture exact raster cycle of each $D010/$D01C write on the probe**.

5. **Compare JaC64's cycle count from $7400 → $7482 vs VICE's**. If off by >1, CPU model or badline model needs work.
