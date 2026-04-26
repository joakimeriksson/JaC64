/**
 * This file is a part of JaC64 - a Java C64 Emulator
 * Main Developer: Joakim Eriksson (Dreamfabric.com)
 * Contact: joakime@sics.se
 * Web: http://www.dreamfabric.com/c64
 * ---------------------------------------------------
 * This is the CPU file for Commodore 64 with its
 * ROM files and memory management, etc.
 *
 * @(#)cpu.java	Created date: 99-5-17
 *
 */
package com.dreamfabric.jac64;
import com.dreamfabric.c64utils.*;
/**
 * CPU "implements" the C64s 6510 processor in java code. reimplemented from old
 * CPU.java
 *
 * @author Joakim Eriksson (joakime@sics.se)
 * @version $Revision:$, $Date:$
 */
public class CPU extends MOS6510Core {

  public static final boolean DEBUG_EVENT = false;
  // The IO RAM memory at 0x10000 (just since there is RAM there...)
  public static final int IO_OFFSET = 0x10000 - 0xd000;
  public static final int BASIC_ROM2 = 0x1a000;
  public static final int KERNAL_ROM2 = 0x1e000;
  public static final int CHAR_ROM2 = 0x1d000;

  public static final boolean EMULATE_1541 = true;

  public static final int CH_PROTECT = 1;
  public static final int CH_MONITOR_WRITE = 2;
  public static final int CH_MONITOR_READ = 4;

  private int romFlag = 0xa000;

  // Defaults for the ROMs
  public boolean basicROM = true;
  public boolean kernalROM = true;
  public boolean charROM = false;
  public boolean ioON = true;

  // The state of the program (runs if running = true)
  public boolean running = true;
  public boolean pause = false;
  private volatile boolean pausedState = false;

  private static final long CYCLES_PER_DEBUG = 10000000;
  public static final boolean DEBUG = false;

  private C1541Emu c1541;
  private Loader loader;
  private int windex = 0;
  private final int execTraceFrom =
      Integer.getInteger("jac64.execTraceFrom", -1);
  private final int execTraceTo =
      Integer.getInteger("jac64.execTraceTo", -1);
  private final boolean baTraceEnabled =
      Boolean.getBoolean("jac64.baTrace");
  private final int baTraceFromLine =
      Integer.getInteger("jac64.baTraceFrom", -1);
  private final int baTraceToLine =
      Integer.getInteger("jac64.baTraceTo", -1);
  private java.io.PrintStream execTraceOut;
  private java.io.PrintStream baTraceOut;

  private int cheatMon[];
  private AutoStore[] autoStore;

  public CPU(IMonitor m, String cb, Loader loader) {
    super(m, cb);
    memory = new int[0x20000];
    this.loader = loader;
    if (EMULATE_1541) {
      IMonitor d = new DefaultIMon(); // new Debugger();
      c1541 = new C1541Emu(d, cb, loader);
      // d.init(c1541);
      // d.setEnabled(true);
    }
  }


  public C1541Emu getDrive() {
    return c1541;
  }


  private final void schedule(long cycles) {
    chips.clock(cycles);
    while (cycles >= scheduler.nextTime) {
      TimeEvent t = scheduler.popFirst();
      if (t != null) {
        if (DEBUG_EVENT) {
          System.out.println("Executing event: " + t.getShort());
        }
        // Give it the actual time also!!!
        t.execute(cycles);
      } else {
        if (DEBUG_EVENT) System.out.println("Nothign to execute...");
        return;
      }
    }
  }

  // VICE-style CPU/VIC interleaving model.
  //
  // VICE's pattern per memory access:
  //   1. check_ba()       — uses BA flag set by the PREVIOUS vicii_cycle().
  //                         Stalls if BA-low. Loops vicii_cycle until high.
  //   2. read or write    — at current clock value.
  //   3. CLK_INC()        — advances clock + runs vicii_cycle once for the
  //                         new cycle. May set BA flag for the NEXT access.
  //
  // Key consequence: BA detection has 1-cycle of LATENCY relative to the
  // VIC processing the BA-low cycle. This is what makes Krestage 3's side-
  // border-open trick (DEC $D016 followed by ChkBrdR check) work — the DEC's
  // dummy/final writes complete BEFORE the BA check observes their effect.
  //
  // JaC64 maps this as:
  //   fetchByte: waitForBus  -> cycles++ -> schedule(cycles)   -> read
  //   writeByte: cycles++    -> schedule(cycles)               -> write
  //              (no waitForBus — VICE writes don't check BA)
  //
  // Set -Djac64.viceMem=false to revert to legacy ordering for A/B testing.
  private static final boolean VICE_MEM_MODEL =
      !"false".equalsIgnoreCase(System.getProperty("jac64.viceMem", "true"));

