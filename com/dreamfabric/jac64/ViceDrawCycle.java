package com.dreamfabric.jac64;

/**
 * Faithful Java port of VICE viciisc/vicii-draw-cycle.c.
 *
 * Source reference:
 *   /Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-draw-cycle.c
 *
 * Multi-week port goal: replace JaC64's legacy drawGraphics/drawColorsVice
 * + retroactive-paint mechanisms with a single VICE-aligned pipeline.
 *
 * Status: skeleton. Functions are stubs until each Phase commit ports
 * them faithfully. Activate via `-Djac64.viceFullPipeline=true`.
 *
 * Mode flag matrix:
 *   color_latency = true  → 6569 PAL old (1-pixel cregs pipe delay)
 *   color_latency = false → 8565 PAL HMOS (immediate cregs + grey-dot)
 *
 * Public API:
 *   init()                     — port of vicii_draw_cycle_init
 *   drawCycle()                — port of vicii_draw_cycle (every cycle)
 *   updateColorReg(reg, value) — register a $D02X write; deferred
 *                                cregs commit happens at end of draw_colors8
 *
 * State setters (from C64Screen on each cycle):
 *   setCycleFlags(int)  — encoded cycle_flags for current cycle
 *   setRasterCycle(int) — raster_cycle index (0..62)
 *   setColorLatency(boolean)
 *   setMainBorder(boolean) / setVborder(boolean)
 *   setIdleState(boolean)
 *   setGbuf(int) / setVbufCbuf(int[][])
 *   setRegs(int[])
 *   setDbufWriter(DbufSink) — output sink (matches JaC64's mem[] writes)
 *
 * NOT yet active in default builds; -Djac64.viceFullPipeline switches
 * the draw path from drawGraphicsVice/drawColorsVice (legacy) to this
 * class's drawCycle().
 */
public final class ViceDrawCycle {

  // ===========================================================
  // COLOR CODE CONSTANTS (port of vicii-draw-cycle.c:42-63)
  // ===========================================================

  public static final int COL_NONE      = 0x10;
  public static final int COL_VBUF_L    = 0x11;
  public static final int COL_VBUF_H    = 0x12;
  public static final int COL_CBUF      = 0x13;
  public static final int COL_CBUF_MC   = 0x14;
  public static final int COL_D02X_EXT  = 0x15;
  public static final int COL_D020      = 0x20;
  public static final int COL_D021      = 0x21;
  public static final int COL_D022      = 0x22;
  public static final int COL_D023      = 0x23;
  public static final int COL_D024      = 0x24;
  public static final int COL_D025      = 0x25;
  public static final int COL_D026      = 0x26;
  public static final int COL_D027      = 0x27;
  public static final int COL_D028      = 0x28;
  public static final int COL_D029      = 0x29;
  public static final int COL_D02A      = 0x2a;
  public static final int COL_D02B      = 0x2b;
  public static final int COL_D02C      = 0x2c;
  public static final int COL_D02D      = 0x2d;
  public static final int COL_D02E      = 0x2e;

  // Cycle-flags bit encoding from vicii-chip-model.h:41-116.
  public static final int CHECK_BRD_M     = 0xe0000000;
  public static final int CHECK_SPR_EXP_M = 0x10000000;
  public static final int CHECK_SPR_M     = 0x0e000000;
  public static final int UPDATE_VC_M     = 0x01000000;
  public static final int UPDATE_RC_M     = 0x00800000;
  public static final int VIS_EN_M        = 0x00400000;
  public static final int XPOS_M          = 0x003f0000;
  public static final int PHI2_FETCH_C_M  = 0x00008000;
  public static final int PHI1_SPR_NUM_M  = 0x00007000;
  public static final int PHI1_TYPE_M     = 0x00000e00;
  public static final int PHI1_FETCH_G    = 0x00000400;
  public static final int FETCH_BA_M      = 0x00000100;
  public static final int SPRITE_BA_MASK_M = 0x000000ff;

