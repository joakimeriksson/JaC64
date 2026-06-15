/**
 * This file is a part of JaC64 - a Java C64 Emulator
 * Main Developer: Joakim Eriksson (Dreamfabric.com)
 * Contact: joakime@sics.se
 * Web: http://www.dreamfabric.com/c64
 * ---------------------------------------------------
 *
 *
 */

package com.dreamfabric.jac64;
import java.io.*;

/**
 * MOS6510Core "implements" the 6510 processor in java code.
 * Other classes are intended to implement the specific
 * write/read from memory for correct emulation of RAM/ROM/IO
 * handling
 *
 * @author  Joakim Eriksson (joakime@sics.se)
 * @author  Jan Blok (jblok@profdata.nl)
 * @version $Revision: $
 *          $Date: $
 */
public abstract class MOS6510Core extends MOS6510Ops {
  protected int memory[];
  protected boolean debug = false;

  public static final int NMI_DELAY = 2;
  public static final int IRQ_DELAY = 2;
  public static final int IRQ_RELEASE_DELAY = 3;
  
  public static final int NMI_INT = 1;
  public static final int IRQ_INT = 2;
  private static final long IRQ_RELEASE_DISABLED = Long.MAX_VALUE;

  // Needed by ...
  protected PatchListener list;
  protected ExtChip chips = null;

  // Fast-load trap (TestRaster -Djac64.fastLoad). When pc reaches this
  // address — the patched KERNAL LOAD point $F4A1 — read the file directly
  // out of the attached .d64 via `list.readFile` instead of the slow IEC
  // transfer, then fall through to the patch's RTS. -1 = disabled.
  // (The legacy LOAD_FILE pseudo-opcode is dead: opcode fetch masks &0xff,
  // so the 0x100 index it needs can never be dispatched.)
  public int fastLoadTrapPc = -1;

  /** Return the chips (VIC/CIA/SID composite) for diagnostic access. */
  public ExtChip getChips() { return chips; }

  protected IMonitor monitor;
  public String codebase;

  // -------------------------------------------------------------------
  // Interrup signals
  // -------------------------------------------------------------------
  public boolean checkInterrupt = false;
  public boolean NMILow = false;
  public boolean NMILastLow = false;
  private boolean NMIPending = false;
  private boolean IRQLow = false;
  protected boolean irqRequested = false;
  public int lastInterrupt = 0;
  public boolean busAvailable = true;
  public long baLowUntil = 0;

  // The processor flags
  boolean sign = false;
  boolean zero = false;
  boolean overflow = false;
  boolean carry = false;
  boolean decimal = false;
  boolean brk = false;
  boolean resetFlag = false;

  // registers
  protected int acc = 0;
  protected int x = 0;
  protected int y = 0;
  protected int s = 0xff; // The stackpointer ??? ff = top?

  protected long nmiCycleStart = 0;
  protected long irqCycleStart = 0;
  protected int irqDelayCycles = 0;
  protected long irqReleaseCycle = IRQ_RELEASE_DISABLED;

  protected EventQueue scheduler = new EventQueue();

  private String[] debugInfo;
  private final boolean irqTraceEnabled =
      Boolean.getBoolean("jac64.irqTrace");
  private PrintStream irqTraceOut;

  public MOS6510Core(IMonitor m, String cb) {
    monitor = m;
    codebase = cb;
  }

  public abstract String getName();

  public int[] getMemory() {
    return memory;
  }

  public void jump(int pc) {
    jumpTo = pc;
    checkInterrupt = true;
  }

  public long getCycles() {
    return cycles;
  }

  private void traceIrqLine(String event) {
    if (!irqTraceEnabled) {
      return;
    }
    if (irqTraceOut == null) {
      String path = System.getProperty("jac64.irqTraceFile",
          "/tmp/jac64_irq_line.log");
      try {
        irqTraceOut = new PrintStream(new java.io.FileOutputStream(path), true);
      } catch (Exception e) {
        irqTraceOut = System.out;
      }
    }
    irqTraceOut.println(event + " clk=" + cycles +
        " baLowUntil=" + baLowUntil +
        " pc=$" + Integer.toHexString(pc & 0xffff) +
        " irqStart=" + irqCycleStart +
        " irqDelayCycles=" + irqDelayCycles +
        " irqLow=" + IRQLow +
        " req=" + irqRequested);
  }

  private void refreshInterruptCheck() {
    checkInterrupt = NMIPending || IRQLow || resetFlag || jumpTo != -1 || brk;
  }

  private void updatePendingIRQLineState() {
    if (!irqRequested && IRQLow && irqReleaseCycle != IRQ_RELEASE_DISABLED
        && cycles >= irqReleaseCycle) {
      IRQLow = false;
      irqReleaseCycle = IRQ_RELEASE_DISABLED;
      refreshInterruptCheck();
    }
  }

  public void setIRQLow(boolean low) {
    if (low) {
      irqReleaseCycle = IRQ_RELEASE_DISABLED;
      if (!irqRequested) {
        // The first active IRQ source makes the CPU-visible IRQ line go low.
        irqRequested = true;
        IRQLow = true;
        irqDelayCycles = 0;
        irqCycleStart = VICE_IRQ_DELAY_COUNTER
            ? cycles
            : cycles + IRQ_DELAY - (IRQ_ASSERT_PRE_INCREMENT ? 1 : 0);
        traceIrqLine("IRQ-ASSERT");
      }
    } else if (irqRequested) {
      irqRequested = false;
      if (IRQLow) {
        // The 6510 does not see the IRQ input line release immediately.
        irqReleaseCycle = cycles + IRQ_RELEASE_DELAY;
        traceIrqLine("IRQ-RELEASE-PEND");
      }
    }
    refreshInterruptCheck();
  }

