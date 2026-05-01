# Phase 10.I: Per-instruction VICE vs JaC64 diff (test4 SS-COL iter 1)

## Method

Captured per-instruction PC trace from both emulators using:
- VICE: `JAC64_PC_TRACE_FILE=...` env var (existing trace patch in `6510dtvcore.c:1834-1852`)
- JaC64: `-Djac64.tracePcCycles=true`

Compared the EXACT instruction stream from `$b23 INY` (post-firstdelay) to
`$b5b LDA $D019` (the failing test4 instruction) for iter 1 of test4 SS-COL.

## Per-instruction comparison (24 instructions before LDA $D019)

| PC | op | mnemonic | VICE cyc | JaC64 cyc | match |
|----|----|----------|----------|-----------|-------|
| $b23 | $c8 | INY | 2 | 2 | ✓ |
| $b24 | $b1 | LDA ($fe),Y | 5 | 5 | ✓ |
| $b26 | $8d | STA abs | 4 | 4 | ✓ |
| $b29 | $a9 | LDA #imm | 2 | 2 | ✓ |
| $b2b | $85 | STA zp | 3 | 3 | ✓ |
| $b2d | $a0 | LDY #imm | 2 | 2 | ✓ |
| $b2f | $b1 | LDA ($fe),Y | 5 | 5 | ✓ |
| $b31 | $18 | CLC | 2 | 2 | ✓ |
| $b32 | $65 | ADC zp | 3 | 3 | ✓ |
| $b34 | $20 | JSR | 13 | 13 | ✓ (incl. 7-cyc BA-low stall) |
| $850 | $4a | LSR | 2 | 2 | ✓ |
| $851 | $90 | BCC (taken) | 3 | 3 | ✓ |
| $853 | $8d | STA abs | 4 | 4 | ✓ |
| $856 | $18 | CLC | 2 | 2 | ✓ |
| $857 | $90 | BCC (taken) | 3 | 3 | ✓ |
| $874-$878 | $ea×5 | NOP | 2×5 | 2×5 | ✓ |
| $879 | $60 | RTS | 6 | 6 | ✓ |
| $b37 | $6c | JMP indirect | 5 | 5 | ✓ |
| $b5a | $78 | SEI | 2 | 2 | ✓ |
| $b5b | $ad | LDA $D019 | 4 | 4 | ✓ |

**Every single instruction has IDENTICAL cycle count.** Both emulators
land at the SAME PC at the SAME relative time within this window (78
cycles from $b23 to end of $b5b LDA in BOTH).

## So where IS the 1-cycle delta?

From handler_2 IrqService to LDA $D019 data read:
- VICE: 4014529 - 4014237 = **292 cycles**
- JaC64: 7867106 - 7866813 = **293 cycles**

The 1 cycle MUST come from **before the $b23 INY** but after handler_2
entry — i.e., somewhere in handler_2's BEQ idiom + irq_handler_3 setup +
firstdelay + post-firstdelay code.

That window contains:
- handler_2: lda #$01, sta $d019, ldx #$07, dex/bne loop (×7),
  nop×2, lda $d012, cmp $d012, beq, nop×2, ldx #$04, dex/bne loop (×4),
  inc $d021, dec $d021, clc, lda $d012, adc #$03, sta $d012,
  lda $d01e, cli, lda #<irq_handler_3, sta $fffe, lda #>irq_handler_3,
  sta $ffff
- firstdelay: lda #$XX, jsr delay, [delay routine: LSR + BCC + STA + CLC +
  BCC + N×NOP + RTS]
- post-firstdelay: ldy #$01, lda ($fe),y, bne, sta $c000, iny,
  lda ($fe),y, sta $c001, lda #'-', sta $fb, ldy #$00 (= $b23 area)

Suspect candidates:
- `cmp $d012` in handler_2's BEQ idiom — depends on $D012 value. If JaC64
  vs VICE returns $D012 = 70 vs 71 due to a 1-cycle phase, the BEQ outcome
  could differ → different code path → different cycle count.
- `inc $d021; dec $d021` (RMW). VICE may handle RMW dummy-write timing
  with subtle differences vs JaC64.
- `lda $d01e` (sprite-sprite collision register read with auto-clear).
  Reading $D01E clears the register. If timing relative to sprite paint
  differs, value differs.
- `lda ($fe),y` indirect-indexed: cycle count depends on page-cross.
  Both emulators should handle correctly per Phase 9.1, but worth
  verifying for THIS specific $fe pointer value.
- BA-low stall during firstdelay's JSR (BA-low can extend any instruction
  that hits it).

## Empirical confirmation

VICE inter-iteration cycle delta: consistent 19657.
JaC64 inter-iteration cycle delta: 19657/19656/19657/19658/19656.

So JaC64 is 1 cyc SHORT for some iterations and 1 cyc LONG for others.
Net: 1 cyc short over 5 iterations.

This matches a per-iteration variance of ±1 cyc in handler chain,
likely from one of the suspect candidates above.

## Status

- **Per-instruction cycles MATCH** (verified against VICE trace).
- The bug is in the handler_2 → firstdelay window (before $b23).
- Need to extend the trace window backwards and find the divergent cycle.

## Next steps

Capture both VICE and JaC64 traces from line 70 IRQ service through
$b23 INY (a window of ~390 cycles). Diff cycle-by-cycle to find
the EXACT instruction with mismatched timing.