  /**
   * PAL 6569 cycle_table[63] — extracted from VICE x64sc's
   * vicii_chip_model_init() at runtime via Phase F1 dump patch.
   * Indexed by vicCycle (1..63 wrapped to 0..62). Used to drive
   * cycleFlags emission per cycle (matches VICE chip-model exactly
   * for PAL 6569R3).
   */
  public static final int[] PAL_6569_CYCLE_TABLE = {
      0x00323618, // cyc 1
      0x00333838, // cyc 2
      0x00344630, // cyc 3
      0x00354870, // cyc 4
      0x00365660, // cyc 5
      0x003758e0, // cyc 6
      0x003866c0, // cyc 7
      0x003968c0, // cyc 8
      0x003a7680, // cyc 9
      0x003b7880, // cyc 10
      0x003c0200, // cyc 11
      0x003d0300, // cyc 12
      0x003e0300, // cyc 13
      0x01000300, // cyc 14
      0x08418300, // cyc 15
      0x06428500, // cyc 16
      0xa0438500, // cyc 17
      0x80448500, // cyc 18
      0x00458500, // cyc 19
      0x00468500, // cyc 20
      0x00478500, // cyc 21
      0x00488500, // cyc 22
      0x00498500, // cyc 23
      0x004a8500, // cyc 24
      0x004b8500, // cyc 25
      0x004c8500, // cyc 26
      0x004d8500, // cyc 27
      0x004e8500, // cyc 28
      0x004f8500, // cyc 29
      0x00508500, // cyc 30
      0x00518500, // cyc 31
      0x00528500, // cyc 32
      0x00538500, // cyc 33
      0x00548500, // cyc 34
      0x00558500, // cyc 35
      0x00568500, // cyc 36
      0x00578500, // cyc 37
      0x00588500, // cyc 38
      0x00598500, // cyc 39
      0x005a8500, // cyc 40
      0x005b8500, // cyc 41
      0x005c8500, // cyc 42
      0x005d8500, // cyc 43
      0x005e8500, // cyc 44
      0x005f8500, // cyc 45
      0x00608500, // cyc 46
      0x00618500, // cyc 47
      0x00628500, // cyc 48
      0x00638500, // cyc 49
      0x00648500, // cyc 50
      0x00658500, // cyc 51
      0x00668500, // cyc 52
      0x00678500, // cyc 53
      0x00688500, // cyc 54
      0x02290401, // cyc 55
      0x522a0001, // cyc 56
      0x602b0003, // cyc 57
      0x04ac0603, // cyc 58
      0x002d0807, // cyc 59
      0x002e1606, // cyc 60
      0x002f180e, // cyc 61
      0x0030260c, // cyc 62
      0x0031281c  // cyc 63
  };

  /**
   * Look up cycle_flags for the given JaC64 vicCycle (0..62).
   * JaC64 vicCycle N corresponds to VICE raster_cycle N+1, so table[N]
   * (= PAL "cyc N+1" entry) returns the matching flags. The legacy
   * `vicCycle - 1` indexing dropped vicCycle=0 to zero flags entirely,
   * masking off VICE raster_cycle 1's sprite ptr/dma indicators.
   */
  public static int cycleFlagsFor(int vicCycle) {
    if (vicCycle < 0 || vicCycle > 62) return 0;
    return PAL_6569_CYCLE_TABLE[vicCycle];
  }

  // ===========================================================
  // STATE — mirrors static state in vicii-draw-cycle.c
  // ===========================================================

  // Foreground/background graphics pipe registers (vicii-draw-cycle.c:67-77).
  private int gbufPipe0Reg, cbufPipe0Reg, vbufPipe0Reg;
  private int gbufPipe1Reg, cbufPipe1Reg, vbufPipe1Reg;
  private int xscrollPipe;
  private int vmode11Pipe, vmode16Pipe, vmode16Pipe2;

  // gbuf shift register (vicii-draw-cycle.c:80-82).
  private int gbufReg;
  private int gbufMcFlop;
  private int gbufPixelReg;

