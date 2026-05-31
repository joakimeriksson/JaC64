# JaC64 ↔ VICE viciisc PAL cycle alignment

## Mapping (case → VICE cycle)

JaC64 has 63 cases (0-62), VICE has 63 cycles (1-63). Indexing aligns:

  **JaC64 case N = VICE PAL cycle (N+1)**

Verified by anchor points:
- JaC64 case 0 does `vbeam++` (start of new line) and SprDma0(3) — matches VICE Phi1(1)+Phi2(1)
- JaC64 case 62 does `lastLine += SCAN_RATE` (line wrap) — matches VICE Phi2(63)

## c-access / g-access timing divergence

This is the actual root cause of the **`fetchsplit.prg` BBB vs BBBB** symptom.

### VICE (gold reference)
| VICE cycle | Phi1 action | Phi2 action |
|---|---|---|
| 14 | Refresh BaFetch | UpdateVc |
| 15 | Refresh BaFetch | **FetchC col 0** |
| 16 | **FetchG col 0** | Vis(0), **FetchC col 1** |
| 17 | Vis(0), FetchG col 1 | Vis(1), FetchC col 2, ChkBrdL1 |
| ... | ... | ... |
| 54 | Vis(37), FetchG col 37 | Vis(38), FetchC col 38 |
| 55 | Vis(38), FetchG col 38 | Vis(39) |
| 56 | Idle | ChkBrdR0 |

So in VICE: **col K c-access is at Phi2(15+K), col K g-access is at Phi1(16+K)**.

### JaC64 (current)
| JaC64 case | Action |
|---|---|
| 13 | vc=vcBase, vmli=0, rc=0-if-badLine |
| 14 | drawBackground+drawSprites (no c-access) |
| 15 | drawBackground+drawSprites (no c-access) |
| 16 | **fetchBadLineData(0)** + **drawGraphics(0)** + drawSprites |
| 17 | fetchBadLineData(1) + drawGraphics(1) + drawSprites |
| 18..53 | fetchBadLineData(K-15) + drawGraphics(K-15) |
| 54 | fetchBadLineData(38) + drawGraphics(38) + sprite-dma-on |
| 55 | fetchBadLineData(39) + drawGraphics(39) + checkHBorderRight |

So in JaC64: **col K c-access at case (16+K) = VICE cycle (17+K)**, c-access AND g-access in the SAME case.

### The 2-cycle offset

  | | col 0 c-access | col 0 g-access |
  |---|---|---|
  | VICE | cycle 15 (Phi2) | cycle 16 (Phi1) |
  | JaC64 | case 16 = cycle 17 | case 16 = cycle 17 |

JaC64's column 0 c-access is **2 cycles late** vs VICE.

This means: a CPU write at cycle T affecting `videoMatrix` (e.g. `$D018` or `$DD00` bank change):
- VICE: visible to col K c-access at cycle 15+K. Effect at K = T-14.
- JaC64: visible to col K fetch at cycle 17+K. Effect at K = T-16.

The bank effect appears **2 columns earlier in JaC64** than in VICE.

For `fetchsplit.prg`'s first `STA $DD00` at line0a_do cycle 17 (relative
to line start, which itself depends on entry timing):
- VICE expects effect at col 17-14=3 → first 3 cols are OLD bank, col 3+ are NEW
  Actual reference shows BBBB (4 B's) so the demo's actual write cycle within
  the raster is presumably +1 from my naïve count, putting the boundary at col 4.
- JaC64 currently puts the effect at col 17-16=1 → first 1 col is OLD, col 1+ NEW
  → 1 B then 9 A's. But user reports BBB (3 B's). So the absolute cycle of the
  write also shifts in JaC64 (because JaC64's IRQ entry / earlier code timing
  differs from VICE — see flicker below). Net effect: 1 column earlier than VICE.

## Proposed fix

Two structural options, in priority order:

### Option 1: Move c-access 1 cycle earlier (case 15-54)
- Move `fetchBadLineData(vmli)` from cases 16,17,default,54,55 → cases 15,16,default(17-53),53,54
- Pixel emit (drawGraphics) stays at cases 16-55
- This separates Phi2 c-access (1 cycle earlier) from Phi1 g-access + emit (current)
- Closes the 1-cycle gap between c-access and g-access, matching VICE's Phi2(N) → Phi1(N+1) pattern

### Option 2: Latch videoMatrix 2 cycles
- Keep the current case structure
- Add a 2-cycle delay buffer for `videoMatrix` reads inside fetchBadLineData
- Simpler code change but doesn't fix the underlying mis-alignment for other cycle-sensitive code (sprite fetches etc.)

**Recommendation: Option 1.** It's a structural fix that brings JaC64's
case dispatcher into proper alignment with VICE's cycle table.

### Risks of Option 1
- Cases 14 and 15 currently do drawBackground+drawSprites. Adding c-access there
  is additive (drawBackground stays).
- Case 55 currently does c-access AND drawGraphics for col 39 + checkHBorderRight.
  Splitting these requires care.
- Sprite DMA timing on case 54 (`sprite-dma-on` y-compare) might shift.
- Many demos work with current layout — regression risk on `lets_scroll_it`,
  Krestage 3 banner stripes, intro runners.

## Flicker = independent IRQ-entry timing variance

The fetchsplit test uses the **double-IRQ stable raster technique**
(`startup.asm:irq_stable -> irq_stable2_pal`). JaC64's IRQ entry is
nominally 7 cycles (matches real hardware) but the per-frame variance
suggests one of:

1. **`$D012` read returning slightly off vbeam** at the read cycle
2. **BA stall during IRQ vector fetch** missing or extra cycle on bad-lines
3. **Per-opcode cycle count off by 1** for some specific instruction in
   the path between $D012 read inside IRQ and the test_perform jsr

This is independent of the c-access alignment bug. Diagnose by adding a
trace that prints `cpu.cycles` at the entry of `irq_stable2_pal` for
several frames and checking variance.

## Next session tasks

The `irq-ack-vicii.prg` test ROM at
`/Users/joakimeriksson/work/VICE-testprogs/interrupts/irq-ackn-bug/`
exposes the underlying bug **isolated from VIC bank switching**:
JaC64 fails this test (red border) while VICE passes (blue/green).

Comparison of `$D019` handlers:

VICE (vicii-mem.c:227, 4 lines):
```c
vicii.irq_status &= ~((value & 0xf) | 0x80);
vicii_irq_set_line();
```

JaC64 (C64Screen.java:1357, ~30 lines):
- Special-cases `cpu.isRmwDummyWrite()` to handle RMW INC $D019 etc.
- Calls `handleLateRasterIrqAcknowledge(boolean fromDummy)` for the
  raster IRQ ACK quirk.
- Multiple paths through the handler.

**Hypothesis**: JaC64 papered over the IRQ-ack RMW timing in the
register handler instead of in the CPU cycle simulation. That works
for some patterns but fails the focused IRQ-ACK test. VICE's CPU
cycle simulation makes the correct ACK timing emerge naturally.

**Recommended approach**:
1. Run `irq-ack-vicii.prg` continuously in JaC64 + VICE side-by-side
   while iterating. Test pass/fail is instant (border green vs red).
2. Simplify JaC64's `$D019` handler to match VICE's 4 lines.
3. Move the RMW dummy-write quirk into the CPU core
   (MOS6510Core.java) where each instruction's exact write timing is
   known per-cycle.
4. Verify `irq-ack-vicii.prg` turns green.
5. Re-test fetchsplit.prg and Krestage 3 — if the IRQ-ack timing was
   the root cause, fetchsplit should now show BBBB and Krestage 3
   scroll-in colors should match VICE.
