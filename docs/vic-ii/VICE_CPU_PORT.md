# VICE CPU port — instruction-by-instruction

Goal: port VICE's `6510core.c` macros into JaC64 so the cycle/VIC
interleaving matches VICE exactly.

## Current JaC64 shape (legacy, to be replaced)

`MOS6510Core.emulateOp()`:
- `fetchByte(pc++)` opcode → `cycles++; schedule(cycles)` (1)
- `fetchByte(pc)` peek p1 → 1
- `switch (addrMode)` → ABS/ZP/IND_X/IND_Y/etc, each does the
  remaining fetches via `fetchByte()`
- RMW dummy write via `writeByte(adr, data)` if read+write
- `switch (op)` → ALU
- Final write via `writeByte(adr, data)` if write

Every `fetchByte`/`writeByte` calls `chips.schedule(cycles)`, which
runs ONE vicii cycle per call.

## VICE shape (target)

`6510core.c`:
- `FETCH_OPCODE(o)` (c64cpusc.c:124-179) — the only place
  `CLK_INC()` (== schedule) is called. Pattern:
  - LOAD opcode + CLK_INC
  - LOAD operand1 + CLK_INC
  - if 3-byte: LOAD operand2 + CLK_INC
- Per-opcode body — uses `CLK_ADD(CLK, n)` (just clock += n,
  NO schedule) interleaved with LOADs/STOREs.

VIC catches up at next instruction's FETCH_OPCODE CLK_INCs.

## Primitives to introduce

In `MOS6510Core` (or a new mixin class):

```java
/** CLK_INC: advance one cycle AND schedule VIC (chips.schedule).
 *  Matches VICE c64cpusc.c:47. Used inside FETCH_OPCODE. */
protected final void clkInc() {
    cycles++;
    sampleIrqLine();
    schedule(cycles);
}

/** CLK_ADD: advance N cycles WITHOUT scheduling VIC. Matches
 *  VICE 6510core.c:114. Used in instruction body. */
protected final void clkAdd(int n) { cycles += n; }

/** Pure read from memory map at current clock (no cycle advance). */
protected final int LOAD(int addr) { /* ... existing read logic ... */ }

/** Pure write to memory map at current clock (no cycle advance). */
protected final void STORE(int addr, int val) { /* ... */ }

/** Dummy LOAD (page-cross fixup). VICE counts it as a real bus
 *  read; we mirror that for memory-mapped IO sensitivity. */
protected final void LOAD_DUMMY(int addr) { LOAD(addr); }
```

## Memory-access macros to port (6510core.c)

| VICE macro | Cycles | Pattern |
|---|---|---|
| `STORE_ABS(addr, val, inc)` | inc | `CLK_ADD(inc); STORE(addr, val)` |
| `STORE_ABS_X(addr, val, inc)` | inc | `CLK_ADD(inc-2); LOAD_DUMMY((addr+x)&ff or addr&ff00); CLK_ADD(2); STORE(addr+x, val)` |
| `STORE_ABS_X_RMW(addr, val, inc)` | inc | `CLK_ADD(inc); STORE(addr+x, val)` |
| `STORE_ABS_Y(...)` | inc | as ABS_X but with Y |
| `STORE_ABS_Y_RMW(...)` | inc | as ABS_X_RMW but with Y |
| `STORE_ABS_SH_X` | special | uses high byte trick |
| `LOAD_ABS_X` | 4 or 5 | dummy read on page-cross or RMW |
| `LOAD_ABS_Y` | 4 or 5 | similar |
| `LOAD_IND_X(addr)` | 6 | `CLK_ADD(2); LOAD_ZERO; LOAD_ZERO+1; LOAD_IND` |
| `LOAD_IND_Y(addr)` | 5 or 6 | 2-cycle ZP fetch + page-cross dummy |
| `BRANCH(cond, addr)` | 2/3/4 | taken+page-cross delays IRQ (`branchDelaysIrq`) |

## Opcodes to port (groups)

### Group A — RMW ABS/ZP/ABS_X (Krestage 3 critical)
- ASL/LSR/ROL/ROR
- INC/DEC
- DCP, ISB, RLA, RRA, SRE, SLO (illegals — Krestage 3 banner uses DEC $D016)

### Group B — Loads
- LDA/LDX/LDY (all addr modes)
- LAX, LAS, LXA (illegals)

### Group C — Stores
- STA/STX/STY (all addr modes)
- SAX, SHA, SHX, SHY, SHS (illegals)

### Group D — ALU immediate/loads
- ADC/SBC, AND/EOR/ORA, BIT, CMP/CPX/CPY
- ANC, ASR, ARR, ANE, SBX

### Group E — Stack/transfer/flags
- PHA/PHP/PLA/PLP
- TAX/TAY/TXA/TYA/TXS/TSX
- INX/INY/DEX/DEY
- CLC/SEC/CLI/SEI/CLD/SED/CLV
- BRK/RTI/JSR/RTS/JMP

### Group F — Branches
- BPL/BMI/BVC/BVS/BCC/BCS/BNE/BEQ
- Critical: page-cross-not-taken sets `branchDelaysIrq` (matches
  VICE OPCODE_DELAYS_INTERRUPT, maincpu.c:484).

### Group G — Special
- NOP variants (1-byte, 2-byte, 3-byte forms)
- JAM/HLT (illegals)

## Rollout strategy

1. **Phase 2** — add primitives (`clkInc`, `clkAdd`, `LOAD`, `STORE`)
   behind flag `jac64.vicCpuModel`. Keep legacy path as default.
2. **Phase 3** — port memory-access macros (STORE_ABS family etc.)
   as Java helpers.
3. **Phase 4** — port opcodes group by group. Run each group
   through TestRaster + a "smoke" demo to catch regressions early.
4. **Phase 5** — port illegals.
5. **Phase 6** — flip `vicCpuModel` to default-on; remove legacy
   path; run full validation matrix.

## Validation matrix (per phase)

For each group ported:
- ✅ TestRaster boots Krestage 3 to scroll-in scene
- ✅ TestRaster boots lets_scroll_it
- ✅ cia-timer-oldcias.prg passes 8/8
- 🎯 irq-ack-vicii RASTER 4/4 + SS-COL ≥3/4
- 🎯 vicii_reg_timing OPEN BORDER positions trending toward
  reference (final goal: all match)

## Known divergences this fixes

1. VIC sees register write at correct cycle relative to subsequent
   VIC cycle actions (currently OFF by ~1 due to schedule-on-every-access).
2. VIC register reads return state lagging by N cycles where N
   = CLK_ADDs since last CLK_INC (currently always fresh in JaC64).
3. BA-low detection latency matches VICE (currently approximate).

## Estimated effort

- Phase 2: 0.5 day
- Phase 3: 1 day
- Phase 4: 2-3 days (groups A-G)
- Phase 5: 1 day (illegals)
- Phase 6: 0.5-1 day validation + cleanup

**Total: ~5-7 days of focused work.**