  // cbuf/vbuf registers (vicii-draw-cycle.c:85-86).
  private int cbufReg, vbufReg;

  private int dmli;

  // Border (vicii-draw-cycle.c:107).
  private int borderState;

  // Pixel/render/pri buffers (vicii-draw-cycle.c:110-113).
  private final int[] renderBuffer = new int[8];
  private final int[] priBuffer = new int[8];
  private final int[] pixelBuffer = new int[8];

  // Color resolution registers (vicii-draw-cycle.c:116-118).
  private final int[] cregs = new int[0x2f];

  // VICE has TWO last_color_reg state variables:
  //  vicii.last_color_reg (vicii-draw-cycle.c:117 via vicii struct) is
  //  set by CPU $D02X writes in vicii-mem.c. update_cregs() at start of
  //  draw_colors8 captures it into the file-static last_color_reg, then
  //  RESETS vicii.last_color_reg = 0xff so the next cycle starts fresh.
  //
  //  vicii.last_color_reg  <- pendingFromCpu  (set by updateColorReg)
  //  last_color_reg        <- thisCycleLastReg (set in drawColors8, used
  //                                              for cregs commit AND
  //                                              draw_colors_8565 grey-
  //                                              dot pixel-0 check)
  private int pendingFromCpu = 0xff;
  private int pendingFromCpuValue;
  private int thisCycleLastReg = 0xff;
  private int thisCycleLastValue;

  // cycle_flags pipe (vicii-draw-cycle.c:120).
  private int cycleFlagsPipe;

  // VIC-II inputs (mirrors of vicii.X state from C64Screen).
  private int regs0x11, regs0x16, regs0x18;
  private int gbuf;
  private boolean colorLatency;
  private boolean idleState;
  private boolean mainBorder;
  private int vborder; // 0 or 1
  private int rasterCycle;
  private int cycleFlags;
  private int[] vbuf;   // 40 entries (matrix bytes)
  private int[] cbuf;   // 40 entries (color RAM bytes)

  // Output sink: per-pixel RGBA writes via a callback. C64Screen wires
  // this to its mem[]/cbmcolor[] painting.
  public interface DbufSink {
    /** Resolve a 4-bit color (cregs result) to RGBA via cbmcolor[]. */
    int paletteLookup(int color4);
    /** Write a pixel at dbuf offset (per-cycle increment of 8). */
    void writePixel(int offs, int colorIndex);
  }
  private DbufSink sink;
  private int dbufOffset;

  // Phase 7: per-cycle sprite output snapshot, populated by C64Screen
  // from ViceSpritePipeline outputs BEFORE drawCycle() is called.
  // Code 0 = transparent. Code 1 = COL_D025 (MC0). Code 2 = COL_D027+s
  // (sprite individual). Code 3 = COL_D026 (MC1). fgWin = graphics
  // foreground priority beat the sprite at this pixel.
  private final int[] sprColorCode = new int[8];
  private final int[] sprIndex = new int[8];
  private final boolean[] sprFgWin = new boolean[8];

  public void setSpriteOutput(int[] colorCode, int[] sprite, boolean[] fgWin) {
    System.arraycopy(colorCode, 0, sprColorCode, 0, 8);
    System.arraycopy(sprite, 0, sprIndex, 0, 8);
    System.arraycopy(fgWin, 0, sprFgWin, 0, 8);
  }

  public void clearSpriteOutput() {
    java.util.Arrays.fill(sprColorCode, 0);
    java.util.Arrays.fill(sprFgWin, false);
  }

  // ===========================================================
  // INIT — port of vicii_draw_cycle_init (vicii-draw-cycle.c:724)
  // ===========================================================

  public void init() {
    java.util.Arrays.fill(pixelBuffer, 0);
    java.util.Arrays.fill(cregs, 0);
    for (int i = 0; i < 0x10; i++) {
      cregs[i] = i;
    }
    pendingFromCpu = 0xff;
    thisCycleLastReg = 0xff;
    cycleFlagsPipe = 0;
    dbufOffset = 0;
  }