  public void setNMILow(boolean low) {
    if (!NMILow && low) {
      // Latch the falling edge until the CPU can service it.
      checkInterrupt = true;
      if (!NMIPending) {
        nmiCycleStart = cycles + NMI_DELAY;
      }
      NMIPending = true;
      //System.out.println("*** NMI Goes low!");
    }
    NMILow = low;
    NMILastLow = low;
    //System.out.println(low ? "*** NMI Goes low!" : "*** NMI Goes hi!");
  }

  protected int jumpTo = -1;
  public long cycles = 0;
  protected long lastMillis = 0;

  // Some temporary and other variables...
  protected long nr_ins = 0;
  protected long nr_irq = 0;
  protected long start = System.currentTimeMillis();
  protected volatile int pc;
  protected int interruptInExec = 0;
  protected boolean disableInterupt = false;
  protected int irqEnableDelayOps = 0;
  protected boolean lastOpcodeDisablesIrq = false;
  private boolean rmwDummyWrite = false;
  protected int instructionStartPC = 0;

  // Deterministic-pause support: when set to a non-negative value, the
  // CPU loop will exit at the FIRST instruction boundary where
  // cycles >= pauseAtCycle. This produces a deterministic pause cycle
  // (the first instruction boundary past the target) across runs,
  // unlike Thread.sleep-based polling which races with JIT/OS timing.
  // Used by the TestRaster autostart harness.
  public volatile long pauseAtCycle = -1;

  // Phase A: real-6510 IRQ-line latching (kept as opt-in flag; see
  // docs/vic-ii/PHASE_A_IRQ_LATCHING.md). Empirical comparison with
  // VICE source (maincpu.c:484, interrupt.h:39) showed VICE uses the
  // SAME irq_clk + IRQ_DELAY=2 model JaC64 already had. The rolling
  // latch was the wrong direction. Default OFF.
  protected boolean irqLineAtCurrCall = false;
  protected boolean irqLineAtPrevCall = false;
  protected static final boolean PHASE_A_IRQ_LATCH =
      Boolean.getBoolean("jac64.phaseAIrqLatch");

  // VICE's "branch-taken-no-page-cross" IRQ delay quirk
  // (6510core.c:991, OPCODE_DELAYS_INTERRUPT). Real 6502: when a branch
  // is taken WITHOUT crossing a page boundary, IRQs/NMIs are delayed
  // by 1 extra cycle. Tracked in branchDelaysIrq and consumed by the
  // IRQ-pending check.
  protected boolean branchDelaysIrq = false;

  // VICE 6510dtvcore.c RTI(): "RTI does must not use
  // OPCODE_ENABLES_IRQ()" when it restores I from 1 to 0, because status
  // is restored before the final RTI cycles complete.
  // VICE-correct: RTI does NOT have the 1-instruction IRQ recognition
  // delay that CLI has. Locked in (no flag).
  protected static final boolean VICE_RTI_NO_IRQ_ENABLE_DELAY = true;

  // Diagnostic only: JaC64 labels/schedules read cycles after cycles++,
  // whereas VICE captures IRQ clk in the CLK_INC() path after the CPU access.
  // This tests whether IRQ assertion should be based on the pre-increment
  // physical cycle for the remaining irq-ack drift.
  protected static final boolean IRQ_ASSERT_PRE_INCREMENT =
      Boolean.getBoolean("jac64.irqAssertPreIncrement");

  // VICE 6510dtvcore.c:1593-1600 — SEI grants 1-boundary IRQ window when
  // I was clear and IRQ was pending. Locked in (no flag); was breaking
  // ackcia3 when off.
  protected static final boolean VICE_SEI_IRQ_WINDOW = true;

  protected static final boolean VICE_IRQ_DELAY_COUNTER =
      Boolean.getBoolean("jac64.vicIrqDelayCounter");

  protected final void vicInterruptDelayBeforeClockInc() {
    if (VICE_IRQ_DELAY_COUNTER && IRQLow && irqCycleStart <= cycles) {
      irqDelayCycles++;
    }
  }

  protected final void vicInterruptDelayAfterSteal() {
    if (!VICE_IRQ_DELAY_COUNTER || !IRQLow || irqDelayCycles != 0
        || irqCycleStart >= cycles) {
      return;
    }
    int opcode = memory[instructionStartPC & 0xffff] & 0xff;
    if (opcode != 0x78) {
      irqDelayCycles++;
    }
  }

  // Used for actual address...
  protected int rindex = 0;
  protected int lastReadOP = 0;

  public int getSP() { return s; }
  public void setSP(int sp) { s = sp & 0xFF; }
  public int getPC() { return pc; }
  public int getInstructionStartPC() { return instructionStartPC; }
  public int getAcc() { return acc; }
  public void setAcc(int a) { acc = a & 0xFF; }
  public int getX() { return x; }
  public void setX(int xr) { x = xr & 0xFF; }
  public int getY() { return y; }
  public void setY(int yr) { y = yr & 0xFF; }
  public int getStatus() { return getStatusByte(); }

