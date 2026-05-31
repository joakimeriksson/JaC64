# JaC64 case ↔ VICE raster_cycle mapping

Reference for the Phi1/Phi2 refactor. **Convention: JaC64 case N = VICE
raster_cycle (N+1).** This is consistent across the dispatcher — there is
no global mis-numbering. The actual architectural delta vs VICE is the
absence of an explicit Phi1/Phi2 split, not a label mismatch.

## Cycle table

| VICE | JaC64 | VICE Phi1 | VICE Phi2 | JaC64 events |
|------|-------|-----------|-----------|--------------|
| 1 | 0 | SprPtr(3) | SprDma0(3); BA SPR3,4 | vbeam++; sprite capt; badLine check; SPR3 fetch |
| 2 | 1 | SprDma1(3) | SprDma2(3); BA SPR3,4,5 | SPR5 BA-low set; SPR3 fetch (unaligned) |
| 3 | 2 | SprPtr(4) | SprDma0(4); BA SPR4,5 | SPR4 fetch (aligned) |
| 4 | 3 | SprDma1(4) | SprDma2(4); BA SPR4,5,6 | SPR6 BA-low set; SPR4 fetch (unaligned) |
| 5 | 4 | SprPtr(5) | SprDma0(5); BA SPR5,6 | SPR5 fetch (aligned) |
| 6 | 5 | SprDma1(5) | SprDma2(5); BA SPR5,6,7 | SPR7 BA-low set; SPR5 fetch (unaligned) |
| 7 | 6 | SprPtr(6) | SprDma0(6); BA SPR6,7 | SPR6 fetch (aligned) |
| 8 | 7 | SprDma1(6) | SprDma2(6); BA SPR6,7 | SPR6 fetch (unaligned) |
| 9 | 8 | SprPtr(7) | SprDma0(7); BA SPR7 | SPR7 fetch (aligned) |
| 10 | 9 | SprDma1(7) | SprDma2(7); BA SPR7 | SPR7 fetch (unaligned); lineFinished sentinel |
| 11 | 10 | Refresh | (none) | (empty) |
| 12 | 11 | Refresh | BA matrix | badLine → BA-low for c-access |
| 13 | 12 | Refresh | BA matrix | drawBackground; mpos+=8 |
| 14 | 13 | Refresh | UpdateVc; BA matrix | drawBg; drawSprites; vc=vcBase; vmli=0; rc=0 (badLine); gfxVisible=true |
| 15 | 14 | Refresh | FetchC; BA matrix; ChkSprCrunch | drawBg; drawSprites |
| 16 | 15 | FetchG | FetchC; UpdateMcBase; BA matrix | drawBg; drawSprites; (cAccessShift) fetchBadLineData(0) |
| 17 | 16 | FetchG | FetchC; ChkBrdL1; BA matrix | checkHBorderLeft; drawGfx; drawSprites; mpos+=8 |
| 18 | 17 | FetchG | FetchC; ChkBrdL0; BA matrix | (similar to 16) |
| 19–54 | 18–53 | FetchG | FetchC; BA matrix | drawGfx; drawSprites; mpos+=8 |
| 55 | 54 | FetchG; ChkSprDma | (none); BA SPR0; ChkBrdR0 | sprite Y-compare / DMA-on; SPR0 BA-low; (!viceBrdrPhi2: ChkBrdR0) |
| 56 | 55 | Idle; ChkSprDma | ChkBrdR0; ChkSprExp; BA SPR0 | (viceBrdrPhi2: ChkBrdR0); spr-paint off; SPR1 BA-low; mpos+=8 |
| 57 | 56 | Idle | ChkBrdR1; BA SPR0,1 | (viceBrdrPhi2: ChkBrdR1); spr-paint on; rc++/gfxVisible; mpos+=8 |
| 58 | 57 | SprPtr(0); ChkSprDisp | SprDma0(0); UpdateRc; BA SPR0,1 | SPR0 paint enable; SPR0 readSpriteData; mpos+=8 |
| 59 | 58 | SprDma1(0) | SprDma2(0); BA SPR0,1,2 | drawBg; drawSprites; mpos+=8; SPR1 readSpriteData |
| 60 | 59 | SprPtr(1) | SprDma0(1); BA SPR1,2 | drawBg; drawSprites; SPR1 readSpriteData |
| 61 | 60 | SprDma1(1) | SprDma2(1); BA SPR1,2,3 | drawSprites; **SPR3 BA-low set** |
| 62 | 61 | SprDma1(2) | SprDma2(2); BA SPR2,3 | SPR2 readSpriteData |
| 63 | 62 | SprPtr(2) | SprDma0(2); BA SPR2,3,4 | **SPR4 BA-low set**; sprite reset; lastLine += SCAN_RATE; frame logic |

## Phi2 vs Phi1 partition (for the refactor)

**Phi1-natured events** (run BEFORE CPU's clk-N access, in current `chips.clock`):
- All sprite ptr / DMA fetches (s-access)
- All graphics fetches (g-access, c-access)
- BA-low setup for next cycle's bus
- vbeam increment / lastLine update at line transition
- Sprite Y-compare (decides DMA on/off)
- Refresh
- Sprite paint-enable / paint-disable flag updates

**Phi2-natured events** (should run AFTER CPU's clk-N access, in new `chipsPhi2`):
- ChkBrdR0 (cycle 56 Phi2 in VICE) — currently deferred via `viceBrdrPhi2`
- ChkBrdR1 (cycle 57 Phi2 in VICE) — currently deferred via `viceBrdrPhi2`
- ChkBrdL0 / ChkBrdL1 (cycles 17/18 Phi2)
- SSCol / SBCol IRQ fire (end-of-cycle, after sprite paint detection)
- Raster IRQ trigger evaluation
- ChkSprExp (sprite expansion flipflop, cycle 56 Phi2)
- ChkSprCrunch (sprite crunch detection, cycle 15 Phi2)

## Why the renumber alone is insufficient

The case dispatcher labels are already a consistent +1 mapping vs VICE. A
pure relabel changes nothing functionally. The visible bugs (irq-ack-vicii
slot 5 LDA SS-COL, the RMW dummy-write timing sensitivity, Krestage 3
mid-line writes) all come from CPU and VIC sharing a single chips.clock
hook per cycle — there is no slot for "VIC's end-of-cycle work that should
see the CPU's writes from this cycle."

The fix requires:
1. `ExtChip.clockPhi2(long cycles)` hook (default no-op).
2. CPU calls `chipsPhi2(cycles)` AFTER the memory access in fetchByte/writeByte.
3. Move Phi2-natured events from `clock()` to `clockPhi2()`.
4. Drop the `viceBrdrPhi2`, `viceD019Phi2 && !rmwInProgress` carve-outs and
   the SSCol fire defer — they become natural under the new ordering.

## Empirical evidence the carve-outs are load-bearing

- Removing `!rmwInProgress` from `viceD019Phi2`: INC RASTER row 00 grows a
  second dash (slot 4 + slot 5), 1 cell worse. Confirms RMW dummy-write
  timing IS sensitive to schedule order.
- Removing the SSCol fire defer (commit 580fa6e): STA SS-COL slot 4 fails.
- These demonstrate the compensations encode real timing information that
  the proper Phi2 split would carry naturally.