  // ===========================================================
  // STATE SETTERS — called by C64Screen per cycle
  // ===========================================================

  public void setSink(DbufSink s) { this.sink = s; }
  public void setRasterCycle(int rc) { this.rasterCycle = rc; }
  public void setCycleFlags(int cf) { this.cycleFlags = cf; }
  public void setColorLatency(boolean cl) { this.colorLatency = cl; }
  public void setMainBorder(boolean mb) { this.mainBorder = mb; }
  public void setVborder(int v) { this.vborder = v; }
  public void setIdleState(boolean idle) { this.idleState = idle; }
  public void setGbuf(int g) { this.gbuf = g & 0xff; }
  public void setRegs0x11(int v) { this.regs0x11 = v & 0xff; }
  public void setRegs0x16(int v) { this.regs0x16 = v & 0xff; }
  public void setRegs0x18(int v) { this.regs0x18 = v & 0xff; }
  public void setVbufCbuf(int[] vb, int[] cb) { this.vbuf = vb; this.cbuf = cb; }

  /**
   * Register a $D02X write. Mirrors VICE setting vicii.last_color_reg /
   * vicii.last_color_value. The new value is committed to cregs[] at
   * the END of the next drawColors8 call (matches VICE update_cregs).
   */
  public void updateColorReg(int reg, int value) {
    if (reg >= 0x20 && reg <= 0x2e) {
      pendingFromCpu = reg;
      pendingFromCpuValue = value & 0x0f;
    }
  }

  // ===========================================================
  // MAIN ENTRY: drawCycle — port of vicii_draw_cycle (line 705)
  // ===========================================================

  public void drawCycle() {
    if (rasterCycle == 1) {
      dbufOffset = 0;
    }
    int offsBefore = dbufOffset;  // for trace emission
    // BUGFIX: VICE passes the current cycle's flags directly. The legacy
    // cycleFlagsPipe introduced a spurious 1-cycle delay that pushed the
    // first g-fetch byte into gbufReg one cycle after VICE on every line.
    drawGraphics8(cycleFlags);
    drawSprites8();
    drawBorder8();
    drawColors8();
    if (traceDraw) emitTrace(offsBefore);
    cycleFlagsPipe = cycleFlags;
  }

  // VICE-shaped split: the caller runs Part1 (graphics) first, then
  // advances the sprite pipeline (which reads the fresh priBuffer below),
  // then calls Part2 (sprite composite + border + colors) with the
  // current-cycle sprite output. Matches vicii_draw_cycle order exactly.
  private int splitOffsBefore;
  public void drawCyclePart1() {
    if (rasterCycle == 1) {
      dbufOffset = 0;
    }
    splitOffsBefore = dbufOffset;
    drawGraphics8(cycleFlags);
  }
  public void drawCyclePart2() {
    drawSprites8();
    drawBorder8();
    drawColors8();
    if (traceDraw) emitTrace(splitOffsBefore);
    cycleFlagsPipe = cycleFlags;
  }

  /** Copy current-cycle gfx priority bits into dst (8 booleans, true = foreground). */
  public void copyPriBufferInto(boolean[] dst) {
    for (int i = 0; i < 8; i++) dst[i] = priBuffer[i] != 0;
  }

  // ===========================================================
  // TRACE (Phase A) — emit EV-DrawCycle matching VICE's patch in
  // vicii-draw-cycle.c. Gated by -Djac64.viceDrawTrace + clk window.
  // ===========================================================

  private static final boolean traceDraw =
      Boolean.getBoolean("jac64.viceDrawTrace");
  private static final long traceDrawClkStart =
      Long.getLong("jac64.viceDrawTraceStart", 0L);
  private static final long traceDrawClkEnd =
      Long.getLong("jac64.viceDrawTraceEnd", Long.MAX_VALUE);
  private static java.io.PrintStream traceOut;
  private final int[] emittedColors = new int[8];
  private long traceClk;
  private int traceRasterLine;

