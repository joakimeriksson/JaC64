/**
 * This file is a part of JaC64 - a Java C64 Emulator
 * Main Developer: Joakim Eriksson (Dreamfabric.com)
 * Contact: joakime@sics.se
 * Web: http://www.dreamfabric.com/c64
 * ---------------------------------------------------
 *
 * Platform-neutral VIC-II emulation. No AWT/Swing dependencies.
 * Desktop rendering is handled by C64Canvas; Android by EmulatorSurfaceView.
 */

package com.dreamfabric.jac64;

/**
 * Implements the VIC chip + some other HW
 *
 * @author  Joakim Eriksson (joakime@sics.se) / main developer, still active
 * @author  Jan Blok (jblok@profdata.nl) / co-developer during ~2001
 * @version $Revision: 1.11 $, $Date: 2006/05/02 16:26:26 $
 */

public class C64Screen extends ExtChip implements Observer {
  public static final String version = "1.11";

  public static final int SERIAL_ATN = (1 << 3);
  public static final int SERIAL_CLK_OUT = (1 << 4);
  public static final int SERIAL_DATA_OUT = (1 << 5);
  public static final int SERIAL_CLK_IN = (1 << 6);
  public static final int SERIAL_DATA_IN = (1 << 7);

  public static final int RESID_6581 = 1;
  public static final int RESID_8580 = 2;
  public static final int JACSID = 3;

  public static final boolean IRQDEBUG = false;
  public static final boolean SPRITEDEBUG = false;
  public static final boolean IODEBUG = false;
  public static final boolean VIC_MEM_DEBUG = false;
  public static final boolean BAD_LINE_DEBUG = false;
  public static final boolean STATE_DEBUG = false;
  public static final boolean DEBUG_IEC = false;

  public static final boolean DEBUG_CYCLES = false;
  private static final int RASTER_LINES = 312;
  private static final long RASTER_IRQ_DISABLED = Long.MAX_VALUE;
  private static final int BADLINE_FETCH_CYCLE = 15;

  public static final int IO_UPDATE = 37;
  // This is PAL speed! - will be called each scan line...

  private static final int VIC_IRQ = 1;

  // This might be solved differently later!!!
  public static final int CYCLES_PER_LINE = VICConstants.SCAN_RATE;

  // Allow the IO to write in same as RAM
  public static final int IO_OFFSET = CPU.IO_OFFSET;
  public static final boolean SOUND_AVAIABLE = true;

  public static final int LABEL_COUNT = 32;
  private int colIndex = 0;

  // This is the screen width and height used...
  private final static int SC_WIDTH = 384; //403;
  private final static int SC_HEIGHT = 284;
  private final int SC_XOFFS = 32;
  // Done: this should be - 24!
  private final int SC_SPXOFFS = SC_XOFFS - 24;
  private final int FIRST_VISIBLE_VBEAM = 15;
  private final int SC_SPYOFFS = FIRST_VISIBLE_VBEAM + 1;


  private IMonitor monitor;

  private int targetScanTime = 20000;
  private int actualScanTime = 20000;
  private long lastScan = 0;
  private long nextIOUpdate = 0;
  private boolean DOUBLE = false;
  private int reset = 100;

  private int[] memory;

  ExtChip sidChip;

  CIA cia[];
  //  C1541 c1541;
  C1541Chips c1541Chips;

  TFE_CS8900 tfe;

  public int iecLines = 0;
  public boolean iecTrace = false;
  public long iecTraceCount = 0;
  public static final int IEC_LOG_SIZE = 200;
  public String[] iecLog = new String[IEC_LOG_SIZE];
  public int iecLogPos = 0;
  private int iecLoopReadLogs = 0;
  // for disk emulation...
  int cia2PRA = 0;
  int cia2DDRA = 0;

  private int lastTrack = 0;
  private int lastSector = 0;
  boolean ledOn = false;
  boolean motorOn = false;

  // This is an IEC emulation (non ROM based)
  boolean emulateDisk = false; //true; //!CPU.EMULATE_1541; // false;

  private int[] cbmcolor = VICConstants.COLOR_SETS[0];

  // -------------------------------------------------------------------
  // VIC-II variables
  // -------------------------------------------------------------------
  public int vicBank;
  public int charSet;
  public int videoMatrix;
  public int videoMode;

  // VIC Registers
  int irqMask = 0;
  int irqFlags = 0;
  int control1 = 0;
  int control2 = 0;
  int sprXMSB = 0;
  int sprEN = 0;
  int sprYEX = 0;
  int sprXEX = 0;
  int sprPri = 0;
  int sprMul = 0;
  int sprCol = 0;
  int sprBgCol = 0;
  int sprMC0 = 0;
  int sprMC1 = 0;
  int vicMem = 0;
  int vicMemDDRA = 0;
  int vicMemDATA = 0;
  // Read for debugging on other places...
  public int vbeam = 0; // read at d012
  public int raster = 0;
  int bCol = 0;
  int bgCol[] = new int[4];

  private int vicBase = 0;
  private boolean badLine = false;
  private int spr0BlockSel;

  // New type of position in video matrix - Video Counter (VIC II docs)
  int vc = 0;
  int vcBase = 0;
  int rc = 0;
  int vmli = 0;
  // The current vBeam pos - 9... => used for keeping track of memory
  // position to write to...
  int vPos = 0;
  int mpos = 0;

  int displayWidth = SC_WIDTH;
  int displayHeight = SC_HEIGHT;
  int offsetX = 0;
  int offsetY = 0;

  // Cached variables...
  boolean gfxVisible = false;
  boolean paintBorder = false;
  boolean paintSideBorder = false;

  int borderColor = cbmcolor[0];
  int bgColor = cbmcolor[1];

  private boolean extended = false;
  private boolean multiCol = false;
  private boolean blankRow = false;
  private boolean hideColumn = false;

  int multiColor[] = new int[4];

  // 48 extra for the case of an expanded sprite byte
  int collissionMask[] = new int[SC_WIDTH + 48];

  Sprite sprites[] = new Sprite[8];

  private int horizScroll = 0;
  private int vScroll = 0;
  private int badLineFetchStartColumn = 0;
  private int badLineDummyColumns = 0;
  private int badLineFetchSourceColumn = 0;
  private long rasterIrqClock = RASTER_IRQ_DISABLED;

  // The font is in a copy in "ROM"...
  private int charMemoryIndex = 0;

  // Caching all 40 chars (or whatever) each "bad-line"
  private int[] vicCharCache = new int[40];
  private int[] vicColCache = new int[40];

  private AudioDriver audioDriver;

  // Double-buffered pixel arrays for tear-free rendering.
  // VIC-II writes into mem (back buffer), which is swapped to memFront
  // at frame completion. getPixelBuffer() returns the stable front buffer.
  int mem[] = new int[SC_WIDTH * (SC_HEIGHT + 10)];
  private int[] memFront = new int[SC_WIDTH * (SC_HEIGHT + 10)];

  int rnd = 754;
  String message;
  String tmsg = "";

  int frame = 0;
  private boolean updating = false;
  boolean displayEnabled = true;
  // Debug: trace FLD/scroll issues - call startFldTrace() to capture 2 frames
  int fldTraceFrames = 0;
  boolean fldTrace = false;
  private java.io.PrintStream fldOut;
  public void startFldTrace() {
    try {
      fldOut = new java.io.PrintStream("/tmp/jac64_fld_trace.log");
    } catch (Exception e) { fldOut = System.out; }
    fldTraceFrames = 2;
    fldTrace = true;
    fldOut.println("=== FLD TRACE START ===");
  }
  void fldLog(String line) {
    if (fldTrace && fldOut != null) {
      fldOut.println(line);
    }
  }
  boolean irqTriggered = false;
  long lastLine = 0;
  long firstLine = 0;
  long lastIRQ = 0;

  int potx = 0;
  int poty = 0;
  boolean button1 = false;
  boolean button2 = false;

  // This variable changes when Kernal has installed
  // a working ISR that is reading the keyboard
  private boolean isrRunning = false;
  private int     ciaWrites = 0;