  private final void doInterrupt(int adr, int status) {
    // VICE 6510dtvcore.c:314-348 (DO_IRQBRK + DO_INTERRUPT) cycle order:
    //   1-2. dummy fetches at PC, PC+1
    //   3. PUSH PCH
    //   4. PUSH PCL
    //   5. PUSH P
    //   6. LOAD vector_lo at $FFFE/$FFFA  (lo BEFORE hi)
    //   7. LOAD vector_hi at $FFFF/$FFFB
    // JaC64 trace: EV-IrqService at the cycle CPU enters doInterrupt.
    // For diffing IRQ delivery cycle precision against VICE.
    if (TRACE_IRQ_SERVICE && cycles >= TRACE_IRQ_SERVICE_START
        && cycles <= TRACE_IRQ_SERVICE_END) {
      int rasterLine = -1;
      long rasterCycle = -1;
      if (chips instanceof C64Screen) {
        C64Screen screen = (C64Screen) chips;
        rasterLine = screen.vbeam;
        rasterCycle = cycles - screen.lastLine;
      }
      tracePcOut.println("EV-IrqService clk=" + cycles
          + " adr=$" + Integer.toHexString(adr)
          + " pc_pushed=$" + Integer.toHexString(pc & 0xffff)
          + " irqClkStart=" + irqCycleStart
          + " rast=$" + Integer.toHexString(rasterLine)
          + " cyc=" + rasterCycle);
      tracePcOut.flush();
    }
    fetchByte(pc);
    fetchByte(pc + 1);
    push((pc & 0xff00) >> 8);
    push(pc & 0x00ff);
    push(status);
    interruptInExec++;
    pc = fetchByte(adr);
    pc |= fetchByte(adr + 1) << 8;
  }

  private static final boolean TRACE_IRQ_SERVICE =
      Boolean.getBoolean("jac64.traceIrqService");
  private static final long TRACE_IRQ_SERVICE_START =
      Long.getLong("jac64.traceIrqServiceStart", 0L);
  private static final long TRACE_IRQ_SERVICE_END =
      Long.getLong("jac64.traceIrqServiceEnd", Long.MAX_VALUE);

  protected final int getStatusByte() {
    return
    ((carry ? 0x01 : 0) + (zero ? 0x02 : 0) + (disableInterupt ? 0x04 : 0) +
        (decimal ? 0x08 : 0) + (brk ? 0x10 : 0) + 0x20 +
        (overflow ? 0x40 : 0) + (sign ? 0x80 : 0));
  }

  private final void setStatusByte(int status) {
    carry = (status & 0x01) != 0;
    zero = (status & 0x02) != 0;
    disableInterupt = (status & 0x04) != 0;
    decimal = (status & 0x08) != 0;
    brk = (status & 0x10) != 0;
    overflow = (status & 0x40) != 0;
    sign = (status & 0x80) != 0;
  }

  // Memory handling - both methods always add 1 to cycles!!!
  protected abstract int fetchByte(int adr);
  protected int loadByte(int adr) { return fetchByte(adr); }
  protected abstract void writeByte(int adr, int data);
  protected void storeByte(int adr, int data) { writeByte(adr, data); }

  private final void setZS(int data) {
    zero = data == 0;
    sign = data > 0x7f;
  }

  private final void setCarry(int data) {
    carry = data > 0x7f;
  }

  // -------------------------------------------------------------------
  // Old m4 macros as methods... should be replaced some day (with above)
  // -------------------------------------------------------------------
  // Stack operations...
  //can access array directly 's' is filed with one byte
  private final int pop() {
    int r = fetchByte((s = (s + 1) & 0xff) | 0x100);
    return r;
  }

  //can access array directly 's' is filed with one byte
  private final void push(int data) {
    writeByte((s & 0xff) | 0x100, data);
    s = (s - 1) & 0xff;
  }

  private final void opADCimp(int data) {
    int tmp = data + acc + (carry ? 1 : 0);
    zero = (tmp & 0xff) == 0; // not valid in decimal mode

    if (decimal) {
      tmp = (acc & 0xf) + (data & 0xf) + (carry ? 1 : 0);
      if (tmp > 0x9)
        tmp += 0x6;
      if (tmp <= 0x0f)
        tmp = (tmp & 0xf) + (acc & 0xf0) + (data & 0xf0);
      else
        tmp = (tmp & 0xf) + (acc & 0xf0) + (data & 0xf0) + 0x10;

      overflow = (((acc ^ data) & 0x80) == 0) &&
      (((acc ^ tmp) & 0x80) != 0);

      sign = (tmp & 0x80) > 0;

      if ((tmp & 0x1f0) > 0x90)
        tmp += 0x60;
      carry = tmp > 0x99;
    } else {
      overflow = (((acc ^ data) & 0x80) == 0) &&
      (((acc ^ tmp) & 0x80) != 0);
      carry = tmp > 0xff;
      sign = (tmp & 0x80) > 0;
    }
    acc = tmp & 0xff;
  }

  private final void branch(boolean branch, int adr, int cycDiff) {
    if (branch) {
      int oldPC = pc;
      pc = adr & 0xffff;
      /* correct branch */
      if (cycDiff == 1) {
        fetchByte(pc);
        // Branch taken with NO page boundary cross → 1 extra cycle of
        // IRQ delay (VICE 6510core.c:991, OPCODE_DELAYS_INTERRUPT).
        // Real 6502 latches the IRQ line state from BEFORE the branch
        // instruction's last cycle, so the IRQ for these branches is
        // pushed to the instruction-after-next.
        branchDelaysIrq = true;
      } else {
        if (pc < oldPC)
          fetchByte(pc + 0x100);
        else
          fetchByte(pc - 0x100);
        fetchByte(pc); // Should be fwd or backwd...
      }
    }
  }