  public void setTraceClk(long clk, int rasterLine) {
    this.traceClk = clk;
    this.traceRasterLine = rasterLine;
  }

  private void emitTrace(int offsBefore) {
    if (traceClk < traceDrawClkStart || traceClk > traceDrawClkEnd) return;
    if (traceOut == null) {
      String path = System.getProperty("jac64.viceDrawTraceFile",
          "/tmp/jac64_draw.trace");
      try { traceOut = new java.io.PrintStream(path); }
      catch (Exception e) { traceOut = System.err; }
    }
    StringBuilder sb = new StringBuilder(256);
    sb.append("EV-DrawCycle clk=").append(traceClk)
      .append(" rast=$").append(Integer.toHexString(traceRasterLine))
      .append(" cyc=").append(rasterCycle)
      .append(" offs=").append(offsBefore)
      .append(" cregs21=$").append(Integer.toHexString(cregs[0x21]))
      .append(" last=$").append(Integer.toHexString(thisCycleLastReg))
      .append(" gbuf=$").append(Integer.toHexString(gbuf))
      .append(" vbuf=$").append(Integer.toHexString(vbufReg))
      .append(" cbuf=$").append(Integer.toHexString(cbufReg))
      .append(" mb=").append(mainBorder ? 1 : 0)
      .append(" vb=").append(vborder)
      .append(" px=");
    for (int i = 0; i < 8; i++) {
      if (i > 0) sb.append('.');
      sb.append(Integer.toHexString(emittedColors[i] & 0xf));
    }
    sb.append(" rb=");
    for (int i = 0; i < 8; i++) {
      if (i > 0) sb.append('.');
      sb.append(Integer.toHexString(renderBuffer[i] & 0xff));
    }
    traceOut.println(sb.toString());
    traceOut.flush();
  }

  // ===========================================================
  // draw_sprites8 — port of vicii-draw-cycle.c:469 (overlay only).
  // The full VICE draw_sprites8 advances sprite shift registers + does
  // priority math; ViceSpritePipeline already produces byte-identical
  // sbufReg/output (see project_sprite_shift_match.md). Phase 7 reads
  // those outputs and overlays them into renderBuffer / priBuffer.
  // ===========================================================

  private void drawSprites8() {
    // ViceSpritePipeline sets outColorCode[i] = 0 when sprite loses
    // priority to graphics foreground, so an unconditional overlay on
    // non-zero is correct (matches VICE's draw_sprites priority gate).
    for (int i = 0; i < 8; i++) {
      int code = sprColorCode[i];
      if (code == 0) continue;
      int spriteCode;
      if (code == 1) {
        spriteCode = COL_D025;
      } else if (code == 3) {
        spriteCode = COL_D026;
      } else {
        int s = sprIndex[i];
        if (s < 0 || s > 7) continue;
        spriteCode = COL_D027 + s;
      }
      renderBuffer[i] = spriteCode;
    }
  }

  // ===========================================================
  // GRAPHICS COLORS TABLE (vicii-draw-cycle.c:135-144)
  // ===========================================================

  private static final int[] COLORS = {
    COL_D021, COL_D021, COL_CBUF, COL_CBUF,         // ECM=0 BMM=0 MCM=0
    COL_D021, COL_D022, COL_D023, COL_CBUF_MC,      // ECM=0 BMM=0 MCM=1
    COL_VBUF_L, COL_VBUF_L, COL_VBUF_H, COL_VBUF_H, // ECM=0 BMM=1 MCM=0
    COL_D021, COL_VBUF_H, COL_VBUF_L, COL_CBUF,     // ECM=0 BMM=1 MCM=1
    COL_D02X_EXT, COL_D02X_EXT, COL_CBUF, COL_CBUF, // ECM=1 BMM=0 MCM=0
    COL_NONE, COL_NONE, COL_NONE, COL_NONE,         // ECM=1 BMM=0 MCM=1
    COL_NONE, COL_NONE, COL_NONE, COL_NONE,         // ECM=1 BMM=1 MCM=0
    COL_NONE, COL_NONE, COL_NONE, COL_NONE          // ECM=1 BMM=1 MCM=1
  };

