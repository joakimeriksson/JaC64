# CPU Refactor Phase ζ — Per-Cycle PC Trace Side-by-Side

## Setup
Generated synchronized PC traces in both emulators:
- VICE: `JAC64_TRACE_FILE=... JAC64_PC_TRACE_FILE=... x64sc ...`
  (both must be set in the SAME run; earlier comparisons failed
  because traces came from different runs with different clks).
- JaC64: `-Djac64.tracePcCycles=true -Djac64.tracePcFile=...
  -Djac64.tracePcStart=N -Djac64.tracePcEnd=N`.

Test program: `colorsplit.prg`. Window: line 58→60 IRQ transition.

## The exact bug

Per-instruction PC + clk trace through the irq_stable2 handler:

| PC | Instruction | VICE clk | JaC64 clk | VICE cyc | JaC64 cyc | Diff |
|---|---|---|---|---|---|---|
| $916 | NOP (irq_stable2 entry) | 3030751 | 7020920 | — | — | base |
| $917 | NOP (BA-low stall) | 3030796 | 7020965 | +45 | +45 | **✓** |
| $918 | NOP | 3030798 | 7020967 | +2 | +2 | ✓ |
| $919-$922 | NOPs | (each +2) | (each +2) | +2 each | +2 each | ✓ |
| $923 | TXS | 3030820 | 7020989 | +2 (prev) | +2 (prev) | ✓ |
| $924 | DEC $D019 | **3030822** | **7020995** | **+2** | **+6** | **❌ +4 cyc** |
| $927 | DEC $D012 | 3030828 | (~+6) | +6 | +6 | ✓ |
| $92a | LDA # | 3030834 | (~+6) | +6 | +6 | ✓ |

**Between $923 TXS and $924 DEC $D019, JaC64 takes 6 cycles vs
VICE's 2.** That's 4 extra cycles per IRQ pair, accumulating to the
~45-cycle/frame drift in $D021 split-write timing.

## What causes the 4-cycle gap?

Investigating. Possibilities:
1. **`waitForBus` stalling** at clk=7020991 (start of DEC fetch).
   But: `baLowUntil` for line 59's badline = lastLine + 54 = 7020909
   + 54 = 7020963. At clk=7020991, 7020991 > 7020963 → no stall.
2. **Sprite DMA stall** at line 60. colorsplit doesn't use sprites,
   but maybe a sprite DMA is being asserted spuriously.
3. **IRQ check/re-entry**: maybe an immediate IRQ check fires
   between $923 and $924 without leaving a trace entry.
4. **`emulateOp` overhead** between instructions that's mis-credited.

## Next-session investigation

Add per-cycle clk tracing inside `CPU.fetchByte` to see what
happens between $923's end (clk=7020991) and $924's start (clk=7020995).
The 4 cycles must be:
- 2x `cycles++` in `waitForBus`, OR
- A `viceInterruptDelayBeforeClockInc` increment, OR
- Direct `cycles += N` somewhere.

Find the call site, identify the bug, fix.

## Status
✅ Pinpointed the EXACT instruction boundary where JaC64 differs
from VICE.
✅ All earlier phases (α-ε) ruled out as causes.
✅ Concrete next step: trace `cycles` between specific instructions.

The fix is now one specific code change away — likely in
`CPU.fetchByte` or `waitForBus`, fixing a stall that shouldn't fire
at this cycle.

## Files for next session
- Trace data preserved at `/tmp/vice_pc_combined.trace` and
  `/tmp/jac64_pc2.trace`.
- This doc.
- The 4-cycle gap MUST come from somewhere in `MOS6510Core.java` or
  `CPU.java`. Add EV-CycleAdvance traces between every instruction
  to find it.