  private final void opSBCimp(int data) {
    int tmp = acc - data - (carry ? 0 : 1);
    boolean nxtcarry = (tmp >= 0);
    tmp = tmp & 0x1ff; // Carry is set!
    sign = (tmp & 0x80) == 0x80;  // Invalid in decimal mode??
    zero = ((tmp & 0xff) == 0);
    overflow = (((acc ^ tmp) & 0x80) != 0) && (((acc ^ data) & 0x80) != 0);
    if (decimal) {
      tmp = (acc & 0xf) - (data & 0xf) - (carry ? 0 : 1);
      if ((tmp & 0x10) > 0)
        tmp = ((tmp - 6) & 0xf) |
        ((acc & 0xf0) - (data & 0xf0) - 0x10);
      else
        tmp = (tmp & 0xf) | ((acc & 0xf0) - (data & 0xf0));
      if ((tmp & 0x100) > 0)
        tmp -= 0x60;
    }
    acc = tmp & 0xff;
    carry = nxtcarry;
  }

  // Per-instruction cycle trace: when -Djac64.tracePcCycles=true, log
  // each instruction's PC and cycle count. Used for VICE-side cycle
  // diff to find timing discrepancies. Output via stderr or file.
  private static final boolean TRACE_PC_CYCLES =
      Boolean.getBoolean("jac64.tracePcCycles");
  private static final long TRACE_PC_START =
      Long.getLong("jac64.tracePcStart", 0L);
  private static final long TRACE_PC_END =
      Long.getLong("jac64.tracePcEnd", Long.MAX_VALUE);
  private static java.io.PrintStream tracePcOut = System.err;
  static {
    if (TRACE_PC_CYCLES || TRACE_IRQ_SERVICE) {
      String f = System.getProperty("jac64.tracePcFile", "");
      if (!f.isEmpty()) {
        try {
          tracePcOut = new java.io.PrintStream(
              new java.io.FileOutputStream(f, false));
        } catch (Exception e) {}
      }
    }
  }

  // Fast-load: read filename from FNADR ($BB/$BC)/FNLEN ($B7), the load
  // address from $2B/$2C when secondary-address ($B9)==0 (LOAD",8"), and load
  // the file out of the .d64 via `list`. Returns through the patched RTS at
  // $F4A2 with the KERNAL LOAD convention (carry clear + X/Y = end address).
  private void doFastLoad() {
    int fnlen = memory[0xb7] & 0xff;
    int fnadr = (memory[0xbb] & 0xff) | ((memory[0xbc] & 0xff) << 8);
    StringBuilder nm = new StringBuilder();
    for (int i = 0; i < fnlen; i++) {
      nm.append((char) (memory[(fnadr + i) & 0xffff] & 0xff));
    }
    int sa = memory[0xb9] & 0xff;
    int loadAdr = (sa == 0)
        ? ((memory[0x2b] & 0xff) | ((memory[0x2c] & 0xff) << 8)) : -1;
    boolean ok = list.readFile(nm.toString(), loadAdr);
    if (ok) {
      acc = 0;
      carry = false;                 // success
      x = memory[0xae] & 0xff;        // end address -> X/Y (KERNAL LOAD return)
      y = memory[0xaf] & 0xff;
    } else {
      carry = true;                  // error
      acc = 4;                        // FILE NOT FOUND
    }
    pc = (fastLoadTrapPc + 1) & 0xffff; // skip the marker byte -> the RTS
  }

