# Per-cycle trace findings

First run on `vicii_reg_timing.prg` with `-Djac64.traceVicCycle=true`,
window 12_000_000…12_063_000 (~1000 cycles, 16 raster lines).

## Sprite event alignment (JaC64 case N vs VICE cycle M)

| Event       | JaC64 case | VICE cycle | Diff (JaC64 - VICE) |
|-------------|-----------:|-----------:|--------------------:|
| SPR3Read    |          0 |          1 |                  -1 |
| SPR4Read    |          2 |          3 |                  -1 |
| SPR5Read    |          4 |          5 |                  -1 |
| SPR6Read    |          6 |          7 |                  -1 |
| SPR7Read    |          8 |          9 |                  -1 |
| SPR0Read    |         57 |         58 |                  -1 |
| SPR1Read    |         59 |         60 |                  -1 |
| SPR2Read    |         61 |         62 |                  -1 |
| BA-SPR0     |         54 |         55 |                  -1 |
| BA-SPR1     |         56 |         57 |                  -1 |
| BA-SPR4     |         62 |         63 |                  -1 |
| BA-SPR5     |          1 |          2 |                  -1 |
| BA-SPR6     |          3 |          4 |                  -1 |
| BA-SPR7     |          5 |          6 |                  -1 |
| BA-SPR2     |         57 |         59 |                  -2 |
| BA-SPR3     |         61 |         61 |                   0 |

## Border check alignment (CSEL=1 / 40-col)

| Event       | JaC64 case | VICE cycle | Diff |
|-------------|-----------:|-----------:|-----:|
| ChkBrdL1    |         16 |         17 |   -1 |
| ChkBrdR1    |         56 |         57 |   -1 |

## Bad-line / c-access (cAccessShift=false default)

| Event       | JaC64 case | VICE cycle | Diff |
|-------------|-----------:|-----------:|-----:|
| BA-BADLINE-C11 |      11 |      ~12 |   -1 |
| FetchC col 0|         16 |         15 |   +1 |
| FetchC col 1|         17 |         16 |   +1 |
| ...         |         18+|         17+|   +1 |

Note: JaC64's c-access lags VICE by 1 cycle (col 0 at case 16, but
VICE fetches col 0 at Phi2(15)). With `-Djac64.cAccessShift=true`,
case 15 fetches col 0 — which would align case 15 → VICE cycle 15.

## Conclusion: the dispatcher offset is NOT uniform

- **Sprite events**: JaC64 case N corresponds to VICE cycle (N+1).
  All 16 measured events align under this mapping.
- **Border events**: same offset (case N = cycle N+1). Aligned.
- **C-access (default)**: case N corresponds to VICE cycle (N-1).
  Reversed offset! JaC64 fetches col 0 one cycle LATER than VICE.

This mismatch explains why turning on `cAccessShift` aligns
c-access with the rest, and why default-off c-access is "1 cycle
late vs everything else."

## The two specific BA-low anomalies

1. **SPR2 BA-low at case 57** (= VICE cycle 58)
   VICE's BaSpr3(0,1,2) starts at Phi1(59). JaC64 fires BA-low
   for sprite 2 ONE CYCLE EARLIER than VICE (case 57 = VICE cycle
   58, but VICE wants cycle 59).

   File: `C64Screen.java:2225` (case 57: `setBaLowUntil(... "SPR2")`)
   Should move to: case 58.

2. **SPR3 BA-low at case 61** (= VICE cycle 62)
   VICE's BaSpr3(1,2,3) starts at Phi1(61). JaC64 fires BA-low
   for sprite 3 ONE CYCLE LATER than VICE (case 61 = VICE cycle
   62, but VICE wants cycle 61).

   File: `C64Screen.java:2209` (case 61: `setBaLowUntil(... "SPR3")`)
   Should move to: case 60.

These two are real bugs — they break the otherwise-consistent
"case N = cycle N+1" sprite alignment.

## Why "OPEN BORDER WITH STA" window is ~4 cycles too short

ChkBrdR1 fires at JaC64 case 56 (= VICE cycle 57). STA $D016/$D011
writes that commit at clk = lastLine + N for N ≤ 56 should
"open the border" since the write commits before ChkBrdR. But the
test reports only A,B,C (3 cycles) work in JaC64 vs A-G (7) in VICE.

The likely root cause: **Phi1 vs Phi2 timing within a single cycle.**
JaC64's case dispatcher runs at the START of clk N. VICE Phi1 runs
in the first half of clk N, Phi2 in the second half. ChkBrdR1 is a
Phi2 event in VICE, so it fires LATER within cycle 57.

In JaC64, an STA at case 53 commits its write at clk = lastLine+56
(end of 4-cycle STA), which is THE SAME clk as the ChkBrdR check.
The order is: STA commits → schedule(56) runs case 56 (ChkBrdR).
So STA at case 53-56 should affect ChkBrdR.

In VICE, an STA at cycle 53 commits its write at clk = line+56
(also end of 4-cycle STA). Same physical moment. ChkBrdR1 at
Phi2(57) is one CLK LATER. So STAs at cycles 53-57 (all the way
to the cycle ChkBrdR fires Phi1) affect ChkBrdR.

This means VICE has a 1-CLK extra window that JaC64 lacks. But
that's only 1 cycle of difference, not 4.

The remaining 3-cycle gap probably comes from cumulative effects:
- 1 cycle from Phi1/Phi2 split (above)
- 1 cycle from ChkBrdR0 (CSEL=0) vs ChkBrdR1 (CSEL=1) — JaC64 only
  has one ChkBrdR per case, may miss one of the two
- 1 cycle from c-access being late (delays all column-related effects)

Need to test individually to confirm.

## Next actionable items

1. **Fix SPR2 BA-low**: move from case 57 to case 58
2. **Fix SPR3 BA-low**: move from case 61 to case 60
3. **Verify with re-trace** — both anomalies should disappear
4. **Investigate Phi1/Phi2 split for ChkBrdR** — possible 1-cycle
   gain on OPEN BORDER WITH STA window
