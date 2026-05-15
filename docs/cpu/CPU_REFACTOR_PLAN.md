# CPU Sub-Cycle Refactor Plan — VICE-Alignment Push

## Goal
Close the +1888 cell-diff residual in pipeline-vs-VICE-references by
aligning JaC64's CPU/IRQ/CIA cycle accounting with VICE x64sc.

## Hypothesis
The VIC pipeline is byte-perfect to VICE per emission (proven via
4M+ trace events). The residual cell-diff reflects the CPU running
test programs at slightly different VIC cycles than VICE.

Concrete evidence (colorsplit):
- VICE writes $D021 split at rast=$106 cyc=36, cyc=56.
- JaC64 writes $D021 split at rast=$107 cyc=18, cyc=38.
- ~45-cycle per-frame drift.
- IRQ entry delay differs: VICE 3-4 cyc, JaC64 2-3 cyc per IRQ.
- ~22 raster IRQs/frame × 1 cyc = 22 cyc contribution.

## Reference path
- **VICE 6510 cycle-exact CPU**: `vice-emu/vice/src/6510dtvcore.c`
  (included via `mainc64cpu.c` for x64sc).
- **JaC64 CPU**: `com/dreamfabric/jac64/CPU.java` extends
  `MOS6510Core.java`. Currently passes `irq-ack-vicii` 48/48 —
  any refactor must preserve this.

## Phase plan

### Phase α — CPU trace parity (~1-2 days)
**Why first**: Identify the FIRST divergent CPU cycle between JaC64
and VICE. Avoids speculation.

**Steps**:
1. Add `EV-CpuStep` trace to BOTH emulators emitting per-instruction:
   - clk at instruction start.
   - PC.
   - opcode byte.
   - A/X/Y/SP/P regs.
   - IRQ pending state.
2. Use `JAC64_TRACE_FILE` env var (already exists in VICE).
3. Run on colorsplit, find first divergent instruction.
4. Determine: is divergence from (a) instruction cycle count,
   (b) IRQ entry timing, or (c) memory access?

**Exit criteria**: First N divergent CPU events catalogued.

### Phase β — IRQ entry sequence alignment (~2-3 days)
**Why**: Phase α likely shows IRQ entry timing as the first
divergence (per existing IRQ trace data).

**Steps**:
1. Port VICE's `DO_INTERRUPT` macro exactly (cycle-by-cycle):
   - 2 dummy reads (`LOAD_DUMMY(reg_pc); CLK_INC();` × 2).
   - PUSH PCH/PCL/P (3 × `CLK_INC()`).
   - LOAD vector lo/hi (2 × `CLK_INC()`).
   - Match VICE's irq_clk + INTERRUPT_DELAY computation.
2. Verify `irq-ack-vicii` still passes 48/48.
3. Verify CIA-related tests don't regress.

**Validation**: irq-ack-vicii 48/48 + colorsplit cell-diff trend down.

### Phase γ — CIA timer + raster-IRQ assertion timing (~1-2 days)
**Why**: Test programs use CIA timer + VIC raster-compare for IRQ.
The clk at which VIC sets IRQ line + the clk CPU samples it must match.

**Steps**:
1. Trace EV-CiaUnderflow + EV-VicSetIrq events.
2. Compare assertion clk between JaC64 and VICE.
3. Port VICE's exact assertion timing in `CIA.java` + `C64Screen.java`.

### Phase δ — RMW instruction cycle ordering (~2 days)
**Why**: colorsplit's stable raster uses `INC $D019 / INC $D012`
(RMW instructions). The clk at which the WRITE phase of an RMW
fires affects when $D019 IRQ flag is cleared.

**Steps**:
1. Audit JaC64 RMW path: read-cycle, dummy-write, write phases.
2. Compare to VICE: each RMW takes 6 cycles for absolute, with
   specific cycle accounting for the dummy write.
3. Align.

### Phase ε — BA-low cycle accounting (~1 day)
**Why**: When VIC sets BA low (sprite DMA, badline), CPU stalls
reads on the NEXT read access (not writes). Subtle timing affecting
IRQ entry too.

**Steps**:
1. Trace BA-low transitions + CPU stall behavior.
2. Verify VICE's CLK_INC-with-BA-check pattern matches JaC64's
   `waitForBus`.

### Phase ζ — Full suite re-baseline + cleanup (~1 day)
**Why**: After cycle-by-cycle alignment, re-run all tests + irq-ack
to verify net improvement.

**Exit criteria**: Pipeline cell-diff TOTAL ≤ legacy baseline 3210.

## Risk assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| irq-ack-vicii regression | **High** | Run after every CPU change. Phase β specifically validates. |
| CIA-test regression | Medium | Phase γ has explicit CIA-test validation. |
| Boot-time autostart breakage | Medium | TestRaster's detSysJump path is robust to timing changes. |
| Multi-week investment with no visible improvement | Medium | Phase α gates: only proceed if first divergence is clearly fixable. |

## Effort estimate

| Phase | Days | Risk | Cumulative |
|---|---|---|---|
| α — CPU trace parity | 2.0 | low | 2 |
| β — IRQ entry | 3.0 | **high** | 5 |
| γ — CIA + raster-IRQ | 2.0 | medium | 7 |
| δ — RMW cycle ordering | 2.0 | medium | 9 |
| ε — BA-low accounting | 1.0 | medium | 10 |
| ζ — Re-baseline + cleanup | 1.0 | low | **11 days** |

**Total: ~11 focused engineering days** (~2-3 calendar weeks with
overhead, similar to VIC pipeline port).

## Sequence

1. Read this plan.
2. Phase α: CPU trace parity setup.
3. Phase β: IRQ entry alignment (driven by Phase α findings).
4. Phase γ: CIA + raster IRQ.
5. Iterate δ/ε based on intermediate sweep results.
6. Phase ζ: baseline + commit.

## Per-phase gates (every phase must satisfy)
- Compile clean.
- irq-ack-vicii 48/48 (CRITICAL — don't break what works).
- 9-test suite sweep with cell-diff numbers reported.
- Flag-off path byte-identical (where applicable).
- Memory note updated.
- Commit landed (single-phase, co-authored).

## Acceptance metric
Pipeline `viceFullPipeline=true` cell-diff TOTAL ≤ legacy 3210
on the 9-test sweep, with irq-ack-vicii still passing 48/48.

Stretch: pipeline TOTAL near 0 (= VICE byte-perfect, the original
"fully VICE like" goal).