  // Callback for frame refresh (replaces AWT canvas.repaint())
  private ScreenRefreshListener screenRefreshListener;

  public interface ScreenRefreshListener {
    void onFrameReady();
  }

  private Keyboard keyboard;

  public C64Screen(IMonitor m, boolean dob) {
    monitor = m;
    DOUBLE = dob;
    setScanRate(50);
  }

  public void setScreenRefreshListener(ScreenRefreshListener listener) {
    this.screenRefreshListener = listener;
  }

  /**
   * Returns the front pixel buffer for rendering.
   * This buffer is stable (not being written to by VIC-II).
   */
  public int[] getPixelBuffer() {
    return memFront;
  }

  public int getScreenWidth() {
    return SC_WIDTH;
  }

  public int getScreenHeight() {
    return SC_HEIGHT;
  }

  public void setColorSet(int c) {
    if (c >= 0 && c < VICConstants.COLOR_SETS.length) {
      cbmcolor = VICConstants.COLOR_SETS[c];
      borderColor = cbmcolor[bCol];
      bgColor = cbmcolor[bgCol[0]];
      for (int i = 0, n = 8; i < n; i++) {
        sprites[i].color[0] = bgColor;
        sprites[i].color[1] = cbmcolor[sprMC0];
        sprites[i].color[3] = cbmcolor[sprMC1];
      }
    }
  }

  public CIA[] getCIAs() {
    return cia;
  }

  public void setSID(int sid) {
    switch (sid) {
    case RESID_6581:
    case RESID_8580:
      if (!(sidChip instanceof RESIDChip)) {
	if (sidChip != null) sidChip.stop();
        sidChip = new RESIDChip(cpu, audioDriver);
      }
      ((RESIDChip) sidChip).setChipVersion(sid);
      break;
    case JACSID:
      if (!(sidChip instanceof SIDChip)) {
	if (sidChip != null) sidChip.stop();
        sidChip = new SIDChip(cpu, audioDriver);
      }
      break;
    }
  }

  public void setScanRate(double hertz) {
    // Scan time for 10 scans...
    targetScanTime = (int) (1000000 / hertz);
    float diff = 1.0f * VICConstants.SCAN_RATE / 65;
  }

  public int getScanRate() {
    return (1000000 / targetScanTime);
  }

  public int getActualScanRate() {
    return (1000000 / actualScanTime);
  }

  public AudioDriver getAudioDriver() {
    return audioDriver;
  }

  public boolean ready() {
    return isrRunning;
  }

  public Keyboard getKeyboard() {
    return keyboard;
  }

  public void setDisplayFactor(double f) {
    displayWidth = (int) (SC_WIDTH * f);
    displayHeight = (int) (SC_HEIGHT * f);
  }

  public void setDisplayOffset(int x, int y) {
    offsetX = x;
    offsetY = y;
  }

  private boolean isBadLine(int scroll) {
    return displayEnabled && vbeam >= 0x30 && vbeam <= 0xf7
        && (vbeam & 0x7) == scroll;
  }

  private void resetBadLineFetchWindow() {
    badLineFetchStartColumn = 0;
    badLineDummyColumns = 0;
    badLineFetchSourceColumn = 0;
  }

  private void updateDisplayEnabledFromControl(int data) {
    if (vbeam == 0x30) {
      displayEnabled = (data & 0x10) != 0;
      if (displayEnabled) {
        borderState &= ~0x04;
      } else {
        borderState |= 0x04;
      }
    }
  }

  private boolean shouldTraceRasterReads() {
    int pc = cpu.getPC() & 0xffff;
    return fldTrace && pc >= 0x0a20 && pc <= 0x1600;
  }

  private boolean shouldTraceRasterWrites() {
    int pc = cpu.getPC() & 0xffff;
    return fldTrace && pc >= 0x0a00 && pc <= 0x1600;
  }

  private void traceRasterRead(String reg, int value) {
    fldOut.println(reg + "-READ=$" + Integer.toHexString(value & 0xff) +
        " vbeam=" + vbeam + " cyc=" + (cpu.cycles - lastLine) +
        " clk=" + cpu.cycles +
        " pc=$" + Integer.toHexString(cpu.getPC() & 0xffff));
  }

  private int spriteDmaMask() {
    int mask = 0;
    for (int i = 0; i < sprites.length; i++) {
      if (sprites[i].dma) {
        mask |= 1 << i;
      }
    }
    return mask;
  }

  private void setBaLowUntil(long until, String source) {
    long oldUntil = cpu.baLowUntil;
    cpu.baLowUntil = until;
    if (oldUntil != until) {
      ((CPU) cpu).traceBaEvent("BA-SET src=" + source + " old=" + oldUntil +
          " new=" + until);
    }
  }

  private void scheduleRasterIrq() {
    if (raster < 0 || raster >= RASTER_LINES) {
      rasterIrqClock = RASTER_IRQ_DISABLED;
      return;
    }

    int delta = raster - vbeam;
    if (delta <= 0) {
      delta += RASTER_LINES;
    }

    rasterIrqClock = lastLine + (long) delta * VICConstants.SCAN_RATE;
    if (raster == 0) {
      rasterIrqClock++;
    }

    if (fldTrace) {
      fldOut.println("IRQ-SCHED line=" + raster + " clk=" + rasterIrqClock +
          " now=" + cpu.cycles + " vbeam=" + vbeam +
          " cyc=" + (cpu.cycles - lastLine));
    }
  }

  private void advanceRasterIrqClock() {
    if (rasterIrqClock == RASTER_IRQ_DISABLED) {
      return;
    }
    rasterIrqClock += (long) RASTER_LINES * VICConstants.SCAN_RATE;
  }

  private void updateVicIrqLine() {
    if ((irqFlags & 1) == 0) {
      irqTriggered = false;
    }

    if ((irqMask & 0x0f & irqFlags) != 0) {
      irqFlags |= 0x80;
      setIRQ(VIC_IRQ);
    } else {
      irqFlags &= 0x7f;
      clearIRQ(VIC_IRQ);
    }
  }

  private void handleLateRasterIrqAcknowledge(boolean rmwDummyWrite) {
    if (rasterIrqClock == RASTER_IRQ_DISABLED
        || raster < 0 || raster >= RASTER_LINES) {
      return;
    }

    long clk = cpu.cycles;
    if (rmwDummyWrite) {
      if (clk - 1 > rasterIrqClock) {
        if (clk - 2 == rasterIrqClock) {
          advanceRasterIrqClock();
        } else {
          triggerRasterIrq(clk);
        }
      }
    } else if (clk > rasterIrqClock) {
      if (clk - 1 == rasterIrqClock) {
        advanceRasterIrqClock();
      } else {
        triggerRasterIrq(clk);
      }
    }
  }

  private void triggerRasterIrq(long irqClock) {
    irqFlags |= 0x1;
    if ((irqMask & 1) != 0) {
      irqFlags |= 0x80;
      irqTriggered = true;
      setIRQ(VIC_IRQ);
      lastIRQ = irqClock;
    }
    if (rasterIrqClock != RASTER_IRQ_DISABLED && rasterIrqClock <= irqClock) {
      advanceRasterIrqClock();
    }

    if (fldTrace) {
      fldOut.println("IRQ-FIRE line=" + raster + " clk=" + irqClock +
          " next=" + rasterIrqClock + " vbeam=" + vbeam +
          " cyc=" + (irqClock - lastLine) +
          " baLowUntil=" + cpu.baLowUntil +
          " pc=$" + Integer.toHexString(cpu.getPC() & 0xffff));
    }
  }

  private void updateRasterIrqLine(int oldRaster) {
    if (raster == oldRaster) {
      return;
    }

    boolean triggerNow = raster == vbeam && oldRaster != vbeam
        && raster >= 0 && raster < RASTER_LINES;
    scheduleRasterIrq();
    if (triggerNow) {
      triggerRasterIrq(cpu.cycles);
    }
  }