  // ===========================================================
  // draw_graphics(i) — port of vicii-draw-cycle.c:146
  // ===========================================================

  // iter#17: per-pixel PX-LATCH trace for diffing mode-pipe state
  // against VICE. Format matches VICE patch in vicii-draw-cycle.c.
  // Gated by -Djac64.tracePxLatch=true. Trace window via
  // -Djac64.tracePxLatchStart / -Djac64.tracePxLatchEnd (clk).
  private static final boolean TRACE_PX_LATCH =
      Boolean.getBoolean("jac64.tracePxLatch");
  private static final long TRACE_PX_LATCH_START =
      Long.getLong("jac64.tracePxLatchStart", 0L);
  private static final long TRACE_PX_LATCH_END =
      Long.getLong("jac64.tracePxLatchEnd", Long.MAX_VALUE);
  private static java.io.PrintStream pxLatchOut = null;
  private void pxLatchTrace(int i) {
    if (!TRACE_PX_LATCH) return;
    if (traceClk < TRACE_PX_LATCH_START || traceClk > TRACE_PX_LATCH_END) return;
    if (pxLatchOut == null) {
      String p = System.getProperty("jac64.tracePxLatchFile", "/tmp/jac64_pxlatch.trace");
      try {
        pxLatchOut = new java.io.PrintStream(
            new java.io.FileOutputStream(p), true /* autoflush */);
      } catch (Exception e) { pxLatchOut = System.err; }
    }
    int emitted = renderBuffer[i];
    pxLatchOut.println("PX-LATCH clk=" + traceClk
        + " rast=$" + Integer.toHexString(traceRasterLine)
        + " cyc=" + rasterCycle + " pix=" + i
        + " regs11=$" + Integer.toHexString(regs0x11 & 0xff)
        + " regs16=$" + Integer.toHexString(regs0x16 & 0xff)
        + " vmode11=$" + Integer.toHexString(vmode11Pipe & 0xff)
        + " vmode16=$" + Integer.toHexString(vmode16Pipe & 0xff)
        + " vmode16p2=$" + Integer.toHexString(vmode16Pipe2 & 0xff)
        + " gbufReg=$" + Integer.toHexString(gbufReg & 0xff)
        + " mcFlop=" + gbufMcFlop
        + " emitted=$" + Integer.toHexString(emitted & 0xff));
  }

  private void drawGraphics(int i) {
    // Load new gbuf/vbuf/cbuf values at offset == xscroll
    if (i == xscrollPipe) {
      vbufReg = vbufPipe1Reg;
      cbufReg = cbufPipe1Reg;
      gbufReg = gbufPipe1Reg;
      gbufMcFlop = 1;
    }

    int px;
    if (vmode16Pipe2 != 0) {
      if ((vmode11Pipe & 0x08) != 0 || (cbufReg & 0x08) != 0) {
        if (gbufMcFlop != 0) {
          gbufPixelReg = (gbufReg >> 6) & 0x03;
        }
      } else {
        gbufPixelReg = ((gbufReg & 0x80) != 0) ? 3 : 0;
      }
    } else {
      if ((vmode11Pipe & 0x08) != 0 || (cbufReg & 0x08) != 0) {
        gbufPixelReg = ((gbufReg & 0x80) != 0) ? 2 : 0;
      } else {
        gbufPixelReg = ((gbufReg & 0x80) != 0) ? 3 : 0;
      }
    }
    px = gbufPixelReg;

    // shift the graphics buffer
    gbufReg = (gbufReg << 1) & 0xff;
    gbufMcFlop ^= 1;

    int vmode = vmode11Pipe | vmode16Pipe;
    int pixelPri = (px & 0x2);
    int cc = COLORS[vmode | px];

    switch (cc) {
      case COL_NONE:     cc = 0; break;
      case COL_VBUF_L:   cc = vbufReg & 0x0f; break;
      case COL_VBUF_H:   cc = (vbufReg >> 4) & 0x0f; break;
      case COL_CBUF:     cc = cbufReg & 0x0f; break;
      case COL_CBUF_MC:  cc = cbufReg & 0x07; break;
      case COL_D02X_EXT: cc = COL_D021 + ((vbufReg >> 6) & 0x03); break;
      default:           break;
    }

    renderBuffer[i] = cc;
    priBuffer[i] = pixelPri;
  }

