# JaC64 VIC-II + CPU timing flags inventory (2026-05-09)

Comprehensive catalog of all `jac64.*` system properties that affect
emulation timing. Used to plan a structural VICE-port refactor that
removes "compensating wrong" flags in favor of correct, structural
behavior.

Generated from grep of `Boolean.getBoolean`, `Integer.getInteger`,
`Long.getLong`, and `System.getProperty` across `C64Screen.java`,
`CPU.java`, `MOS6510Core.java`.

## Active behavior flags (default on/off matters)

### CPU timing — MOS6510Core.java
| Flag | Default | What it does | VICE behavior | Refactor target |
|---|---|---|---|---|
| `viceCycleAccessPhase` | **on** (CPU.java) | CPU performs memory access at current cycle THEN CLK_INC, matching VICE x64sc | matches VICE | keep on permanently; remove flag |
| `viceMem` | **on** (CPU.java) | check_ba (with prev cycle BA) → CLK_INC ordering | matches VICE | keep on permanently; remove flag |
| `viceMemBus` | off (CPU.java) | Phi1/Phi2 split for non-IO writes (kept off — broke Krestage 3 banner) | partially matches | INVESTIGATE; needs to align before removal |
| `viceBodyAccessPhase` | off (CPU.java) | Opcode body LOAD/STORE at current clock | matches VICE for some opcodes | merge into viceCycleAccessPhase |
| `viceRtiNoIrqDelay` | **on** (MOS6510Core.java) | RTI does not have 1-instruction IRQ delay | matches VICE | keep on permanently; remove flag |
| `viceSeiIrqWindow` | **on** (MOS6510Core.java) | SEI grants 1-boundary IRQ window | matches VICE | keep on permanently; remove flag |
| `viceIrqDelayCounter` | off (MOS6510Core.java) | Use VICE-style delay counter for IRQ recognition | partial match | port fully + flip default |
| `irqAssertPreIncrement` | off (MOS6510Core.java) | Diagnostic only — IRQ assert based on pre-increment cycle | mismatch | remove; diagnostic |
| `phaseAIrqLatch` | off (MOS6510Core.java) | Phase-A IRQ latching | unknown | remove if unused |

### VIC-II timing — C64Screen.java
| Flag | Default | What it does | VICE behavior | Refactor target |
|---|---|---|---|---|
| `cAccessShift` | **on** | c-access for col K at JaC64 case (15+K) instead of (16+K) | VICE Phi2(15+K) | structural fix: move c-access into clockPhi2 hook properly |
| `cAccessPhi2` | off | c-access at JaC64 vc=14+K (= VICE cycle 15+K Phi2) | matches VICE | structural — see refactor §1 |
| `gAccessShift` | off | bitmap g-access uses lagged vicBaseFetchDelay | partial | structural — see refactor §1 |
| `dd00BankLatch` | **on** | $DD00 bank change uses deferred commit | VICE: immediate (PLA glue) — but JaC64 default delay=2 compensates for case-N=VICE-N+1 mismatch | structural — see refactor §2 |
| `dd00BankDelayCycles` | **2** | Cycles before $DD00 commit fires | VICE: 0 (PLA) or 1 (discrete glue) | remove after structural fix |
| `d018Latch` | **on** | $D018 write defers screen base / charset by 1 cycle | VICE: immediate | structural — see refactor §2 |
| `viceFetchDelay` | off | fetchBadLineData uses lagged videoMatrix/charMemoryIndex | partial | merge into structural fix |
| `viceRenderDelay` | off | drawGraphics renders col vmli-1 instead of vmli (1-cycle pipeline) | partial | merge |
| `viceCollisionIrqDelay` | **on** | SSCol/SBCol IRQ visibility delayed 1 Phi2 | matches VICE | keep on permanently; remove flag |
| `viceLineAlign` | unknown default | Line transition phase alignment | matches VICE | verify and lock in |
| `viceRasterGuard` | unknown default | Raster IRQ guard | unknown | check |
| `colorDelay` | off | 1-cycle delayed apply for color register writes | matches VICE | flip default on after verification |
| `viceGfx` | off | Use VICE-style cycle-driven gfx pipeline (drawGraphicsVice) | matches VICE | structural — see refactor §3 |
| `newSprites` | off | Use V2 sprite pipeline (RasterChangeQueue + SpriteSequencer) | matches VICE for probe | flip default on once Let's Scroll It verified |
| `colorSet` | **3** | Default to WinVICE-ripped palette | matches VICE x64sc visible | keep |

### Disable / debug flags (don't refactor)
| Flag | Purpose |
|---|---|
| `disableRepeatBug` | sprite repeat-bug bypass |
| `enableProbeAssist` | Krestage 3 probe assist |
| `forceRcReset` | force rc reset on bad lines |
| `spriteDisableMask` | mask out specific sprites |
| `debugProbe` | trace probe behavior |
| `writeRasterXOffset` | sprite raster_x compensation knob |