  private void handleBadLineStart(int vicCycle, boolean wasVisible) {
    setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-START");

    if (vicCycle <= 13) {
      rc = 0;
    }

    if (vicCycle >= 16 && vicCycle < 59) {
      // VICE derives the current text column from the cycle where the line
      // becomes bad: xpos = cycle - (fetch cycle + 3). Using vmli directly
      // leaves JaC64 one column behind during mid-line FLD changes.
      badLineFetchStartColumn = vicCycle - (BADLINE_FETCH_CYCLE + 3);
      if (badLineFetchStartColumn < 0) {
        badLineFetchStartColumn = 0;
      } else if (badLineFetchStartColumn > 40) {
        badLineFetchStartColumn = 40;
      }
      badLineDummyColumns = 3;
      badLineFetchSourceColumn = wasVisible ? badLineFetchStartColumn : 0;
    } else {
      resetBadLineFetchWindow();
    }

    if (!wasVisible) {
      vc = vcBase;
      vmli = badLineFetchStartColumn;
    }

    gfxVisible = true;
  }

  private void handleBadLineStop(int vicCycle, boolean wasVisible) {
    resetBadLineFetchWindow();

    if (vicCycle > 0) {
      gfxVisible = true;
      if (!wasVisible && vicCycle > 13) {
        rc = 0;
      }
    }
  }

  private void fetchBadLineData(int column) {
    int sourceColumn = column;

    if (column >= badLineFetchStartColumn && badLineDummyColumns > 0) {
      int fetchColumn = column - badLineFetchStartColumn;
      if (fetchColumn < badLineDummyColumns) {
        vicCharCache[column] = 0xff;
        vicColCache[column] = 0;
        return;
      }

      sourceColumn = badLineFetchSourceColumn + fetchColumn - badLineDummyColumns;
    }

    if (sourceColumn < 0 || sourceColumn >= 40) {
      vicCharCache[column] = 0xff;
      vicColCache[column] = 0;
      return;
    }

    int videoOffset = (vcBase + sourceColumn) & 0x3ff;
    vicCharCache[column] = memory[videoMatrix + videoOffset];
    vicColCache[column] = memory[IO_OFFSET + 0xd800 + videoOffset];
  }

  public void dumpGfxStat() {
    monitor.info("Char MemoryIndex: 0x" +
        Integer.toString(charMemoryIndex, 16));
    monitor.info("CharSet adr: 0x" +
        Integer.toString(charSet, 16));
    monitor.info("VideoMode: " + videoMode);
    monitor.info("Vic Bank: 0x" +
        Integer.toString(vicBank, 16));
    monitor.info("Video Matrix: 0x" +
        Integer.toString(videoMatrix, 16));

    monitor.info("Text: extended = " + extended +
        " multicol = " + multiCol);

    monitor.info("24 Rows on? " +
        (((control1 & 0x08) == 0) ? "yes" : "no"));

    monitor.info("YScroll = " + (control1 & 0x7));
    monitor.info("$d011 = " + control1);

    monitor.info("IRQ Latch: " +
        Integer.toString(irqFlags, 16));
    monitor.info("IRQ  Mask: " +
        Integer.toString(irqMask, 16));
    monitor.info("IRQ RPos : " + raster);

    for (int i = 0, n = 8; i < n; i++) {
      monitor.info("Sprite " + (i + 1) + " pos = " +
          sprites[i].x + ", " + sprites[i].y);
    }

    monitor.info("IRQFlags: " + getIRQFlags());
    monitor.info("NMIFlags: " + getNMIFlags());
    monitor.info("CPU IRQLow: " + cpu.getIRQLow());
    monitor.info("CPU NMILow: " + cpu.NMILow);
    monitor.info("Current CPU cycles: " + cpu.cycles);
    monitor.info("Next IO update: " + nextIOUpdate);
  }

  public void setSoundOn(boolean on) {
   audioDriver.setSoundOn(on);
  }

  public void setStick(boolean one) {
    keyboard.setStick(one);
  }

  public void registerHotKey(int key, int mod, String script, Object o) {
    keyboard.registerHotKey(key, mod, script, o);
  }

  public void setKeyboardEmulation(boolean extended) {
    monitor.info("Keyboard extended: " + extended);

    keyboard.stickExits = !extended;
    keyboard.extendedKeyboardEmulation = extended;
  }

  /**
   * Initialize the screen with the given CPU and audio driver.
   * On Android, we inject the AudioDriver instead of creating AudioDriverSE.
   */
  public void init(CPU cpu, AudioDriver driver) {
    super.init(cpu);

    this.memory = cpu.getMemory();
    this.audioDriver = driver;

    c1541Chips = cpu.getDrive().chips;
    c1541Chips.initIEC2(this);
    c1541Chips = cpu.getDrive().chips;
    c1541Chips.setObserver(this);

    for (int i = 0, n = sprites.length; i < n; i++) {
      sprites[i] = new Sprite();
      sprites[i].spriteNo = i;
    }

    cia = new CIA[2];
    cia[0] = new CIA(cpu, IO_OFFSET + 0xdc00, this);
    cia[1] = new CIA(cpu, IO_OFFSET + 0xdd00, this);

    tfe = new TFE_CS8900(IO_OFFSET + 0xde00);

    keyboard = new Keyboard(this, cia[0], memory);

    audioDriver.init(44000, 22000);
    setSID(RESID_6581);
    charMemoryIndex = CPU.CHAR_ROM2;

    for (int i = 0; i < SC_WIDTH * SC_HEIGHT; i++) {
      mem[i] = cbmcolor[6];
    }

    initUpdate();
  }

  public boolean isDoubleSize() {
    return DOUBLE;
  }

  public void update(Object src, Object data) {
    if (src != c1541Chips) {
      // Print some kind of message...
      message = (String) data;
    } else {
      updateDisk(src, data);
    }
  }

  void restoreKey(boolean down) {
    if (down) setNMI(KEYBOARD_NMI);
    else clearNMI(KEYBOARD_NMI);
  }

  // Should be checked up!!!
  private static final int[] IO_ADDRAND = new int[] {
    0xd03f, 0xd03f, 0xd03f, 0xd03f,
    0xd41f, 0xd41f, 0xd41f, 0xd41f,
    0xd8ff, 0xd9ff, 0xdaff, 0xdbff, // Color ram
    0xdc0f, 0xdd0f, 0xdeff, 0xdfff, // CIA + Expansion...
  };