  private void waitForBus() {
    waitForBus(false);
  }

  private void waitForBus(boolean isRead) {
    if (baLowUntil <= cycles) {
      return;
    }
    traceBaEvent("BA-WAIT-START until=" + baLowUntil);
    while (baLowUntil > cycles) {
      cycles++;
      schedule(cycles);
    }
    traceBaEvent("BA-WAIT-END");
  }

  // Reads the memory with all respect to all flags...
  protected final int fetchByte(int adr) {
    if (VICE_MEM_MODEL) {
      /* VICE order: check_ba (with prev cycle's BA flag) -> CLK_INC. */
      waitForBus(true);
      cycles++;
      // Sample IRQ line BEFORE schedule() so we see the line state
      // from END OF PREVIOUS cycle, not after this cycle's VIC work.
      // This adds a 1-cycle propagation delay between an IRQ source
      // (raised during a VIC cycle handler) and the CPU seeing it,
      // matching real 6510 semantics where line transitions take one
      // Phi cycle to propagate.
      sampleIrqLine();
      schedule(cycles);
    } else {
      /* Legacy order: cycles++ -> schedule -> waitForBus. */
      cycles++;
      sampleIrqLine();
      schedule(cycles);
      waitForBus(true);
    }

    if ((romFlag & adr) == romFlag) {
      return memory[rindex = adr | 0x10000];
    } else if ((adr & 0xf000) == 0xd000) {
      if (ioON) {
        return chips.performRead(rindex = adr, cycles);
      } else if (charROM) {
        return memory[rindex = adr | 0x10000];
      } else {
        return memory[rindex = adr];
      }
    } else {
      return memory[rindex = adr];
    }
  }

  // A byte is written directly to memory or to ioChips.
  //
  // VICE memory-bus split (jac64.viceMemBus, default OFF — opt-in):
  //   - Memory writes (non-IO): schedule VIC catch-up FIRST, then apply the
  //     write. Models Phi1/Phi2 hardware semantics — VIC's Phi1 read at
  //     cycle N happens before CPU's Phi2 write, so VIC sees the OLD byte
  //     for cycle N's fetch and the new byte takes effect from cycle N+1.
  //   - IO writes ($D000-$DFFF): schedule AFTER the write, so the new VIC
  //     register state is observed when VIC catches up (preserves the
  //     side-border-open trick fixed in 4d05dc6).
  //
  // Tested against Krestage 3 scroll-in and did NOT fix the right-half
  // color stripes — the artifact must have a different root cause. Keeping
  // the option for future experimentation; default-off so we don't change
  // any other demo's timing.
  // Enable with -Djac64.viceMemBus=true.
  private static final boolean VICE_MEM_BUS_SPLIT =
      VICE_MEM_MODEL && Boolean.getBoolean("jac64.viceMemBus");