### Trace flags (no behavior change)
- `traceVicCycle{,Start,End,File}`, `traceVicIrq`, `traceDD00`, `traceFli`,
  `traceColorWrites`, `traceSprFetch`, `traceSprFirstPaint`,
  `traceSpriteRepeat`, `traceBitmap`, `tracePcCycles`, `tracePcStart`,
  `tracePcEnd`, `tracePcFile`, `traceIrqService{,Start,End}`, `irqTrace`,
  `irqTraceFile`, `traceScreenWrites{,Start,End,File}`, `traceSprPtrWrites`,
  `baTrace{,From,To,File}`, `execTrace{From,To,File}`

## Structural refactor plan

### §1 — c-access / g-access alignment with VICE chip-model.c table

VICE PAL fetch table places:
- `Phi2(15+K)` FetchC for col K
- `Phi1(16+K)` FetchG for col K

JaC64 case-dispatcher convention: case N = VICE cycle N+1.

Currently:
- `cAccessShift=true` puts c-access at JaC64 case (15+K) = VICE cycle (16+K)
  Phi1+Phi2 — **1 VICE cycle late** for c-access (should be Phi2(15+K)).
- g-access happens in case body at (16+K) = VICE cycle (17+K) — **1 VICE
  cycle late** for g-access (should be Phi1(16+K)).

The default `dd00BankDelayCycles=2` is a "compensating wrong" — defers
$DD00 effect by 2 cycles to align with the late c-access boundary. Net
result: bank-effect boundary col matches VICE FOR LINE0A_DO writes BUT
introduces row-dependent diffs on other test sub-routines.

**Refactor target:**
1. Move c-access (`fetchBadLineData`) from case-body to a new Phi2 hook that
   fires at vc=14+K. Note: clockPhi2 doesn't fire during BA-low stalls
   (badline c-access) — needs a different mechanism (run inside `clock()`
   at the case-body Phi2-equivalent point AFTER current case work).
2. Move g-access from case (16+K) to case (15+K) so col K's bitmap fetch
   lands at JaC64 case 15+K = VICE cycle 16+K Phi1.
3. After both shifts, **remove $DD00 latch** (= flip `dd00BankLatch=false`)
   and **remove $D018 latch** (= `d018Latch=false`), matching VICE
   immediate behavior.
4. Verify on irq-ack-vicii (must stay 48/48), cia-timer (byte-identical),
   fetchsplit (target: down to <100 cell-diffs).

Risk: row-dependent regression on tests that previously matched. Need
sub-routine-by-sub-routine validation.

### §2 — Mid-line $DD00 / $D018 bank change → VICE-immediate semantics

After §1 lands, `dd00BankLatch` and `d018Latch` should be unnecessary
since the underlying c/g-access timing is now correct. Remove these flags
and the deferred-commit machinery (`cia2BankPending`, `vicMemPending`,
related fields). Both writes should call `setVideoMem()` synchronously.

Reference: VICE `c64gluelogic.c:perform_vbank_switch` for PLA glue
(immediate); only DISCRETE glue type uses +1 alarm — JaC64 should default
to PLA for x64sc compatibility.

### §3 — Switch default renderer to drawGraphicsVice (`viceGfx=true`)

The `useViceGfx` flag activates a VICE-style cycle-driven gfx pipeline
that mirrors `viciisc/vicii-draw-cycle.c`. Currently default off because
the legacy `drawGraphics` is fast and works for most cases. After §1+§2,
flip `viceGfx=true` default to get full per-cycle pixel pipeline match.

### §4 — Remove "wrong direction" flags after verification

Flags currently default-on that VICE source explicitly does NOT do should
become default-off + flag removed once a structural alternative is in
place:
- `dd00BankLatch=true` → false (after §1+§2)
- `d018Latch=true` → false (after §1+§2)
- `dd00BankDelayCycles=2` → 0 (after §1+§2)

Flags currently default-off that match VICE behavior should become
default-on:
- `cAccessPhi2=true` (after §1 implementation)
- `gAccessShift=true` (after §1)
- `viceFetchDelay=true` (after §1)
- `viceRenderDelay=true` (after §3)
- `colorDelay=true` (after verification)
- `viceGfx=true` (after §3)
- `newSprites=true` (after Let's Scroll It verification)

### Estimated effort

- §1 c/g-access structural shift: 4-8 hours, multi-test verification
- §2 $DD00 / $D018 immediate: 1-2 hours after §1
- §3 viceGfx renderer default: 2-4 hours verification across demos
- §4 flag cleanup: 1-2 hours

Total: ~1-2 weeks of focused work to fully port the VIC-II to VICE-x64sc
behavior. Result: fetchsplit at ~100% match + Krestage 3 side-panel
+ cleaner codebase with fewer "compensating wrong" knobs.