  public int performRead(int address, long cycles) {
    // dX00 => and address
    // d000 - d3ff => &d063
    int pos = (address >> 8) & 0xf;
    //    monitor.info("Address before: " + address);
    address = address & IO_ADDRAND[pos];
    int val = 0;
    switch (address) {
    case 0xd000:
    case 0xd002:
    case 0xd004:
    case 0xd006:
    case 0xd008:
    case 0xd00a:
    case 0xd00c:
    case 0xd00e:
      return sprites[(address - 0xd000) >> 1].x & 0xff;
    case 0xd001:
    case 0xd003:
    case 0xd005:
    case 0xd007:
    case 0xd009:
    case 0xd00b:
    case 0xd00d:
    case 0xd00f:
      return sprites[(address - 0xd000) >> 1].y;
    case 0xd010:
      return sprXMSB;
    case 0xd011:
      val = control1 & 0x7f | ((vbeam & 0x100) >> 1);
      if (shouldTraceRasterReads()) {
        traceRasterRead("D011", val);
      }
      return val;
    case 0xd012:
      val = vbeam & 0xff;
      if (shouldTraceRasterReads()) {
        traceRasterRead("D012", val);
      }
      return val;
      // Sprite collission registers - zeroed after read!
    case 0xd013:
    case 0xd014:
      // Lightpen x/y
        return 0;
    case 0xd015:
      return sprEN;
    case 0xd016:
      return control2;
    case 0xd017:
      return sprYEX;
    case 0xd018:
      return vicMem;
    case 0xd019:
      if (SPRITEDEBUG)
        monitor.info("Reading d019: " + memory[address + IO_OFFSET]);
      return irqFlags;
    case 0xd01a:
      return irqMask;
    case 0xd01b:
      return sprPri;
    case 0xd01c:
      return sprMul;
    case 0xd01d:
      return sprXEX;
    case 0xd01e:
      val = sprCol;
      if (SPRITEDEBUG)
        monitor.info("Reading sprite collission: " +
            Integer.toString(address, 16) + " => " + val);
      sprCol = 0;
      return val;
    case 0xd01f:
      val = sprBgCol;
      if (SPRITEDEBUG)
        monitor.info("Reading sprite collission: " +
            Integer.toString(address, 16) + " => " + val);

      sprBgCol = 0;
      return val;
    case 0xd020:
      return bCol | 0xf0;
    case 0xd021:
    case 0xd022:
    case 0xd023:
    case 0xd024:
        return bgCol[address - 0xd021] | 0xf0;
    case 0xd025:
        return sprMC0 | 0xf0;
    case 0xd026:
        return sprMC1 | 0xf0;
    case 0xd027:
    case 0xd028:
    case 0xd029:
    case 0xd02a:
    case 0xd02b:
    case 0xd02c:
    case 0xd02d:
    case 0xd02e:
      return sprites[address - 0xd027].col | 0xf0;
    case 0xd41b:
    case 0xd41c:
      return sidChip.performRead(IO_OFFSET + address, cycles);
    case 0xd419:
      return potx;
    case 0xd41A:
      return poty;
    case 0xdc00:
      return keyboard.readDC00(cpu.lastReadOP);
    case 0xdc01:
      return keyboard.readDC01(cpu.lastReadOP);
    case 0xdd00:
      // Match VICE: bring the drive up to the current C64 clock before
      // sampling IEC state on reads as well as writes.
      if (c1541Chips != null) {
        ((CPU)cpu).getDrive().tick(cpu.cycles);
      }
      val = (cia2PRA | ~cia2DDRA) & 0x3f
      | iecLines & c1541Chips.iecLines;

      val &= 0xff;
      if (iecLoopReadLogs < 16 && cpu.getPC() >= 0x01a9 && cpu.getPC() <= 0x01ad) {
        System.out.printf(
            "C64 loop read DD00=$%02X c64=%02X drv=%02X A=%02X X=%02X Y=%02X SP=%02X cyc=%d%n",
            val, iecLines & 0xd0, c1541Chips.iecLines & 0xd0,
            cpu.acc & 0xff, cpu.x & 0xff, cpu.y & 0xff, cpu.s & 0xff, cpu.cycles);
        iecLoopReadLogs++;
      }
      return val;
    default:
      if (pos == 0x4) {
        return sidChip.performRead(address + IO_OFFSET, cycles);
      } else if (pos == 0xd) {
        return cia[1].performRead(address + IO_OFFSET, cycles);
      } else if (pos == 0xc) {
        return cia[0].performRead(address + IO_OFFSET, cycles);
      } else if (pos == 0xe) {
        return tfe.performRead(address + IO_OFFSET, cycles);
      } else if (pos >= 0x8) {
        return memory[IO_OFFSET + address] | 0xf0;
      }
      return 0xff;
    }
  }

  private void updateCia2IecBus(boolean syncDrive) {
    if (c1541Chips == null) {
      return;
    }

    if (syncDrive) {
      ((CPU)cpu).getDrive().tick(cpu.cycles);
    }

    int data = ~cia2PRA & cia2DDRA;
    int oldLines = iecLines;
    iecLines = (data << 2) & 0x80   // DATA
    | (data << 2) & 0x40            // CLK
    | (data << 1) & 0x10;           // ATN

    if (((oldLines ^ iecLines) & 0x10) != 0) {
      c1541Chips.atnChanged((iecLines & 0x10) == 0);
    }
    c1541Chips.updateIECLines();

    if (iecTrace) {
      iecTraceCount++;
      int combined = iecLines & c1541Chips.iecLines;
      iecLog[iecLogPos] = String.format("#%d cy=%d PC=$%04X W=$%02X ATN=%d CLK=%d DAT=%d c64[%02X] drv[%02X]",
          iecTraceCount, cpu.cycles, cpu.getPC(),
          cia2PRA & 0xff,
          (combined >> 4) & 1, (combined >> 6) & 1, (combined >> 7) & 1,
          iecLines & 0xd0, c1541Chips.iecLines & 0xd0);
      iecLogPos = (iecLogPos + 1) % IEC_LOG_SIZE;
    }

    if (DEBUG_IEC) printIECLines();
  }

