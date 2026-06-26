# JaC64 vs VICE / real-HW conformance matrix (2026-06-26)

Measured with `tools/vice-compare/survey_drift.sh` (per-test cell-diff, JaC vs
VICE shot AND JaC vs reference image). `png_cell_diff.py` is palette- and
title-aligned. 8565 refs = VICE-derived; border/FLI refs = real C64 hardware.

## The headline

- **Static VIC test suite (53 tests, 8565 refs): 14 cells JaC-vs-VICE.**
  → `spriteenable5`=12, `rmwtest`=1, `bug`=1. Everything else 0.
- **CPU: passes the Lorenz suite (231 tests).** One *deliberate* divergence:
  JaC emulates the **new 8521 CIA**, VICE/Lorenz-Disk3 expect the old 6526.
- **Border/FLI suite (real-HW refs): JaC matches REAL HARDWARE, and on several
  tests BEATS VICE.** e.g. `blackmail-ee` JaC-vs-REF=0 but JaC-vs-VICE=306 —
  VICE is the inaccurate one there; `border-250/251/252` JaC-vs-REF=0,
  vs-VICE=14.

### Critical reframe
For FLI/border, **"100% vs VICE" is the wrong target** — VICE is *less* accurate
than JaC vs real silicon on those. The correct standard is **real hardware**.

## FIXES LANDED (2026-06-26)
- **`vborder2-35`: 40 → 0** (clean vs real-HW AND VICE). Ported VICE's
  `check_border_l` cyc-17/18 re-latch (`vicii-cycle.c:222`,
  `vicii.vborder = vicii.set_vborder`) that JaC had dropped in "Phase K iter#7".
  Flag `jac64.viceVBorderLatchL` default true. **Zero regressions** across the
  full static + border survey (border-250/251/252, hvborder, fldscroll,
  vborder2-21/22/36/63/64, vborder-32/33, sprite suite all unchanged).

## Remaining genuine bugs (vs the BEST available reference)

| test | cells | where | class |
|------|-------|-------|-------|
| `vborder2-35` | 40 | one raster line (y≈231), full width, bottom flop | V-border flop off-by-one-line (only the -35 variant; -21/-63 clean) |
| `spriteenable5` | 12 | sprite-3 first line, cols 26-29 | sprite first-line display-enable on `INC $D007` cycle |
| `border-bm-ysh` | 8 | — | bitmap+yscroll border edge |
| `border-bm-ysh2` | 8 | — | same family |
| `border-bm-idle` | 8 (REF) | — | idle-state border (VICE also off: 39 vs-VICE) |
| `rmwtest` | 1 | — | single cell (possibly capture edge) |
| `bug` | 1 | — | single cell |

**Total genuine remaining ≈ 78 cells** across the whole measured suite, all
sub-cycle / sub-line micro-edges in **border-flop** and **sprite-DMA** timing.

## Where JaC already EXCEEDS VICE (do NOT "fix" toward VICE)
`blackmail-ee` (0 vs REF / 306 vs VICE), `blackmail-fixed` (0/285),
`border-250/251/252` (0/14), `border-mcbm` (0/4), `border-bm-idle` (8/39).
The vs-VICE deltas here are VICE's inaccuracy, not JaC's.

## Confirmed clean vs VICE (0 cells), formerly-buggy now fixed
screenpos, colorsplit, modesplit, fetchsplit, greydot, vicii_reg_timing(×3),
ss-pri/-mc/-exp variants, ss-hires/-mc/color variants, ss-xpos, test1,
spriteenable1-4, videomode-v/w/x/y/z/1/2, rasterirq_hold, hvborder1/2,
d017-54/57, fldscroll-20/21/22/29/2A/2B, spritecrunch-3b/3c/3d,
spritecrunch2-07/08/09, vborder-32/33, vborder2-21/63.

## Not covered by static survey (separate efforts)
- **K3 picture-mover (deer FLI)**: blocked on demo-phase sync — JaC slides the
  deer where VICE holds it. Locus pinned to `bmmVcFetchFix`/vcBase
  (see `docs/cpu/CYCLE_STEPPED_CPU_PHASE_PLAN.md`). Needs aligned reference.
- **CIA model selectability**: a feature (8521 vs 6526 switch) to pass both the
  new-CIA tests and Lorenz Disk3, rather than a bug.

## Cycle-stepping write-phase: PROVEN already correct (2026-06-26)
Tested a VICE-faithful write phase (`jac64.vicIoWritePhi2`: clock the VIC's
Phi1(N) BEFORE applying the CPU's $D0xx Phi2 write, so the write is first seen at
N+1 — VICE's `STORE` → `CLK_INC` → `vicii_cycle` order). Full-suite A/B:
**ZERO change** — rmwtest still 1, spriteenable5 still 12, no regressions
anywhere. ⇒ **JaC's CPU↔VIC write-phase already matches VICE.** Confirmed by
trace: on spriteenable5 JaC's cyc-58 sprite-display check already reads the
post-`INC $d007` value and suppresses the first line correctly. The flag was
reverted (no-op). **The remaining residuals are NOT cycle-stepping/phase bugs.**

## Remainder classified (after the vborder2-35 fix)
The genuine gap dropped **78 → 38 cells**. The rest splits into two classes,
neither a simple copy-VICE:

1. **VIC sub-feature micro-details (NOT cycle-stepping — phase proven correct
   above):**
   - `spriteenable5` (12) — the cyc-58 display check already suppresses the
     first line correctly; the residual is the sprite **mc/data** behaviour while
     the first line is display-suppressed (sprite-sequencer detail at the sprite
     edges, cols 26-29, rast 34-38). Needs a VICE sprite-mc port, not phase work.
   - `rmwtest` (1), `bug` (1) — single-cell `$D0xx` RMW edges (write-phase ruled
     out by the vicIoWritePhi2 A/B; likely double-write/ack micro-edges or
     capture artifacts).

2. **Real-HW divergences where VICE is ALSO wrong** (copying VICE is the wrong
   direction — it would move JaC further from silicon):
   - `border-bm-ysh`/`ysh2` (8 vs REF, but 23 vs VICE), `border-bm-idle`
     (8 vs REF, 39 vs VICE). JaC is already CLOSER to real-HW than VICE here.

## Verdict on "are we 100%?"
- **vs VICE**: static = 99.997% (14/~424k cells); FLI/border = JaC meets or
  exceeds VICE.
- **vs real hardware**: ≈ 78 micro-edge cells remain (border-flop + sprite-DMA),
  the genuine last mile. Each carries regression risk against the now-clean
  broad suite, so each needs isolated trace + full A/B before landing.