  protected final void writeByte(int adr, int data) {
    cycles++;
    if (!VICE_MEM_MODEL) {
      // Legacy: schedule + waitForBus BEFORE write. Writes can stall on BA-low.
      schedule(cycles);
      waitForBus();
    }
    if (adr <= 1) {
      memory[adr] = data;
      int p = (memory[0] ^ 0xff) | memory[1];

      kernalROM = ((p & 2) == 2); // Kernal on
      basicROM = ((p & 3) == 3); // Basic on

      charROM = ((p & 3) != 0) && ((p & 4) == 0);
      ioON = ((p & 3) != 0) && ((p & 4) != 0);

      if (basicROM)
        romFlag = 0xa000;
      else if (kernalROM)
        romFlag = 0xe000;
      else
        romFlag = 0x10000; // No Rom at all (Basic / Kernal)
    }

    adr &= 0xffff;
    final boolean isIO = ioON && ((adr & 0xf000) == 0xd000);

    if (VICE_MEM_BUS_SPLIT && !isIO) {
      // Phi1/Phi2 split (opt-in): VIC's Phi1 fetch at cycle N runs
      // BEFORE CPU's Phi2 write applies. Catch up the VIC first; the
      // memory[] update happens after — visible to VIC's case-(N+1)
      // fetch onwards. Default off because the VICE STORE_ABS-style
      // schedule-before-store ALSO breaks the Krestage 3 banner trick
      // (RMW DEC $D016 timing). The conflict suggests JaC64's case
      // dispatcher numbering is off by 1 vs VICE's PAL cycle table —
      // fixing that root cause would let schedule-before-store work
      // for both VIC tests AND the banner. Multi-day refactor.
      schedule(cycles);
    }

    if (isIO) {
      chips.performWrite(adr, data, cycles);
    } else {
      memory[windex = adr] = data;
      if (Boolean.getBoolean("jac64.traceSprPtrWrites")
          && adr >= 0x07F8 && adr <= 0x07FF) {
        System.err.println("SPRPTR-WR adr=$" + Integer.toHexString(adr)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " clk=" + cycles
            + " pc=$" + Integer.toHexString(pc & 0xffff));
      }
    }
    if (VICE_MEM_MODEL && isIO) {
      schedule(cycles);
    } else if (VICE_MEM_MODEL && !VICE_MEM_BUS_SPLIT) {
      schedule(cycles);
    }
    sampleIrqLine();
  }

  // Sample the IRQ line at this CPU cycle and shift the rolling history.
  // After the last memory access of an instruction, irqLineAtPrevCall
  // holds the value sampled at the SECOND-TO-LAST cycle — matching real
  // 6510 IRQ-latching semantics. See docs/vic-ii/PHASE_A_IRQ_LATCHING.md.
  private void sampleIrqLine() {
    if (PHASE_A_IRQ_LATCH) {
      irqLineAtPrevCall = irqLineAtCurrCall;
      irqLineAtCurrCall = irqRequested;
    }
  }


  private void fixRindex(int adr) {
    // ROM/RAM address fix
    if ((basicROM && ((adr & 0xe000) == 0xa000))
        || (kernalROM && ((adr & 0xe000) == 0xe000))
        || (charROM && ((adr & 0xf000) == 0xd000))) {
      // Add ROM address for the read!
      adr |= 0x10000;
    }
    rindex = adr;
  }

  public void poke(int address, int data) {
    writeByte(address & 0xffff, data & 0xff);
  }

  public void patchROM(PatchListener list) {
    this.list = list;

    int pos = 0xf49e | 0x10000;
    memory[pos++] = M6510Ops.JSR;
    memory[pos++] = 0xd2;
    memory[pos++] = 0xf5;

    System.out.println("Patched LOAD at: " + Hex.hex2(pos));
    memory[pos++] = LOAD_FILE;
    memory[pos++] = M6510Ops.RTS;
  }

  public void runBasic() {
    memory[631] = (int) 'R';
    memory[632] = (int) 'U';
    memory[633] = (int) 'N';
    memory[634] = 13;// enter
    memory[198] = 4; // length
  }

  public void enterText(String txt) {
    System.out.println("Entering text into textbuffer: " + txt);
    txt = txt.toUpperCase();
    int len = txt.length();
    int pos = 0;
    for (int i = 0, n = len; i < n; i++) {
      char c = txt.charAt(i);
      if (c == '~')
        c = 13;
      memory[631 + pos] = c;
      pos++;
      if (pos == 5) {
        memory[198] = pos;
        pos = 0;
        int tries = 5;
        while (tries > 0 && memory[198] > 0) {
          try {
            Thread.sleep(50);
          } catch (Exception e) {
            e.printStackTrace();
          }
          tries--;
          if (tries == 0) {
            System.out.println("Buffer still full: " + memory[198]);
          }
        }
      }
    }
    memory[198] = pos;
    int tries = 5;
    while (tries > 0 && memory[198] > 0) {
      try {
        Thread.sleep(50);
      } catch (Exception e) {
        e.printStackTrace();
      }
      tries--;
      if (tries == 0) {
        System.out.println("Buffer still full: " + memory[198]);
      }
    }
  }

  protected void installROMS() {
    loadROM(loader.getResourceStream("/roms/kernal.c64"), KERNAL_ROM2,
        0x2000);
    loadROM(loader.getResourceStream("/roms/basic.c64"), BASIC_ROM2, 0x2000);
    loadROM(loader.getResourceStream("/roms/chargen.c64"), CHAR_ROM2,
        0x1000);
  }

