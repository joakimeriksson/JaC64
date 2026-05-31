# Phase 10.H: Direct VICE comparison reveals per-iteration cycle drift

## Method

Ran VICE x64sc with `-warp -limitcycles 50000000 -autostartprgmode 1`
and trace patches. Captured `EV-RdD019` events during SS-COL test4
(pc=$b5b in VICE, $b5e in JaC64 — same logical instruction).

Captured matching JaC64 trace with `jac64.traceVicCycle=true`.

## Empirical comparison (SS-COL test4 LDA $D019, 6 slots = 6 frames)

| iter | VICE_clk | VICE_cyc | VICE_ret | JaC64_clk | JaC64_cyc | JaC64_ret | delta | result |
|------|----------|----------|----------|-----------|-----------|-----------|-------|--------|
| 1    | 3955561  | 43       | $70      | 7867106   | 44        | $70       | +1    | both `.` |
| 2    | 3975218  | 44       | $70      | 7886763   | 45        | $f4       | +1    | **VICE `.`, JaC64 `d` ← FAIL** |
| 3    | 3994875  | 45       | $f4      | 7906419   | 45        | $f4       | 0     | both `d` |
| 4    | 4014532  | 46       | $f4      | 7926076   | 46        | $f4       | 0     | both `d` |
| 5    | 4034189  | 47       | $f4      | 7945734   | 48        | $f4       | +1    | both `d` |
| 6    | 4053846  | 48       | $f4      | 7965390   | 48        | $f4       | 0     | both `d` |

## Inter-iteration cycle deltas

```
            iter1→2  iter2→3  iter3→4  iter4→5  iter5→6  total
VICE:       19657    19657    19657    19657    19657    98285
JaC64:      19657    19656    19657    19658    19656    98284
```

VICE is **consistent at 19657 cyc/iter** (= 1 PAL frame + 1 cycle).
JaC64 **alternates** 19656/19657/19658, total 1 cyc short over 5 iter.

PAL frame = 19656 cyc. So VICE adds exactly 1 extra cyc per iteration
inside the test/handler/irq_reset_frame chain. JaC64 inconsistently
adds 0, 1, or 2 cycles — averaging to almost the same total but
producing different per-iteration cyc-within-line.

## Why slot 1 (= iter 2) fails

iter 1 already at +1 cyc-within-line (JaC64 cyc 44 vs VICE cyc 43).
iter 2 at +1 cyc-within-line (JaC64 cyc 45 vs VICE cyc 44).
SSCol fire at cyc 45. JaC64's LDA at cyc 45 = AT fire = sees $f4.
VICE's LDA at cyc 44 = BEFORE fire = sees $70 → cell = $00.

## Why other slots happen to pass

iter 3 LDA at cyc 45 in BOTH (different paths, same result coincidence).
iter 4 LDA at cyc 46 in both.
iter 6 LDA at cyc 48 in both.
iter 5 LDA at cyc 48 (JaC64) vs cyc 47 (VICE) — both POST-fire so
both read $f4 → both `d`. Failure invisible because both are post-fire.

## Root cause is NOT what Phase 10.D thought

Phase 10.D documented "JMP loop mod-3 alignment differs at line-69 IRQ
fire." With Phi2, autostart shift no longer affects the test — so JMP
mod-3 alignment is no longer the dominant factor. The actual bug:

**JaC64's per-iteration handler/test/irq_reset_frame chain produces
inconsistent cycle counts (19656/19657/19658) where VICE is consistent
(19657).** Per-instruction cycles match (Phase 9.1), so divergence
must be in:

1. A branch with different page-cross behavior across iterations.
2. An addressing mode (LDA ($FE),Y) where page-cross detection differs.
3. JSR/RTS cycle accounting in some specific calling pattern.
4. Sprite DMA / BA-low timing inside the test frame (sprites enabled
   for SS-COL testset; BA-low cycles depend on sprite Y position
   intersecting current line).
5. CIA timer interaction (each test_setup writes $D012, which may
   trigger CIA-driven side effects).

## Path to slot-1 fix

Find the instruction that takes 0 cycles in JaC64 but should take 1
cycle (per VICE). Likely candidates:

- **`lda ($fe),y` page-cross**: if $FE points to address X and X+Y crosses
  a page, real 6502 takes +1 cyc. JaC64 vs VICE should match (per 9.1)
  but possibly tested only for non-page-cross case.
- **`bne` taken**: 3 cyc no-page-cross, 4 cyc page-cross. JaC64's
  branch cycles should match.
- **BA-low for sprite DMA**: VICE may stall CPU 1 extra cyc per line
  when sprites are active that JaC64 doesn't.
- **CIA timer underflow during the iteration**: VICE may signal
  underflow 1 cyc earlier/later, affecting downstream timing.

## Suggested next steps

1. **Capture full instruction trace** of one iteration in both emulators,
   diff cycle-by-cycle, find the divergent instruction.
2. **Run cycle-counted micro-tests** (Lorenz cycle tests) to catch any
   per-instruction or per-addressing-mode timing bugs.
3. **Check sprite DMA cycle accounting** — sprites are enabled during
   SS-COL. BA-low may differ between JaC64 and VICE in subtle ways.