  public void emulateOp() {
    // PC wraps within 16 bits: code executing across the $FFFF->$0000
    // boundary (e.g. an opcode at $FFFE) must fetch the next byte from
    // $0000, not spill into the extended/ROM-bank array at $10000.
    pc &= 0xffff;
    instructionStartPC = pc;
    if (fastLoadTrapPc >= 0 && pc == fastLoadTrapPc && list != null) {
      doFastLoad();
      return;
    }
    updatePendingIRQLineState();
    boolean hadIrqEnableDelay = irqEnableDelayOps > 0;
    boolean irqAllowedByStatus = !disableInterupt
        || (VICE_SEI_IRQ_WINDOW && lastOpcodeDisablesIrq);
    boolean irqDelayReady = VICE_IRQ_DELAY_COUNTER
        ? (IRQLow && irqDelayCycles >= IRQ_DELAY + (branchDelaysIrq ? 1 : 0)
            && irqEnableDelayOps == 0)
        : (IRQLow && cycles >= irqCycleStart + (branchDelaysIrq ? 1 : 0)
            && irqEnableDelayOps == 0);
    // Before executing an operation - check for interrupts!!!
    if (checkInterrupt) {
      if (NMIPending && (cycles >= nmiCycleStart)) {
        log("NMI interrupt at " + cycles);
        lastInterrupt = NMI_INT;
        NMIPending = false;
        doInterrupt(0xfffa, getStatusByte() & 0xef);
        disableInterupt = true;
        //prevent irq during nmi,RTI will clear by poping status back
        //checkInterrupt = false;
        // Remember last NMI state in order to check on next...
        NMILastLow = NMILow;
        // Just the interrupt handling... do nothing more...
        return;
      } else if ((PHASE_A_IRQ_LATCH
                    ? (irqLineAtPrevCall && irqEnableDelayOps == 0)
                    : irqDelayReady)
                || brk) {
        if (irqAllowedByStatus || brk) {
          log("IRQ interrupt > " + IRQLow + " BRK: " +  brk);
          lastInterrupt = IRQ_INT;
          //checkInterrupt = false; //does not make sense to leave more
          int status = getStatusByte();
          if (brk) {
            status |= 0x10;
            pc++;
          }
          else status &= 0xef;
          doInterrupt(0xfffe, status);
          disableInterupt=true;
          lastOpcodeDisablesIrq = false;
          //prevent irq during irq, RTI will clear by poping status back
          brk = false;

          // Just the interrupt handling... do nothing more...
          // Remember last NMI state in order to check on next...
          // NMILastLow = NMILow;
          return;
        } else {
          brk = false;
          checkInterrupt = NMIPending;
        }
      } else if (resetFlag) {
        doReset();
      } else if (jumpTo != -1) {
        pc = jumpTo;
        jumpTo = -1;
      }
    }

    // Clear the per-instruction "branch-delays-IRQ" flag now that
    // the IRQ-pending check (which read it above) has finished. The
    // flag will be set again if the upcoming instruction is a branch
    // taken with no page-boundary cross.
    branchDelaysIrq = false;
    lastOpcodeDisablesIrq = false;

    // Phase J trace: emit at INSTRUCTION START (pre-state) — matching
    // VICE's FETCH_OPCODE trace point in 6510dtvcore.c (line 1821+).
    // CRITICAL: this block runs AFTER the IRQ check above. If an IRQ
    // fired, the `return` in the checkInterrupt branch skipped this,
    // so the trace will NOT log a preempted instruction. The next
    // emulateOp() call will log the first instruction of the IRQ
    // handler. Matches VICE's ordering where DO_INTERRUPT is dispatched
    // BEFORE the trace block (6510dtvcore.c:1771-1791 vs 1821).
    if (TRACE_PC_CYCLES && cycles >= TRACE_PC_START
        && cycles <= TRACE_PC_END
        && "C64 CPU".equals(getName())) {
      int rasterLine = -1, rasterCyc = -1;
      int baFlag = 0;
      if (chips instanceof C64Screen) {
        C64Screen scr = (C64Screen) chips;
        rasterLine = scr.vbeam;
        rasterCyc = (int) (cycles - scr.lastLine);
        baFlag = (baLowUntil > cycles) ? 1 : 0;
      }
      tracePcOut.println("I=" + (jac64InstrCounter++)
          + " PC=$" + Integer.toHexString(pc & 0xffff)
          + " op=$" + Integer.toHexString(memory[pc & 0xffff] & 0xff)
          + " clk=" + cycles
          + " A=$" + Integer.toHexString(acc & 0xff)
          + " X=$" + Integer.toHexString(x & 0xff)
          + " Y=$" + Integer.toHexString(y & 0xff)
          + " SP=$" + Integer.toHexString(s & 0xff)
          + " P=$" + Integer.toHexString(getStatusByte() & 0xff)
          + " rast=" + rasterLine
          + " cyc=" + rasterCyc
          + " ba=" + baFlag);
    }

    // Ok no interrupts, execute instruction
    // fetch instruction!
    int opcode = fetchByte(pc++) & 0xff;
    int data = INSTRUCTION_SET[opcode];
    int op = data & OP_MASK;
    int addrMode = data & ADDRESSING_MASK;
    boolean read = (data & READ) != 0;
    boolean write = (data & WRITE) != 0;
    int adr = 0;
    int tmp = 0;
    boolean nxtcarry = false;
    lastReadOP = rindex;


//  System.out.println("AddrMode:" + Hex.hex2(addrMode) +
//  " op: " + Hex.hex2(op)
//  + " data: " + Hex.hex2(data));

    // fetch first argument (always fetched...?) - but not always pc++!!
    int p1 = fetchByte(pc & 0xffff);

    // Fetch addres, and read if it should be done!
    switch (addrMode) {
    // never any address when immediate
    case IMMEDIATE:
      pc++;
      data = p1;
      break;
    case ABSOLUTE:
      pc++;
      adr = (fetchByte((pc++) & 0xffff) << 8) + p1;
      if (read) {
        data = loadByte(adr);
      }
      break;
    case ZERO:
      pc++;
      adr = p1;
      if (read) {
        data = loadByte(adr);
      }
      break;
    case ZERO_X:
    case ZERO_Y:
      pc++;
      // Read from wrong address first...
      fetchByte(p1);

      if (addrMode == ZERO_X)
        adr = (p1 + x) & 0xff;
      else
        adr = (p1 + y) & 0xff;

      if (read) {
        data = loadByte(adr);
      }
      break;
    case ABSOLUTE_X:
    case ABSOLUTE_Y:
      pc++;
      // Fetch hi byte!
      adr = fetchByte((pc++) & 0xffff) << 8;

      // add x/y to low byte & possibly faulty fetch!
      if (addrMode == ABSOLUTE_X)
        p1 += x;
      else
        p1 += y;

      tmp = (adr + (p1 & 0xff)) & 0xffff;
      // Effective address wraps within 16 bits (real 6502): $FFxx + index
      // carries past $FFFF and wraps to $00xx, not the extended/ROM region.
      adr = (adr + p1) & 0xffff;

      // If read - a fifth cycle patches the incorrect address...
      // Always done if RMW!
      if (read) {
        if (p1 > 0xff || write) {
          fetchByte(tmp);
          data = loadByte(adr);
        } else {
          data = loadByte(tmp);
        }
      } else {
        fetchByte(tmp);
      }
      break;
    case RELATIVE:
      pc++;
      adr = pc + (byte) p1;
      if (((adr ^ pc) & 0xff00) > 0) {
        // loose one cycle since adr is on another page...
        tmp = 2;
      } else {
        tmp = 1;
      }
      break;
    case ACCUMULATOR:
      data = acc;
      write = false;
      break;
    case INDIRECT_X:
      pc++;
      // unneccesary read... fetchByte(p1);
      fetchByte(p1);
      tmp = (p1 + x) & 0xff;

      // Pointer high byte wraps within zero page (real 6502 quirk):
      // for tmp=$FF the high byte comes from $00, not $0100.
      adr = (fetchByte((tmp + 1) & 0xff) << 8);
      adr |= fetchByte(tmp);

      if (read) {
        data = loadByte(adr);
      }
      break;
    case INDIRECT_Y:
      pc++;
      // Fetch hi and lo. Pointer high byte wraps within zero page
      // (real 6502 quirk): for p1=$FF the high byte comes from $00.
      adr = (fetchByte((p1 + 1) & 0xff) << 8);
      p1 = fetchByte(p1);
      p1 += y;

      tmp = (adr + (p1 & 0xff)) & 0xffff;
      // Effective address wraps within 16 bits (real 6502): a base of
      // $FFxx plus Y can carry past $FFFF and must wrap to $00xx, not
      // spill into the extended/ROM-bank memory region.
      adr = (adr + p1) & 0xffff;

      // If read - a sixth cycle patches the incorrect address...
      // Always done if RMW!
      if (read) {
        if (p1 > 0xff || write) {
          fetchByte(tmp);
          data = loadByte(adr);
        } else {
          data = loadByte(tmp);
        }
      } else {
        fetchByte(tmp);
      }
      break;
    case INDIRECT:
      pc++;
      // Fetch pointer (pc wraps within 16 bits at the $FFFF boundary)
      adr = (fetchByte(pc & 0xffff) << 8) + p1;

      // Calculate address
      tmp = (adr & 0xfff00) | ((adr + 1) & 0xff);
      // fetch the real address
      adr = fetchByte(adr);
      adr += (fetchByte(tmp) << 8);
      break;
    }

    // -------------------------------------------------------------------
    // Addressing handled! now on to instructions in order of appearance
    // -------------------------------------------------------------------

    // If RMW - it will write before proceeding
    boolean rmwWrite = read && write;
    if (rmwWrite) {
      rmwDummyWrite = true;
      storeByte(adr, data);
      rmwDummyWrite = false;
    }

    switch(op) {
    case BRK:
      brk = true;
      checkInterrupt = true;
      break;
    case AND:
      acc = acc & data;
      setZS(acc);
      break;
    case ADC:
      opADCimp(data);
      break;
    case SBC:
      opSBCimp(data);
      break;
    case ORA:
      acc = acc | data;
      setZS(acc);
      break;
    case EOR:
      acc = acc ^ data;
      setZS(acc);
      break;
    case BIT:
      sign = data > 0x7f;
      overflow = (data & 0x40) > 0;
      zero = (acc & data) == 0;
      break;
    case LSR:
      carry = (data & 0x01) != 0;
      data = data >> 1;
      zero = (data == 0);
      sign = false;
      break;
    case ROL:
      data = (data << 1) + (carry ? 1 : 0);
      carry = (data & 0x100) != 0;
      data = data & 0xff;
      setZS(data);
      break;
    case ROR:
      nxtcarry = (data & 0x01) != 0;
      data = (data >> 1) + (carry ? 0x80 : 0);
      carry = nxtcarry;
      setZS(data);
      break;
    case TXA:
      acc = x;
      setZS(acc);
      break;
    case TAX:
      x = acc;
      setZS(x);
      break;
    case TYA:
      acc = y;
      setZS(acc);
      break;
    case TAY:
      y = acc;
      setZS(y);
      break;
    case TSX:
      x = s;
      setZS(x);
      break;
    case TXS:
      s = x & 0xff;
      break;
    case DEC:
      data = (data - 1) & 0xff;
      setZS(data);
      break;
    case INC:
      data = (data + 1) & 0xff;
      setZS(data);
      break;
    case INX:
      x = (x + 1) & 0xff;
      setZS(x);
      break;
    case DEX:
      x = (x - 1) & 0xff;
      setZS(x);
      break;
    case INY:
      y = (y + 1) & 0xff;
      setZS(y);
      break;
    case DEY:
      y = (y - 1) & 0xff;
      setZS(y);
      break;
      // Jumps
    case JSR:
      // VICE 6510dtvcore.c:1201-1220 cycle order:
      //   3. STACK_PEEK + CLK_INC  (dummy read at $0100|S)
      //   4. PUSH PCH + CLK_INC
      //   5. PUSH PCL + CLK_INC
      //   6. LOAD reg_pc + CLK_INC (fetch ADH — last cycle)
      // The hi-byte fetch happens AFTER the pushes, not before.
      pc++;                              // pc -> address of hi byte (= N+2)
      fetchByte(s | 0x100);              // cycle 3: stack peek
      push((pc & 0xff00) >> 8);          // cycle 4: push PCH (of N+2)
      push(pc & 0x00ff);                 // cycle 5: push PCL
      // pc wraps within 16 bits: a JSR at $FFFE fetches its ADH from $0000,
      // not the extended/ROM-bank array at $10000. (Lorenz TRAP16/17.)
      adr = (fetchByte(pc & 0xffff) << 8) + p1;  // cycle 6: fetch ADH + JUMP
      pc = adr & 0xffff;
      break;
    case JMP:
      pc = adr;
      break;
    case RTS:
      fetchByte(s | 0x100);
      pc = pop() + (pop() << 8);
      pc = (pc + 1) & 0xffff;            // RTS return wraps within 16 bits
      fetchByte(pc);
      break;
    case RTI:
      fetchByte(s | 0x100);
      tmp = pop();
      boolean irqWasDisabledOnRti = disableInterupt;
      setStatusByte(tmp);
      if (!VICE_RTI_NO_IRQ_ENABLE_DELAY
          && irqWasDisabledOnRti && !disableInterupt) {
        irqEnableDelayOps = 1;
      }
      pc = pop() + (pop() << 8);
      brk = false;
      interruptInExec--;
      // Need to check for interrupts
      checkInterrupt = true;
      break;

    case TRP:
      monitor.info("TRAP Instruction executed");
      break;
    case NOP:
      break;
    case ASL:
      setCarry(data);
      data = (data << 1) & 0xff;
      setZS(data);
      break;
    case PHA:
      push(acc);
      break;
    case PLA:
      fetchByte(s | 0x100);
      acc = pop();
      setZS(acc);
      break;
    case PHP:
      brk = true;
      push(getStatusByte());
      brk = false;
      break;
    case PLP:
      fetchByte(s | 0x100);
      tmp = pop();
      boolean irqWasDisabled = disableInterupt;
      setStatusByte(tmp);
      if (irqWasDisabled && !disableInterupt) {
        irqEnableDelayOps = 1;
      }
      brk = false;
      checkInterrupt = true;
      break;
    case ANC:
      acc = acc & data;
      setZS(acc);
      carry = (acc & 0x80) != 0;
      break;
    case CMP:
      data = acc - data;
      carry = data >= 0;
      setZS((data & 0xff));
      break;
    case CPX:
      data = x - data;
      carry = data >= 0;
      setZS((data & 0xff));
      break;
    case CPY:
      data = y - data;
      carry = data >= 0;
      setZS((data & 0xff));
      break;
      // Branch instructions
    case BCC:
      branch(!carry, adr, tmp);
      break;
    case BCS:
      branch(carry, adr, tmp);
      break;
    case BEQ:
      branch(zero, adr, tmp);
      break;
    case BNE:
      branch(!zero, adr, tmp);
      break;
    case BVC:
      branch(!overflow, adr, tmp);
      break;
    case BVS:
      branch(overflow, adr, tmp);
      break;
    case BPL:
      branch(!sign, adr, tmp);
      break;
    case BMI:
      branch(sign, adr, tmp);
      break;
      // Modify flags
    case CLC:
      carry = false;
      break;
    case SEC:
      carry = true;
      break;
    case CLD:
      decimal = false;
      break;
    case SED:
      decimal = true;
      break;
    case CLV:
      overflow = false;
      break;
    case SEI:
      if (VICE_SEI_IRQ_WINDOW && !disableInterupt) {
        lastOpcodeDisablesIrq = true;
      }
      disableInterupt = true;
      break;
    case CLI:
      if (disableInterupt) {
        irqEnableDelayOps = 1;
      }
      disableInterupt = false;
      checkInterrupt = true;
      log(getName() + " Enabled interrupts: IRQ: " + chips.getIRQFlags() + " IRQLow: " + IRQLow);
      break;
      // Load / Store instructions
    case LDA:
      acc = data;
      setZS(data);
      break;
    case LDX:
      x = data;
      setZS(data);
      break;
    case LDY:
      y = data;
      setZS(data);
      break;
    case STA:
      data = acc;
      break;
    case STX:
      data = x;
      break;
    case STY:
      data = y;
      break;

      // -------------------------------------------------------------------
      //  Undocumented ops
      // -------------------------------------------------------------------
    case ANE:
      acc = p1 & x & (acc | 0xee);
      setZS(acc);
      break;
    case ARR: // ARR = AND + ROR ??? - not???
      // A'la frodo
      tmp = p1 & acc;
      acc = (carry ? (tmp >> 1) | 0x80 : tmp >> 1);
      if (!decimal) {
        setZS(acc);
        carry = (acc & 0x40) != 0;
        overflow = ((acc & 0x40) ^ ((acc & 0x20) << 1)) != 0;
      } else {
        sign = carry;
        zero = acc == 0;
        overflow = ((tmp ^ acc) & 0x40) != 0;
        if ((tmp & 0x0f) + (tmp & 0x01) > 5)
          acc = acc & 0xf0 | (acc + 6) & 0x0f;
        if (carry = ((tmp + (tmp & 0x10)) & 0x1f0) > 0x50)
          acc += 0x60;
      }
      break;

    case ASR: // AND + LSR
      acc = acc & data;
      nxtcarry = (acc & 0x01) != 0;
      acc = (acc >> 1);
      carry = nxtcarry;
      setZS(acc);
      break;

    case DCP:
      data = (data - 1) & 0xff;
      setZS(data);
      tmp = acc - data;
      carry = tmp >= 0;
      setZS((tmp & 0xff));
      break;

    case ISB:
      data = (data + 1) & 0xff;
      // SBC PART!
      opSBCimp(data);
      break;
    case LAX:
      acc = x = data;
      setZS(acc);
      break;

    case LAS:  // A,X,S:={adr}&S
      acc = x = s = (data & s);
      setZS(acc);
      break;

    case LXA:
      x = acc = (acc | 0xee) & p1;
      setZS(acc);
      break;

    case RLA:
      data = (data << 1) + (carry ? 1 : 0);
      carry = (data & 0x100) != 0;
      data = data & 0xff;
      // AND PART
      acc = acc & data;
      zero = (acc == 0);
      sign = (acc > 0x7f);
      break;

    case RRA: // RRA ROR + ADC
      nxtcarry = (data & 0x01) != 0;
      data = (data >> 1) + (carry ? 0x80 : 0);
      carry = nxtcarry;
      // ADC PART!
      opADCimp(data);
      break;

    case SBX:
      x = ((acc & x) - p1);
      carry = x >= 0;
      x = x & 0xff;
      setZS(x);
      break;
    case SHA:
      data =  acc & x & ((adr  >> 8) + 1);
      break;

    case SHS:
      data = acc & x & ((adr >> 8) + 1);
      s = acc & x;
      break;

    case SHX:
      data = x & ((adr >> 8) + 1);
      break;
    case SHY:
      data = y & ((adr >> 8) + 1);
      break;

    case SAX:
      data = acc & x;
      break;

    case SRE:
      carry = (data & 0x01) != 0;
      data = data >> 1;
      // EOR PART
      acc = acc ^ data;
      setZS(acc);
      break;

    case SLO:
      // ASL
      setCarry(data);
      data = (data << 1) & 0xff;
      // Written later...
      // THE ORA PART
      acc = acc | data;
      setZS(acc);
      break;
    default:
      unknownInstruction(pc, op);
    }

    if (write) {
      storeByte(adr, data);
    } else if (addrMode == ACCUMULATOR) {
      acc = data;
    }

    if (hadIrqEnableDelay && irqEnableDelayOps > 0) {
      irqEnableDelayOps--;
    }

    // Phase J trace MOVED to top of emulateOp (line ~465) to match
    // VICE's FETCH_OPCODE trace point (pre-state).
  }