  // ===========================================================
  // draw_graphics8 — port of vicii-draw-cycle.c:229
  // ===========================================================

  private void drawGraphics8(int cycleFlags) {
    boolean visEn = (cycleFlags & VIS_EN_M) != 0;

    drawGraphics(0);  pxLatchTrace(0);
    drawGraphics(1);  pxLatchTrace(1);
    drawGraphics(2);  pxLatchTrace(2);
    drawGraphics(3);  pxLatchTrace(3);

    // pixel 4: latch MCM bit, rising-edge BMM/ECM for 6569
    vmode16Pipe = (regs0x16 & 0x10) >> 2;
    if (colorLatency) {
      vmode11Pipe |= (regs0x11 & 0x60) >> 2;
    }
    drawGraphics(4);  pxLatchTrace(4);
    drawGraphics(5);  pxLatchTrace(5);

    // pixel 6: falling-edge BMM/ECM for 6569
    if (colorLatency) {
      vmode11Pipe &= (regs0x11 & 0x60) >> 2;
    }
    drawGraphics(6);  pxLatchTrace(6);

    // pixel 7: MCM 0→1 transition resets mc_flop
    if (vmode16Pipe != 0 && vmode16Pipe2 == 0) {
      gbufMcFlop = 0;
    }
    vmode16Pipe2 = vmode16Pipe;
    drawGraphics(7);  pxLatchTrace(7);

    if (!colorLatency) {
      vmode11Pipe = (regs0x11 & 0x60) >> 2;
    }

    // shift and put next data into pipe.
    vbufPipe1Reg = vbufPipe0Reg;
    cbufPipe1Reg = cbufPipe0Reg;
    gbufPipe1Reg = gbufPipe0Reg;

    if (visEn && vborder == 0) {
      gbufPipe0Reg = gbuf;
      xscrollPipe = regs0x16 & 0x07;
    } else {
      gbufPipe0Reg = 0;
    }

    // Phase 8: VICE doesn't reset dmli per-cycle on non-visible cycles —
    // it only advances during vis_en && !vborder && !idle_state, and
    // resets via init or vicii-cycle.c state changes. C64Screen drives
    // dmli explicitly via setDmli() before each drawCycle() call, so
    // the pipeline reads vbuf/cbuf at the same index JaC64 just fetched.
    if (visEn && vborder == 0 && !idleState
        && vbuf != null && cbuf != null
        && dmli >= 0 && dmli < vbuf.length) {
      vbufPipe0Reg = vbuf[dmli];
      cbufPipe0Reg = cbuf[dmli];
    }
  }

  public void setDmli(int v) { this.dmli = v; }

  /**
   * Legacy setter — VICE's file-static `border_state` is updated INSIDE
   * `draw_border8` (at the end of the transition logic) and persists
   * across cycles. C64Screen used to overwrite it each cycle from
   * `borderStatePrev`, which clobbered the transition state and caused
   * JaC64 to paint content 1 cycle earlier than VICE on the left-border
   * close (and similarly on right-border open). With
   * `-Djac64.viceInternalBorderState=true` (default), the field is
   * managed internally by drawBorder8 only — matching VICE.
   */
  public void setBorderState(int s) {
    if (Boolean.parseBoolean(System.getProperty("jac64.viceInternalBorderState", "true"))) {
      return; // ignore external overrides; drawBorder8 owns the state
    }
    this.borderState = s & 0x01;
  }

  // ===========================================================
  // draw_border8 — port of vicii-draw-cycle.c:574
  // ===========================================================

