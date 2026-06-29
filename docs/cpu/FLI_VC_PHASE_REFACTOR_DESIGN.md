# FLI vc/vmli-phase refactor — design from VICE source

Status: **DESIGN** (2026-06-29). Target: the K3 deer light-gray (`$f`) left-edge
strip — the last remaining deer symptom after the cbuf "gray should be blue" fix
(commit 7bf2067) and vborder2-35 (f1712d8). Root: JaC's `vcBase` is one-low in
the FLI late-badline, so the leading-cell g-fetch reads the wrong bitmap byte
(gbuf `$68` vs VICE `$c0`) → MC pairs select vbuf nibble `$f` = gray.

This doc is derived from a direct read of VICE `viciisc` and specifies the
VICE-faithful vc model JaC must adopt.

---

## 1. VICE's exact model (the ground truth)

### 1a. Per-cycle order (`vicii-cycle.c:vicii_cycle`)
Within one `vicii_cycle()` call:
1. Phi2 sprite fetch (previous cycle)
2. `next_vicii_cycle()` — advance `raster_cycle`, load `cycle_flags`
3. **Phi1 fetch** (`cycle_phi1_fetch`): at `FetchG` cycles → `vicii_fetch_graphics()`
4. `vicii_draw_cycle()` — draws pixels, reads `vbuf[dmli]`, `dmli++`
5. `check_badline()`
6. **UpdateVc** (`cycle_is_update_vc`)
7. **UpdateRc** (`cycle_is_update_rc`)
8. **Matrix fetch** (`vicii_fetch_matrix`) at `bad_line && cycle_may_fetch_c`

### 1b. Cycle table (PAL 6569, `vicii-chip-model.c`)
| raster_cycle | Phi | what | Vis |
|---|---|---|---|
| Phi2(14) | Phi2 | **UpdateVc** | — |
| Phi2(15) | Phi2 | **FetchC** (vbuf[vmli=0]) | None |
| Phi1(16) | Phi1 | **FetchG** (uses vc, then vmli++/vc++) | None |
| Phi2(16) | Phi2 | FetchC (vbuf[1]) | Vis(0) |
| Phi1(17) | Phi1 | FetchG | Vis(0) |
| Phi2(17) | Phi2 | FetchC (vbuf[2]) + **ChkBrdL1** (CSEL=1) | Vis(1) |
| Phi2(18) | Phi2 | FetchC (vbuf[3]) + **ChkBrdL0** (CSEL=0/38col) | Vis(2) |
| … | | FetchG/FetchC per cell | Vis(k) |
| Phi1(55) | Phi1 | last FetchG (vc++ → +40 total) | |
| Phi2(58) | Phi2 | **UpdateRc** (vcbase=vc if rc==7) | |

### 1c. State machine (`vicii-cycle.c` + `vicii-fetch.c`)
```c
// UpdateVc @ Phi2(14):
vc = vcbase;  vmli = 0;  if (bad_line) rc = 0;

// FetchG @ Phi1(16+k)  (vicii_fetch_graphics):
addr = g_fetch_addr(vc);      // BMM: (vc<<3)|rc | (d018&8)<<10   — USES vc
gbuf = fetch_phi1(addr);
vmli++;  vc &= 0x3ff;  vc++;  // POST-increment, AFTER the fetch

// FetchC @ Phi2(15+k)  (vicii_fetch_matrix):
if (prefetch_cycles) { vbuf[vmli]=0xff; cbuf[vmli]=ram_base_phi2[reg_pc]&0xf; }
else                 { vbuf[vmli]=fetch(v_fetch_addr(vc)); cbuf[vmli]=color_ram[vc]; }

// UpdateRc @ Phi2(58):
if (rc==7) { idle=1; vcbase=vc; }
if (!idle || bad_line) { rc=(rc+1)&7; idle=0; }
```

Key invariants:
- **vc is used PRE-increment in the g-fetch** (addr uses the current vc, then vc++).
  So the g-fetch for display column k uses `vc = vcbase + k`.
- **vmli is the single fetch index** for both FetchC (write `vbuf[vmli]`) and FetchG
  (read `vbuf[vmli]`); it advances once per FetchG.