  /** Phase J: monotonic instruction counter for diff alignment. */
  private long jac64InstrCounter = 0;

  public void unknownInstruction(int pc, int op) {
    System.out.println("Unknown instruction: " + op);
  }

  public void init(ExtChip scr) {
    super.init();
    installROMS();
    chips = scr;
  }

  protected abstract void installROMS();
  protected abstract void patchROM(PatchListener list);

  public void hardReset() {
    for (int i = 0; i < 0x10000; i++) {
      memory[i] = 0;
    }
    reset();
  }

  private void doReset() {
    sign = false;
    zero = false;
    overflow = false;
    carry = false;
    decimal = false;
    brk = false;

    disableInterupt = false;
    interruptInExec = 0;
    rindex = 0;

    checkInterrupt = false;
    NMILow = false;
    NMILastLow = false;
    NMIPending = false;
    IRQLow = false;
    irqRequested = false;
    irqReleaseCycle = IRQ_RELEASE_DISABLED;
    log("Set IRQLOW to false...");
    resetFlag = false;

    scheduler.empty();
    chips.reset();

    pc = fetchByte(0xfffc) + (fetchByte(0xfffd) << 8);

    log("Reset to: " + pc);

    if (list != null)
      patchROM(list);
  }

  // Reset the MOS6510Core!!!
  // This can be called with any thread!!!
  public void reset() {
    // Clear and copy!
    // The processor flags
    NMILow = false;
    NMIPending = false;
    brk = false;
    IRQLow = false;
    irqRequested = false;
    irqReleaseCycle = IRQ_RELEASE_DISABLED;
    log("Set IRQLOW to false...");
    resetFlag = true;
    checkInterrupt = true;
  }
  