  public void run(int address) {
    reset();
    running = true;
    setPC(address);
    loop();
  }

  public void unknownInstruction(int pc, int op) {
    switch (op) {
    case SLEEP:
      cycles += 100;
      break;
    case LOAD_FILE:
      if (acc == 0)
        monitor.info("**** LOAD FILE! ***** PC = " +
            Integer.toString(pc, 16) + " => " +
            Integer.toString(rindex, 16));
      else
        monitor.info("**** VERIFY!    ***** PC = " + pc + " => " + rindex);
      int len;
      int mptr = memory[0xbb] + (memory[0xbc] << 8);
      monitor.info("Filename len:" + (len = memory[0xb7]));
      String name = "";
      for (int i = 0; i < len; i++)
        name += (char) memory[mptr++];
      name += '\n';
      int sec = memory[0xb9];
      monitor.info("name = " + name);
      monitor.info("Sec Address: " +  sec);
      int loadAdr = -1;
      if (sec == 0)
        loadAdr = memory[0x2b] + (memory[0x2c] << 8);
      if (list != null) {
        if (list.readFile(name, loadAdr)) {
          acc = 0;
        }
      }
      pc--;
      break;
    }
  }



  // Takes the thread and loops!!!
  public void start() {
    run(0xfce2); // Power UP reset routine!
    while (pause || running) {
      pausedState = true;
      System.out.println("Entering pause mode...");
      synchronized(this) {
        while (pause) {
          try {
            wait();
          } catch (Exception e) {
          }
        }
      }
      if (!running) {
        break;
      }
      pausedState = false;
      System.out.println("Exiting pause mode...");
      loop();
    }
  }

  // Should pause the application!
  public synchronized void setPause(boolean p) {
    if (p) {
      pause = true;
      running = false;
    } else {
      pause = false;
      running = true;
    }
    notify();
  }