- **dmli is a SEPARATE display index** (vicii-draw-cycle.c), advancing only in
  `vis_en && !vborder && !idle`, reset to 0 outside the visible window.
- **vcbase = vc captured at Phi2(58)** only when rc==7. In continuous FLI rc is
  forced to 0 every line (UpdateVc `if bad rc=0`), so **rc never reaches 7 →
  vcbase is NOT recaptured → it holds the value latched on the last pre-FLI line.**

---

## 2. JaC's current model and the divergence

`C64Screen.updateVicStateVic` (runs at TOP of `clock()`, before the fetch):
- cyc13: `vc=vcBase; vmli=0; vVmli=0; if bad rc=0`  (= VICE UpdateVc, one cyc早)
- cyc15-54: `if !idle { vc=(vc+1); vVmli++ }`  ← **PRE-increment, before the g-fetch**
- cyc57: `if rc==7 { idle=1; vcBase=vc }; if(!idle||bad){rc++}` (= UpdateRc)

g-fetch (`isFetchG` block): `bmmVc = (vc-1)` (`bmmVcFetchFix`) — undoes the
pre-increment so the address uses `vc-1 = vcbase+k` (matches VICE's pre-inc vc).

**Why it's render-correct yet wrong for FLI vcBase:**
- For a single cell the `-1` makes the address right (`vc-1 == VICE vc`).
- BUT the **vcBase capture at cyc57 uses the post-increment `vc`**, and JaC's
  pre-increment phase + the FLI rc-reset interaction make the captured vcBase
  land one-low vs VICE's Phi2(58) capture. In continuous FLI that one-low value
  is frozen for the whole effect → every leading-cell g-fetch reads `vcbase-1`
  worth of offset → wrong bitmap byte → the `$f` gray.
- The `-1` is LOAD-BEARING: removing it globally regresses fetchsplit (+53),
  border-mcbm (+6019) and corrupts the deer body, because those rely on the
  pre-increment+(-1) being self-consistent.

---

## 3. The proper fix: adopt VICE's post-increment vc

Make JaC's vc handling structurally identical to VICE, eliminating the `-1` hack:

### 3a. Move the vc/vmli increment from pre-fetch to the g-fetch (post-fetch)
- **Remove** the `vc=(vc+1); vVmli++` at `updateVicStateVic` cyc15-54.
- **In the g-fetch handler** (`isFetchG` block / `drawGraphicsVic`), after computing
  `fetchAddr` from the CURRENT `vc` (no `-1`), do `vc=(vc+1)&0x3ff; vVmli++`.
- This makes the g-fetch address use `vc=vcbase+k` directly (VICE-identical),
  and removes `bmmVcFetchFix` entirely.

### 3b. Keep UpdateVc (cyc13) and UpdateRc (cyc57) as-is, but verify the capture
- With vc now post-incremented in the g-fetch, the cyc57 `vcBase=vc` capture sees
  the same vc VICE captures at Phi2(58) (vcbase_prev + 40). Confirm by trace that
  the pre-FLI transition line latches the same vcBase as VICE (the one-low goes away).

### 3c. c-access write index
- VICE writes `vbuf[vmli]`/`cbuf[vmli]` in FetchC (Phi2), where `vmli` is the
  pre-FetchG value. With the increment moved to FetchG, JaC's `writeCAccess(vVmli)`
  + col0 backfill should map 1:1 to VICE's `vbuf[vmli]` — re-verify the col0 case
  (VICE's first FetchC at Phi2(15) writes vbuf[0]; JaC must too, not vbuf[1]).

### 3d. dmli (display index) stays independent
- `VicDrawCycle` already has the VICE-faithful self-incrementing `dmli`
  (`vis_en && !vborder && !idle`); leave it. The fix is purely the FETCH-side vc.

---

## 4. Why this fixes the deer without breaking the suite

- The leading FLI cell's g-fetch uses the CORRECT `vc=vcbase+k` (VICE-identical),
  reads the right bitmap byte (gbuf `$c0` not `$68`) → MC pairs `11/00` → cbuf/bg,
  not vbuf nibble `$f` → **no gray**.
- fetchsplit/border-mcbm: they currently need `-1` only because of the
  pre-increment; with post-increment they use `vc` directly = the same address,
  so they stay correct (this is the VICE model they already match at cell level).
- blackmail/fldscroll: unaffected fetch addresses (post-inc vc = pre-inc vc-1).
- The cbuf "gray should be blue" fix (prefetchRegPc) is orthogonal (color path).

---

## 5. Risks & gate

- **Highest-risk change in the VIC** — vc drives every BMM/text g-fetch. Any
  off-by-one in the move regresses the whole 99.95%-clean suite.
- Mitigation: flag `jac64.viceVcPostInc` default OFF. Gate = the FULL survey
  (53 static 8565 + 30 border/FLI) must be byte-identical OFF-vs-ON EXCEPT the
  deer light-gray improving; Lorenz CPU unaffected (CPU untouched).
- Validation trace: per-cycle vc/vmli/vcbase vs VICE EV-State across the pre-FLI
  → FLI transition (confirm vcBase no longer one-low) AND across fetchsplit
  (confirm the g-fetch address is unchanged from today's `vc-1`).

### Migration steps
1. Add the post-inc path behind `viceVcPostInc` (don't delete the old path yet).
2. Trace-verify g-fetch addresses identical to today on fetchsplit (static).
3. Trace-verify vcBase no longer one-low on the K3 FLI transition.
4. Full A/B survey; deer light-gray → background, zero regressions.
5. Flip default, remove `bmmVcFetchFix` + the pre-inc path, document.

## 6. EMPIRICAL (2026-06-29) — a WORKING fix exists, gating is the blocker

`-Djac64.fliVcCyc15Inc=true` (the existing flag that adds the skipped cyc15 vc++
when `vcIncSkippedWhileIdle`) **fixes the deer light-gray: x=39 146 → 21** (the
86 `$f` cells vanish). It directly confirms the root: the leading FLI cell's vc
is one-low because JaC skips the cyc15 vc++ (idle still true at the check) where
VICE's FetchG increments it.

**BUT ungated it regresses `screenpos` +3062** (a normal-badline text test): the
`vc++/vVmli++` shifts the text char/color index. So it MUST be gated to continuous
FLI only.

**Gating is the hard part** (verified, not yet solved):
- A `prevLineBadLine` (consecutive-badline) detector built on the `badLine` FIELD
  does NOT work: traced — `badLine` is **false for the deer inside
  `updateVicStateVic`** (the FLI badline is recognized via the fetch/ba_low path,
  not the `badLine` field). So the field-based FLI detector never triggers, and the
  gate keeps the deer at 146.
- `lateBadlineThisLine` is also 0 on the deer (traced earlier).
- So a reliable continuous-FLI signal must come from the FETCH-side badline
  recognition (the same signal that drives `prefetchCycles`/`fliBaLowFix`), not the
  `badLine` field — OR from a per-line `$D018`-changed (FLI signature) detector.

**Recommended concrete fix** = fliVcCyc15Inc + a fetch-side continuous-FLI gate:
1. Maintain a per-line "this line did an FLI prefetch / fetch-badline" flag set
   where `prefetchCycles` is armed (the FLI-recognized path), not the `badLine`
   field.
2. Roll it to `prevFliLine`; gate the cyc15 vc++ compensation on
   `vcIncSkippedWhileIdle && prevFliLine` so it fires on the deer (continuous FLI)
   but not screenpos (isolated badline).
3. Validate: deer x=39 → ~21; screenpos 0; fetchsplit/border-mcbm/blackmail/
   fldscroll/colorfetchbug unchanged.

This is lower-risk than the full §3 post-increment move (it's localized to the
already-existing fliVcCyc15Inc) and is the path to try FIRST.

### Smaller fallback if the full move is too risky
If §3 destabilizes the suite, the contained alternative is to fix ONLY the FLI
vcBase capture: detect continuous-FLI (consecutive badlines) and at the cyc57
capture, store the VICE-equivalent vcBase (the value matching a post-inc vc) so
the frozen FLI vcBase is correct while leaving the pre-inc+(-1) path for the body.
This is hackier but localized to the capture, not the per-cell fetch.