  public void setDebug(int adr, String msg) {
    if (debugInfo == null) {
      debugInfo = new String[0x10000];
    }
    debugInfo[adr & 0xffff] = msg;
  }

  public String getDebug(int adr) {
    if (debugInfo != null)
      return debugInfo[adr & 0xffff];
    return null;
  }

  protected void loadROM(InputStream ins, int startMem, int len) {
    try {
      BufferedInputStream stream = new BufferedInputStream(ins);
      if (stream != null) {
        byte[] charBuf = new byte[len];
        int pos = 0;
        int t;
        try {
          while((t = stream.read(charBuf, pos, len - pos)) > 0) {
            pos += t;
          }
          monitor.info("Installing rom at :" + Integer.toString(startMem,16) + " size:" + pos);
          for (int i = 0; i < charBuf.length; i++) {
            memory[i + startMem] = ((int)charBuf[i]) & 0xff;
          }
        } catch (Exception e) {
          monitor.error("Problem reading rom file ");
          e.printStackTrace();
        } finally {
          try {
            stream.close();
          } catch (Exception e2) {}
        }
      }
    } catch(Exception e) {
      monitor.error("Error loading resource" + e);
    }
  }

  void log(String s) {
    if (debug)
      monitor.info(getName() + " : " + s);
  }

  public boolean getIRQLow() {
    updatePendingIRQLineState();
	  return IRQLow;
  }

  public boolean isRmwDummyWrite() {
    return rmwDummyWrite;
  }
}
