# Per-cycle VIC-II trace — finding dispatcher offsets

The cycle dispatcher in `C64Screen.clock()` runs case 0..62 once
per absolute cycle. To find timing divergences from VICE viciisc,
enable `-Djac64.traceVicCycle=true` and compare the output to the
VICE PAL reference table below.

## Running the trace

```
java -Djac64.traceVicCycle=true \
     -Djac64.traceVicCycleStart=2000000 \
     -Djac64.traceVicCycleEnd=2003000 \
     -Djac64.traceVicCycleFile=/tmp/jac64.trace \
     -cp build/libs/JaC64.jar TestRaster <input.prg>
```

Each line: `TVIC clk=N rast=$RR cyc=Z bl=B baU=BL vmli=V vc=VC rc=RC act=[ACTIONS]`

`ACTIONS` is a comma-list of high-level events fired this cycle:
- `BA-SPRn` — BA-low set for sprite N
- `BA-BADLINE-cN` — BA-low set for badline at case N
- `FetchC-cN` — c-access for column N
- `SPRnRead` — sprite N data fetched (corresponds to VICE SprDma)
- `ChkBrdL` / `ChkBrdR` — left/right border check
- `RasterIRQ-fire` — raster IRQ delivered

## VICE PAL cycle reference (vicii-chip-model.c:111)

For each VICE PAL cycle (1-63), the table below summarizes Phi1/Phi2
fetches plus side-effects:

| VICE cyc | Phi1 fetch | Phi2 fetch | BA   | Misc           |
|----------|------------|------------|------|----------------|
| 1        | SprPtr(3)  | SprDma0(3) | BaSpr2(3,4) |          |
| 2        | SprDma1(3) | SprDma2(3) | BaSpr3(3,4,5) |        |
| 3        | SprPtr(4)  | SprDma0(4) | BaSpr2(4,5) |          |
| 4        | SprDma1(4) | SprDma2(4) | BaSpr3(4,5,6) |        |
| 5        | SprPtr(5)  | SprDma0(5) | BaSpr2(5,6) |          |
| 6        | SprDma1(5) | SprDma2(5) | BaSpr3(5,6,7) |        |
| 7        | SprPtr(6)  | SprDma0(6) | BaSpr2(6,7) |          |
| 8        | SprDma1(6) | SprDma2(6) | BaSpr2(6,7) |          |
| 9        | SprPtr(7)  | SprDma0(7) | BaSpr1(7) |            |
| 10       | SprDma1(7) | SprDma2(7) | BaSpr1(7) |            |
| 11       | Refresh    |            |          | (idle Phi2)    |
| 12       | Refresh    |            | BaFetch  |                |
| 13       | Refresh    |            | BaFetch  |                |
| 14       | Refresh    |            | BaFetch  | UpdateVc       |
| 15       | Refresh    | FetchC col0| BaFetch  | ChkSprCrunch   |
| 16       | FetchG c0  | FetchC c1  | BaFetch  | UpdateMcBase Vis(0) |
| 17       | FetchG c1  | FetchC c2  | BaFetch  | ChkBrdL1 Vis(1) |
| 18       | FetchG c2  | FetchC c3  | BaFetch  | ChkBrdL0 Vis(2) |
| 19-54    | FetchG cN  | FetchC c(N+1) | BaFetch | Vis(N)       |
| 55       | FetchG c39 |            | BaSpr1(0)| ChkSprDma Vis(38) |
| 56       | Idle       |            | BaSpr1(0)| ChkSprDma Vis(39) |
| 57       |            |            | BaSpr1(0)| ChkBrdR0 ChkSprExp |
| 58       | SprPtr(0)  | SprDma0(0) | BaSpr2(0,1) | ChkSprDisp UpdateRc |
| 59       | SprDma1(0) | SprDma2(0) | BaSpr3(0,1,2) |          |
| 60       | SprPtr(1)  | SprDma0(1) | BaSpr2(1,2) |           |
| 61       | SprDma1(1) | SprDma2(1) | BaSpr3(1,2,3) |          |
| 62       | SprPtr(2)  | SprDma0(2) | BaSpr2(2,3) |           |
| 63       | SprDma1(2) | SprDma2(2) | BaSpr3(2,3,4) |          |

(57 has Phi2 ChkBrdR1 split: fires at cyc 56 if CSEL=0, 57 if CSEL=1.)

## How to diff

1. Run the trace on a chosen .prg with TestRaster.
2. Pick one raster line (e.g., the first display row, $30).
3. Find the JaC64 trace lines for that line (rast=$30).
4. Compare each `cyc=N act=[…]` to the VICE table above.

The mismatch — JaC64 fires X at case N, VICE expects it at cycle M
— gives the dispatcher offset.

## Known anchors (currently in JaC64)

- `case 1` "Sprite data - sprite 3" — comment says SprPtr(3). VICE
  has SprPtr(3) at cycle 1 Phi1 + SprDma0(3) at cycle 1 Phi2. Looks
  aligned.
- `case 54` sets BA-low for sprite 0 (SP0). VICE BaSpr1(0) starts
  at cycle 55 Phi1. Possibly **off by 1 cycle**.
- `case 55` runs `checkHBorderRight` if hideColumn. VICE
  ChkBrdR0 at cycle 56 Phi2, ChkBrdR1 at cycle 57. Possibly
  **off by 1-2 cycles**.
- `case 15` (with cAccessShift=on) does FetchC col 0. VICE Phi2(15).
  Aligned.
- `case 16` does FetchG col 0 + FetchC col 1. VICE Phi1(16) +
  Phi2(16). Aligned.
- `case 17` (with hideColumn) `checkHBorderLeft`. VICE ChkBrdL1 at
  Phi2(17), ChkBrdL0 at Phi2(18). Possibly aligned for L1 only.

## Next: actually run it

The first raster line worth tracing is one with active sprites
AND visible border effects — ideally a side-border-open trick scene
(Krestage 3 banner) or the vicii_reg_timing test ROM.