  public void performWrite(int address, int data, long cycles) {
    int pos = (address >> 8) & 0xf;
    address = address & IO_ADDRAND[pos];

    // Store in the memory given by "CPU"
    memory[address + IO_OFFSET] = data;

    if (shouldTraceRasterWrites() && address >= 0xd000 && address <= 0xd010) {
      fldOut.println("VIC-W $" + Integer.toHexString(address) +
          "=$" + Integer.toHexString(data & 0xff) +
          " vbeam=" + vbeam + " cyc=" + (cpu.cycles - lastLine) +
          " clk=" + cpu.cycles +
          " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
    }

    switch (address) {
    // -------------------------------------------------------------------
    // VIC related
    // -------------------------------------------------------------------
    case 0xd000:
    case 0xd002:
    case 0xd004:
    case 0xd006:
    case 0xd008:
    case 0xd00a:
    case 0xd00c:
    case 0xd00e:
      int sprite = (address - 0xd000) >> 1;
      sprites[sprite].x &= 0x100;
      sprites[sprite].x += data;
      break;
    case 0xd001:
    case 0xd003:
    case 0xd005:
    case 0xd007:
    case 0xd009:
    case 0xd00b:
    case 0xd00d:
    case 0xd00f:
      sprites[(address - 0xd000) >> 1].y = data;
      break;
    case 0xd010:
      sprXMSB = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].x &= 0xff;
        sprites[i].x |= (data & m) != 0 ? 0x100 : 0;
      }
      break;
      // d011 -> high address of raster pos
    case 0xd011 :
      int oldRaster = raster;
      raster = (raster & 0xff) | ((data << 1) & 0x100);
      updateRasterIrqLine(oldRaster);
      control1 = data;

      int vicCycle = (int) (cpu.cycles - lastLine);
      boolean wasBadLine = badLine;
      boolean wasVisible = gfxVisible;

      updateDisplayEnabledFromControl(data);

      // Update vScroll and recalculate badLine on any D011 write.
      vScroll = data & 0x7;
      boolean newBadLine = isBadLine(vScroll);

      if (!wasBadLine && newBadLine) {
        badLine = true;
        handleBadLineStart(vicCycle, wasVisible);
      } else if (wasBadLine && !newBadLine) {
        if (vicCycle < BADLINE_FETCH_CYCLE) {
          badLine = false;
          handleBadLineStop(vicCycle, wasVisible);
        } else {
          // Once badline DMA has started, the current line stays bad even if
          // Y-scroll changes mid-line. The new scroll value applies next line.
          badLine = true;
        }
      } else {
        badLine = newBadLine;
      }

      if (shouldTraceRasterWrites()) {
        fldOut.println("D011=" + Integer.toHexString(data) +
            " vbeam=" + vbeam + " cyc=" + vicCycle +
            " vScroll=" + vScroll + " badLine=" + badLine +
            " gfxVis=" + gfxVisible + " rc=" + rc +
            " vmli=" + vmli + " vc=" + vc +
            " clk=" + cpu.cycles +
            " pc=$" + Integer.toHexString(cpu.pc & 0xffff) +
            " a=$" + Integer.toHexString(cpu.acc & 0xff) +
            " x=$" + Integer.toHexString(cpu.x & 0xff) +
            " y=$" + Integer.toHexString(cpu.y & 0xff) +
            " dispEn=" + displayEnabled);
      }

      extended = (data & 0x40) != 0;
      blankRow = (data & 0x08) == 0;

      videoMode = (extended ? 0x02 : 0)
      | (multiCol ? 0x01 : 0) | (((data & 0x20) != 0) ? 0x04 : 0x00);

      if (VIC_MEM_DEBUG || BAD_LINE_DEBUG) {
        monitor.info("d011 = " + data + " at " + vbeam +
            " => YScroll = " + (data & 0x7) +
            " cyc since line: " + (cpu.cycles-lastLine) +
            " cyc since IRQ: " + (cpu.cycles-lastIRQ));
      }
      if (IRQDEBUG)
        monitor.info("Setting raster position (hi) to: " +
            (data & 0x80));

      break;

      // d012 -> raster position
    case 0xd012 :
      oldRaster = raster;
      raster = (raster & 0x100) | data;
      updateRasterIrqLine(oldRaster);
      if (shouldTraceRasterWrites()) {
        fldOut.println("D012=" + Integer.toHexString(data) +
            " vbeam=" + vbeam + " cyc=" + (cpu.cycles - lastLine) +
            " clk=" + cpu.cycles +
            " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      if (IRQDEBUG)
        monitor.info("Setting Raster Position (low) to " + data);
      break;
    case 0xd013:
    case 0xd014:
      // Write to lightpen...
      break;
    case 0xd015:
      sprEN = data;
      if (shouldTraceRasterWrites()) {
        fldOut.println("D015=" + Integer.toHexString(data) +
            " vbeam=" + vbeam + " cyc=" + (cpu.cycles - lastLine) +
            " clk=" + cpu.cycles +
            " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].enabled = (data & m) != 0;
      }
      break;
    case 0xd016:
      control2 = data;
      horizScroll = data & 0x7;
      multiCol = (data & 0x10) != 0;

      hideColumn = (data & 0x08) == 0;

      // Set videmode...
      videoMode = (extended ? 0x02 : 0)
      | (multiCol ? 0x01 : 0) | (((control1 & 0x20) != 0)
          ? 0x04 : 0x00);
      break;

    case 0xd017:
      sprYEX = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].expandY = (data & m) != 0;
      }
      break;

    case 0xd018:
      vicMem = data;
      setVideoMem();
      break;

    case 0xd019 : {
      if ((data & 0x80) != 0) data = 0xff;
      if (IRQDEBUG) {
        monitor.info("Latching VIC-II: " + Integer.toString(data, 16)
            + " on " + Integer.toString(irqFlags, 16));
      }

      if (((CPU) cpu).isRmwDummyWrite()) {
        irqFlags &= ~((data & 0x0f) | 0x80);
        if ((data & 1) != 0) {
          handleLateRasterIrqAcknowledge(true);
        }
        updateVicIrqLine();
        break;
      }

      if ((data & 1) != 0) {
        handleLateRasterIrqAcknowledge(false);
      }
      irqFlags &= ~((data & 0x0f) | 0x80);
      updateVicIrqLine();
    }
    break;
    case 0xd01a:
      irqMask = data;
      updateVicIrqLine();

      if (IRQDEBUG) {
        monitor.info("Changing IRQ mask to: " +
            Integer.toString(irqMask, 16) + " vbeam: " + vbeam);
      }
      break;

    case 0xd01b:
      sprPri = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].priority = (data & m) != 0;
      }
      break;
    case 0xd01c:
      sprMul = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].multicolor = (data & m) != 0;
      }
      break;
    case 0xd01d:
      sprXEX = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].expandX = (data & m) != 0;
      }
      break;

    case 0xd020:
      borderColor = cbmcolor[bCol = data & 15];
      break;
    case 0xd021:
      bgColor = cbmcolor[bgCol[0] = data & 15];
      for (int i = 0, n = 8; i < n; i++) {
        sprites[i].color[0] = bgColor;
      }
      break;
    case 0xd022:
    case 0xd023:
    case 0xd024:
      bgCol[address - 0xd021] = data & 15;
      break;
    case 0xd025:
      sprMC0 = data & 15;
      for (int i = 0, n = 8; i < n; i++) {
        sprites[i].color[1] = cbmcolor[sprMC0];
      }
      break;
    case 0xd026:
      sprMC1 = data & 15;
      for (int i = 0, n = 8; i < n; i++) {
        sprites[i].color[3] = cbmcolor[sprMC1];
      }
      break;
    case 0xd027:
    case 0xd028:
    case 0xd029:
    case 0xd02a:
    case 0xd02b:
    case 0xd02c:
    case 0xd02d:
    case 0xd02e:
      sprites[address - 0xd027].color[2] = cbmcolor[data & 15];
      sprites[address - 0xd027].col = data & 15;
      break;
    case 0xd02f:
      // Debug: trigger FLD trace
      if (data == 0x01) startFldTrace();
      break;
      // CIA 1 & 2 - 'special' addresses
    case 0xdc00:
    case 0xdc01:
    case 0xdc02:
    case 0xdc03:
      cia[0].performWrite(address + IO_OFFSET, data, cpu.cycles);
      if (!isrRunning) {
        if (ciaWrites++ > 20) {
          isrRunning = true;
          ciaWrites = 0;
        } else {
          System.out.println("startup CIA write# " + ciaWrites + ": set " + address + " to " + data);
        }
      }
      break;
    case 0xdd00:
      // Matching VICE's iecbus_cpu_write_conf1 order:
      // 1. Sync drive FIRST (catches up with OLD bus state)
      // 2. THEN update bus state
      // 3. Signal ATN change via CA1 interrupt
      // 4. Recalculate drive IEC lines
      if (c1541Chips != null) {
        ((CPU)cpu).getDrive().tick(cpu.cycles);
      }
      if (DEBUG_IEC)
        monitor.info("C64: IEC Write: " + Integer.toHexString(data));

      if (VIC_MEM_DEBUG)
        System.out.println("Set dd00 to " + Integer.toHexString(data));

      cia[1].performWrite(address + IO_OFFSET, data, cpu.cycles);
      cia2PRA = data;
      updateCia2IecBus(false);
      setVideoMem();
      break;

    case 0xdd02:
      if (c1541Chips != null) {
        ((CPU)cpu).getDrive().tick(cpu.cycles);
      }
      cia2DDRA = data;
      cia[1].performWrite(address + IO_OFFSET, data, cpu.cycles);
      updateCia2IecBus(false);
      setVideoMem();
      break;

    default:
      if (pos == 0x4) {
        sidChip.performWrite(address + IO_OFFSET, data, cycles);
      } else if (pos == 0xd) {
        cia[1].performWrite(address + IO_OFFSET, data, cycles);
      } else if (pos == 0xc) {
        cia[0].performWrite(address + IO_OFFSET, data, cycles);
      } else if (pos == 0xe) {
        tfe.performWrite(address + IO_OFFSET , data, cycles);
      }
      // handle color ram!
    }
  }

  private void printIECLines() {
    System.out.print("IEC/F: ");
    if ((iecLines & 0x10) == 0) {
      System.out.print("A1");
    } else {
      System.out.print("A0");
    }

    // The c64 has id = 1
    int sdata = ((iecLines & 0x40) == 0) ? 1 : 0;
    System.out.print(" C" + sdata);
    sdata = ((iecLines & 0x80) == 0) ? 1 : 0;
    System.out.print(" D" + sdata);

    // The 1541 has id = 2
    sdata = ((c1541Chips.iecLines & 0x40) == 0) ? 1 : 0;
    System.out.print(" c" + sdata);
    sdata = ((c1541Chips.iecLines & 0x80) == 0) ? 1 : 0;
    System.out.print(" d" + sdata);

    System.out.println(" => C" +
        ((iecLines & c1541Chips.iecLines & 0x80) == 0 ? 1 : 0)
        + " D" +
        ((iecLines & c1541Chips.iecLines & 0x40) == 0 ? 1 : 0));
  }

  private void setVideoMem() {
    if (VIC_MEM_DEBUG) {
      monitor.info("setVideoMem() cycles since line: " +
          (cpu.cycles - lastLine) +
          " cycles since IRQ: " + (cpu.cycles-lastIRQ) +
          " at " + vbeam);
    }
    // Set-up vars for screen rendering
    vicBank = (~(~cia2DDRA | cia2PRA) & 3) << 14;
    charSet = vicBank | (vicMem & 0x0e) << 10;
    videoMatrix = vicBank | (vicMem & 0xf0) << 6;
    vicBase = vicBank | (vicMem & 0x08) << 10;
    spr0BlockSel = 0x03f8 + videoMatrix;

    //check if vic not looking at char rom 1, 2, 4, 8
    if ( (vicMem & 0x0c) != 4 || (vicBank & 0x4000) == 0x4000) {
      charMemoryIndex = charSet;
    } else {
      charMemoryIndex = (((vicMem & 0x02) == 0) ? 0 : 0x0800) +
        CPU.CHAR_ROM2;
    }
  }

  private void initUpdate() {
    vc = 0;
    vcBase = 0;
    vmli = 0;
    updating = true;

    for (int i = 0; i < 8; i++) {
      sprites[i].nextByte = 0;
      sprites[i].painting = false;
      sprites[i].spriteReg = 0;
    }
  }

  // -------------------------------------------------------------------
  // Screen rendering!
  // -------------------------------------------------------------------
  // keep track of if the border is to be painted...
  private int borderState = 0;
  private boolean notVisible = false;
  private int xPos = 0;
  private long lastCycle = 0;

  public final void clock(long cycles) {
    if (DEBUG_CYCLES || true) {
      if (lastCycle + 1 < cycles) {
        System.out.println("More than one cycle passed: " +
            (cycles - lastCycle) + " at " + cycles + " PC: "
            + Integer.toHexString(cpu.pc));
      }

      if (lastCycle == cycles) {
        System.out.println("No diff since last update!!!: " +
            (cycles - lastCycle) + " at " + cycles + " PC: "
            + Integer.toHexString(cpu.pc));
      }
      lastCycle = cycles;
    }

    // Delta is cycles into the current raster line!
    int vicCycle = (int) (cycles - lastLine);

    // At cycle 0, increment vbeam BEFORE the raster compare
    // (real VIC-II updates raster counter at start of line)
    if (vicCycle == 0) {
      vbeam = (vbeam + 1) % 312;
      if (vbeam == 0) {
        frame++;
        if (fldTrace && --fldTraceFrames <= 0) {
          fldTrace = false;
          fldOut.println("=== FLD TRACE END ===");
          fldOut.close();
        }
      }
      vPos = vbeam - (FIRST_VISIBLE_VBEAM + 1);
    }

    while (rasterIrqClock != RASTER_IRQ_DISABLED && cycles >= rasterIrqClock) {
      triggerRasterIrq(rasterIrqClock);
    }

    if (badLine) {
      gfxVisible = true;
    }

    switch (vicCycle) {
    case 0:
      // vbeam already incremented before the raster compare above

      if (vbeam == FIRST_VISIBLE_VBEAM) {
        colIndex++;
        if (colIndex >= LABEL_COUNT) colIndex = 0;
        // Display enabled?
        initUpdate();
      }

      // Sprite collission interrupts - checked once per line
      if (((irqMask & 2) != 0) && (sprBgCol != 0) &&
          (irqFlags & 2) == 0) {
        irqFlags |= 82;
        setIRQ(VIC_IRQ);
      }
      if (((irqMask & 4) != 0) && (sprCol != 0) &&
          (irqFlags & 4) == 0) {
        irqFlags |= 84;
        setIRQ(VIC_IRQ);
      }
      notVisible = false;
      if (vPos < 0 || vPos >= 284) {
        notVisible = true;
        if (STATE_DEBUG)
          monitor.info("FINISH next at " + vbeam);
        break;
      }

      // Check if display should be enabled...
      if (vbeam == 0x30) {
        displayEnabled = (control1 & 0x10) != 0;
        if (displayEnabled) {
          borderState &= ~0x04;
        } else {
          borderState |= 0x04;
        }
      }

      badLine = isBadLine(vScroll);
      resetBadLineFetchWindow();

      if (fldTrace && (vbeam < 0x30 || vbeam <= 0xf7)) {
        fldOut.println("CYC0 vbeam=" + vbeam + " badLine=" + badLine +
            " gfxVis=" + gfxVisible + " rc=" + rc +
            " vScroll=" + vScroll + " borderSt=" + borderState +
            " sprDMA=$" + Integer.toHexString(spriteDmaMask()) +
            " d011=$" + Integer.toHexString(control1) +
            " d016=$" + Integer.toHexString(control2));
      }

      // Clear the collission masks each line...
      for (int i = 0, n = SC_WIDTH; i < n; i++) {
        collissionMask[i] = 0;
      }
      break;
    case 1: // Sprite data - sprite 3
      if (sprites[3].dma) {
        sprites[3].readSpriteData();
      }
      if (sprites[5].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP5, "SPR5");
      }
      break;
    case 2:
      break;
    case 3:
      if (sprites[4].dma) {
        sprites[4].readSpriteData();
      }
      if (sprites[6].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP6, "SPR6");
      }
      break;
    case 4:
      break;
    case 5:
      if (sprites[5].dma) {
        sprites[5].readSpriteData();
      }
      if (sprites[7].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP7, "SPR7");
      }
      break;
    case 6:
      break;
    case 7:
      if (sprites[6].dma) {
        sprites[6].readSpriteData();
      }
      break;
    case 8:
      break;
    case 9:
      if (sprites[7].dma) {
        sprites[7].readSpriteData();
      }

      // Border management!
      if (blankRow) {
        if (vbeam == 247) {
          borderState |= 1;
        }
      } else {
        if (vbeam == 251) {
          borderState |= 1;
        }
        if (vbeam == 51) {
          borderState &= 0xfe;

          for (int i = 0, n = 7; i < n; i++) {
            if (!sprites[i].painting) {
              sprites[i].lineFinished = true;
            }
          }
        }
      }
      // No border after vbeam 55 (ever?)
      if (vbeam == 55) {
        borderState &= 0xfe;

        for (int i = 0, n = 7; i < n; i++) {
          if (!sprites[i].painting)
            sprites[i].lineFinished = true;
        }
      }
      break;
    case 10:
      break;
    case 11: // Set badline fetching...
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C11");
      }
      break;
    case 12: // First visible cycle (on screen)
      // calculate mpos before starting the rendering!
      mpos = vPos * SC_WIDTH;
      drawBackground();

      xPos = 16;
      mpos += 8;

      break;
    case 13:
      drawBackground();
      drawSprites();
      mpos += 8;

      // Set vc, reset vmli...
      vc = vcBase;
      vmli = 0;
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C13");
        if (BAD_LINE_DEBUG) System.out.println("#### RC = 0 (" + rc + ") at "
            + vbeam + " vc: " + vc);
        rc = 0;
      }
      break;
    case 14:
      drawBackground();
      drawSprites();
      mpos += 8;
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C14");
      }
      break;
    case 15:

      drawBackground();
      drawSprites();
      mpos += 8;

      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C15");
      }

      // Turn off sprite DMA if finished reading!
      for (int i = 0, n = 8; i < n; i++) {
        if (sprites[i].nextByte == 63)
          sprites[i].dma = false;
      }

      break;
    case 16:
      if (!hideColumn) {
        borderState &= 0xfd;
      }
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C16");
        fetchBadLineData(vmli);
      }

      // Draw one character here!
      drawGraphics(mpos + horizScroll);
      drawSprites();
      if (borderState != 0)
        drawBackground();
      mpos += 8;

      break;
    case 17:
      if (hideColumn) {
        borderState &= 0xfd;
      }

      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C17");
        fetchBadLineData(vmli);
      }
      drawGraphics(mpos + horizScroll);
      drawSprites();
      mpos += 8;
      break;
      // Cycle 18 - 53
    default:
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-FETCH");
        fetchBadLineData(vmli);
      }
      drawGraphics(mpos + horizScroll);
      drawSprites();

      mpos += 8;
      break;
    case 54:
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C54");
        fetchBadLineData(vmli);
      }
      int mult = 1;
      int ypos = vPos + SC_SPYOFFS;

      for (int i = 0, n = 8; i < n; i++) {
        Sprite sprite = sprites[i];
        if (sprite.enabled) {
          if (sprite.y == (ypos & 0xff) && (ypos < 270)) {
            sprite.nextByte = 0;
            sprite.dma = true;
            sprite.expFlipFlop = true;
            if (fldTrace) {
              fldOut.println("SPR-DMA-ON s=" + i +
                  " vbeam=" + vbeam + " cyc=" + vicCycle +
                  " y=$" + Integer.toHexString(sprite.y & 0xff) +
                  " en=" + sprite.enabled +
                  " clk=" + cpu.cycles);
            }
            if (SPRITEDEBUG)
              System.out.println("Starting painting sprite " + i + " on "
                  + vbeam + " first visible at " + (ypos + 1));
          }
        }
        mult = mult << 1;
      }
      if (sprites[0].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP0, "SPR0");
      }

      drawGraphics(mpos + horizScroll);
      drawSprites();

      mpos += 8;

      break;
    case 55:
      if (hideColumn) {
        borderState |= 2;
      }
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C55");
        fetchBadLineData(vmli);
      }
      drawGraphics(mpos + horizScroll);
      drawSprites();
      if (borderState != 0)
          drawBackground();
      mpos += 8;

      break;
    case 56:
      if (!hideColumn) {
        borderState |= 2;
      }

      drawBackground();
      drawSprites();
      mpos += 8;


      // If time to turn of sprite display...
      for (int i = 0, n = 8; i < n; i++) {
        Sprite sprite = sprites[i];
        if (!sprite.dma) {
          sprite.painting = false;
          if (SPRITEDEBUG)
            System.out.println("Stopped painting sprite " +
                i + " at (after): " + vbeam);
        }
      }

      if (sprites[1].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP1, "SPR1");
      }
      break;
    case 57:
      for (int i = 0, n = 8; i < n; i++) {
        Sprite sprite = sprites[i];
        if (sprite.dma)
          sprite.painting = true;
      }

      drawBackground();
      drawSprites();
      mpos += 8;


      if (rc == 7) {
        vcBase = vc;
        gfxVisible = false;
        if (BAD_LINE_DEBUG) {
          monitor.info("#### RC7 ==> vc = " + vc + " at " + vbeam +
              " vicCycle = " + vicCycle);
          if (vc == 1000) {
            monitor.info("--------------- last line ----------------");
          }
        }
      }

      if (badLine || gfxVisible) {
        rc = (rc + 1) & 7;
        gfxVisible = true;
      }

      if (sprites[0].painting) {
        sprites[0].readSpriteData();
      }

      if (sprites[2].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP2, "SPR2");
      }

      break;
    case 58:
      drawBackground();
      drawSprites();
      mpos += 8;

      break;
    case 59:
      drawBackground();
      drawSprites();
      mpos += 8;

      if (sprites[1].painting) {
        sprites[1].readSpriteData();
      }
      break;
    case 60:
      drawSprites();
      break;
    case 61:
      if (sprites[2].painting) {
        sprites[2].readSpriteData();
      }
      if (sprites[3].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP3, "SPR3");
      }
      break;
    case 62:
      if (sprites[4].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP4, "SPR4");
      }
      // Reset sprites so that they can be repainted again...
      for (int i = 0; i < sprites.length; i++) {
        sprites[i].reset();
      }
      lastLine += VICConstants.SCAN_RATE;
      // Update screen
      if (updating) {
        if (vPos == 285) {
          // Throttle to 50 Hz only when audio driver has no sound
          // (e.g. Android without Java Sound). With ReSID, audio
          // output already provides the timing — double throttle
          // causes half-speed audio and flickering.
          long now = audioDriver.getMicros();
          if (lastScan > 0 && !audioDriver.fullSpeed() && !audioDriver.hasSound()) {
            long frameElapsed = now - lastScan;
            long targetMicros = 20000; // 20ms = 50Hz PAL
            if (frameElapsed < targetMicros) {
              long sleepMs = (targetMicros - frameElapsed) / 1000;
              if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) {}
              }
            }
          }

          // Swap front and back buffers for tear-free rendering
          int[] tmp = memFront;
          memFront = mem;
          mem = tmp;
          // Signal frame ready (replaces AWT mis.newPixels() + canvas.repaint())
          if (screenRefreshListener != null) {
            screenRefreshListener.onFrameReady();
          }
          actualScanTime = (actualScanTime * 9 + (int)
              ((audioDriver.getMicros() - lastScan))) / 10;
          lastScan = audioDriver.getMicros();
          updating = false;
        }
      }
      notVisible = false;
      break;
    }
  }

  // Used to draw background where either border or background should be
  // painted...
  private void drawBackground() {
    if (notVisible) {
      return;
    }
    int bpos = mpos;
    int currentBg = borderState > 0 ? borderColor : bgColor;
    for (int i = 0; i < 8; i++) {
      mem[bpos++] = currentBg;
    }
    if (fldTrace && vbeam >= 0x30 && vbeam <= 0xf7 && gfxVisible) {
      fldOut.println("  drawBG overwrite at mpos=" + mpos +
          " vmli=" + vmli + " borderSt=" + borderState);
    }
  }

  /**
   * <code>drawGraphics</code> - draw the VIC graphics (text/bitmap)
   */
  private final void drawGraphics(int mpos) {
    if (notVisible) {
      if (gfxVisible && !paintBorder && (borderState & 1) == 0) {
        vc++;
      }
      vmli++;
      return;
    }

    if (!gfxVisible || paintBorder || (borderState & 1) == 1) {
      mpos -= horizScroll;
      int color = (paintBorder || (borderState > 0)) ? borderColor : bgColor;
      for (int i = mpos, n = mpos + 8; i < n; i++) {
        mem[i] = color;
      }
      vmli++;
      return;
    }

    int collX = (vmli << 3) + horizScroll + SC_XOFFS;

    // Paint background if first col
    if (vmli == 0) {
      for (int i = mpos - horizScroll, n = i + 8; i < n; i++) {
        mem[i] = bgColor;
      }
    }

    int position = 0, data = 0, penColor = 0, bgcol = bgColor;

    if ((control1 & 0x20) == 0) {
      int tmp;
      int pcol;

      if (multiCol) {
        multiColor[0] = bgColor;
        multiColor[1] = cbmcolor[bgCol[1]];
        multiColor[2] = cbmcolor[bgCol[2]];
      }

      penColor = cbmcolor[pcol = vicColCache[vmli] & 15];
      if (extended) {
        position = charMemoryIndex +
        (((data = vicCharCache[vmli]) & 0x3f) << 3);
        bgcol = cbmcolor[bgCol[(data >> 6)]];
      } else {
        position = charMemoryIndex + (vicCharCache[vmli] << 3);
      }

      data = memory[position + rc];

      if (multiCol && pcol > 7) {
        multiColor[3] = cbmcolor[pcol & 7];
        for (int pix = 0; pix < 8; pix += 2) {
          tmp = (data >> pix) & 3;
          mem[mpos + 6 - pix] = mem[mpos + 7 - pix] = multiColor[tmp];
          if (tmp > 0x01) {
            tmp = 256;
          } else {
            tmp = 0;
          }
          collissionMask[collX + 7 - pix] =
            collissionMask[collX + 6 - pix] = tmp;
        }
      } else {
        for (int pix = 0; pix < 8; pix++) {
          if ((data & (1 << pix)) > 0) {
            mem[mpos + 7 - pix] = penColor;
            collissionMask[collX + 7 - pix] = 256;
          } else {
            mem[mpos + 7 - pix] = bgcol;
            collissionMask[collX + 7 - pix] = 0;
          }
        }
      }

      if (multiCol && extended) {
        for (int pix = 0; pix < 8; pix++) {
          mem[mpos + 7 - pix] = 0xff000000;
        }
      }

      if (BAD_LINE_DEBUG && badLine) {
        for (int pix = 0; pix < 8; pix += 4) {
          mem[mpos + 7 - pix] = (mem[mpos + 7 - pix] & 0xff7f7f7f) | 0x0fff;
        }
      }
    } else {
      // -------------------------------------------------------------------
      // Bitmap mode!
      // -------------------------------------------------------------------
      position = vicBase + (vc & 0x3ff) * 8 + rc;
      if (multiCol) {
        multiColor[0] = bgColor;
      }
      int vmliData = vicCharCache[vmli];
      penColor =
        cbmcolor[(vmliData & 0xf0) >> 4];
      bgcol = cbmcolor[vmliData & 0x0f];

      data = memory[position];

      if (multiCol) {
        multiColor[1] =
          cbmcolor[(vmliData >> 4) & 0x0f];
        multiColor[2] =
          cbmcolor[vmliData & 0x0f];
        multiColor[3] = cbmcolor[vicColCache[vmli] & 0x0f];

        int tmp;
        for (int pix = 0; pix < 8; pix += 2) {
          mem[mpos + 6 - pix] = mem[mpos + 7 - pix] =
            multiColor[tmp = (data >> pix) & 3];
          if (tmp > 0x01) {
            tmp = 256;
          } else {
            tmp = 0;
          }
          collissionMask[collX + 7 - pix] =
            collissionMask[collX + 6 - pix] = tmp;
        }
      } else {
        for (int pix = 0; pix < 8; pix++) {
          if ((data & (1 << pix)) > 0) {
            mem[7 - pix + mpos] = penColor;
            collissionMask[collX + 7 - pix] = 256;
          } else {
            mem[7 - pix + mpos] = bgcol;
            collissionMask[collX + 7 - pix] = 0;
          }
        }
      }

      if (extended) {
        for (int pix = 0; pix < 8; pix++) {
          mem[mpos + 7 - pix] = 0xff000000;
        }
      }

      if (BAD_LINE_DEBUG && badLine) {
        for (int pix = 0; pix < 8; pix += 4) {
          mem[mpos + 7 - pix] = (mem[mpos + 7 - pix] & 0xff3f3f3f) | 0x0fff;
        }
      }
    }
    vc++;
    vmli++;
  }

  // -------------------------------------------------------------------
  // Sprites...
  // -------------------------------------------------------------------
  private final void drawSprites() {
    if (notVisible) {
      return;
    }
    int smult = 0x100;
    int lastX = xPos - 8;

    for (int i = 7; i >= 0; i--) {
      Sprite sprite = sprites[i];
      // Done before the continue...
      smult = smult >> 1;
      if (sprite.lineFinished || !sprite.painting) {
        continue;
      }
      int x = sprite.x + SC_SPXOFFS; // 0 in sprite x => xPos = 8
      int mpos = vPos * SC_WIDTH;

      if (x < xPos) {
        int minX = lastX > x ? lastX : x;

        for (int j = minX, m = xPos; j < m; j++) {
          int c = sprite.getPixel();
          if (c != 0 && borderState == 0) {
            int tmp = (collissionMask[j] |= smult);
            if (!sprite.priority || (tmp & 0x100) == 0) {
              mem[mpos + j] = sprite.color[c];
            }

            if (tmp != smult) {
              if ((tmp & 0x100) != 0) {
                sprBgCol |= smult;
              }
              if ((tmp & 0xff) != smult) {
                sprCol |= tmp & 0xff;
              }
            }
          }

          if (SPRITEDEBUG) {
            if ((sprite.nextByte == 3) && ((j & 4) == 0)) {
              mem[mpos + j] = 0xff00ff00;
            }
            if ((sprite.nextByte == 63) && ((j & 4) == 0)) {
              mem[mpos + j] = 0xffff0000;
            }

            if (j == x) {
              mem[mpos + j] = 0xff000000 + sprite.pointer;
            }
          }
        }
      }
    }
    xPos += 8;
  }

  public void setFullSpeed(boolean fullSpeed) {
    System.err.println("C64Screen.setFullSpeed(" + fullSpeed + ") sidChip=" + sidChip.getClass().getSimpleName());
    audioDriver.setFullSpeed(fullSpeed);
    if (sidChip instanceof SIDChip) {
      ((SIDChip) sidChip).mixer.setFullSpeed(fullSpeed);
    } else if (sidChip instanceof RESIDChip) {
      ((RESIDChip) sidChip).setFullSpeed(fullSpeed);
    }
  }

  public void stop() {
    sidChip.stop();
    audioDriver.shutdown();
  }

  public void reset() {
    // Clear a lot of stuff...???
    initUpdate();
    sidChip.reset();
    lastLine = cpu.cycles;
    nextIOUpdate = cpu.cycles + 47;

    for (int i = 0; i < mem.length; i++) mem[i] = 0;
    reset = 100;

    sprCol = 0;
    sprBgCol = 0;

    cia[0].reset();
    cia[1].reset();
    keyboard.reset();
    ciaWrites = 0;
    isrRunning = false;

    resetInterrupts();
    rasterIrqClock = RASTER_IRQ_DISABLED;
  }

  public static final int IMG_TOTWIDTH = SC_WIDTH;
  public static final int IMG_TOTHEIGHT = SC_HEIGHT;

  // -------------------------------------------------------------------
  // Internal sprite class to handle all data for sprites
  // -------------------------------------------------------------------
  private class Sprite {

    boolean painting = false;
    boolean dma = false;

    int nextByte;
    int pointer;
    int x;
    int y;

    int spriteNo;
    int spriteReg;

    boolean enabled;
    boolean expFlipFlop;
    boolean multicolor = false;
    boolean expandX = false;
    boolean expandY = false;
    boolean priority = false;
    boolean lineFinished = false;

    int pixelsLeft = 0;
    int currentPixel = 0;

    int col;
    int[] color = new int[4];

    int getPixel() {
      if (lineFinished) return 0;
      pixelsLeft--;
      if (pixelsLeft > 0) return currentPixel;
      if (pixelsLeft <= 0 && spriteReg == 0) {
        currentPixel = 0;
        lineFinished = true;
        return 0;
      }

      if (multicolor) {
        currentPixel = (spriteReg & 0xc00000) >> 22;
        spriteReg = (spriteReg << 2) & 0xffffff;
        pixelsLeft = 2;
      } else {
        currentPixel = (spriteReg & 0x800000) >> 22;
        spriteReg = (spriteReg << 1) & 0xffffff;
        pixelsLeft = 1;
      }
      if (expandX) {
        pixelsLeft = pixelsLeft << 1;
      }

      return currentPixel;
    }

    void reset() {
      lineFinished = false;
    }

    void readSpriteData() {
      pointer = vicBank + memory[spr0BlockSel + spriteNo] * 0x40;
      spriteReg = ((memory[pointer + nextByte++] & 0xff) << 16) |
      ((memory[pointer + nextByte++] & 0xff)  << 8) |
      memory[pointer + nextByte++];

      if (!expandY) expFlipFlop = false;

      if (expFlipFlop) {
        nextByte = nextByte - 3;
      }

      expFlipFlop = !expFlipFlop;
      pixelsLeft = 0;
    }
  }

  // -------------------------------------------------------------------
  // Observer (1541)
  // -------------------------------------------------------------------

  public void updateDisk(Object obs, Object msg) {
    if (msg == C1541Chips.HEAD_MOVED) {
      if (lastTrack != c1541Chips.currentTrack) {
        lastTrack = c1541Chips.currentTrack;
      }
    }

    lastSector = c1541Chips.currentSector;
    tmsg = " track: " + lastTrack + " / " + lastSector;

    ledOn = c1541Chips.ledOn;
    motorOn = c1541Chips.motorOn;
  }

  // Pointer input (for paddle/lightpen emulation)
  public void setPointerPosition(int x, int y) {
    potx = 0xff - (x & 0xff);
    poty = y & 0xff;
  }

  public void setPointerButton(int button, boolean pressed) {
    if (button == 1) {
      button1 = pressed;
    } else {
      button2 = pressed;
    }
    keyboard.setButtonval(0xff - (button1 | button2 ? 0x10 : 0));
  }
}
