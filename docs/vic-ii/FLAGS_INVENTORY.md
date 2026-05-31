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
| `cAccessShift` | removed | c-access is now structurally aligned in the case dispatcher | VICE Phi2(15+K) | done |
| `cAccessPhi2` | removed | no longer needed as an opt-in c-access experiment | VICE Phi2(15+K) | done |
| `gAccessShift` | removed | g-access uses the lagged fetch base structurally | VICE Phi1(16+K) | done |
| `dd00BankLatch` | removed | $DD00 bank visibility is handled by 252535-01 glue state | local x64sc custom glue | done |
| `dd00BankDelayCycles` | removed | fixed delay knob replaced by VICE custom-glue alarm state | local x64sc custom glue | done |
| `d018Latch` | removed | $D018 updates video memory bases immediately | matches VICE | done |
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

Status after the fetchsplit refactor:
- c-access no longer uses `cAccessShift` / `cAccessPhi2`; badline fetches
  are placed directly at the VICE PAL column slots.
- g-access no longer uses `gAccessShift`; bitmap/text fetch address selection
  samples the lagged bank and `$D018` state structurally.
- `$D011` BMM address selection uses a two-stage delayed copy to match the
  local x64sc 8565 fetchsplit boundary.
- fetchsplit now matches the live local x64sc target at
  `Total cell-diffs: 0/8000 cells = 0.00%`.

### §2 — Mid-line $DD00 / $D018 bank change → VICE-immediate semantics

`dd00BankLatch`, `dd00BankDelayCycles`, and `d018Latch` are removed.
`$D018` calls `setVideoMem()` synchronously. `$DD00` / `$DD02` update the
VIC-visible bank through the VICE 252535-01 custom glue state used by local
x64sc defaults.

Reference: VICE `c64gluelogic.c:perform_vbank_switch` for PLA glue
(immediate); only DISCRETE glue type uses +1 alarm — JaC64 should default
to PLA for x64sc compatibility.

### §3 — Switch default renderer to drawGraphicsVice (`viceGfx=true`)

The `useViceGfx` flag activates a VICE-style cycle-driven gfx pipeline
that mirrors `viciisc/vicii-draw-cycle.c`. Currently default off because
the legacy `drawGraphics` is fast and works for most cases. After §1+§2,
flip `viceGfx=true` default to get full per-cycle pixel pipeline match.

### §4 — Remove "wrong direction" flags after verification

Flags removed by the fetchsplit refactor:
- `cAccessShift`
- `cAccessPhi2`
- `gAccessShift`
- `dd00BankLatch`
- `dd00BankDelayCycles`
- `d018Latch`

Flags currently default-off that match VICE behavior should become
default-on:
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