  public boolean pauseAndWait(long timeoutMs) {
    setPause(true);
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!pausedState) {
      if (System.currentTimeMillis() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  public synchronized void stop() {
    // stop completely
    running = false;
    pause = false;
    notify();
  }

  public void reset() {
    writeByte(1, 0x7);
    super.reset();

    if (EMULATE_1541) {
      c1541.reset();
    }
  }

  public void setPC(int startAdress) {
    // The processor flags
    pc = startAdress;
  }

  private void setStatusFromByte(int status) {
    carry = (status & 0x01) != 0;
    zero = (status & 0x02) != 0;
    disableInterupt = (status & 0x04) != 0;
    decimal = (status & 0x08) != 0;
    brk = (status & 0x10) != 0;
    overflow = (status & 0x40) != 0;
    sign = (status & 0x80) != 0;
  }

  public boolean callSubroutine(int address, long timeoutMs) {
    if (!pausedState && !pauseAndWait(timeoutMs)) {
      return false;
    }

    int returnAddress = pc & 0xffff;
    int jsrReturn = (returnAddress - 1) & 0xffff;

    memory[(s & 0xff) | 0x100] = (jsrReturn >> 8) & 0xff;
    s = (s - 1) & 0xff;
    memory[(s & 0xff) | 0x100] = jsrReturn & 0xff;
    s = (s - 1) & 0xff;

    // BASIC SYS uses these locations to seed the machine-code registers.
    acc = memory[0x030c] & 0xff;
    x = memory[0x030d] & 0xff;
    y = memory[0x030e] & 0xff;
    setStatusFromByte(memory[0x030f] & 0xff);
    pc = address & 0xffff;

    setPause(false);
    return true;
  }

  public void jumpToSubroutine(int address) {
    int stubAddress = 0x033c;
    int basicSysReturn = 0xe147;

    // BASIC SYS uses these locations to seed the machine-code registers.
    acc = memory[0x030c] & 0xff;
    x = memory[0x030d] & 0xff;
    y = memory[0x030e] & 0xff;
    setStatusFromByte(memory[0x030f] & 0xff);

    // Match the observed VICE/BASIC SYS entry frame closely enough for
    // bootstrap code that keys off TSX/SP on entry.
    s = 0xf8;

    memory[stubAddress] = 0x20;
    memory[stubAddress + 1] = address & 0xff;
    memory[stubAddress + 2] = (address >> 8) & 0xff;
    memory[stubAddress + 3] = 0x4c;
    memory[stubAddress + 4] = basicSysReturn & 0xff;
    memory[stubAddress + 5] = (basicSysReturn >> 8) & 0xff;
    jump(stubAddress);
  }

  public String getName() {
    return "C64 CPU";
  }

  private void traceExecIfEnabled() {
    if (execTraceFrom < 0 || execTraceTo < execTraceFrom) {
      return;
    }

    int pcNow = pc & 0xffff;
    if (pcNow < execTraceFrom || pcNow > execTraceTo) {
      return;
    }

    if (execTraceOut == null) {
      String path = System.getProperty("jac64.execTraceFile",
          "/tmp/jac64_exec_trace.log");
      try {
        execTraceOut = new java.io.PrintStream(path);
      } catch (Exception e) {
        execTraceOut = System.out;
      }
    }

    long lineClock = 0;
    int rasterLine = -1;
    if (chips instanceof C64Screen) {
      C64Screen screen = (C64Screen) chips;
      lineClock = screen.lastLine;
      rasterLine = screen.vbeam;
    }

    String pcHex = Integer.toHexString(0x10000 | pcNow).substring(1);
    int op0 = memory[pcNow] & 0xff;
    int op1 = memory[(pcNow + 1) & 0xffff] & 0xff;
    int op2 = memory[(pcNow + 2) & 0xffff] & 0xff;
    execTraceOut.println("EXEC pc=$" + pcHex +
        " op=" + Hex.hex2(op0) + "," + Hex.hex2(op1) + "," + Hex.hex2(op2) +
        " vbeam=" + rasterLine +
        " cyc=" + (cycles - lineClock) +
        " clk=" + cycles +
        " a=$" + Hex.hex2(acc & 0xff) +
        " x=$" + Hex.hex2(x & 0xff) +
        " y=$" + Hex.hex2(y & 0xff) +
        " sp=$" + Hex.hex2(s & 0xff) +
        " p=$" + Hex.hex2(getStatusByte() & 0xff));
  }

  private boolean shouldTraceBa() {
    if (!baTraceEnabled) {
      return false;
    }
    if (!(chips instanceof C64Screen)) {
      return true;
    }
    if (baTraceFromLine < 0 || baTraceToLine < 0) {
      return true;
    }

    int rasterLine = ((C64Screen) chips).vbeam;
    if (baTraceFromLine <= baTraceToLine) {
      return rasterLine >= baTraceFromLine && rasterLine <= baTraceToLine;
    }
    return rasterLine >= baTraceFromLine || rasterLine <= baTraceToLine;
  }

  public void traceBaEvent(String event) {
    if (!shouldTraceBa()) {
      return;
    }
    if (baTraceOut == null) {
      String path = System.getProperty("jac64.baTraceFile",
          "/tmp/jac64_ba_trace.log");
      try {
        baTraceOut = new java.io.PrintStream(path);
      } catch (Exception e) {
        baTraceOut = System.out;
      }
    }

    long lineClock = 0;
    int rasterLine = -1;
    if (chips instanceof C64Screen) {
      C64Screen screen = (C64Screen) chips;
      lineClock = screen.lastLine;
      rasterLine = screen.vbeam;
    }

    baTraceOut.println(event +
        " clk=" + cycles +
        " vbeam=" + rasterLine +
        " cyc=" + (cycles - lineClock) +
        " baLowUntil=" + baLowUntil +
        " pc=$" + Integer.toHexString(pc & 0xffff));
  }

  /**
   * The main emulation <code>loop</code>.
   *
   * @param startAdress
   *            an <code>int</code> value that represent the starting
   *            address of the emulator
   */
  public void loop() {
    if (cheatMon != null) {
      cheatLoop();
      return;
    }
    long next_print = cycles + CYCLES_PER_DEBUG;
    // How much should this be???
    monitor.info("Starting CPU at: " + Integer.toHexString(pc));
    try {
      while (running) {

        // Debugging?
        if (monitor.isEnabled()) { // || interruptInExec > 0) {
          if (baLowUntil <= cycles) {
            fixRindex(pc); // sets the rindex!
            monitor.disAssemble(memory, rindex, acc, x, y,
                (byte) getStatusByte(), interruptInExec,
                lastInterrupt);
          }
        }

        traceExecIfEnabled();

        // Run one instruction!
        emulateOp();

        // Deterministic pause-at-cycle: exit loop at the first
        // instruction boundary past the requested cycle. Used by the
        // TestRaster autostart harness to inject PRGs at a known
        // emulated cycle count, producing reproducible test results
        // across JVM runs.
        if (pauseAtCycle >= 0 && cycles >= pauseAtCycle) {
          pauseAtCycle = -1;
          pause = true;
          running = false;
          break;
        }

        // Also allow the 1541 to run an instruction!
        // 1541 drive runs on-demand only (synced on $DD00 read/write)
        // Matching VICE's DRIVE_IDLE_SKIP_CYCLES architecture

        nr_ins++;
        if (next_print < cycles) {
          long sec = System.currentTimeMillis() - lastMillis;
          int level = monitor.getLevel();

          if (DEBUG && level > 1) {
            monitor.info("--------------------------");
            monitor.info("Nr ins:" + nr_ins + " sec:" + (sec)
                + " -> " + ((nr_ins * 1000) / sec) + " ins/s"
                + "  " + " clk: " + cycles + " clk/s: "
                + ((CYCLES_PER_DEBUG * 1000) / sec) + "\n"
                + ((nr_irq * 1000) / sec));
            if (level > 2)
              monitor.disAssemble(memory, rindex, acc, x, y,
                  (byte) getStatusByte(), interruptInExec,
                  lastInterrupt);
            monitor.info("--------------------------");
          }
          nr_irq = 0;
          nr_ins = 0;
          lastMillis = System.currentTimeMillis();
          next_print = cycles + CYCLES_PER_DEBUG;
        }
      }
    } catch (Exception e) {
      monitor.error("Exception in loop " + pc + " : " + e);
      e.printStackTrace();
      monitor.disAssemble(memory, rindex, acc, x, y,
          (byte) getStatusByte(), interruptInExec, lastInterrupt);
    }
  }

  // -------------------------------------------------------------------
  // Cheat loop!
  // Protection
  // + rule triggered auto get/store
  // Rule: xpr & xpr & xpr ...
  // rule: int[] adr, cmptype, cmpval ...
  // autostore: int[] adr, len => result in hex! from adr and on!
  // -------------------------------------------------------------------

  public void setAutoStore(int index, AutoStore au) {
    autoStore[index] = au;
  }

  public AutoStore getAutoStore(int index) {
    return autoStore[index];
  }

  public void setCheatEnabled(int maxAutostores) {
    cheatMon = new int[0x10000];
    autoStore = new AutoStore[maxAutostores];
  }

  public void protect(int address, int value) {
    cheatMon[address] = (cheatMon[address] & 0xff) | (value << 8) | CH_PROTECT;
  }

  public void monitorRead(int address) {
    cheatMon[address] |= CH_MONITOR_READ;
  }

  public void monitorWrite(int address) {
    cheatMon[address] |= CH_MONITOR_WRITE;
  }


  public void cheatLoop() {
    int t;
    try {
      while (running) {

        // Run one instruction!
        emulateOp();

        // Processor read from address...
        if (rindex < 0x10000) {
          if ((t = cheatMon[rindex]) != 0) {
            if ((t & CH_MONITOR_READ) != 0) {
              for (int i = 0, n = autoStore.length; i < n; i++) {
                if (autoStore[i] != null)
                  autoStore[i].checkRules(memory);
              }
            }
          }
        }
        if (windex < 0x10000) {
          if ((t = cheatMon[windex]) != 0) {
            if ((t & CH_PROTECT) != 0) {
              // Write back value from then protected...
              memory[windex] = (cheatMon[windex] >> 16) & 0xff;
            }
            if ((t & CH_MONITOR_WRITE) != 0) {
              for (int i = 0, n = autoStore.length; i < n; i++) {
                if (autoStore[i] != null)
                  autoStore[i].checkRules(memory);
              }
            }
          }
        }

        // Also allow the 1541 to run an instruction!
        // 1541 drive runs on-demand only (synced on $DD00 read/write)
        // Matching VICE's DRIVE_IDLE_SKIP_CYCLES architecture
      }
    } catch (Exception e) {
      monitor.error("Exception in loop " + pc + " : " + e);
      e.printStackTrace();
      monitor.disAssemble(memory, rindex, acc, x, y,
          (byte) getStatusByte(), interruptInExec, lastInterrupt);
    }
  }
}
