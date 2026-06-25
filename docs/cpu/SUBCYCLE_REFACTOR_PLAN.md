# Sub-cycle CPU refactor — detailed plan (VICE-grounded)

Status: PLAN (not started). Author notes 2026-06-18.
Cross-refs: docs/vic-ii/WORKPLAN.md (every fix cites a VICE source line + a
divergent trace event). See memory project_lorenz_disk3_cia_model_2026_06_11
(BRK/NMI-hijack finding) and project_cpu_subcycle_floor.

---

## 0. Executive summary — there are TWO different "sub-cycle" problems

People lump these together as "the sub-cycle floor"; they are independent,
with very different cost and value:

- **(A) CPU-internal interrupt sub-cycle precision** — the exact cycle an
  IRQ/NMI is recognised within an instruction, and the **BRK/NMI hijack**
  (an NMI arriving during a BRK steals the BRK's vector). This is the
  Lorenz `nmi` sub-test 00 blocker and the general `irq`/`nmi` recognition
  sweeps.
- **(B) VIC register-write intra-cycle pixel phase** — *when within a cycle*
  a CPU write to a `$D0xx` register lands, so the VIC samples it at the
  right pixel. This is the `modesplit` / `vicii_reg_timing` residual.

**Key structural fact (verified):** JaC already interleaves the VIC with the
CPU *per memory access* — `CPU.fetchByte/writeByte` do `cycles++` then
`schedule()`→`chips.clock(cycles)` and `schedulePhi2()`→`chips.clockPhi2()`
(CPU.java ~101-144, 198-220, 411-460). So (B) is **not** a CPU rewrite — the
per-cycle lockstep exists; (B) is a localised write-phase modelling task.
(A) is where the CPU model is genuinely coarse (interrupts sampled only at
instruction boundaries).

**Bottom line:** (A)'s headline item, the BRK/NMI hijack, is closable with a
**contained** change (post-push vector decision), NOT a multi-week rewrite.
The genuine multi-week item is (B), the VIC write-phase floor, and it's
orthogonal to the interrupt work.

---

## 1. VICE model (x64sc) — from source, cited

### 1a. Per-access clock + VIC steal + interrupt-delay counters
`mainc64cpu.c`:
- Every memory access goes through `memmap_mem_read/store` → `check_ba()`
  (mainc64cpu.c:295-322). `check_ba()` (212-230) calls `maincpu_steal_cycles()`
  when BA is low (VIC DMA), keeping CPU↔VIC in per-cycle lockstep.
- `maincpu_steal_cycles()` (112-191) also advances the **interrupt delay
  counters**: `irq_delay_cycles++` (unless opcode is SEI 0x78), `nmi_delay_cycles++`,
  each only `if (delay==0 && int_clk < maincpu_clk)`.

### 1b. Interrupt sampling (when an interrupt is allowed to fire)
`mainc64cpu.c:699-748` — `interrupt_check_nmi_delay` / `interrupt_check_irq_delay`:
- Fire when `*_delay_cycles >= INTERRUPT_DELAY` (`INTERRUPT_DELAY = 2`,
  interrupt.h:39).
- **Branch quirk:** `OPINFO_DELAYS_INTERRUPT(last_opcode)` adds `+1` (branch
  taken, no page cross). [JaC already mirrors this for IRQ and — since
  commit 1f09a31 — for NMI.]
- **BRK delays NMI by one opcode:** `interrupt_check_nmi_delay` returns 0 if
  `OPINFO_NUMBER(last_opcode) == 0x00` (BRK).
- **SEI (0x78):** does not advance `irq_delay_cycles`.
- **CLI/ANE/LXA (0x58/0x8b/0xab):** `OPINFO_SET_ENABLES_IRQ` — don't delay.

### 1c. Interrupt entry + the HIJACK (vector chosen AFTER the pushes)
`6510core.c` `DO_INTERRUPT` (436-500) and `BRK()` (1019-1060):
- Sequence: (NMI/IRQ only) 2 dummy reads; `PUSH PCH; PUSH PCL` (`CLK_ADD 2`);
  `PUSH status` (`CLK_ADD 1`); `PROCESS_ALARMS`.
- **THEN** choose the vector:
  `if (global_pending_int & IK_NMI && CLK >= nmi_clk + INTERRUPT_DELAY)`
  → `handler_vector = 0xFFFA` (and `interrupt_ack_nmi`), else `0xFFFE`.
- `BRK()` is identical in shape: `INC_PC(2); SET_BREAK(1); PUSH PCH/PCL/status;
  PROCESS_ALARMS;` **then** the same NMI-pending test → `$FFFA` (hijack) else
  `$FFFE`. The pushed PC is always `PC+2` and B is always set — only the
  *vector* changes. **This is the entire hijack mechanism.**

---

## 2. JaC current model — from source, cited

- `MOS6510Core.emulateOp()` runs a whole instruction atomically (516+). Each
  `fetchByte/writeByte` advances `cycles` by 1 and steps the VIC (per-cycle
  lockstep already present — see §0).
- **Interrupts sampled only at the instruction boundary** (top of
  `emulateOp`, ~536-567): `NMIPending && cycles >= nmiCycleStart`;
  IRQ `cycles >= irqCycleStart + (branchDelaysIrq?1:0)`. Absolute-cycle model
  (`nmiCycleStart = cycles + NMI_DELAY` at the falling edge, 174-187).
- A per-cycle counter path EXISTS but is **off by default**:
  `VICE_IRQ_DELAY_COUNTER` (249), `vicInterruptDelayBeforeClockInc` /
  `…AfterSteal` (252-267) increment `irqDelayCycles` — mirrors VICE 1a/1b but
  is gated by `-Djac64.vicIrqDelayCounter`.
- `doInterrupt(adr, status, brkInstr)` (285-336): the **vector is chosen by
  the caller up front** (`doInterrupt(0xfffa,…)` for NMI at 525,
  `doInterrupt(0xfffe,…, brk)` for IRQ/BRK at ~560). The 7-cycle body runs
  the 2 dummy reads (skipped for BRK), 3 pushes, 2 vector reads — **but never
  re-checks for a pending NMI after the pushes.** No hijack.

---

## 3. Gap analysis (per target behaviour)

| Target | VICE mechanism | JaC gap |
|---|---|---|
| Normal IRQ/NMI recognition boundary | per-cycle delay counters ≥2 | absolute `cycles>=xCycleStart`; coarse — patched per-case (branch fix) |
| Branch-delays-NMI | `OPINFO_DELAYS_INTERRUPT` +1 on NMI | ✅ fixed (1f09a31) |
| BRK delays NMI one opcode | `nmi_delay`==0 if last op==BRK | missing |
| **BRK/NMI hijack** | vector chosen **post-push** | vector fixed up-front in `doInterrupt` — **no post-push re-check** |
| VIC write pixel-phase | per-access lockstep + intra-cycle phase | lockstep ✅; intra-cycle pixel phase not modelled |

---

## 4. Phased plan

### Phase 0 — Harness & parity (prereq, cheap)
- Reuse the skip-opcode-0 `nmi` build (`64tass -D NEWCIA=0`, main `lda #0;sta
  cmd` → `lda #1`) — already proven to isolate normal-opcode NMI timing.
  Build the analogous `irq` skip variant.
- Both VICE `DO_INTERRUPT`/`BRK()` and JaC already emit an `EV-IrqService
  clk=… pc_pushed=…` trace (the in-repo VICE fork is patched). Lockstep-diff
  per opcode to get a per-opcode pass baseline before changing anything.

### Phase 1 — Per-cycle interrupt delay counters (generalises the branch fix)
- Make `VICE_IRQ_DELAY_COUNTER` the default and route **NMI through the same
  counter** (`nmiDelayCycles`), incremented once per CPU cycle in the
  per-access path, mirroring `maincpu_steal_cycles` (1a): `if (delay==0 &&
  int_clk < cycles) delay++`; fire at `>=2` (+1 branch; SEI no-advance;
  CLI/ANE/LXA enable).
- Replace the absolute `nmiCycleStart`/`irqCycleStart` compares with the
  counter compares.
- **Validation:** irq-ack-vicii must stay 48/48; Disk3 `irq` + `nmi`(normal)
  green; 16-test VIC survey unchanged (133). This is the **riskiest** phase
  (touches all interrupt delivery) — A/B every anchor + a demo.

### Phase 2 — Post-push vector decision = BRK/NMI hijack (contained)
- Restructure `doInterrupt` **and** the BRK entry so the vector is chosen
  **after** the 3 pushes (JaC's `push()`→`writeByte`→`cycles++` already
  advances the clock per push, so the post-push sample is meaningful):
  after the pushes, `if (NMIPending && nmiDelayCycles>=2)` → `$FFFA` + clear
  NMI; else `$FFFE`. Mirror VICE `BRK()` (1019) and `DO_INTERRUPT` (436).
- Add **BRK-delays-NMI-one-opcode** (NMI suppressed when `lastOpcode==0x00`).
- **Validation:** Lorenz `nmi` sub-test 00 (the BRK hijack sweep 00.0–00.9)
  passes; full Disk3 `nmi` chain proceeds past opcode 0. No regression to
  Disk2 TRAP (BRK functional) or CPUTIMING (BRK = 7 cyc).
- **Note:** a minimal version of Phase 2 (post-push NMI re-check in the
  existing BRK path) likely closes *most* of the hijack sweep even without
  full Phase 1; exact per-phase (00.x) correctness needs the Phase-1 counter
  so the post-push sample lands on the right cycle. Try minimal first.

### Phase 3 — VIC write intra-cycle pixel phase (SEPARATE, the real multi-week)
- Independent of Phases 1–2. For `modesplit`/`vicii_reg_timing`: model the
  pixel offset within the 8-pixel cycle at which a `$D0xx` write becomes
  visible to the VIC sequencer. Lockstep is already there (`clockPhi2`); this
  refines *where in the cycle* the write applies. This is the documented
  write-phase floor — do NOT couple it to the interrupt work.

---

## 5. Risk & validation matrix

Run at EACH phase (A/B vs the commit before it):
- irq-ack-vicii.prg (48/48) — the tuned IRQ timing; most sensitive.
- Disk3 `irq`, `nmi`(skip-0 normal opcodes), Timer B — must stay green.
- Disk1 (legal) + Disk2 (TRAP/CPUTIMING — BRK functional + 7-cyc).
- 16-test VIC survey — must stay 133 JaC-vs-VICE (NMI/IRQ changes should be
  VIC-neutral; if not, investigate).
- One NMI-using demo (Krestage 3 / RESTORE) — visual parity.

Riskiest: Phase 1 (all interrupt delivery). Most contained: Phase 2 (BRK path).

---

## 6. Effort & recommendation

- **Phase 0:** ~0.5 day (harness exists).
- **Phase 1:** ~2–4 days (delicate, counter path partly exists but default-on
  flip needs broad A/B).
- **Phase 2:** ~1–2 days (mechanism fully understood; contained).
- **Phase 3:** separate multi-day/week (write-phase floor).

**Recommendation:** The Disk3 NMI blocker (BRK/NMI hijack) is a *contained*
CPU change (Phase 2, optionally minimal Phase 1), well worth doing for CPU
completeness — NOT a multi-week project. The genuine multi-week item is
Phase 3 (VIC write-phase), which is independent and lower priority given the
remaining VIC residual is a documented data-layer floor. Do Phase 0→2 as one
focused effort; schedule Phase 3 only if the VIC accuracy goal demands it.
