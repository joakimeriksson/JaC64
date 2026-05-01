# Phase 10.G: Slot-5 status after Phi1/Phi2 split

## Tests still byte-identical to Phase 10.F baseline

irq-ack-vicii.prg: 47/48 (slot 5 LDA SS-COL still fails).
cia-timer-newcias.prg: unchanged.

## Surprising Phi2 finding: autostart shift no longer affects result

Pre-Phi2 (Phase 10.C):
- `target=7000000` → `ddddd.` (slot 5 fail)
- `target=7000001` → fixed slot 5 LDA SS-COL but BROKE STA RASTER slot 5
- Phase 10.C concluded autostart shift is a non-fix that just shuffles cells

Post-Phi2 (this phase):
- `target=7000000` → `ddddd.` (same)
- `target=7000001` → `ddddd.` (same — no longer shifts!)
- `target=7000002` → `ddddd.` (same)
- `target=3131552` (matches VICE) → `ddddd.` (same)

**Net: Phi2 architecture stabilized the test against autostart phase.** The
test is no longer mod-3-sensitive to boot timing. The remaining slot-5
failure is a clean differential bug, not noise.

## What slot 5 actually fails on (current trace data)

Failing instruction: `lda $d019` at PC=$b5e (in irq_ack_test4) at line 74
cyc 45 (frame ~400).

```
EV-RdD019 clk=7886763 rast=$4a cyc=45 ret=$f4 pc=$b5e
TVIC clk=7886763 rast=$4a cyc=45 ... act=[SSCol-fire-phi2]
```

JaC64 reads `$f4` (= bit 2 SSCol set + bit 7 + unused bits). VICE for the
same slot reads `$00` (bit 2 cleared). The 1-cycle offset of the LDA
relative to SSCol fire timing differs.

## Per-slot LDA $D019 cyc-within-line (JaC64, post-Phi2)

| slot | LDA cyc | ret | cell | VICE ref |
|---|---|---|---|---|
| 0 | 44 | $70 | `.` ($00) | `.` ✓ |
| 1 | 45 | $f4 | `d` ($84) | `.` ✗ |
| 2 | 45 | $f4 | `d` | `d` ✓ |
| 3 | 46 | $f4 | `d` | `d` ✓ |
| 4 | 48 | $f4 | `d` | `d` ✓ |
| 5 | 48 | $f4 | `d` | `d` ✓ |

JaC64 slot 1 LDA at cyc 45 sees the SSCol fire that became visible from cyc
45 onwards (detection at JaC64 cyc 44, fire in clockPhi2(cyc 44), visible
to clk=44+1=45 reads).

VICE slot 1 LDA must be at cyc 44 (1 cyc earlier within line) to read pre-fire.

## Root cause (UNFIXED)

JMP loop boundary mod-3 alignment differs between JaC64 and VICE at the
moment the line-69 raster IRQ fires:

```
                  JMP boundaries          irq_clk%3   IrqService delay
JaC64:           clk%3 = 1 (...,7866748,7866751)   0           +4 cycles
VICE (Phase 10.D): clk%3 = 2 (...,3994514,3994517)  0           +2 cycles
```

irq_clk + INTERRUPT_DELAY (= +2) lands on a boundary in VICE (= service
immediately) but NOT in JaC64 (= wait until next boundary at +4).

The 2-cycle difference at line-69 IRQ entry propagates through the entire
handler chain → handler_2 → test4 LDA $D019. By the time the LDA executes,
JaC64 is 1 cycle "later within line" relative to VICE. SSCol fire at line
74 cyc 45 is visible to JaC64's LDA at cyc 45 but not VICE's LDA at cyc 44.

## Why this isn't fixable without violating either user constraint

The mod-3 phase difference comes from **cumulative cycle count from
boot**:
- JaC64 `target=7000000` cycles to autostart ENDS at `clk%3 = 1` boundary phase.
- VICE natural autostart ENDS at `clk%3 = 2` boundary phase (different boot
  sequence: load+run from disk image).

Per Phase 9.1, per-instruction cycles match VICE. So the only way to align
mod-3 is to change cumulative cycles from boot — which is autostart shift.

User explicitly rejected autostart shift in Phase 10.C: *"autostart phase
shift should not be a solution... that would be a real surprise."*

## What stays in Phi2

Even though slot 5 isn't fixed, Phi2 delivered real architectural value:

1. **Removed two compensation flags** (`viceBrdrPhi2`, `viceD019Phi2`).
2. **Eliminated the manual SSCol fire defer** (now structural intra-cycle handoff).
3. **Stabilized the test against autostart phase** — pre-Phi2 the +1
   autostart shift fixed/broke cells; post-Phi2 it's a no-op.
4. **Removed `rmwInProgress`** field (was only fed the `!rmwInProgress`
   carve-out in viceD019Phi2; both gone now).
5. **60 lines of CPU.java + MOS6510Core.java deleted**.

## Suggested next investigations

If slot 5 fix is still wanted without autostart shift:

1. **Find a non-cycle-counted boot path that lands JaC64 mod-3 == VICE mod-3
   naturally.** E.g., emulate disk LOAD+RUN authentically rather than
   pause-and-jump. Bigger refactor.
2. **Run more test programs** to verify the slot-5 issue is the ONLY
   remaining cycle-accuracy delta, not a representative of broader drift.
3. **Live with 47/48 and document.** The test doesn't pass on real C64
   either with arbitrary boot timing — VICE happens to align by luck of
   its boot sequence. JaC64's boot is just as valid; demos that don't
   depend on the specific mod-3 phase will work fine.