  private void drawBorder8() {
    boolean csel = (regs0x16 & 0x08) != 0;

    // Early exits.
    if (borderState == 0 && !mainBorder) {
      return;
    }
    if (borderState != 0 && mainBorder) {
      java.util.Arrays.fill(renderBuffer, COL_D020);
      return;
    }

    // Transition.
    if (csel) {
      if (borderState != 0) {
        java.util.Arrays.fill(renderBuffer, COL_D020);
      }
      borderState = mainBorder ? 1 : 0;
    } else {
      if (borderState != 0) {
        for (int i = 0; i < 7; i++) renderBuffer[i] = COL_D020;
      }
      borderState = mainBorder ? 1 : 0;
      if (borderState != 0) {
        renderBuffer[7] = COL_D020;
      }
    }
  }

  // ===========================================================
  // draw_colors8 — port of vicii-draw-cycle.c:659
  // ===========================================================

  private void drawColors8() {
    // VICE update_cregs (vicii-draw-cycle.c:618-623):
    //   last_color_reg = vicii.last_color_reg;
    //   last_color_value = vicii.last_color_value;
    //   vicii.last_color_reg = 0xff;
    thisCycleLastReg = pendingFromCpu;
    thisCycleLastValue = pendingFromCpuValue;
    pendingFromCpu = 0xff;

    // Commit (vicii-draw-cycle.c:669-671).
    if (thisCycleLastReg != 0xff) {
      cregs[thisCycleLastReg] = thisCycleLastValue;
    }

    if (colorLatency) {
      drawColors6569(0);
      drawColors6569(1);
      drawColors6569(2);
      drawColors6569(3);
      drawColors6569(4);
      drawColors6569(5);
      drawColors6569(6);
      drawColors6569(7);
    } else {
      drawColors8565(0);
      drawColors8565(1);
      drawColors8565(2);
      drawColors8565(3);
      drawColors8565(4);
      drawColors8565(5);
      drawColors8565(6);
      drawColors8565(7);
    }
    dbufOffset += 8;
  }

  // 6569 PAL: 1-pixel pipe-delayed resolution
  // (port of vicii-draw-cycle.c:625).
  private void drawColors6569(int i) {
    int lookupIndex = (i + 1) & 0x07;
    int code = pixelBuffer[lookupIndex];
    if (code >= 0x20 && code <= 0x2e) {
      pixelBuffer[lookupIndex] = cregs[code];
    }
    // Output: pixelBuffer[i] (already resolved earlier).
    emittedColors[i] = pixelBuffer[i];
    if (sink != null) {
      sink.writePixel(dbufOffset + i, pixelBuffer[i]);
    }
    // Carry CURRENT render_buffer[i] into pixel_buffer for NEXT cycle.
    pixelBuffer[i] = renderBuffer[i];
  }

  // 8565 PAL HMOS: immediate resolution + grey-dot at pixel 0
  // (port of vicii-draw-cycle.c:639). thisCycleLastReg is 0xff when
  // no CPU write happened this cycle, so the grey-dot check naturally
  // gates to write-cycles only.
  private void drawColors8565(int i) {
    int code = pixelBuffer[i];
    if (i == 0 && code == thisCycleLastReg && code >= 0x20 && code <= 0x2e) {
      pixelBuffer[i] = 0x0f;  // grey-dot
    } else if (code >= 0x20 && code <= 0x2e) {
      pixelBuffer[i] = cregs[code];
    }
    emittedColors[i] = pixelBuffer[i];
    if (sink != null) {
      sink.writePixel(dbufOffset + i, pixelBuffer[i]);
    }
    pixelBuffer[i] = renderBuffer[i];
  }

  // ===========================================================
  // ACCESSORS for debugging / state inspection
  // ===========================================================

  public int getCregs(int reg) { return reg >= 0 && reg < cregs.length ? cregs[reg] : -1; }
  public int getDbufOffset() { return dbufOffset; }
  public int[] getRenderBuffer() { return renderBuffer; }
  public int[] getPixelBuffer() { return pixelBuffer; }
}
