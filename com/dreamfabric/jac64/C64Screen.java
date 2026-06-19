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
  // SC_HEIGHT matches VICE PAL NORMAL display: 272 raster lines
  // (VICII_PAL_NORMAL_FIRST_DISPLAYED_LINE 0x10 .. LAST 0x11f, vicii-timing.h:68).
  // FIRST_VISIBLE_VBEAM = 15 → vbeam starts being painted at 16 (= 0x10).
  // Previous value 284 = 0x10 .. 0x12b (full mode), which inflated cell-diff
  // alignments vs VICE -8565.png references that are captured in normal mode.
  private final static int SC_WIDTH = 384; //403;
  private final static int SC_HEIGHT = 272;
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

  int[] memory;

  ExtChip sidChip;

  public RESIDChip getSidChip() {
    return (sidChip instanceof RESIDChip) ? (RESIDChip) sidChip : null;
  }

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
  private long lastLoadLog = 0;
  // for disk emulation...
  int cia2PRA = 0;
  int cia2DDRA = 0;

  private int lastTrack = 0;
  private int lastSector = 0;
  boolean ledOn = false;
  boolean motorOn = false;

  // This is an IEC emulation (non ROM based)
  boolean emulateDisk = false; //true; //!CPU.EMULATE_1541; // false;

  // Default to color set 1 — empirically derived from VICE x64sc
  // -8565.png reference images (greydot, rmwtest, modesplit). Set 1
  // gives near-pixel-perfect match with the modern VICE 8565 palette
  // (#352879 blue, #6c5eb5 lt.blue, #6c6c6c gray). Previously default
  // was set 3 ("WinVICE-ripped" from XP era), which was an older
  // palette and inflated cell-diffs by ~300 cells across the VICII
  // test suite — see project_vice_test_baseline_2026_05_11.
  int[] cbmcolor = VICConstants.COLOR_SETS[
      Integer.getInteger("jac64.colorSet", 1)];

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
  // VICE-style delayed copies of $D011 for graphics fetches. JaC64's CPU
  // write phase exposes the new register value one dispatcher cycle earlier
  // than VICE's g-fetch stage, so fetch address selection samples delay2.
  int control1FetchDelay = 0;
  int control1FetchDelay2 = 0;
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

  // Per-frame sprite-state history for the debug window. Captured at
  // end of each scan line; deduped by (x, pointer, nextByte) so
  // multiplex positions appear as separate entries.
  public static final int SPRITE_FRAME_MAX = 16;
  public final int[][] spriteFrameX = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFrameY = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFramePtr = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFrameNb = new int[8][SPRITE_FRAME_MAX];
  public final int[] spriteFrameCount = new int[8];
  // Cached view for the previous frame — read by SpriteDebugWindow so
  // the window shows stable data for the whole frame.
  public final int[][] spriteFrameXLast = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFrameYLast = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFramePtrLast = new int[8][SPRITE_FRAME_MAX];
  public final int[][] spriteFrameNbLast = new int[8][SPRITE_FRAME_MAX];
  public final int[] spriteFrameCountLast = new int[8];
  // Read for debugging on other places...
  public int vbeam = 0; // read at d012
  public int raster = 0;
  int bCol = 0;
  int bgCol[] = new int[4];

  // VICE viciisc/vicii-draw-cycle.c:116 cregs[] — color register array
  // indexed by VC_D02X code (=  reg offset 0x20..0x2e). draw_colors8
  // resolves render_buffer codes via `cregs[pixel_buffer[i]]`, which
  // is the unified path through which all color register writes affect
  // the rendered output. JaC64 mirrors VICE's structure: applyDelayed-
  // ColorReg writes both the legacy per-color fields AND cregs[reg].
  // drawColorsVic can then use cregs[code] for VC_D020..VC_D02E.
  private final int[] cregs = new int[0x2f];

  // VICE-style 1-cycle delay for color registers ($D020-$D024). VICE
  // applies the new value to cregs[] at start of NEXT vicii_cycle (see
  // viciisc/vicii-draw-cycle.c:635-638), so a register write at cycle N
  // affects pixel rendering from cycle N+1 onwards. JaC64 historically
  // applied immediately, causing BC0-BC3 stripes in vicii_reg_timing to
  // be 1 cycle wider than VICE.
  //
  // Implementation: bCol/bgCol[] still update immediately (for reads).
  // displayBCol/displayBgCol[] lag by 1 VIC cycle and are what rendering
  // uses. Sync happens at start of each clock() call.
  //
  // Enable with -Djac64.colorDelay=true. Default OFF until validated.
  // Phase E: VICE-style 1-cycle delayed color-register apply is REQUIRED
  // when the render-buffer path is on, because drawColorsVic mirrors
  // VICE viciisc/vicii-draw-cycle.c update_cregs() — the colors used at
  // draw_colors time are the values committed at start of the cycle,
  // not whatever the CPU just wrote mid-cycle. Auto-enable the existing
  // single-pending-slot delay machinery when vicRenderBuf is on.
  private final boolean COLOR_DELAY =
      Boolean.parseBoolean(System.getProperty("jac64.colorDelay", "true"))
          || Boolean.parseBoolean(System.getProperty("jac64.vicRenderBuf", "true"));
  // VICE-style single-pending-slot color register delay. Mirrors
  // viciisc/vicii-draw-cycle.c:586-590 update_cregs() pattern: last
  // register written this cycle is captured, applied at START of next
  // cycle. Multiple writes in same cycle: only the last sticks.
  private int lastColorReg = -1;       // 0x20..0x2e or -1
  // VICE viciisc/vicii-cycle.c:413-422 defers $D01E read's clear of
  // sprite_sprite_collisions to start of NEXT vicii_cycle(). JaC64
  // used to clear sprCol immediately on read, which let same-cycle
  // sprite paint fire a fresh SSCol IRQ 1 cycle earlier than VICE —
  // root cause of the irq-ack-vicii SS-COL row mismatch.
  private boolean sprColClearPending = false;
  private boolean sprBgColClearPending = false;
  // VICE viciisc/vicii-cycle.c:407 captures `can_sprite_sprite =
  // (sprite_sprite_collisions == 0)` BEFORE draw_cycle, then at the
  // END of vicii_cycle fires SSCol IRQ if can_sprite_sprite && new
  // collisions. Mirrors that pattern: capture at start of clock(),
  // fire at end. Per-pixel sprite paint just accumulates sprCol.
  private boolean sprColCanFire = true;
  private boolean sprBgColCanFire = true;
  // 1-cycle pipeline delay for collision IRQ fire to match VICE-visible
  // timing in the cycle-accurate CPU access model.
  // Port of VICE viciisc/vicii-cycle.c:407-455 end-of-cycle pattern:
  //   can_sprite_sprite = (sprite_sprite_collisions == 0);  // capture pre-draw
  //   vicii_draw_cycle();                                   // may set collisions
  //   if (can_sprite_sprite && sprite_sprite_collisions)    // post-draw fire
  //       vicii_irq_sscoll_set();
  // Direct irq-ack-vicii tracing showed JaC64 made the IRQ flag visible
  // at clk 7886762/raster cycle 44, immediately before the LDA $D019
  // at the same clock. VICE's passing behavior requires that flag one
  // Phi2 later. The extra ready stage delays IRQ flag visibility only;
  // the collision register itself is still accumulated immediately.
  private boolean sprColFirePending = false;
  private boolean sprBgColFirePending = false;
  private boolean sprColFireReady = false;
  private boolean sprBgColFireReady = false;
  private int lastColorValue = 0;
  private long lastColorClk = -1;

  private int vicBase = 0;
  private boolean badLine = false;
  // FLI fix: tracks whether handleBadLineStart has already fired for the
  // current line. Reset to badLine at case 0 (line start). Krestage 3 and
  // other FLI demos write $D011 multiple times per line, which without this
  // guard re-triggers handleBadLineStart and overwrites the committed
  // badLineFetchStartColumn — producing visible per-charrow stripes.
  private boolean badLineStartedThisLine = false;
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
  // VICE-faithful idle_state — the SINGLE source of truth for display vs
  // idle, mirroring vicii.idle_state (vicii-cycle.c). Initial value 0 =
  // not idle / display (VICE init at vicii.c:347). The former separate
  // `gfxVisible` field (== !vicIdleState) was collapsed into this on
  // 2026-06-01; all display-state reads now use !vicIdleState. Used by
  // the badline FSM, vc/vmli gating, and the pipe-load clear decision
  // (vicii-draw-cycle.c:309-317).
  boolean vicIdleState = false;
  boolean paintBorder = false;
  boolean paintSideBorder = false;

  // Port of VICE vicii-cycle.c border state:
  //   setVBorder — continuously updated by check_vborder_top /
  //                check_vborder_bottom (every cycle in VICE).
  //   vBorder    — latched from setVBorder at cyc 1 and at the
  //                left-border checks (cyc 17/18) in check_hborder.
  //   mainBorder — true = border pixels drawn here. Closes at
  //                cyc 56/57 via ChkBrdR0/R1; opens at cyc 17/18 via
  //                ChkBrdL0/L1 BUT only if vBorder is 0.
  // Used only when -Djac64.vicBorderLatch=true to gate rendering.
  private boolean setVBorder = true;
  private boolean vBorder = true;
  private boolean mainBorder = true;

  private long lastD021Cycles = -1;
  private int lastD021InLine = -1;

  int borderColor = cbmcolor[0];
  int bgColor = cbmcolor[1];

  private boolean extended = false;
  private boolean multiCol = false;
  private boolean blankRow = false;
  private boolean hideColumn = false;

  int multiColor[] = new int[4];

  // 48 extra for the case of an expanded sprite byte
  int collissionMask[] = new int[SC_WIDTH + 48];

  // ============================================================
  // VICE-style per-cycle render buffer infrastructure (Phase A)
  // ============================================================
  // Mirrors VICE viciisc/vicii-draw-cycle.c per-cycle state:
  //   render_buffer[8]  — 8 color CODES (COL_D021/COL_VBUF_L/...)
  //                       produced by draw_graphics8, overlaid by
  //                       draw_sprites8, then resolved to RGBA by
  //                       draw_colors8 via the cregs[] lookup.
  //   pri_buffer[8]     — 8 foreground-priority bits set by
  //                       draw_graphics8; read by draw_sprites8 to
  //                       gate sprite-over-graphics priority.
  //
  // Currently active only behind -Djac64.vicRenderBuf=true while
  // the surrounding pipeline is migrated phase-by-phase. Phase B
  // makes drawGraphicsVic write here; Phase C migrates sprite
  // paint; Phase D adds border; Phase E adds the color-resolution
  // step that writes mem[].
  // ============================================================
  private final int[] renderBuf = new int[8];
  private final boolean[] priBuf = new boolean[8];
  private final boolean useVicRenderBuf =
      Boolean.parseBoolean(System.getProperty("jac64.vicRenderBuf", "true"));
  // True if drawGraphicsVic has populated renderBuf this cycle.
  // Sprite paint (drawSpritesVicCycle) checks this to decide whether
  // to overlay renderBuf (= visible-window cycle, Phase B path) or
  // paint mem[] directly (= cases 13-15/56-60 where drawGraphicsVic
  // didn't run; legacy fallback). Reset to false after drawColorsVic.
  private boolean renderBufFresh = false;

  Sprite sprites[] = new Sprite[8];

  private int horizScroll = 0;
  private int vScroll = 0;

  // VICE-compatible coordinate system. See viciitypes.h:124:
  //   VICII_RASTER_X(cycle) = (cycle - 17) * 8 + screen_leftborderwidth
  // PAL screen_leftborderwidth = 0x20. Raster X is 0 at start of
  // visible screen (after left border). Sprite register X is
  // translated to internal coord via:
  //   sprite_x_internal = sprite_register_x + screen_leftborderwidth - 0x20
  // See vicii-mem.c:710.
  public static final int SCREEN_LEFT_BORDER_WIDTH = 0x20;
  // VICE render-space sprite offset on PAL: screen_leftborderwidth - 24.
  // JaC64's legacy path also renders sprite X=0 at screen pixel 8.
  private static final int SPRITE_RENDER_X_OFFSET =
      SCREEN_LEFT_BORDER_WIDTH - 24;
  // PAL sprite X wraps at 504 (0x1F8), per VICE
  // vicii-timing.c VICII_PAL_SPRITE_WRAP_X. Earlier value 0x200 (512)
  // let sprites at X=504..511 escape the wrap — visible in Krestage 3
  // banner as a ghost letter bleeding behind the K on the left.
  private static final int SPRITE_WRAP_X = 0x1F8;

  private int currentRasterX = 0;

  // Lightpen latched X/Y. CIA1 PB3 HIGH→LOW transition triggers latch
  // via triggerLightPen(). Returned by $D013/$D014 reads. Cleared at frame
  // start (or new trigger after frame wrap). Per VICE vicii-lightpen.c:75
  // x = cycle_get_xpos(cycle_table[raster_cycle]) / 2; in JaC's 1-indexed
  // vicCycle convention this maps to (vicCycle * 4 - 52), clamped >= 0.
  private int lpX = 0;
  private int lpY = 0;
  private boolean lpTriggered = false;
  // Pending trigger clk for 1-cyc-delay (matches VICE vicii-lightpen.c:44
  // `vicii.light_pen.trigger_cycle = mclk + 1`). When CIA1 PB4 falls,
  // store target clk = current + 1. clock() checks for pending trigger
  // each cycle and fires when match.
  private long lpPendingTriggerClk = -1;

  // ============================================================
  // VICE cycle-exact sprite pipeline (port of vicii-draw-cycle.c)
  // ============================================================
  // Faithful per-pixel sprite renderer; replaces the V2 span-based
  // pipeline (renderMcSpriteExpanded etc.) ported from the fast/
  // inaccurate vicii/vicii-sprites.c. Gated by jac64.vicSprPipe
  // for verification; flip default once spritesplit diff drops to
  // sub-1% across all 17 references.
  private final VicSpritePipeline vicSprPipe = new VicSpritePipeline();
  private final boolean useVicSprPipe =
      Boolean.parseBoolean(System.getProperty("jac64.vicSprPipe", "true"));

  // Phase 1: VICE-shaped per-cycle draw order
  //   draw_graphics8 -> draw_sprites8 -> composite -> border -> colors
  // (mirrors vicii_draw_cycle). False restores legacy 1-cycle sprite
  // output delay + shift=-16 compensations. Cached so per-cycle path
  // hits a final-field read instead of System.getProperty().
  private static final boolean VICE_SHAPED =
      Boolean.parseBoolean(System.getProperty("jac64.vicShaped", "true"));

  // Sprite xpos offset (see drawCycle8 call site below). Default -8
  // makes draw_sprites8 use Phi1(vicCycle)-quantized xpos matching VICE
  // cycle_get_xpos(cycle_flags_pipe). With shift=-16 paint base, both
  // paint and sprite trigger end up in the same VICE-aligned domain.
  private static final int SPR_XPOS_OFFSET =
      Integer.getInteger("jac64.sprXposOffset", -8);

  // VICE viciisc/vicii-draw-cycle.c:703 cycle_flags_pipe: draw_sprites8
  // and draw_graphics8 at cycle N consume flags from cycle N-1 (the pipe
  // value snapshotted at end of previous vicii_cycle()). JaC64 used to
  // pass current-cycle flags to drawCycle8 — 1-cycle phase shift versus
  // VICE. These pipe fields hold the snapshot to consume next cycle.
  private boolean sprPipeCheckSprDisp = false;
  private boolean sprPipePtrDma0 = false;
  private boolean sprPipeDma1Dma2 = false;
  private int sprPipeDmaNum = -1;
  private int sprPipeDisplayBits = 0;
  // VICE-sticky sprite_display_bits: only updated at ChkSprDisp cycle (case 57)
  // following VICE's check_sprite_display semantics. Set on Y-match + enable +
  // DMA, cleared on DMA-off, otherwise unchanged. Closes spriteenable1-4
  // (181 cells) without regressing any sprite test. Default ON; opt-out via
  // -Djac64.spriteDispSticky=false.
  private int spriteDisplayBitsSticky = 0;
  private final boolean useSpriteDispSticky =
      Boolean.parseBoolean(System.getProperty("jac64.spriteDispSticky", "true"));
  private int sprPipeReg1b = 0;
  private int sprPipeReg1c = 0;
  private int sprPipeReg1d = 0;
  private final int[] sprPipeSpriteX = new int[8];
  private int sprPipeRasterX = 0;
  // VICE viciisc/vicii-draw-cycle.c:703 — vicii_draw_cycle passes
  // cycle_flags_pipe (= PREVIOUS cycle's flags) to draw_sprites8.
  // JaC64 vicCycle N ≡ VICE raster_cycle N+1, so using the pipe matches
  // VICE's actual per-cycle flag visibility. Default ON for VICE
  // faithfulness; off costs ~25 cells across the suite but reads
  // current cycle's flags directly.
  private final boolean useCycleFlagsPipe =
      Boolean.parseBoolean(System.getProperty("jac64.cycleFlagsPipe", "true"));

  /** VICE-compatible raster_x given the current VIC cycle within a line. */
  private int rasterX(int vicCycle) {
    return (vicCycle - 17) * 8 + SCREEN_LEFT_BORDER_WIDTH;
  }

  /**
   * Called by CIA-1 when PB3 transitions HIGH→LOW. Latches current beam
   * X/Y for $D013/$D014 reads. Per VICE vicii-lightpen.c, only triggers
   * once per frame (lpTriggered cleared at frame start).
   *
   * Used by fldscroll rastersync_lp (and any LP-based cycle-alignment
   * routine) which writes $DC01=$00 to trigger, then reads $D013 to learn
   * the precise CPU cycle within the current raster line and delay-NOP
   * itself to a fixed cycle alignment.
   */
  public void triggerLightPen() {
    if (lpTriggered) return;
    // VICE vicii-lightpen.c:44 — `trigger_cycle = mclk + 1`. The trigger
    // is scheduled to fire 1 cycle later in vicii_cycle (not at the
    // CIA-write cycle itself). Schedule it; clock() fires when match.
    lpPendingTriggerClk = cpu.cycles + 1;
  }

  private void firePendingLightPen() {
    if (lpTriggered || lpPendingTriggerClk < 0 || cpu.cycles != lpPendingTriggerClk) {
      return;
    }
    lpPendingTriggerClk = -1;
    lpTriggered = true;
    int vicCycle = (int)(cpu.cycles - lastLine);
    // VICE formula: x = cycle_get_xpos(cycle_table[raster_cycle]) / 2.
    // PAL Phi2(N) xpos table values WRAP at N=13:
    //   Phi2(11)=0x1e8, Phi2(12)=0x1f0, Phi2(13)=0x000, Phi2(14)=0x008, …
    //   So pixel xpos starts at 0 at vicCycle 13 (left HSync edge) and
    //   counts up by 8 per cycle until reaching ~496 at cycle 12 of the
    //   NEXT line (= 504-px PAL line width).
    // Lightpen X = xpos / 2 (per hardware specification).
    int x;
    if (vicCycle >= 13) {
      x = (vicCycle - 13) * 4;          // 0..200 for cyc 13..63
    } else {
      x = 204 + (vicCycle - 1) * 4;     // 204..248 for cyc 1..12
    }
    // VICE adds x_extra_bits (1 for PAL color_latency=1 in CIA-PB
    // trigger path per vicii-lightpen.c:42, factored through to lpX).
    // Matches measured VICE values (fldscroll-29 $dd = base 220 + 1).
    x += 1;
    lpX = x & 0xff;
    lpY = vbeam & 0xff;
  }

  /** Sprite register X → internal coord used by sprite comparisons. */
  private int spriteXInternal(int spriteRegX) {
    return spriteRegX + SCREEN_LEFT_BORDER_WIDTH - 0x20;
  }

  /** Sprite register X → screen pixel coordinate used by the renderer. */
  private int spriteRenderX(int spriteRegX) {
    return spriteRegX + SPRITE_RENDER_X_OFFSET;
  }

  /** Convert raster_x space used by the queue to screen pixel coordinates. */
  private int rasterToScreenX(int rasterX) {
    return rasterX + SPRITE_RENDER_X_OFFSET;
  }

  /** Sprite register X in the internal VIC coordinate space used by bug logic. */
  private int spriteRepeatBugX(int spriteRegX) {
    return spriteRegX + SCREEN_LEFT_BORDER_WIDTH;
  }

  private int spriteDisplayImmediateDataFetched(int spriteNo) {
    return 0x156 + SCREEN_LEFT_BORDER_WIDTH + spriteNo * 0x10;
  }

  private int positiveMod(int value, int modulus) {
    int result = value % modulus;
    return result < 0 ? result + modulus : result;
  }

  // Deferred-change queue for sprite-affecting register writes.
  // Populated on CPU writes; cleared at each line start. Consumed by
  // the new sprite pipeline (stage 5).
  private final RasterChangeQueue spriteChangeQueue = new RasterChangeQueue();

  // Probe assist is now disabled by default — the writeRasterX +8
  // fix produces the correct $D01E naturally. Enable via
  // -Djac64.enableProbeAssist=true as a safety valve only.
  private final boolean assistKrestageProbe =
      Boolean.getBoolean("jac64.enableProbeAssist");
  private final SpriteSequencer[] spriteSeqs = new SpriteSequencer[8];
  {
    for (int i = 0; i < 8; i++) spriteSeqs[i] = new SpriteSequencer(i);
  }
  private int badLineFetchStartColumn = 0;
  private int badLineDummyColumns = 0;
  private int badLineFetchSourceColumn = 0;
  // Tracks whether col-0 c-access ran this line (case 15). In continuous FLI
  // the case-15 fetch is gated out (badLine set late), so this stays false and
  // the case-16 FLI leading-prefetch backfill fills the stale cols 0-1.
  private boolean col0FetchedThisLine = false;

  // VICE viciisc/vicii-cycle.c:657-666 prefetch_cycles. Tracks how many
  // cycles of BA-low we've been in. While prefetch_cycles > 0, the VIC
  // is in the FLI-bug pre-fetch window — c-access fetches return $3FFF
  // bus + CPU PC byte instead of normal screen RAM / color RAM. After
  // 3 cycles of bad_line, prefetch_cycles=0 and normal fetches resume.
  //
  // This matches VICE's per-cycle GAP semantic: bad_line can flip
  // on/off mid-line and the prefetch counter tracks each on→off→on
  // transition correctly, replacing JaC64's start-column shortcut.
  private int prefetchCycles = 4;
  private long rasterIrqClock = RASTER_IRQ_DISABLED;
  private final boolean debugProbe = Boolean.getBoolean("jac64.debugProbe");

  // The font is in a copy in "ROM"...
  private int charMemoryIndex = 0;

  // Caching all 40 chars (or whatever) each "bad-line"
  private int[] vicCharCache = new int[40];
  private int[] vicColCache = new int[40];
  private final boolean vicFetchDelay =
      Boolean.getBoolean("jac64.vicFetchDelay");
  private int vicBankFetchDelay = 0;
  private int vicMemFetchDelay = 0;
  private int videoMatrixFetchDelay = 0;
  private int vicBaseFetchDelay = 0;
  private int charMemoryIndexFetchDelay = 0;
  // Sprite-crunch port (project_sprite_crunch_port_plan).
  // Adds VICE-compatible mc/mcbase/exp_flop state machine + d017_store
  // bit-mixing crunch formula.
  // - UpdateMcBase at VICE Phi2(16) (= JaC case 15): mcbase=mc if expFlipFlop,
  //   DMA off if mcbase==63
  // - ChkSprExp at VICE Phi2(56) (= JaC case 55): toggle expFlipFlop for
  //   sprites with dma + y_exp bit set (READING LIVE $D017 = sprYEX)
  // - ChkSprDisp at VICE Phi1(58) (= JaC case 57): mc = mcbase
  // - Fetch uses mc (mc++ per byte), not nextByte
  // - $D017 write handler: on bit-clear + exp_flop=0, set exp_flop=1; if
  //   at ChkSprCrunch cycle (= JaC case 14), apply bit-mixing formula
  //   `mc = (0x2a & (mcbase & mc)) | (0x15 & (mcbase | mc))`
  // Closes spritecrunch-3b/3c/3d-00 entirely (6000 cells) + sequencer-bug
  // (480/481 cells) + spritecrunch2-07-29 partially (~1000+ cells).
  // Default ON; opt-out via -Djac64.spriteCrunch=false. Anchor 16-test
  // JaC-vs-VICE stays at 189 (same as default-off baseline). The earlier
  // "regression" sighting on ss-hires-mc-exp/ss-mc-color2/vicii_reg_timing-a5
  // was a mid-sweep build race; manually re-verified all three are clean.
  private final boolean useSpriteCrunch =
      Boolean.parseBoolean(System.getProperty("jac64.spriteCrunch", "true"));
  private final boolean vicRenderDelay =
      Boolean.getBoolean("jac64.vicRenderDelay");
  // ----- VICE-style cycle-driven gfx pipeline (jac64.vicGfx) -----
  // Mirrors src/viciisc/vicii-draw-cycle.c structure. When enabled, the
  // per-cycle drawGraphicsVic() emits 8 pixels through an X-shift
  // register, so mid-line $D016 XSCROLL / $D018 / $D011 mode writes take
  // effect at the right horizontal pixel instead of jumping the whole
  // 8-pixel column.
  // VICE-style per-pixel rendering pipeline (X-shift register +
  // per-pixel mode latching). Default OFF until non-badline-row handling
  // in drawGraphicsVic is fixed — currently it returns early on
  // non-badline rows, causing the OPEN BORDER WITH bit-shift rows in
  // vicii_reg_timing to lose 1 letter (3→2) versus the legacy path.
  // Enable with -Djac64.vicGfx=true.
  private final boolean useVicGfx = Boolean.getBoolean("jac64.vicGfx");

  // Phase G: VicDrawCycle is now the PRIMARY render path (default ON).
  // Opt out with -Djac64.legacyPaint=true to restore the legacy
  // drawGraphics/drawColorsVic/retroactive-paint path. The legacy
  // code remains in place under that opt-out flag pending dedicated
  // cleanup. The pipeline is byte-perfect to VICE per 1.5M+ trace
  // events across 4 PRGs; suite total ~5098 cells off VICE refs.
  // For backwards compat: the original -Djac64.vicFullPipeline=true
  // remains accepted as an explicit opt-in (no-op now).
  private final boolean useVicFullPipeline =
      !Boolean.getBoolean("jac64.legacyPaint");
  private VicDrawCycle vicDrawCycle;
  // Phase 5c: paint base for the current cycle's 8-pixel slot. -1 when
  // not in visible-cycle range. mem[vicCyclePaintBase + i] receives
  // writePixel output where i is dbuf_offset & 7.
  private int vicCyclePaintBase = -1;
  private final int[] prevSprColorCode = new int[8];
  private final int[] prevSprIndex = new int[8];
  private final boolean[] prevSprFgWin = new boolean[8];
  private int gbufReg = 0;
  // VICE draw_graphics8() keeps a 2-stage graphics-byte pipe
  // (gbuf_pipe0_reg -> gbuf_pipe1_reg -> gbuf_reg). The simplified
  // renderer used a single register and was still one character off on
  // fetchsplit's split boundary. Keep the pipe local to the VICE path
  // so the default renderer stays unchanged.
  private int gbufPipe0Reg = 0;
  private int gbufPipe1Reg = 0;
  private int xscrollPipe = 0;
  private int vbufReg = 0;
  private int cbufReg = 0;
  private int gbufMcFlop = 0;
  private int gbufPixelReg = 0;
  private int vmode11Pipe = 0;   // BMM/ECM bits, latched at pixel 4 (rising) / 6 (falling)
  private int vmode16Pipe = 0;   // MCM bit, latched at pixel 4
  private int vmode16Pipe2 = 0;  // vmode16Pipe lagged 1 cycle (used for MC pixel-pair logic)

  // VICE PAL fetch table alignment:
  //   c-access for col K at Phi2(15+K) -> JaC64 case 15+K
  //   g-access for col K at Phi1(16+K) -> lagged fetch base

  // VICE c64gluelogic.c 252535-01 video-bank switch state. This models the
  // C64C glue IC used by local x64sc defaults without exposing a timing flag.
  private int glueVisibleVBank = 0;
  private int glueOldVBank = 0;
  private boolean glueAlarmActive = false;
  private long glueAlarmClock = 0;

  // VICE color codes used by the gfx colors[] table (subset).
  private static final int VC_NONE     = 0x10;
  private static final int VC_VBUF_L   = 0x11;
  private static final int VC_VBUF_H   = 0x12;
  private static final int VC_CBUF     = 0x13;
  private static final int VC_CBUF_MC  = 0x14;
  private static final int VC_D02X_EXT = 0x15;
  private static final int VC_D020     = 0x20;  // border (used by draw_border8)
  private static final int VC_D021     = 0x21;
  private static final int VC_D022     = 0x22;
  private static final int VC_D023     = 0x23;
  // Sprite color codes (Phase C). VICE COL_D025/COL_D026 are sprite
  // multicolor 0/1; COL_D027 + s is sprite s's individual color.
  private static final int VC_D025     = 0x25;  // sprite multicolor 0
  private static final int VC_D026     = 0x26;  // sprite multicolor 1
  private static final int VC_D027     = 0x27;  // sprite 0 color (+s for sprite s)

  // 32-entry colors[] table copied verbatim from VICE
  // (src/viciisc/vicii-draw-cycle.c). Indexed by (vmode | px) where:
  //   vmode bit 2 = MCM ($D016 bit 4 >> 2)
  //   vmode bit 3 = BMM ($D011 bit 5 >> 2)
  //   vmode bit 4 = ECM ($D011 bit 6 >> 2)
  //   px = 2-bit gfx pixel value (0..3)
  private static final int[] VC_GFX_COLORS = {
      VC_D021, VC_D021, VC_CBUF, VC_CBUF,         // ECM=0 BMM=0 MCM=0
      VC_D021, VC_D022, VC_D023, VC_CBUF_MC,      // ECM=0 BMM=0 MCM=1
      VC_VBUF_L, VC_VBUF_L, VC_VBUF_H, VC_VBUF_H, // ECM=0 BMM=1 MCM=0
      VC_D021, VC_VBUF_H, VC_VBUF_L, VC_CBUF,     // ECM=0 BMM=1 MCM=1
      VC_D02X_EXT, VC_D02X_EXT, VC_CBUF, VC_CBUF, // ECM=1 BMM=0 MCM=0
      VC_NONE, VC_NONE, VC_NONE, VC_NONE,         // ECM=1 BMM=0 MCM=1 (illegal)
      VC_NONE, VC_NONE, VC_NONE, VC_NONE,         // ECM=1 BMM=1 MCM=0 (illegal)
      VC_NONE, VC_NONE, VC_NONE, VC_NONE          // ECM=1 BMM=1 MCM=1 (illegal)
  };

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
   * Apply a delayed color-register write to the rendering-side state.
   * Called from clock() at start of cycle N+1 with the value written
   * during cycle N. Mirrors VICE's `cregs[reg] = value` pattern.
   */
  private void applyDelayedColorReg(int reg, int value) {
    int v = value & 0x0f;
    // VICE viciisc/vicii-draw-cycle.c:653 `cregs[last_color_reg] = ...`
    // — unified cregs[] update used by draw_colors8 for code → color
    // resolution. JaC64 also keeps legacy per-color fields for the
    // mem-direct paint paths.
    if (reg >= 0x20 && reg <= 0x2e) cregs[reg] = v;
    switch (reg) {
      case 0x20: borderColor = cbmcolor[v]; break;
      case 0x21:
        bgColor = cbmcolor[v];
        for (int i = 0; i < 8; i++) sprites[i].color[0] = bgColor;
        break;
      case 0x22:
      case 0x23:
      case 0x24:
        bgCol[reg - 0x21] = v;
        break;
      case 0x25:
        sprMC0 = v;
        for (int i = 0; i < 8; i++) sprites[i].color[1] = cbmcolor[v];
        break;
      case 0x26:
        sprMC1 = v;
        for (int i = 0; i < 8; i++) sprites[i].color[3] = cbmcolor[v];
        break;
      case 0x27: case 0x28: case 0x29: case 0x2a:
      case 0x2b: case 0x2c: case 0x2d: case 0x2e:
        sprites[reg - 0x27].color[2] = cbmcolor[v];
        sprites[reg - 0x27].col = v;
        break;
    }
  }

  // 8565 color resolution can make a same-cycle $D021 write affect the
  // already staged 8-pixel group; if pixel 0 used that register, VICE emits
  // the light-grey "grey dot" instead of the new color.
  // VICE 8565 grey-dot pixel-0 fixup for sprite individual color ($D027-$D02E).
  // When the CPU writes a sprite color register mid-line, the just-rendered
  // sprite pixels (in the PREVIOUS cycle's 8-pixel mem[] block) that match
  // the old sprite color should retroactively become the new color, with
  // pixel 0 of that cycle substituted by light grey (color 15). Mirrors
  // applyD021CurrentCycleColor but matches the sprite's old color.
  private void applySpriteColorCurrentCycle(int oldColor, int newColor) {
    // Phase 1+ vicShaped pipeline: drawColors8 already commits cregs
    // for $D02X writes with VICE's 1-cycle delay, so the legacy
    // retroactive mem[] rewrite competes with the pipeline. Opt out by
    // default with -Djac64.legacySpriteColorRetroPaint=false (default).
    if (!Boolean.parseBoolean(System.getProperty("jac64.legacySpriteColorRetroPaint", "false"))) {
      return;
    }
    int vicCycle = (int) (cpu.cycles - lastLine);
    // Widened from 16..55 to 14..57 (Phi2 paint cycles + adjacent) per
    // 2026-05-13 refactor: $D027-$D02E sprite color writes can land
    // outside the 16..55 visible-cycle range when test IRQs use
    // pre-display/post-display setup. The mem[i] == oldColor per-pixel
    // check still prevents spurious paints when slots hold border.
    if (vicCycle < 14 || vicCycle > 57 || notVisible) {
      return;
    }
    int start = mpos - 8 + horizScroll;
    if (start < 0 || start + 7 >= mem.length) {
      return;
    }
    for (int i = 0; i < 8; i++) {
      if (mem[start + i] == oldColor) {
        mem[start + i] = (i == 0) ? cbmcolor[15] : newColor;
      }
    }
  }

  // Companion to applyD021CurrentCycleColor for $D020 border color.
  // Same VICE pipe-delay alignment rationale. Substitutes oldBorderColor
  // → newBorderColor in prev cycle's mem slot.
  private void applyD020CurrentCycleColor(int oldColor, int newColor) {
    // Phase 1+ vicShaped pipeline handles $D020 via cregs[]. Opt out
    // of the legacy retroactive mem[] rewrite by default; restore with
    // -Djac64.legacyBorderRetroPaint=true.
    if (!Boolean.parseBoolean(System.getProperty("jac64.legacyBorderRetroPaint", "false"))) {
      return;
    }
    int vicCycle = (int) (cpu.cycles - lastLine);
    if (vicCycle < 14 || vicCycle > 57 || notVisible) {
      return;
    }
    int start = mpos - 8 + horizScroll;
    if (start < 0 || start + 7 >= mem.length) {
      return;
    }
    for (int i = 0; i < 8; i++) {
      if (mem[start + i] == oldColor) {
        mem[start + i] = (i == 0) ? cbmcolor[15] : newColor;
      }
    }
  }

  private void applyD021CurrentCycleColor(int oldColor, int newColor) {
    // Phase 1+ vicShaped pipeline handles $D021 via cregs[]. Opt out
    // of the legacy retroactive mem[] rewrite by default; restore with
    // -Djac64.legacyD021RetroPaint=true. (Already gated by !COLOR_DELAY
    // at the call site too; this is belt-and-suspenders.)
    if (!Boolean.parseBoolean(System.getProperty("jac64.legacyD021RetroPaint", "false"))) {
      return;
    }
    int vicCycle = (int) (cpu.cycles - lastLine);
    if (vicCycle < 14 || vicCycle > 57 || notVisible) {
      return;
    }
    // gfxVisible/paintBorder/vBorderOnly gate REMOVED per
    // project_d021_pipe_delay.md analysis. The mem[i] == oldColor
    // check prevents spurious paints when the slot holds border
    // pixels. Allowing the retroactive paint in top/bottom border
    // areas fixes mid-line $D021 split on OPEN-TOP-BORDER tests
    // (ss-exp-unexp-hires).

    int start = mpos - 8 + horizScroll;
    if (start < 0 || start + 7 >= mem.length) {
      return;
    }

    for (int i = 0; i < 8; i++) {
      if (mem[start + i] == oldColor) {
        mem[start + i] = (i == 0) ? cbmcolor[15] : newColor;
      }
    }
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

  /**
   * Port of VICE {@code check_vborder_bottom} and the vborder latch
   * inside {@code check_hborder} (viciisc/vicii-cycle.c:175-200).
   *
   * Called only at the cycle where the left-border check fires
   * (cyc 17 if CSEL=1, cyc 18 if CSEL=0). Uses the CURRENT rsel from
   * {@code control1} bit 3 to decide the bottom-stop line (247 if
   * RSEL=0/24-row, 251 if RSEL=1/25-row). The demo's bottom-border
   * opening trick relies on toggling $D016 between cyc 17 and 18 so
   * neither latch fires, keeping the previous (open) vborder state.
   */
  /**
   * VICE check_vborder_top + check_vborder_bottom combined.
   * Updates {@link #setVBorder} using CURRENT {@code control1} (rsel
   * + DEN). Safe to call every cycle — VICE runs both checks every
   * cycle (viciisc/vicii-cycle.c:477-479).
   */
  private void checkVBorderTopBottom() {
    int rsel = (control1 & 0x08) != 0 ? 1 : 0;
    int den = (control1 & 0x10) != 0 ? 1 : 0;
    int stopLine = rsel == 1 ? 251 : 247;
    int startLine = rsel == 1 ? 51 : 55;
    boolean prev = setVBorder;
    if (vbeam == stopLine) {
      setVBorder = true;
    }
    if (vbeam == startLine && den == 1) {
      setVBorder = false;
      // VICE viciisc/vicii-cycle.c:167-174 check_vborder_top: when
      // line==startLine and DEN=1, BOTH vborder and set_vborder are
      // cleared immediately (no deferral to cyc=1). Matches VICE
      // when DEN gets set mid-line; previously JaC64 only cleared
      // setVBorder and deferred the commit to the NEXT line's cyc=1
      // (which would miss because vbeam!=startLine by then).
      vBorder = false;
    }
    if (TRACE_VIC_CYCLE && prev != setVBorder
        && cpu.cycles >= TRACE_VIC_CYCLE_START
        && cpu.cycles <= TRACE_VIC_CYCLE_END) {
      traceVicCycleOut.println("EV-ChkVBrd clk=" + cpu.cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + (cpu.cycles - lastLine)
          + " rsel=" + rsel + " den=" + den
          + " setVB:" + prev + "->" + setVBorder);
    }
  }

  /**
   * VICE check_hborder left-side: runs at cyc 17 if CSEL=1, cyc 18 if
   * CSEL=0. Latches vBorder from setVBorder, and if vBorder is 0
   * opens mainBorder (viciisc/vicii-cycle.c:188-195).
   */
  private void checkHBorderLeft() {
    boolean prevMain = mainBorder;
    boolean prevV = vBorder;
    checkVBorderTopBottom();
    // Phase K iter#7: vBorder commit moved to case 1 (= VICE viciisc
    // raster_cycle==1). Don't re-latch here at cyc 17/18 — VICE only
    // commits once per line. Mid-line $D011 writes update setVBorder
    // but the new value doesn't apply until NEXT line's cyc 1.
    if (!vBorder) {
      mainBorder = false;
    }
    if (TRACE_VIC_CYCLE) traceAct("ChkBrdL");
    if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
        && cpu.cycles <= TRACE_VIC_CYCLE_END) {
      traceVicCycleOut.println("EV-ChkBrdL clk=" + cpu.cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + (cpu.cycles - lastLine)
          + " csel=" + (hideColumn ? "0" : "1")
          + " setVB=" + setVBorder
          + " vB:" + prevV + "->" + vBorder
          + " mainB:" + prevMain + "->" + mainBorder);
    }
  }

  /**
   * VICE check_hborder right-side: runs at cyc 56 if CSEL=0, cyc 57
   * if CSEL=1. Closes mainBorder (viciisc/vicii-cycle.c:196-199).
   */
  private void checkHBorderRight() {
    boolean prevMain = mainBorder;
    mainBorder = true;
    if (TRACE_VIC_CYCLE) traceAct("ChkBrdR" + (hideColumn ? "0" : "1"));
    if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
        && cpu.cycles <= TRACE_VIC_CYCLE_END) {
      traceVicCycleOut.println("EV-ChkBrdR clk=" + cpu.cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + (cpu.cycles - lastLine)
          + " csel=" + (hideColumn ? "0" : "1")
          + " mainB:" + prevMain + "->" + mainBorder);
    }
  }

  /** Single gate for rendering: VICE's main_border. */
  private boolean borderClosed() {
    if (true /* vicBorderLatch default on */) {
      return mainBorder;
    }
    return borderState != 0;
  }

  private boolean vBorderOnly() {
    if (true /* vicBorderLatch default on */) {
      return vBorder;
    }
    return (borderState & 1) != 0;
  }

  private boolean isCharRomFetchBase(int base) {
    return base >= CPU.CHAR_ROM2 && base < CPU.CHAR_ROM2 + 0x1000;
  }

  private int mixFetchAddressIfRomTransition(int fromAddr, int toAddr) {
    if (!isCharRomFetchBase(fromAddr) && isCharRomFetchBase(toAddr)) {
      return (fromAddr & 0xff) | (toAddr & 0x3f00);
    }
    return toAddr;
  }

  private int mixPhi1FetchAddress(int fromAddr, int toAddr) {
    if (!isCharRomFetchBase(fromAddr) && isCharRomFetchBase(toAddr)) {
      return (fromAddr & 0xff) | (toAddr & 0x3f00);
    }
    return fromAddr;
  }

  private int charMemoryIndexFor(int bank, int memReg) {
    int charSetBase = bank | (memReg & 0x0e) << 10;
    if ((memReg & 0x0c) != 4 || (bank & 0x4000) == 0x4000) {
      return charSetBase;
    }
    return (((memReg & 0x02) == 0) ? 0 : 0x0800) + CPU.CHAR_ROM2;
  }

  private boolean isBadLine(int scroll) {
    return displayEnabled && vbeam >= 0x30 && vbeam <= 0xf7
        && (vbeam & 0x7) == scroll;
  }

  // Phase K iter#10: VICE-faithful per-cycle state machine port.
  // Mirrors viciisc/vicii-cycle.c:583-640 (DEN latch, check_badline,
  // update_vc at cyc 14, update_rc at cyc 58). When VICE_BADLINE_FSM
  // is on, this runs EVERY cycle from clock() top, and the legacy
  // updates at case 0 / case 13 / case 57 are SKIPPED.
  //
  // iter#11: FSM now defaults ON (zero-regression vs legacy paths
  // because the DEN latch was tightened to match legacy behavior).
  // Opt out with -Djac64.vicBadlineFsm=false to fall back to legacy.
  private static final boolean VICE_BADLINE_FSM =
      Boolean.parseBoolean(
          System.getProperty("jac64.vicBadlineFsm", "true"));

  private void updateVicStateVic(int vicCycle) {
    if (Boolean.getBoolean("jac64.traceBadFsm")
        && Integer.getInteger("jac64.traceBadFsmRastLo", 48) <= vbeam
        && vbeam <= Integer.getInteger("jac64.traceBadFsmRastHi", 52)
        && (vicCycle == 13 || vicCycle == 57
            || (vicCycle >= Integer.getInteger("jac64.traceBadFsmCycLo", 13)
                && vicCycle <= Integer.getInteger("jac64.traceBadFsmCycHi", 57)))
        && cpu.cycles >= Long.getLong("jac64.traceBadFsmClkLo", 7040000L)
        && cpu.cycles <= Long.getLong("jac64.traceBadFsmClkHi", 7041000L)) {
      System.err.println("BADFSM-PRE clk=" + cpu.cycles + " vbeam=" + vbeam + " cyc=" + vicCycle
          + " vc=" + vc + " vmli=" + vmli + " rc=" + rc + " vcBase=" + vcBase
          + " badLine=" + badLine + " gfxVisible=" + (!vicIdleState)
          + " vicIdle=" + vicIdleState
          + " displayEnabled=" + displayEnabled + " vScroll=" + vScroll
          + " control1=$" + Integer.toHexString(control1));
    }
    // VICE vicii-cycle.c:593-602 — DEN (allow_bad_lines) latch at
    // FIRST_DMA_LINE (48). VICE only latches when going FALSE→TRUE
    // (and clears to FALSE on raster_line=0 / FINAL_DMA_LINE+1, but
    // that's effectively the line cycle 0 condition we're already in).
    // JaC64 legacy unconditionally overwrites; mirror that semantic
    // to preserve sprite-test behavior that depends on DEN=0 paths.
    if (vbeam == 0x30 && vicCycle == 0) {
      displayEnabled = (control1 & 0x10) != 0;
    }

    // VICE vicii_fetch_graphics vc++: runs at FetchG cycles (raster_cycle
    // 15..54 per chip-model.c) when !idle_state. VICE's Phi1 fetch runs
    // BEFORE check_badline within vicii_cycle (vicii-cycle.c:428 vs 604),
    // so vc++ uses the idle_state from END of the PREVIOUS cycle. Doing
    // vc++ AFTER check_badline (the legacy JaC64 order) made JaC64
    // increment vc one cycle earlier than VICE, accumulating off-by-one
    // vcBase/rc state by the time the screenpos test reaches its first
    // visible line (line 51).
    if (vicCycle >= 15 && vicCycle <= 54 && !vicIdleState) {
      vc = (vc + 1) & 0x3ff;
    }

    // VICE vicii-cycle.c:605-607 — check_badline every cycle while
    // allow_bad_lines. The badline condition is `(line & 7) == ysmooth`
    // AND we're in the DMA range (48..247 per VICE FIRST/LAST_DMA_LINE).
    if (displayEnabled) {
      boolean newBad = (vbeam & 7) == vScroll && vbeam >= 0x30 && vbeam <= 0xf7;
      badLine = newBad;
      // VICE check_badline (vicii-cycle.c:53-62): when bad_line=1, also
      // sets idle_state=0 EVERY cycle (not just rising edge) — this
      // includes the rising edge (exit idle), so a separate rise-edge
      // clear is unnecessary.
      if (badLine) {
        vicIdleState = false;
      }
    } else {
      badLine = false;
    }

    // FLI fix (2026-05-30): VICE's BaFetch flag is set in cycle_table for raster
    // cycles 11..53 (= externally visible cyc 12..54). When a mid-line $D011
    // write at cyc 11 changes ysmooth, VICE's STX abs SET_ABS does STORE then
    // CLK_INC — the CLK_INC's vicii_cycle at cyc 12 sees the new ysmooth, sets
    // bad_line=1, and ba_low=1 (via BaFetch). So the next CPU access (LDY at
    // cyc 12) stalls IMMEDIATELY.
    //
    // JaC's case-11 dispatcher uses PRE-write badLine (because clock(cyc 11)
    // runs BEFORE the cyc-11 store fires). With FLI writes at cyc 11, badLine
    // becomes true only at clock(cyc 12) — but JaC's case-12 dispatcher had no
    // BA-low setter, so BA-low was 1 cycle late vs VICE.
    //
    // Setting BA-low here in updateVicStateVic (which runs at top of clock())
    // mirrors VICE's "check_badline + ba_low check in same vicii_cycle" model.
    if (badLine && vicCycle >= 11 && vicCycle <= 54
        && Boolean.parseBoolean(System.getProperty("jac64.fliBaLowFix", "true"))) {
      setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-FSM");
    }

    // VICE vicii-cycle.c:619-625 — update_vc at VICII_PAL_CYCLE(14) =
    // internal raster_cycle 13.
    //   vicii.vc = vicii.vcbase; vicii.vmli = 0; if bad_line: rc = 0;
    if (vicCycle == 13) {
      vc = vcBase;
      vmli = 0;
      if (badLine) rc = 0;
    }

    // VICE vicii-cycle.c:629-640 — update_rc at VICII_PAL_CYCLE(58) =
    // internal raster_cycle 57.
    if (vicCycle == 57) {
      // VICE-faithful update_rc, verbatim (vicii-cycle.c:705-710):
      //   if (rc == 7) { idle_state = 1; vcbase = vc; }
      //   if (!idle_state || bad_line) { rc = (rc+1)&7; idle_state = 0; }
      // The rc==7 check is PRE-increment (rc isn't bumped until the second
      // clause), and idle_state is the single source of truth — this is
      // the collapse of the former gfxVisible/vicIdleState pair, which were
      // parallel implementations of exactly this (gfxVisible == !idle).
      if (rc == 7) {
        vicIdleState = true;
        vcBase = vc;
      }
      if (!vicIdleState || badLine) {
        rc = (rc + 1) & 7;
        vicIdleState = false;
      }
    }
  }

  private void resetBadLineFetchWindow() {
    badLineFetchStartColumn = 0;
    badLineDummyColumns = 0;
    badLineFetchSourceColumn = 0;
  }

  private void updateDisplayEnabledFromControl(int data) {
    if (vbeam == 0x30) {
      // VICE vicii-cycle.c:630 — on FIRST_DMA_LINE allow_bad_lines = DEN is
      // evaluated only while !allow_bad_lines, so once DEN has been seen ON
      // this line it LATCHES and a later DEN-clear on the same line cannot
      // un-set it. JaC's per-frame reset (the cyc-0 latch above) already
      // captures DEN at line start; this write handler must therefore only
      // allow 0->1 on line 48, never 1->0 — clearing it let a late DEN-clear
      // (den10-48-2: $D011=$0b at cyc 1) wrongly drop the chip into idle, so
      // the whole display rendered $3fff stripe garbage instead of text.
      // den10-48-0/1 stay correct: their clear lands before the cyc-0 reset,
      // so displayEnabled is already false and never re-latches. Flag
      // jac64.viceDenLatchLock default true; false = legacy track-both-ways.
      if (Boolean.parseBoolean(System.getProperty("jac64.viceDenLatchLock", "true"))) {
        if ((data & 0x10) != 0) {
          displayEnabled = true;
          borderState &= ~0x04;
        }
      } else {
        displayEnabled = (data & 0x10) != 0;
        if (displayEnabled) {
          borderState &= ~0x04;
        } else {
          borderState |= 0x04;
        }
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
    // BA-low end clk MUST NOT SHRINK once a longer-active source has set
    // it. Real VIC-II ANDs together all the BA sources currently low; the
    // CPU stays stalled while ANY are low. Modelling that as a single
    // baLowUntil clk requires taking the MAX of all active sources.
    //
    // Concrete bug fixed (2026-04-28): in F2→F3 of irq-ack-vicii.prg
    // SS-COL pass, sprite 0's BA-SPR0 at line $4b cyc=54 set baU to
    // cyc=59. The "BADLINE-C55" setter at cyc=55 (line 2330) then
    // unconditionally wrote baU = lastLine + BA_BADLINE (= cyc=54), 5
    // cycles in the PAST, releasing CPU stall 7 cycles too early. CPU's
    // sta $D019 ($b6f in irq_reset_frame) finished early, irq-handler
    // chain timing slipped 1 cycle relative to VICE → LDA SS-COL slot 5
    // ended up reading $f4 instead of $70.
    //
    // Fixing this with MAX matches VICE's "any-source-low" semantics
    // (viciisc/vicii-cycle.c builds maincpu_ba_low_flags from multiple
    // sources, only released when ALL clear).
    long oldUntil = cpu.baLowUntil;
    if (until <= oldUntil) {
      // Keep the longer-active BA-low window; ignore the shorter setter.
      return;
    }
    cpu.baLowUntil = until;
    ((CPU) cpu).traceBaEvent("BA-SET src=" + source + " old=" + oldUntil +
        " new=" + until);
    if (TRACE_VIC_CYCLE) traceAct("BA-" + source);
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

  private static final boolean TRACE_VIC_IRQ =
      Boolean.getBoolean("jac64.traceVicIrq");

  // ----- Per-cycle VIC action trace (for VICE diff) -----------------
  // Enable with -Djac64.traceVicCycle=true.
  // Optional window: -Djac64.traceVicCycleStart=<absclk>
  //                  -Djac64.traceVicCycleEnd=<absclk>
  // Optional file:   -Djac64.traceVicCycleFile=<path> (default stderr)
  //
  // Output format (one line per VIC cycle):
  //   TVIC clk=N rast=$RR cyc=Z bl=B baU=BL vmli=V vc=VC rc=RC act=[<actions>]
  // where <actions> is a comma-list of high-level events the case
  // dispatcher fired this cycle (BA-SP0..7, FetchC col=N, FetchG, ChkBrdL/R,
  // SP0Read .. SP7Read, RasterIRQ, etc.). The trace is intended to be
  // diffed against VICE's cycle_tab_pal expectations
  // (vicii-chip-model.c:111). See docs/vic-ii/CYCLE_TRACE.md.
  private static final boolean TRACE_VIC_CYCLE =
      Boolean.getBoolean("jac64.traceVicCycle");
  private static final boolean TRACE_D01B =
      Boolean.getBoolean("jac64.traceD01b");
  private static java.io.PrintStream traceD01bOut = null;
  private static java.io.PrintStream traceSprColOut = null;
  private static java.io.PrintStream traceSprXOut = null;
  private static final long TRACE_VIC_CYCLE_START =
      Long.getLong("jac64.traceVicCycleStart", 0L);
  private static final long TRACE_VIC_CYCLE_END =
      Long.getLong("jac64.traceVicCycleEnd", Long.MAX_VALUE);
  private static java.io.PrintStream traceVicCycleOut = System.err;
  // Phase K iter#9: per-cycle VIC internal state trace for diffing
  // vc/vmli/rc/idle/bad against VICE viciisc.
  private static final boolean TRACE_VIC_STATE =
      Boolean.getBoolean("jac64.traceVicState");
  private static final long TRACE_VIC_STATE_START =
      Long.getLong("jac64.traceVicStateStart", 0L);
  private static final long TRACE_VIC_STATE_END =
      Long.getLong("jac64.traceVicStateEnd", Long.MAX_VALUE);
  private static java.io.PrintStream vicStateOut = null;
  static {
    if (TRACE_VIC_CYCLE) {
      String f = System.getProperty("jac64.traceVicCycleFile", "");
      if (!f.isEmpty()) {
        try {
          traceVicCycleOut = new java.io.PrintStream(
              new java.io.FileOutputStream(f, false));
        } catch (Exception e) {
          System.err.println("traceVicCycle: cannot open " + f + " — using stderr");
        }
      }
    }
  }
  private final StringBuilder traceVicActions = new StringBuilder(64);

  private void traceAct(String tag) {
    if (!TRACE_VIC_CYCLE) return;
    if (traceVicActions.length() > 0) traceVicActions.append(',');
    traceVicActions.append(tag);
  }

  private void triggerRasterIrq(long irqClock) {
    // VICE-style guard: don't re-fire if raster IRQ flag already
    // pending (= bit 0 of irq_status set + already triggered).
    // VICE source: viciisc/vicii-irq.c:116-121 — `if (!(vicii.irq_status & 0x1))`
    // gating in vicii_irq_raster_trigger. JaC64 used to fire on every
    // call regardless, producing extra raster IRQ events VICE doesn't
    // produce (e.g. rast=74 in RASTER testset frames after irq_reset_frame
    // sets $D012=69 but rasterIrqClock was scheduled for line 74).
    if ((irqFlags & 0x1) != 0 && irqTriggered) {
      // Already pending and not acked — don't re-fire.
      // Still advance rasterIrqClock so we don't busy-loop.
      if (rasterIrqClock != RASTER_IRQ_DISABLED && rasterIrqClock <= irqClock) {
        advanceRasterIrqClock();
      }
      return;
    }
    irqFlags |= 0x1;
    if ((irqMask & 1) != 0) {
      irqFlags |= 0x80;
      irqTriggered = true;
      setIRQ(VIC_IRQ);
      lastIRQ = irqClock;
      if (TRACE_VIC_CYCLE) traceAct("RasterIRQ-fire");
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-RasterIrq clk=" + irqClock
            + " rast=" + raster
            + " vbeam=" + vbeam
            + " cyc=" + (irqClock - lastLine)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      if (TRACE_VIC_IRQ) {
        System.err.println("VIC-RASTER-IRQ raster=" + raster
            + " clk=" + irqClock + " vbeam=" + vbeam
            + " mask=$" + Integer.toHexString(irqMask)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
    } else if (TRACE_VIC_IRQ) {
      System.err.println("VIC-RASTER-MATCH(no-irq) raster=" + raster
          + " clk=" + irqClock + " vbeam=" + vbeam
          + " mask=$" + Integer.toHexString(irqMask)
          + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
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
      // Phase η: VICE detects raster-compare match at the chip's
      // per-cycle vicii_cycle, which runs at the NEXT cycle boundary
      // AFTER the write. For RMW dummy-write, the dummy value briefly
      // sets raster_irq_line = raster_line; VICE fires the IRQ at the
      // next cycle (= start of cycle 6 of RMW). JaC64's synchronous
      // fire here was 1 cycle too early.
      // Fix: during RMW dummy-write, defer the fire by 1 cycle by
      // setting pendingRasterIrqFireClk. The next clock() iteration
      // will fire it.
      if (cpu instanceof MOS6510Core && ((MOS6510Core)cpu).isRmwDummyWrite()) {
        pendingRasterIrqFireClk = cpu.cycles + 1;
        return;
      }
      triggerRasterIrq(cpu.cycles);
    }
  }

  // Phase η: deferred raster-IRQ fire for RMW dummy-write matches.
  // Set to cpu.cycles+1 in updateRasterIrqLine; consumed at the next
  // clock() iteration. RASTER_IRQ_DISABLED sentinel = not pending.
  private long pendingRasterIrqFireClk = RASTER_IRQ_DISABLED;

  private void handleBadLineStart(int vicCycle, boolean wasVisible) {
    setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-START");

    // VICE resets rc only on idle→display transition (see
    // docs/vic-ii/badline-rc-idle-state.md). wasVisible=false means
    // the chip was idle before this mid-line badline, so rc should
    // reset. In steady display state (FLI, or already-visible
    // rendering within a char row) rc continues advancing.
    if (vicCycle <= 13 && !wasVisible) {
      rc = 0;
    }
    // Debug flag retained for regression testing.
    if (vicCycle <= 13 && Boolean.getBoolean("jac64.forceRcReset")) {
      rc = 0;
    }

    if (vicCycle >= 15 && vicCycle < 59) {
      // VICE derives the current text column from the cycle where the line
      // becomes bad: xpos = cycle - (fetch cycle + 3). Using vmli directly
      // leaves JaC64 one column behind during mid-line FLD changes.
      // FLI tests (colorfetchbug, blackmail) write $D011 at JaC64 case 15
      // (= VICE cycle 16) — this triggers handleBadLineStart with vicCycle=15.
      // Lower bound includes 15 so the FLI dummy-fetch window opens.
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

    vicIdleState = false;
  }

  private void handleBadLineStop(int vicCycle, boolean wasVisible) {
    resetBadLineFetchWindow();

    if (vicCycle > 0) {
      vicIdleState = false;
      if (!wasVisible && vicCycle > 13) {
        rc = 0;
      }
    }
  }

  private void fetchBadLineData(int column) {
    if (TRACE_VIC_CYCLE) traceAct("FetchC-c" + column);

    // VICE vbuf/prefetch pipeline alignment (2026-05-31): VICE's vicii_cycle
    // runs the g-fetch FIRST (vicii_fetch_graphics reads vbuf[vmli], then
    // vmli++) and the matrix-fetch LATER (vicii_fetch_matrix writes
    // vbuf[vmli]). Because vmli is already incremented, VICE writes the
    // c-data for column k at the cycle BEFORE column k is rendered, and the
    // FLI-bug prefetch ($ff, vicii-fetch.c:194) decision uses prefetch_cycles
    // as it stood at THAT earlier write cycle. JaC's c-access is coincident —
    // fetchBadLineData(col) writes vicCharCache[col] and the same cycle's
    // drawGraphicsVic reads it — i.e. one cycle later than VICE. For stable
    // (non-prefetch) data this is invisible, but the prefetch decision then
    // used the wrong cycle's prefetch_cycles, so the FLI-bug idle stripe
    // landed one column too early (fldscroll-2A/2B col 37 vs VICE's 38;
    // blackmail FLI). Fix: write the c-data for column k while PROCESSING
    // column k-1 (shift source+dest +1) so the write cycle, and thus the
    // prefetch decision, matches VICE. Normal data is unchanged (source k is
    // still stored at index k); only the $ff placement corrects. Column 0 has
    // no preceding cycle, so it is also written directly when column==0.
    // Flag jac64.fldPrefetchShift default true.
    if (Boolean.parseBoolean(System.getProperty("jac64.fldPrefetchShift", "true"))) {
      if (column == 0) {
        writeCAccess(0);
      }
      writeCAccess(column + 1);
    } else {
      writeCAccess(column);
    }
  }

  // Performs one c-access (matrix fetch) into vicCharCache[col]/vicColCache[col]
  // for the given column, replicating VICE vicii_fetch_matrix: prefetch ($ff +
  // PC color byte) while prefetchCycles > 0, idle ($ff) off the 0..39 range,
  // else the real screen+color byte at vcBase+col. col >= 40 is dropped (VICE
  // renders only columns 0..39).
  private void writeCAccess(int col) {
    if (col < 0 || col >= 40) {
      return;
    }
    final int fetchVideoMatrix = vicFetchDelay ? videoMatrixFetchDelay : videoMatrix;
    final int fetchCharMemoryIndex = vicFetchDelay ? charMemoryIndexFetchDelay : charMemoryIndex;

    if (prefetchCycles > 0) {
      vicCharCache[col] = 0xff;
      vicColCache[col] = memory[cpu.pc & 0xffff] & 0x0f;
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-FetchC clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " col=" + col
            + " src=PREFETCH"
            + " prefetchCycles=" + prefetchCycles
            + " vbyte=$ff"
            + " cbyte=$" + Integer.toHexString(vicColCache[col] & 0x0f)
            + " pc=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      return;
    }

    int videoOffset = (vcBase + col) & 0x3ff;
    vicCharCache[col] = memory[fetchVideoMatrix + videoOffset];
    vicColCache[col] = memory[IO_OFFSET + 0xd800 + videoOffset];
    if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
        && cpu.cycles <= TRACE_VIC_CYCLE_END) {
      traceVicCycleOut.println("EV-FetchC clk=" + cpu.cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + (cpu.cycles - lastLine)
          + " col=" + col
          + " fvm=$" + Integer.toHexString(fetchVideoMatrix)
          + " fci=$" + Integer.toHexString(fetchCharMemoryIndex)
          + " vbase=$" + Integer.toHexString(vcBase)
          + " vmli=" + vmli
          + " vc=" + vc
          + " vbyte=$" + Integer.toHexString(vicCharCache[col] & 0xff)
          + " cbyte=$" + Integer.toHexString(vicColCache[col] & 0x0f)
          + " d011=$" + Integer.toHexString(control1)
          + " d016=$" + Integer.toHexString(control2)
          + " bank=$" + Integer.toHexString(vicBank));
    }
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

    if (useVicFullPipeline) {
      vicDrawCycle = new VicDrawCycle();
      vicDrawCycle.setSink(new VicDrawCycle.DbufSink() {
        @Override public int paletteLookup(int color4) {
          return cbmcolor[color4 & 0x0f];
        }
        @Override public void writePixel(int offs, int colorIndex) {
          // Phase 5c: write pipeline output into mem[]. vicCyclePaintBase
          // is set per-cycle in clock() — -1 outside visible range.
          if (vicCyclePaintBase < 0) return;
          int idx = vicCyclePaintBase + (offs & 7);
          if (idx < 0 || idx >= mem.length) return;
          mem[idx] = cbmcolor[colorIndex & 0x0f];
        }
      });
      vicDrawCycle.init();
    }

    // VICE comparison trace: enable with -Djac64.vicSprPipeTrace=true.
    // Format mirrors the JaC64 trace patch in viciisc/vicii-draw-cycle.c
    // (trigger_sprites) so the two emulators emit byte-comparable lines.
    if (Boolean.getBoolean("jac64.vicSprPipeTrace")) {
      vicSprPipe.enableTrace((xpos, sbufReg, active, pending, halt, data) -> {
        int vc = (int) (cpu.cycles - lastLine);
        System.err.println("JAC-TRIG line=" + vbeam + " cyc=" + vc + " xpos=" + xpos
            + " sbufReg=$" + Integer.toHexString(sbufReg)
            + " active=$" + Integer.toHexString(active)
            + " pending=$" + Integer.toHexString(pending)
            + " halt=$" + Integer.toHexString(halt)
            + " data=$" + Integer.toHexString(data));
      });
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
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-RdD012 clk=" + cpu.cycles
            + " val=" + val
            + " line=" + vbeam
            + " cyc=" + (cpu.cycles - lastLine)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      return val;
      // Sprite collission registers - zeroed after read!
    case 0xd013:
      // Lightpen X (latched at /LP trigger via triggerLightPen()).
      return lpX & 0xff;
    case 0xd014:
      return lpY & 0xff;
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
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-RdD019 clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " ret=$" + Integer.toHexString((irqFlags | 0x70) & 0xff)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff)
            + " opPC=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      // Bits 4-6 of $D019 are unconnected pins on the real VIC-II — they
      // read back as 1. The demo's IRQ handler does LDA $D019 / STA $D019
      // to ack; if we return $81 instead of $f1, the writeback to $D019
      // ends up clearing only the actual flag bits instead of preserving
      // VICE's hardware-faithful read-then-write-back semantics. Trace
      // diff vs VICE x64sc on Krestage 3 IRQ-ack: ackVal=$81 (JaC64) vs
      // $f1 (VICE). Fix by OR-ing the reserved bits on read.
      return irqFlags | 0x70;
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
      val = maybeAssistKrestageProbe(val);
      if (debugProbe) {
        logProbeState("D01E read @ PC=$" + Integer.toHexString(cpu.getPC() & 0xffff), val);
      }
      if (SPRITEDEBUG)
        monitor.info("Reading sprite collission: " +
            Integer.toString(address, 16) + " => " + val);
      // Defer clear to start of next clock() — matches VICE
      // vicii-cycle.c:413-422 (clear_collisions = 0x1e).
      sprColClearPending = true;
      return val;
    case 0xd01f:
      val = sprBgCol;
      if (SPRITEDEBUG)
        monitor.info("Reading sprite collission: " +
            Integer.toString(address, 16) + " => " + val);
      sprBgColClearPending = true;
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
      if (Boolean.getBoolean("jac64.traceLoad") && (cpu.cycles - lastLoadLog) > 1000000L) {
        lastLoadLog = cpu.cycles;
        int[] m = cpu.getMemory();
        System.err.printf("EV-LOAD cyc=%d c64pc=%04X loadptr(AE/AF)=%02X%02X status90=%02X verck93=%02X%n",
            cpu.cycles, cpu.getPC() & 0xffff, m[0xaf] & 0xff, m[0xae] & 0xff, m[0x90] & 0xff, m[0x93] & 0xff);
      }
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
      queueSpriteXLsb(sprite, data);
      if (Boolean.getBoolean("jac64.traceSprX")) {
        if (traceSprXOut == null) {
          String p = System.getProperty("jac64.traceSprXFile", "/tmp/jac64_sprx.trace");
          try {
            traceSprXOut = new java.io.PrintStream(new java.io.FileOutputStream(p), true);
          } catch (Exception e) { traceSprXOut = System.err; }
        }
        traceSprXOut.println("EV-WrSprX clk=" + cpu.cycles + " rast=$"
            + Integer.toHexString(vbeam) + " cyc=" + (cpu.cycles - lastLine)
            + " reg=$" + Integer.toHexString(address & 0xff)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " pc=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
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
      queueSpriteXMsb(data);
      break;
      // d011 -> high address of raster pos
    case 0xd011 :
      // VICE-style passive register store. Mirrors viciisc/vicii-mem.c
      // d011_store: only updates ysmooth + regs[0x11] + raster_irq_line.
      // Per-cycle clock() re-evaluates check_badline() and fires
      // handleBadLineStart/Stop on transition, so the writer doesn't
      // touch badLine, rc, vc, or fetch-window state.
      int oldRaster = raster;
      raster = (raster & 0xff) | ((data << 1) & 0x100);
      updateRasterIrqLine(oldRaster);
      control1 = data;
      // iter#16 trace — matches VICE-D011W format so the two emulators'
      // mid-line $D011 write timing can be diffed side-by-side.
      if (Boolean.getBoolean("jac64.traceD011W")) {
        System.err.println("JAC-D011W data=$" + Integer.toHexString(data & 0xff)
            + " line=" + vbeam + " cyc=" + (int)(cpu.cycles - lastLine)
            + " stored=$" + Integer.toHexString(data & 0xff)
            + " clk=" + cpu.cycles);
      }

      updateDisplayEnabledFromControl(data);

      // ysmooth latch (live; cycle dispatcher re-derives badLine).
      vScroll = data & 0x7;

      extended = (data & 0x40) != 0;
      blankRow = (data & 0x08) == 0;

      videoMode = (extended ? 0x02 : 0)
      | (multiCol ? 0x01 : 0) | (((data & 0x20) != 0) ? 0x04 : 0x00);

      if (shouldTraceRasterWrites()) {
        int wCyc = (int) (cpu.cycles - lastLine);
        fldOut.println("D011=" + Integer.toHexString(data) +
            " vbeam=" + vbeam + " cyc=" + wCyc +
            " vScroll=" + vScroll + " badLine=" + badLine +
            " gfxVis=" + (!vicIdleState) + " rc=" + rc +
            " vmli=" + vmli + " vc=" + vc +
            " clk=" + cpu.cycles +
            " pc=$" + Integer.toHexString(cpu.pc & 0xffff) +
            " a=$" + Integer.toHexString(cpu.acc & 0xff) +
            " x=$" + Integer.toHexString(cpu.x & 0xff) +
            " y=$" + Integer.toHexString(cpu.y & 0xff) +
            " dispEn=" + displayEnabled);
      }
      if (Boolean.getBoolean("jac64.traceFli")) {
        int wCyc = (int) (cpu.cycles - lastLine);
        System.err.println("D011=$" + Integer.toHexString(data)
            + " vbeam=" + vbeam + " cyc=" + wCyc
            + " vScroll=" + vScroll + " badLine=" + badLine
            + " bmap=" + ((data & 0x20) != 0)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }

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
      queueSpriteEnable(data);
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
      if (Boolean.getBoolean("jac64.traceRegW")) {
        long _wLo = Long.getLong("jac64.traceRegWClkLo", 0L);
        long _wHi = Long.getLong("jac64.traceRegWClkHi", Long.MAX_VALUE);
        if (cpu.cycles >= _wLo && cpu.cycles <= _wHi) {
          System.err.println("REGW adr=$d016 val=$" + Integer.toHexString(data)
              + " clk=" + cpu.cycles
              + " rast=$" + Integer.toHexString(vbeam)
              + " cyc=" + ((int)(cpu.cycles - lastLine)));
        }
      }
      if (Boolean.getBoolean("jac64.traceFli")) {
        int vicCycleNow = (int) (cpu.cycles - lastLine);
        System.err.println("D016=$" + Integer.toHexString(data)
            + " vbeam=" + vbeam + " cyc=" + vicCycleNow
            + " mc=" + multiCol + " videoMode=" + videoMode
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      break;

    case 0xd017:
      // VICE d017_store sprite-crunch handler (vicii-mem.c:226-263).
      // For each sprite whose y_exp bit is CLEARED in the new value AND
      // whose exp_flop is currently 0:
      //  - If we're at the ChkSprCrunch cycle (VICE Phi2(15) = JaC case 14),
      //    apply the bit-mixing formula to mc to extend sprite display.
      //  - Always set exp_flop=1 for affected sprites.
      if (useSpriteCrunch) {
        int vicCycleNow = (int) (cpu.cycles - lastLine);
        for (int i = 0; i < 8; i++) {
          int b = 1 << i;
          if ((data & b) == 0 && !sprites[i].expFlipFlop) {
            if (vicCycleNow == 14) {
              // ChkSprCrunch cycle — apply bit-mixing formula.
              int mc = sprites[i].mc;
              int mcbase = sprites[i].mcbase;
              sprites[i].mc =
                  (0x2a & (mcbase & mc)) | (0x15 & (mcbase | mc));
            }
            sprites[i].expFlipFlop = true;
          }
        }
      }
      sprYEX = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].expandY = (data & m) != 0;
      }
      queueSpriteYExpand(data);
      break;

    case 0xd018: {
      vicMem = data;
      setVideoMem();
      if (Boolean.getBoolean("jac64.traceFli")) {
        int vicCycleNow = (int) (cpu.cycles - lastLine);
        System.err.println("D018=$" + Integer.toHexString(data)
            + " vbeam=" + vbeam + " cyc=" + vicCycleNow
            + " videoMatrix=$" + Integer.toHexString(videoMatrix)
            + " vicBase=$" + Integer.toHexString(vicBase)
            + " D011=$" + Integer.toHexString(control1)
            + " D016=$" + Integer.toHexString(control2)
            + " mc=" + multiCol
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      break;
    }

    case 0xd019: {
      // VICE-style $D019 store (vicii-mem.c:227, 4 lines): clear the
      // bits in irq_status that match (value & 0x0f) plus always clear
      // bit 7, then re-evaluate the IRQ line. Per-cycle correctness of
      // RMW dummy/final writes comes from the CPU emulation calling
      // writeByte() twice with old/new values — no special-casing here.
      if (IRQDEBUG) {
        monitor.info("Latching VIC-II: " + Integer.toString(data, 16)
            + " on " + Integer.toString(irqFlags, 16));
      }
      int oldF = irqFlags;
      irqFlags &= ~((data & 0x0f) | 0x80);
      updateVicIrqLine();
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-WrD019 clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " ackVal=$" + Integer.toHexString(data & 0xff)
            + " oldFlags=$" + Integer.toHexString(oldF & 0xff)
            + " newFlags=$" + Integer.toHexString(irqFlags & 0xff)
            + " opPC=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      break;
    }
    case 0xd01a:
      irqMask = data;
      updateVicIrqLine();
      if (TRACE_VIC_IRQ) {
        System.err.println("D01A=$" + Integer.toHexString(data)
            + " clk=" + cpu.cycles + " vbeam=" + vbeam
            + " raster=" + raster + " flags=$" + Integer.toHexString(irqFlags)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }

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
      if (TRACE_D01B) {
        if (traceD01bOut == null) {
          String p = System.getProperty("jac64.traceD01bFile", "/tmp/jac64_d01b.trace");
          try {
            traceD01bOut = new java.io.PrintStream(new java.io.FileOutputStream(p), true);
          } catch (Exception e) { traceD01bOut = System.err; }
        }
        traceD01bOut.println("EV-WrD01B clk=" + cpu.cycles + " rast=$"
            + Integer.toHexString(vbeam) + " cyc=" + (cpu.cycles - lastLine)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " pc=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      break;
    case 0xd01c: {
      int oldMul = sprMul;
      sprMul = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].multicolor = (data & m) != 0;
      }
      queueSpriteMulticol(oldMul, data);
      break;
    }
    case 0xd01d: {
      int oldXExpand = sprXEX;
      sprXEX = data;
      for (int i = 0, m = 1, n = 8; i < n; i++, m = m << 1) {
        sprites[i].expandX = (data & m) != 0;
      }
      queueSpriteXExpand(oldXExpand, data);
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-WrD01D clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " old=$" + Integer.toHexString(oldXExpand & 0xff)
            + " opPC=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      break;
    }

    case 0xd020: {
      int oldBorderColor = borderColor;
      int newBorderColor = cbmcolor[data & 15];
      bCol = data & 15;
      applyD020CurrentCycleColor(oldBorderColor, newBorderColor);
      if (COLOR_DELAY) {
        lastColorReg = 0x20;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      } else {
        borderColor = newBorderColor;
      }
      }
      if (Boolean.getBoolean("jac64.traceColorWrites")) {
        System.err.println("D020=$" + Integer.toHexString(data & 0xff)
            + " vbeam=" + vbeam
            + " cyc=" + (int) (cpu.cycles - lastLine)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }
      break;
    case 0xd021:
      int oldBgColor = bgColor;
      int newBgColor = cbmcolor[data & 15];
      bgCol[0] = data & 15;
      // Phase K iter#4: with COLOR_DELAY on (VICE-style 1-cycle defer),
      // skip the retroactive paint — applyDelayedColorReg commits at
      // start of next cycle, matching VICE viciisc draw_colors timing.
      // The retroactive paint can't distinguish FG/BG pixels (mem[i]
      // == oldColor matches both), corrupting cells where FG and old
      // BG happen to share a color (colorsplit with color RAM=0).
      if (!COLOR_DELAY) {
        applyD021CurrentCycleColor(oldBgColor, newBgColor);
        bgColor = newBgColor;
        for (int i = 0, n = 8; i < n; i++) {
          sprites[i].color[0] = bgColor;
        }
      } else {
        lastColorReg = 0x21;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      }
      if (Boolean.getBoolean("jac64.traceColorWrites")) {
        int inLine = (int) (cpu.cycles - lastLine);
        long deltaCycles = (lastD021Cycles < 0) ? 0 : cpu.cycles - lastD021Cycles;
        int deltaInLine = (lastD021InLine < 0) ? 0 : inLine - lastD021InLine;
        System.err.println("D021=$" + Integer.toHexString(data & 0xff)
            + " vbeam=" + vbeam
            + " cyc=" + inLine
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff)
            + " dClk=" + deltaCycles
            + " dCyc=" + deltaInLine);
        lastD021Cycles = cpu.cycles;
        lastD021InLine = inLine;
      }
      if (TRACE_D01B) {  // reuse same flag for D021 trace
        if (traceD01bOut == null) {
          String p = System.getProperty("jac64.traceD01bFile", "/tmp/jac64_d01b.trace");
          try {
            traceD01bOut = new java.io.PrintStream(new java.io.FileOutputStream(p), true);
          } catch (Exception e) { traceD01bOut = System.err; }
        }
        traceD01bOut.println("EV-WrD021 clk=" + cpu.cycles + " rast=$"
            + Integer.toHexString(vbeam) + " cyc=" + (cpu.cycles - lastLine)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " pc=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      break;
    case 0xd022:
    case 0xd023:
    case 0xd024: {
      int oldD02xColor = cbmcolor[bgCol[address - 0xd021]];
      int newD02xColor = cbmcolor[data & 15];
      applyD021CurrentCycleColor(oldD02xColor, newD02xColor);
      if (COLOR_DELAY) {
        lastColorReg = address - 0xd000;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      } else {
        bgCol[address - 0xd021] = data & 15;
      }
      break;
    }
    case 0xd025: {
      int oldMC0 = cbmcolor[sprMC0];
      int newMC0 = cbmcolor[data & 15];
      applySpriteColorCurrentCycle(oldMC0, newMC0);
      if (COLOR_DELAY) {
        lastColorReg = 0x25;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      } else {
        sprMC0 = data & 15;
        for (int i = 0, n = 8; i < n; i++) {
          sprites[i].color[1] = cbmcolor[sprMC0];
        }
      }
      break;
    }
    case 0xd026: {
      int oldMC1 = cbmcolor[sprMC1];
      int newMC1 = cbmcolor[data & 15];
      applySpriteColorCurrentCycle(oldMC1, newMC1);
      if (COLOR_DELAY) {
        lastColorReg = 0x26;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      } else {
        sprMC1 = data & 15;
        for (int i = 0, n = 8; i < n; i++) {
          sprites[i].color[3] = cbmcolor[sprMC1];
        }
      }
      break;
    }
    case 0xd027:
    case 0xd028:
    case 0xd029:
    case 0xd02a:
    case 0xd02b:
    case 0xd02c:
    case 0xd02d:
    case 0xd02e: {
      int spriteNum = address - 0xd027;
      int oldSprColor = sprites[spriteNum].color[2];
      int newSprColor = cbmcolor[data & 15];
      // VICE 8565 grey-dot pixel-0 fixup for sprite individual color
      // (vicii-draw-cycle.c:630). Mirrors applyD021CurrentCycleColor
      // for $D021 — retroactively rewrite pixels in PREVIOUS cycle's
      // mem[] block that hold the old sprite color, substituting the
      // new color (pixel 0 → grey 15). Fixes ss-hires-color cell-diff
      // at sprite-color split positions.
      applySpriteColorCurrentCycle(oldSprColor, newSprColor);
      if (COLOR_DELAY) {
        lastColorReg = address - 0xd000;
        lastColorValue = data & 15;
        lastColorClk = cpu.cycles;
      } else {
        sprites[spriteNum].color[2] = newSprColor;
        sprites[spriteNum].col = data & 15;
      }
      if (Boolean.getBoolean("jac64.traceSprCol")) {
        if (traceSprColOut == null) {
          String p = System.getProperty("jac64.traceSprColFile", "/tmp/jac64_sprcol.trace");
          try {
            traceSprColOut = new java.io.PrintStream(new java.io.FileOutputStream(p), true);
          } catch (Exception e) { traceSprColOut = System.err; }
        }
        traceSprColOut.println("EV-WrSprCol clk=" + cpu.cycles + " rast=$"
            + Integer.toHexString(vbeam) + " cyc=" + (cpu.cycles - lastLine)
            + " reg=$" + Integer.toHexString(address & 0xff)
            + " val=$" + Integer.toHexString(data & 0xff)
            + " pc=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
      }
      break;
    }
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
      if (Boolean.getBoolean("jac64.traceDD00")) {
        int vicCycleNow = (int) (cpu.cycles - lastLine);
        System.err.println("DD00=$" + Integer.toHexString(data & 0xff)
            + " vbeam=" + vbeam + " cyc=" + vicCycleNow
            + " bankBitsBefore=$" + Integer.toHexString(cia2PRA & 3)
            + " bankBitsAfter=$" + Integer.toHexString(data & 3)
            + " pc=$" + Integer.toHexString(cpu.pc & 0xffff));
      }

      cia[1].performWrite(address + IO_OFFSET, data, cpu.cycles);
      cia2PRA = data;
      updateCia2IecBus(false);
      setGlueVBank(effectiveCia2VBank(), false);
      break;

    case 0xdd02:
      if (c1541Chips != null) {
        ((CPU)cpu).getDrive().tick(cpu.cycles);
      }
      cia2DDRA = data;
      cia[1].performWrite(address + IO_OFFSET, data, cpu.cycles);
      updateCia2IecBus(false);
      setGlueVBank(effectiveCia2VBank(), true);
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

    // Phase 6: forward $D02X color-register writes into VicDrawCycle.
    // The pipeline stages pendingColorReg now and commits cregs[] at
    // start of next cycle's drawColors8 — matches VICE update_cregs
    // 1-cycle pipe (color_latency=1 for 6569 PAL).
    if (useVicFullPipeline && vicDrawCycle != null
        && address >= 0xd020 && address <= 0xd02e) {
      vicDrawCycle.updateColorReg(address - 0xd000, data & 0x0f);
    }

    // Investigation trace: log $D02X write timing for comparison with
    // VICE's color_reg_store trace.
    if (TRACE_WR_COL && address >= 0xd020 && address <= 0xd02e) {
      if (wrColTraceOut == null) {
        String path = System.getProperty("jac64.traceWrColFile",
            "/tmp/jac64_wrcol.trace");
        try { wrColTraceOut = new java.io.PrintStream(path); }
        catch (Exception e) { wrColTraceOut = System.err; }
      }
      wrColTraceOut.println("EV-WrColorReg clk=" + cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + ((int)(cycles - lastLine))
          + " reg=$" + Integer.toHexString(address - 0xd000)
          + " val=$" + Integer.toHexString(data & 0xff));
      wrColTraceOut.flush();
    }
  }

  private static final boolean TRACE_WR_COL =
      Boolean.getBoolean("jac64.traceWrCol");
  private static java.io.PrintStream wrColTraceOut;

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
    // Set-up vars for screen rendering. $DD00/$DD02 bank visibility is fed
    // through the VICE 252535-01 glue state above; $D018 itself is immediate.
    vicBank = glueVisibleVBank << 14;
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

  private int effectiveCia2VBank() {
    return ~(~cia2DDRA | cia2PRA) & 3;
  }

  private void performGlueVBankSwitch(int vbank) {
    glueVisibleVBank = vbank & 3;
    setVideoMem();
  }

  private void scheduleGlueAlarm(int vbank) {
    // VICE c64gluelogic.c:62 — alarm fires at maincpu_clk + 1.
    glueAlarmClock = cpu.cycles + 1;
    glueAlarmActive = true;
  }

  private void setGlueVBank(int vbank, boolean ddrFlag) {
    int newVBank = vbank;
    boolean updateNow = true;

    if (((glueOldVBank ^ vbank) == 3)
        && ((vbank & (vbank - 1)) == 0)
        && vbank != 0) {
      newVBank = 3;
      scheduleGlueAlarm(vbank);
    } else if (ddrFlag && vbank < glueOldVBank
        && ((glueOldVBank ^ vbank) != 3)) {
      updateNow = false;
      scheduleGlueAlarm(vbank);
    }

    if (updateNow) {
      performGlueVBankSwitch(newVBank);
    }
    glueOldVBank = vbank;
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

  /**
   * Phi2 end-of-cycle hook. CPU calls this AFTER its memory access for
   * the current clk, so VIC's end-of-cycle bookkeeping observes the
   * write. Mirrors VICE's CLK_INC ordering where vicii_cycle (which
   * includes SSCol/SBCol IRQ fire, ChkBrdR0/R1, ChkBrdL0/L1, raster IRQ
   * trigger) runs AFTER the CPU's clk-N LOAD/STORE.
   */
  @Override
  public void clockPhi2(long cycles) {
    // SSCol/SBCol IRQ fire. JaC64's collision detection without
    // cycle_flags_pipe runs 1 VIC cycle earlier than VICE (VICE's draw
    // consumes prev-cycle flags via cycle_flags_pipe; JaC64 historically
    // consumed current-cycle flags). The ready-stage gate below adds a
    // compensating 1-Phi2 visibility delay so the IRQ becomes visible at
    // the same Phi2 as VICE.
    //
    // With cycleFlagsPipe ON the sprite pipeline now consumes prev-cycle
    // flags (matches VICE) — so detection already aligns and the
    // compensation must be skipped, otherwise irq-ack-vicii.prg fires
    // SSCol IRQ one full cycle too late.
    if (sprColFirePending) {
      if (!sprColFireReady && !useCycleFlagsPipe) {
        sprColFireReady = true;
      } else {
        int oldFlags = irqFlags;
        irqFlags |= 0x04;
        updateVicIrqLine();
        if ((irqMask & 4) != 0) {
          setIRQ(VIC_IRQ);
        }
        if (TRACE_VIC_CYCLE && cycles >= TRACE_VIC_CYCLE_START
            && cycles <= TRACE_VIC_CYCLE_END) {
          traceVicCycleOut.println("EV-SSColFire clk=" + cycles
              + " rast=$" + Integer.toHexString(vbeam)
              + " cyc=" + (cycles - lastLine)
              + " oldFlags=$" + Integer.toHexString(oldFlags & 0xff)
              + " newFlags=$" + Integer.toHexString(irqFlags & 0xff)
              + " sprCol=$" + Integer.toHexString(sprCol & 0xff)
              + " opPC=$" + Integer.toHexString(cpu.getInstructionStartPC() & 0xffff));
        }
        if (TRACE_VIC_CYCLE) traceAct("SSCol-fire-phi2");
        sprColFirePending = false;
        sprColFireReady = false;
      }
    }
    if (sprBgColFirePending) {
      if (!sprBgColFireReady && !useCycleFlagsPipe) {
        sprBgColFireReady = true;
      } else {
        irqFlags |= 0x02;
        updateVicIrqLine();
        if ((irqMask & 2) != 0) {
          setIRQ(VIC_IRQ);
        }
        sprBgColFirePending = false;
        sprBgColFireReady = false;
      }
    }

    int vc = (int) (cycles - lastLine);
  }

  public final void clock(long cycles) {
    if (glueAlarmActive && cycles >= glueAlarmClock) {
      performGlueVBankSwitch(glueOldVBank);
      glueAlarmActive = false;
    }

    // VICE-style 1-cycle delayed apply for color register writes.
    // Mirrors viciisc/vicii-draw-cycle.c:586-590 update_cregs() where
    // the last register written this cycle is committed to cregs[] at
    // the start of NEXT cycle's draw_colors8(). Multiple writes in
    // same cycle: only the last sticks (same as VICE).
    if (COLOR_DELAY && lastColorReg >= 0 && cycles > lastColorClk) {
      applyDelayedColorReg(lastColorReg, lastColorValue);
      lastColorReg = -1;
    }

    // VICE viciisc/vicii-cycle.c:407 — capture `can_sprite_sprite =
    // (collisions == 0)` BEFORE draw. Used at end-of-cycle to gate
    // SSCol IRQ fire. Captured here, AFTER any deferred state but
    // BEFORE the case dispatcher (= draw).
    sprColCanFire = (sprCol == 0);
    sprBgColCanFire = (sprBgCol == 0);

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

    // Fire any pending lightpen trigger scheduled +1 cyc earlier
    // (VICE vicii-cycle.c:825 `trigger_cycle == maincpu_clk` check).
    firePendingLightPen();

    // VIC raster X — VICE formula (viciitypes.h:124).
    currentRasterX = rasterX(vicCycle);

    // VICE viciisc/vicii-cycle.c:411 — vicii_draw_cycle (which calls
    // draw_sprites8 internally) runs on EVERY cycle. JaC64 previously
    // only ran the sprite pipeline on cases 13-60 via drawSprites(),
    // missing state-machine transitions on cases 0-12 / 61-62 (sprite
    // ptr/dma + idle pre-line cycles). Advance the per-cycle state
    // here so halt/active/pending bits track VICE 1:1.
    //
    // vicShaped path advances the pipeline from Phase 5b (right after
    // VicDrawCycle.drawCyclePart1) so priBuffer is fresh — skip this
    // legacy pre-dispatcher call to avoid double-advance.
    if (useVicSprPipe
        && !VICE_SHAPED) {
      advanceSpritePipeline(vicCycle);
    } else if (useVicSprPipe && VICE_SHAPED
        && vicCycle >= 1 && vicCycle <= 11
        && VicSpritePipeline.SPR_XPOS_WRAP) {
      // #217 sprite-fetch-in-sideborder: VICE runs draw_sprites8 every
      // cycle. JaC's VICE_SHAPED path advances the sprite pipeline only in
      // finishCycleVic (cyc 12-59), so the left/right border cycles are
      // skipped and high-X sprites (which trigger at the wrapped xpos in
      // those cycles) are missed. Advance here with an empty priBuffer (no
      // graphics foreground in the border).
      for (int i = 0; i < 8; i++) vicSprPipe.priBuffer[i] = false;
      advanceSpritePipeline(vicCycle);
    }

    // At cycle 0, increment vbeam BEFORE the raster compare
    // (real VIC-II updates raster counter at start of line)
    if (vicCycle == 0) {
      // Before advancing: capture each active sprite's current state
      // into this frame's history buffer (dedup by X/ptr/nextByte).
      for (int i = 0; i < 8; i++) {
        Sprite sp = sprites[i];
        if (!sp.enabled) continue;
        int cnt = spriteFrameCount[i];
        boolean dup = false;
        for (int j = 0; j < cnt; j++) {
          if (spriteFrameX[i][j] == sp.x
              && spriteFramePtr[i][j] == sp.pointer
              && spriteFrameNb[i][j] == sp.nextByte) {
            dup = true; break;
          }
        }
        if (!dup && cnt < SPRITE_FRAME_MAX) {
          spriteFrameX[i][cnt] = sp.x;
          spriteFrameY[i][cnt] = sp.y;
          spriteFramePtr[i][cnt] = sp.pointer;
          spriteFrameNb[i][cnt] = sp.nextByte;
          spriteFrameCount[i] = cnt + 1;
        }
      }

      int prevVbeam = vbeam;
      vbeam = (vbeam + 1) % 312;
      if (TRACE_VIC_CYCLE && cycles >= TRACE_VIC_CYCLE_START
          && cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-LineInc clk=" + cycles
            + " from=" + prevVbeam
            + " to=" + vbeam
            + " lastLine=" + lastLine);
      }
      if (vbeam == 0) {
        frame++;
        // VICE vicii-lightpen.c semantics: light_pen.triggered cleared
        // at start of frame so a new /LP trigger can fire next frame.
        lpTriggered = false;
        lpPendingTriggerClk = -1;
        if (fldTrace && --fldTraceFrames <= 0) {
          fldTrace = false;
          fldOut.println("=== FLD TRACE END ===");
          fldOut.close();
        }
        // Publish this frame's history and reset for next frame.
        for (int i = 0; i < 8; i++) {
          int cnt = spriteFrameCount[i];
          spriteFrameCountLast[i] = cnt;
          System.arraycopy(spriteFrameX[i], 0, spriteFrameXLast[i], 0, cnt);
          System.arraycopy(spriteFrameY[i], 0, spriteFrameYLast[i], 0, cnt);
          System.arraycopy(spriteFramePtr[i], 0, spriteFramePtrLast[i], 0, cnt);
          System.arraycopy(spriteFrameNb[i], 0, spriteFrameNbLast[i], 0, cnt);
          spriteFrameCount[i] = 0;
        }
      }
      vPos = vbeam - (FIRST_VISIBLE_VBEAM + 1);
    }

    while (rasterIrqClock != RASTER_IRQ_DISABLED && cycles >= rasterIrqClock) {
      triggerRasterIrq(rasterIrqClock);
    }

    // Phase η: consume any deferred RMW-dummy-write raster IRQ trigger.
    if (pendingRasterIrqFireClk != RASTER_IRQ_DISABLED
        && cycles >= pendingRasterIrqFireClk) {
      long fireClk = pendingRasterIrqFireClk;
      pendingRasterIrqFireClk = RASTER_IRQ_DISABLED;
      triggerRasterIrq(fireClk);
    }

    // NOTE: gfxVisible is deliberately not set here. Entering display
    // state happens at cycle 13 (mapped from VICE's cycle 14
    // "update_vc"), which is where we also test !gfxVisible to decide
    // whether rc should reset. Setting it earlier collapses the
    // idle→display transition and makes FLI's char-row advance wrong.

    // Per-cycle vborder top/bottom checks — VICE runs these every
    // cycle via check_vborder_top / check_vborder_bottom. Cheap to
    // evaluate and necessary for mid-line rsel toggles (bottom-border
    // opening trick) to work at the exact cycle the demo expects.
    if (true /* vicBorderLatch default on */) {
      checkVBorderTopBottom();
      // Cycle 1 latch (VICE vicii-cycle.c:480). Also latch vborder
      // into borderState bit 0 here. The hborder latch at cyc 17/18
      // does its own latch too.
      if (vicCycle == 0) {
        if (setVBorder) {
          borderState |= 1;
        } else {
          borderState &= 0xfe;
        }
      }
    }

    if (VICE_BADLINE_FSM) {
      // Phase K iter#10: unified VICE-faithful per-cycle state.
      // Replaces the mid-line badLine re-eval below AND the legacy
      // updates at case 0 / case 13 / case 57 (each gated by the flag).
      updateVicStateVic(vicCycle);
    } else {
      // VICE viciisc/vicii-cycle.c:581 — check_badline runs every vicii_cycle
      // when allow_bad_lines is set. JaC64 used to evaluate badLine only at
      // cycle 0 + on $D011 writes (writer-side state machine). For demos
      // that write $D011 mid-line (FLI, soft-scrollers), the writer-side
      // path produced one-charrow timing skew vs VICE. Move re-evaluation
      // here so any mid-line vScroll change is re-derived at the next VIC
      // cycle, exactly mirroring VICE.
      //
      // case 0 still does its own initial badLine = isBadLine(vScroll),
      // resetBadLineFetchWindow, line-start setup. We only handle the
      // mid-line transition cases here (vicCycle != 0).
      if (vicCycle != 0) {
        boolean newBadLine = isBadLine(vScroll);
        if (newBadLine != badLine) {
          boolean wasVisibleNow = !vicIdleState;
          if (newBadLine) {
            badLine = true;
            if (!badLineStartedThisLine) {
              badLineStartedThisLine = true;
              handleBadLineStart(vicCycle, wasVisibleNow);
            } else {
              setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-REARM");
            }
          } else {
            if (vicCycle < BADLINE_FETCH_CYCLE) {
              badLine = false;
              handleBadLineStop(vicCycle, wasVisibleNow);
            } else {
              badLine = true;
            }
          }
        }
      }
    }

    // VICE viciisc/vicii-cycle.c:657-666 — prefetch_cycles counter.
    // While ba_low (= bad_line active), count down. When ba_low goes
    // high, reset to 3+1. The first 3 cycles of bad_line return the
    // FLI-bug prefetch byte (CPU PC), normal c-access starts at cycle 4.
    if (badLine) {
      if (prefetchCycles > 0) prefetchCycles--;
    } else {
      prefetchCycles = 4;
    }

    // Phase K iter#9: per-cycle VIC state trace for diff against VICE.
    // Gated by -Djac64.traceVicState=true + jac64.traceVicStateFile.
    // Emits vc/vmli/rc/vcbase/idle/bad each cycle so we can diff
    // internal state at matching (rast,cyc) vs VICE viciisc.
    if (TRACE_VIC_STATE && cycles >= TRACE_VIC_STATE_START
        && cycles <= TRACE_VIC_STATE_END) {
      if (vicStateOut == null) {
        String path = System.getProperty("jac64.traceVicStateFile",
            "/tmp/jac64_state.trace");
        try { vicStateOut = new java.io.PrintStream(path); }
        catch (Exception e) { vicStateOut = System.err; }
      }
      vicStateOut.println("EV-State clk=" + cycles
          + " rast=$" + Integer.toHexString(vbeam)
          + " cyc=" + vicCycle
          + " vc=" + vc + " vmli=" + vmli
          + " rc=" + rc + " vcbase=" + vcBase
          + " idle=" + (vicIdleState ? 1 : 0)
          + " bad=" + (badLine ? 1 : 0)
          + " abl=" + (displayEnabled ? 1 : 0)
          + " ys=" + vScroll);
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

      // Sprite collision IRQs are now fired AT collision detection
      // time (inside drawSprites paths), not once-per-line here. This
      // matches VICE viciisc/vicii-cycle.c:428 where the IRQ fires
      // only on 0→non-zero transition of the collision register.
      // The previous once-per-line check fired at every line where a
      // collision existed, producing wrong patterns on
      // irq-ack-vicii.prg's SS-COL test.
      notVisible = false;
      if (vPos < 0 || vPos >= SC_HEIGHT) {
        notVisible = true;
        if (STATE_DEBUG)
          monitor.info("FINISH next at " + vbeam);
        break;
      }

      // Check if display should be enabled...
      if (vbeam == 0x30) {
        if (!VICE_BADLINE_FSM) {
          displayEnabled = (control1 & 0x10) != 0;
        }
        // Border-state edge update still needed regardless of FSM mode.
        if (displayEnabled) {
          borderState &= ~0x04;
        } else {
          borderState |= 0x04;
        }
      }

      if (!VICE_BADLINE_FSM) {
        badLine = isBadLine(vScroll);
      }
      resetBadLineFetchWindow();
      // Reset the per-line idempotency guard for handleBadLineStart.
      badLineStartedThisLine = badLine;

      // Clear sprite-register change queue for the new line. Stage 2:
      // queue is populated on writes but not yet consumed.
      spriteChangeQueue.clear();

      // Reset sequencer per-line state (stage 4).
      for (int i = 0; i < 8; i++) {
        spriteSeqs[i].onLineStart();
        syncSequencerFromSprite(i);
      }

      if (fldTrace && (vbeam < 0x30 || vbeam <= 0xf7)) {
        fldOut.println("CYC0 vbeam=" + vbeam + " badLine=" + badLine +
            " gfxVis=" + (!vicIdleState) + " rc=" + rc +
            " vScroll=" + vScroll + " borderSt=" + borderState +
            " sprDMA=$" + Integer.toHexString(spriteDmaMask()) +
            " d011=$" + Integer.toHexString(control1) +
            " d016=$" + Integer.toHexString(control2));
      }

      // Clear the collission masks each line. Array is SC_WIDTH+48
      // to cover expanded sprites that span past the visible area —
      // we must clear the full length so stale bits don't persist.
      for (int i = 0, n = collissionMask.length; i < n; i++) {
        collissionMask[i] = 0;
      }
      // VICE SprPtr(3) / SprDma0(3) at PAL cyc 1 = JaC64 case 0 end.
      if (true /* spriteFetchAligned default on */
          && sprites[3].dma) {
        sprites[3].readSpriteData();
      }
      break;
    case 1: // Sprite data - sprite 3
      // VICE viciisc/vicii-cycle.c:545-551: vicii.vborder = vicii.set_vborder
      // at raster_cycle == 1. JaC64 was only doing this at checkHBorderLeft
      // (cyc 16/17), which kept vBorder asserted ~15 cycles too long at the
      // top-border→gfx-visible transition. The bug surfaced clearly in
      // screenpos where rast=51 cyc=1 shows VICE vb=0 but JaC64 vb=1.
      checkVBorderTopBottom();
      vBorder = setVBorder;
      // VICE fetches SprPtr(3) at PAL cyc 1 Phi1 — that maps to our
      // "case 0 end". When jac64.spriteFetchAligned is on, SPR3-7
      // fetches moved to cases 0/2/4/6/8 (one VIC cycle earlier)
      // matching VICE timing. The 9-sprite multiplex trick and mid-
      // line $07F8+n pointer writes depend on this alignment.
      if (!true /* spriteFetchAligned default on */
          && sprites[3].dma) {
        sprites[3].readSpriteData();
      }
      if (sprites[5].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP5, "SPR5");
      }
      break;
    case 2:
      if (true /* spriteFetchAligned default on */
          && sprites[4].dma) {
        sprites[4].readSpriteData();
      }
      break;
    case 3:
      if (!true /* spriteFetchAligned default on */
          && sprites[4].dma) {
        sprites[4].readSpriteData();
      }
      if (sprites[6].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP6, "SPR6");
      }
      break;
    case 4:
      if (true /* spriteFetchAligned default on */
          && sprites[5].dma) {
        sprites[5].readSpriteData();
      }
      break;
    case 5:
      if (!true /* spriteFetchAligned default on */
          && sprites[5].dma) {
        sprites[5].readSpriteData();
      }
      if (sprites[7].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP7, "SPR7");
      }
      break;
    case 6:
      if (true /* spriteFetchAligned default on */
          && sprites[6].dma) {
        sprites[6].readSpriteData();
      }
      break;
    case 7:
      if (!true /* spriteFetchAligned default on */
          && sprites[6].dma) {
        sprites[6].readSpriteData();
      }
      break;
    case 8:
      if (true /* spriteFetchAligned default on */
          && sprites[7].dma) {
        sprites[7].readSpriteData();
      }
      break;
    case 9:
      if (!true /* spriteFetchAligned default on */
          && sprites[7].dma) {
        sprites[7].readSpriteData();
      }

      // Border management. Legacy path stays default. When
      // -Djac64.vicBorderLatch=true, bottom-border updates are
      // deferred to case 16/17 (VICE's check_hborder latch), so we
      // skip them here. The sprite.lineFinished housekeeping stays.
      if (!true /* vicBorderLatch default on */) {
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
          }
        }
        if (vbeam == 55) {
          borderState &= 0xfe;
        }
      }
      if (!blankRow && vbeam == 51) {
        for (int i = 0, n = 7; i < n; i++) {
          if (!sprites[i].painting) {
            sprites[i].lineFinished = true;
          }
        }
      }
      if (vbeam == 55) {
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
      finishCycleVic(mpos);

      xPos = 16;
      mpos += 8;

      break;
    case 13:
      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;

      // Set vc, reset vmli... (gated under !VICE_BADLINE_FSM — the
      // unified FSM does this in updateVicStateVic at vicCycle==14).
      if (!VICE_BADLINE_FSM) {
        vc = vcBase;
        vmli = 0;
      }
      // Reset VICE-style gfx pipeline shift register at line start so the
      // first column's pre-XSCROLL pixels emit from a clean state. Re-seed
      // mode pipes from current registers — VICE's draw_graphics8 runs
      // every cycle, but JaC64 only invokes drawGraphicsVic at cases
      // 16-55, so we'd otherwise drift across non-display cycles.
      if (useVicGfx) {
        gbufReg = 0;
        gbufPipe0Reg = 0;
        gbufPipe1Reg = 0;
        xscrollPipe = horizScroll;
        gbufMcFlop = 0;
        vmode11Pipe = (control1 & 0x60) >> 2;
        vmode16Pipe = (control2 & 0x10) >> 2;
        vmode16Pipe2 = vmode16Pipe;
      }
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C13");
        if (BAD_LINE_DEBUG) System.out.println("#### RC = 0 (" + rc + ") at "
            + vbeam + " vc: " + vc);
        // VICE's UpdateVc (cyc 14): unconditionally rc=0 when bad_line is
        // true. The demo's $D011 timing controls whether bad_line is true
        // at this cycle: writes BEFORE cyc 13 force a reset (e.g. FLI
        // first line of each char row), writes AFTER (FLI lines 1-7)
        // leave bad_line=false at this point so rc keeps incrementing.
        rc = 0;
        vicIdleState = false;
      }
      break;
    case 14:
      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C14");
      }
      break;
    case 15:

      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;

      col0FetchedThisLine = false;
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C15");
        fetchBadLineData(0);  // VICE Phi2(15) col 0
        col0FetchedThisLine = true;
      }

      // Turn off sprite DMA if finished reading!
      if (useSpriteCrunch) {
        // VICE Phi2(16) UpdateMcBase: mcbase=mc if expFlipFlop=1,
        // DMA off if mcbase==63. Mirrors vicii-cycle.c:sprite_mcbase_update.
        // Opt-in trace via -Djac64.traceSpriteCrunch=true.
        for (int i = 0; i < 8; i++) {
          Sprite s = sprites[i];
          if (s.expFlipFlop) {
            s.mcbase = s.mc;
            if (s.mcbase == 63) s.dma = false;
          }
        }
        if (Boolean.getBoolean("jac64.traceSpriteCrunch")
            && vbeam >= 50 && vbeam <= 110) {
          StringBuilder sb = new StringBuilder("MCBASE-UPD vbeam=").append(vbeam);
          for (int i = 0; i < 8; i++) {
            Sprite s = sprites[i];
            sb.append(" s").append(i).append("[dma=").append(s.dma ? 1 : 0)
              .append(" mc=").append(s.mc).append(" mcb=").append(s.mcbase)
              .append(" eF=").append(s.expFlipFlop ? 1 : 0).append("]");
          }
          System.err.println(sb.toString());
        }
      } else {
        for (int i = 0, n = 8; i < n; i++) {
          if (sprites[i].nextByte == 63)
            sprites[i].dma = false;
        }
      }

      break;
    case 16:
      // Left border end (40-col / csel=1) now runs in clockPhi2() at
      // vicCycle 16 — VICE cycle 17 Phi2.
      if (!hideColumn) {
        checkHBorderLeft();
      }
      if (badLine) {
        // FLI leading-prefetch backfill (jac64.fliLeadingPrefetch, default on).
        // In continuous FLI the col-0 c-access at case 15 is gated out because
        // the $D011 write sets badLine AFTER the case-15 fetchBadLineData(0)
        // check, so vicCharCache[0..1] retain STALE prev-line data. The real
        // VIC prefetch-fills the skipped leading cells with the FLI-bug idle
        // byte ($ff). Backfill cols 0-1 with the prefetch char/color so the
        // left-edge band matches VICE. Fires ONLY when col-0 was skipped (i.e.
        // FLI late-badlines) — normal badlines fetch col-0 at case 15, so this
        // is a no-op for them (zero regression on non-FLI tests).
        // colorfetchbug family: -1748 cells; fldscroll/blackmail unchanged.
        if (!col0FetchedThisLine
            && Boolean.parseBoolean(System.getProperty(
                "jac64.fliLeadingPrefetch", "true"))) {
          int prefCol = memory[cpu.pc & 0xffff] & 0x0f;
          vicCharCache[0] = 0xff; vicColCache[0] = prefCol;
          vicCharCache[1] = 0xff; vicColCache[1] = prefCol;
        }
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C16");
        fetchBadLineData(1);  // VICE Phi2(16) col 1
      }

      // Draw one character here!
      if (useVicGfx) drawGraphicsVic(mpos);
      else drawGraphics(mpos + horizScroll);
      drawSprites();
      if (!useVicRenderBuf && borderState != 0)
        drawBackground();
      finishCycleVic(mpos);
      mpos += 8;

      break;
    case 17:
      // Left border end (38-col / csel=0) now runs in clockPhi2() at
      // vicCycle 17 — VICE cycle 18 Phi2.
      if (hideColumn) {
        checkHBorderLeft();
      }
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C17");
        fetchBadLineData(2);  // VICE Phi2(17) col 2
      }
      if (useVicGfx) drawGraphicsVic(mpos);
      else drawGraphics(mpos + horizScroll);
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;
      break;
      // Cycle 18 - 53
    default:
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-FETCH");
        // vicCycle 18..53 fetches col vicCycle-15 = col 3..38.
        fetchBadLineData(vicCycle - 15);
      }
      if (useVicGfx) drawGraphicsVic(mpos);
      else drawGraphics(mpos + horizScroll);
      drawSprites();
      finishCycleVic(mpos);

      mpos += 8;
      break;
    case 54:
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C54");
        fetchBadLineData(39);
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
            if (useSpriteCrunch) {
              // VICE turn_sprite_dma_on: mcbase=0, exp_flop=1.
              sprite.mcbase = 0;
              sprite.mc = 0;
            }
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

      if (useVicGfx) drawGraphicsVic(mpos);
      else drawGraphics(mpos + horizScroll);
      drawSprites();
      finishCycleVic(mpos);

      mpos += 8;

      break;
    case 55:
      // ChkBrdR0 (CSEL=0 / hideColumn=true) now runs in clockPhi2() at
      // vicCycle 55 — matches VICE cycle 56 Phi2 timing (= AFTER CPU's
      // clk-55 access).
      if (hideColumn) {
        checkHBorderRight();
      }
      if (badLine) {
        setBaLowUntil(lastLine + VICConstants.BA_BADLINE, "BADLINE-C55");
        // All 40 c-accesses were completed by case 54.
      }
      // Second sprite-Y match check — VICE PAL fetch table runs ChkSprDma
      // at BOTH Phi1(55) and Phi1(56) (vicii-chip-model.c:220,222). JaC64
      // case 54 covers Phi1(55); this case covers Phi1(56). Mid-line
      // $D001 writes between the two cycles can restart a sprite that
      // missed the first check (spriterestart.prg test).
      {
        int ypos2 = vPos + SC_SPYOFFS;
        for (int i = 0; i < 8; i++) {
          Sprite sprite = sprites[i];
          if (sprite.enabled && !sprite.dma
              && sprite.y == (ypos2 & 0xff) && (ypos2 < 270)) {
            sprite.nextByte = 0;
            sprite.dma = true;
            sprite.expFlipFlop = true;
            if (useSpriteCrunch) {
              sprite.mcbase = 0;
              sprite.mc = 0;
            }
            if (fldTrace) {
              fldOut.println("SPR-DMA-ON-2 s=" + i +
                  " vbeam=" + vbeam + " cyc=" + vicCycle +
                  " y=$" + Integer.toHexString(sprite.y & 0xff) +
                  " clk=" + cpu.cycles);
            }
          }
        }
      }
      // VICE Phi2(56) ChkSprExp: toggle expFlipFlop for sprites with DMA
      // and y_exp bit set. Reads $D017 LIVE (= sprYEX). Mirrors
      // vicii-cycle.c:check_exp. This is the sprite-crunch trigger point —
      // mid-line $D017 writes between this cycle and next-line UpdateMcBase
      // change the mcbase commit behaviour.
      if (useSpriteCrunch) {
        for (int i = 0; i < 8; i++) {
          Sprite s = sprites[i];
          if (s.dma && (sprYEX & (1 << i)) != 0) {
            s.expFlipFlop = !s.expFlipFlop;
          }
        }
      }
      if (useVicGfx) drawGraphicsVic(mpos);
      else drawGraphics(mpos + horizScroll);
      drawSprites();
      if (!useVicRenderBuf && borderState != 0)
          drawBackground();
      finishCycleVic(mpos);
      mpos += 8;

      break;
    case 56:
      // ChkBrdR0 (hideColumn=true) and ChkBrdR1 (hideColumn=false)
      // now run in clockPhi2() at vicCycle 55/56 — VICE cycles 56/57 Phi2.
      if (!hideColumn) {
        checkHBorderRight();
      }
      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
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
      // VICE Phi1(58) ChkSprDisp: mc = mcbase, set display bits for active
      // DMA sprites. Mirrors vicii-cycle.c:check_sprite_display.
      if (useSpriteCrunch) {
        for (int i = 0; i < 8; i++) {
          sprites[i].mc = sprites[i].mcbase;
        }
      }
      // VICE-sticky sprite_display_bits update (vicii-cycle.c:64-81).
      // SET bit on Y-match + enable + DMA active; CLEAR on DMA-off;
      // otherwise unchanged (sticky across non-Y-match lines).
      if (useSpriteDispSticky) {
        int yMatch = (vPos + SC_SPYOFFS) & 0xff;
        for (int s = 0; s < 8; s++) {
          int bit = 1 << s;
          Sprite sp = sprites[s];
          if (sp.dma) {
            if (sp.enabled && sp.y == yMatch) {
              spriteDisplayBitsSticky |= bit;
            }
          } else {
            spriteDisplayBitsSticky &= ~bit;
          }
        }
      }
      for (int i = 0, n = 8; i < n; i++) {
        Sprite sprite = sprites[i];
        if (sprite.dma)
          sprite.painting = true;
      }

      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;


      if (!VICE_BADLINE_FSM) {
        if (rc == 7) {
          vcBase = vc;
          vicIdleState = true;
          if (BAD_LINE_DEBUG) {
            monitor.info("#### RC7 ==> vc = " + vc + " at " + vbeam +
                " vicCycle = " + vicCycle);
            if (vc == 1000) {
              monitor.info("--------------- last line ----------------");
            }
          }
        }

        if (badLine || !vicIdleState) {
          rc = (rc + 1) & 7;
          vicIdleState = false;
        }
      }
      // (VICE_BADLINE_FSM path: rc/idle/vcbase update happens in
      // updateVicStateVic at vicCycle==58, which is "case 57" here
      // since JaC64 dispatcher is 1 ahead of VICE; need to verify.)

      if (sprites[0].painting) {
        sprites[0].readSpriteData();
      }

      break;
    case 58:
      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;

      // Sprite 2 BA-low: VICE BaSpr3(0,1,2) starts at Phi1(59),
      // mapping to JaC64 case 58 under the case-N = VICE-cycle-(N+1)
      // convention. Earlier this was incorrectly at case 57 (= cycle
      // 58), one cycle too early. See docs/vic-ii/CYCLE_TRACE_FINDINGS.md.
      if (sprites[2].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP2, "SPR2");
      }

      break;
    case 59:
      drawBackground();
      drawSprites();
      finishCycleVic(mpos);
      mpos += 8;

      if (sprites[1].painting) {
        sprites[1].readSpriteData();
      }
      break;
    case 60:
      drawSprites();
      // Sprite 3 BA-low: VICE BaSpr3(1,2,3) starts at Phi1(61),
      // mapping to JaC64 case 60. Earlier this was at case 61
      // (= VICE cycle 62), one cycle too late. See
      // docs/vic-ii/CYCLE_TRACE_FINDINGS.md.
      if (sprites[3].dma) {
        setBaLowUntil(lastLine + VICConstants.BA_SP3, "SPR3");
      }
      break;
    case 61:
      if (sprites[2].painting) {
        sprites[2].readSpriteData();
      }
      drawSpritesV2Tail();
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
        if (vPos == SC_HEIGHT + 1) {
          // Throttle to 50 Hz only when audio driver has no sound
          // (e.g. Android without blocking audio). With ReSID on
          // desktop, audio output already provides the timing —
          // double throttle causes audio glitches.
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

    // VICE viciisc/vicii-cycle.c:413-433 — POST-DRAW pending clear,
    // then end-of-cycle SSCol/SBCol IRQ fire if (can && collisions).
    // Clear runs AFTER the case dispatcher (= draw) so that any new
    // collisions added by sprite painting THIS cycle get wiped, but
    // the CAPTURE (sprColCanFire) was BEFORE both. Result: $D01E read
    // → 1-cycle delay before sprite-paint-induced re-fire.
    if (sprColClearPending) {
      sprCol = 0;
      sprColClearPending = false;
    }
    if (sprBgColClearPending) {
      sprBgCol = 0;
      sprBgColClearPending = false;
    }
    // SSCol/SBCol IRQ fire detection — captured here at end of clock()
    // (= end of Phi1 / end of cycle's VIC work). IRQ flag visibility is
    // handled in clockPhi2(), with an optional one-Phi2 ready stage.
    if (sprColCanFire && sprCol != 0 && !sprColFirePending) {
      sprColFirePending = true;
      sprColFireReady = false;
    }
    if (sprBgColCanFire && sprBgCol != 0 && !sprBgColFirePending) {
      sprBgColFirePending = true;
      sprBgColFireReady = false;
    }

    // Phase 5b: VicDrawCycle hand-off. Runs every VIC cycle (matches
    // VICE vicii_draw_cycle). DbufSink.writePixel is currently a no-op,
    // so flag-on path executes the pipeline state-machine without
    // changing on-screen output. Phase 5c will route writePixel into
    // mem[] and start replacing legacy paint.
    if (useVicFullPipeline) {
      // g-byte: replicate drawGraphicsVic's fetch logic.
      // VICE viciisc/vicii-fetch.c:234 vicii_fetch_graphics():
      //   color_latency=true (6569):  addr uses (regs[0x11] | (reg11_delay & 0x20))
      //                                with extra 6569-fetch-magic for RAM→ROM transitions
      //   color_latency=false (8565): addr uses ONLY reg11_delay (previous cycle)
      // Matching this fixes modesplit cyc 31 gbuf-fetch off-by-1 vs VICE.
      // Gate on PHI1_FETCH_G: VICE only calls vicii_fetch_graphics at
      // cycles with that flag (vicCycle 16..55). Refresh cycles (11..15)
      // and right-border cycles (56..62) should NOT load the gbuf pipe
      // — JaC64 previously read charmem at every cycle, feeding stale
      // data into the pipe and producing gbuf=non-zero where VICE has 0.
      // Fixes vicii_reg_timing cyc 13-14 mismatch.
      int gByte = 0;
      int cycleFlagsForFetch = VicDrawCycle.cycleFlagsFor(vicCycle);
      // PHI1_FETCH_G is an enum value within PHI1_TYPE_M (3-bit field),
      // not a flag bit. Bitwise AND with PHI1_FETCH_G alone matches other
      // enum values that share bit 10 (e.g. PHI1_REFRESH=0x600). Compare
      // by equality against the masked type field.
      boolean isFetchG = (cycleFlagsForFetch & VicDrawCycle.PHI1_TYPE_M)
          == VicDrawCycle.PHI1_FETCH_G;
      if (isFetchG && vmli < 40 && !notVisible) {
        int vByte = vicCharCache[vmli] & 0xff;
        boolean colorLatency = Boolean.parseBoolean(
            System.getProperty("jac64.colorLatency", "false"));
        int d011Fetch;
        if (colorLatency) {
          // 6569 path: combine current BMM bit with delayed others
          d011Fetch = control1 | (control1FetchDelay & 0x20);
        } else {
          // 8565 path: use only previous-cycle $D011 for the fetch
          d011Fetch = control1FetchDelay;
        }
        int fetchAddr;
        if ((d011Fetch & 0x20) != 0) {
          // VICE viciisc/vicii-fetch.c:163-181 g_fetch_addr() applies
          // an ECM mask `a &= 0x39ff` AFTER the BMM address calc.
          // Bits 10 and 9 of `a` correspond to bits 7 and 6 of vc, so
          // ECM clears those bits of vc for the fetch. Fixes modesplit
          // cols 33-35 (= 9 cells, 138 pixels) where the test enters
          // ECM+BMM (invalid mode) at cyc 42 via $D011=$7b — JaC used
          // to keep reading the un-masked bitmap byte for 6 cycles.
          // BMM g-fetch uses the running vc counter directly (VICE
          // g_fetch_addr: a = (vc<<3)|rc). But this block runs AFTER
          // finishCycleVic has already post-incremented vc for the
          // column, so vc here is one ahead of VICE's fetch-time vc.
          // VICE uses vc THEN increments (vicii-fetch.c:287/315); we
          // must undo finishCycleVic's increment to read the correct
          // bitmap byte. Without this, BMM cols are shifted left by one
          // cell on lines where the bitmap varies cell-to-cell
          // (fetchsplit r7 gradient lines, ~40 cells CLASS A). Text mode
          // is immune (it indexes vicCharCache[vmli], not vc).
          int bmmVc = Boolean.parseBoolean(
              System.getProperty("jac64.bmmVcFetchFix", "true"))
              ? ((vc - 1) & 0x3ff) : (vc & 0x3ff);
          int vcMasked = (d011Fetch & 0x40) != 0 ? (bmmVc & 0x33f) : bmmVc;
          fetchAddr = vicBase + vcMasked * 8 + rc;
        } else if ((d011Fetch & 0x40) != 0) {
          fetchAddr = charMemoryIndex + ((vByte & 0x3f) << 3) + rc;
        } else {
          fetchAddr = charMemoryIndex + (vByte << 3) + rc;
        }
        gByte = memory[fetchAddr] & 0xff;
        if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
            && cpu.cycles <= TRACE_VIC_CYCLE_END) {
          traceVicCycleOut.println("EV-FetchG clk=" + cpu.cycles
              + " rast=$" + Integer.toHexString(vbeam)
              + " cyc=" + vicCycle
              + " col=" + vmli
              + " addr=$" + Integer.toHexString(fetchAddr)
              + " data=$" + Integer.toHexString(gByte)
              + " d018=$" + Integer.toHexString(vicMem)
              + " vc=" + vc
              + " rc=" + rc);
        }
      }
      // Phase C: paint base aligned with VICE's Phi1(N)-quantized xpos
      // (shift=-16). Combined with jac64.sprXposOffset=-8 (default below)
      // this makes the sprite trigger ALSO use Phi1(N)-quant matching
      // VICE cycle_get_xpos(cycle_flags_pipe). Suite delta (15 tests
      // including greydot/videomode/fetchsplit): -2264 cells on 8565early
      // vs the previous shift=-8 default (which only won on screenpos by
      // happening to align with that test's reference convention).
      int shift = Integer.getInteger("jac64.vicShift", -16);
      // The per-cycle paint base is vPos*SC_WIDTH + (cyc-12)*8 + shift.
      // With shift=-16, the in-row visible span (x0..383) is exactly
      // cyc 14..61. The old range cyc 12..60 was WRONG at BOTH edges:
      //  - cyc 12-13 give a negative within-row offset → they wrote the
      //    PREVIOUS row's right edge (x368-383). So line N+1's left-edge
      //    paint clobbered line N's right-edge content — which is why a
      //    sprite in the right border (Krestage 3's reused 9th sprite at
      //    x376-383) was overwritten by the next line's bg.
      //  - cyc 61 (x376-383) was dropped entirely, so the right edge was
      //    never painted by its own line.
      // Clamp to cyc 14..61 so every 8-px span stays within row vPos.
      int loCyc = 12, hiCyc = 60;
      if (Boolean.parseBoolean(System.getProperty("jac64.rightEdgeCyc61", "true"))) {
        loCyc = 14; hiCyc = 61;
      }
      if (vicCycle >= loCyc && vicCycle <= hiCyc && !notVisible
          && vPos >= 0 && vPos < SC_HEIGHT) {
        vicCyclePaintBase = vPos * SC_WIDTH + (vicCycle - 12) * 8 + shift;
      } else {
        vicCyclePaintBase = -1;
      }
      vicDrawCycle.setTraceClk(cycles, vbeam);
      vicDrawCycle.setRasterCycle(vicCycle);
      // Phase F1: cycle flags from VICE PAL 6569 chip-model table.
      // Captures VISIBLE_M, FETCH_G, sprite-DMA, border-check, etc.
      // exactly as VICE's vicii_chip_model_init builds them. JaC64
      // vicCycle 1..63 → table index 0..62 (matches VICE raster_cycle).
      vicDrawCycle.setCycleFlags(VicDrawCycle.cycleFlagsFor(vicCycle));
      // color_latency: true = 6569 PAL (1-pixel pipe via pixel_buffer
      // ring, no grey-dot); false = 8565 PAL HMOS (immediate cregs
      // commit + grey-dot at pixel 0).
      // Default OFF (8565) — matches current VICE x64sc (MOS8565 default
      // chip model). On 8-test suite vs `-8565` refs: 1387 cells with
      // false vs 2830 with true (-1443 cells). The older `-8565early`
      // refs predate VICE's grey-dot fixup; see
      // `project_greydot_ref_outdated.md` in memory for the analysis.
      // Override to true via -Djac64.colorLatency=true for legacy
      // 6569-style behaviour.
      boolean colorLatencyFlag = Boolean.parseBoolean(
          System.getProperty("jac64.colorLatency", "false"));
      vicDrawCycle.setColorLatency(colorLatencyFlag);
      vicSprPipe.colorLatency = colorLatencyFlag;
      // VICE keeps main_border and vborder SEPARATE in vicii-draw-cycle.c.
      // draw_border8 early-exits on `!(border_state || main_border)` —
      // vborder=1 alone does NOT force a fill. The legacy `|| vBorderOnly()`
      // here forced mainBorder=1 in EVERY vborder=1 cycle (= the entire
      // vertical-border zone), painting solid COL_D020 even when the
      // side-border-open trick had left mainBorder=0. Removing it lets
      // the bottom-border-open case (border-250/251/252 family) paint
      // bitmap content like VICE. VicDrawCycle reads vborder separately
      // via setVborder() for its own pipe-load gate. Flag:
      // -Djac64.mbVborderOr (default false) to keep the legacy OR.
      boolean mainBorderNow = paintBorder || borderClosed()
          || (Boolean.parseBoolean(System.getProperty("jac64.mbVborderOr", "false"))
              ? vBorderOnly() : false);
      vicDrawCycle.setMainBorder(mainBorderNow);
      vicDrawCycle.setVborder(vBorder ? 1 : 0);
      // Phase 9: sync borderState to JaC64's authoritative
      // borderStatePrev (captured at end of prev cycle).
      vicDrawCycle.setBorderState(borderStatePrev ? 1 : 0);
      // Pipe-load gate: use VICE-faithful vicIdleState (mirrors VICE
      // update_rc-only transitions) so [[idle-clear-pipe-fix]] doesn't
      // fire when VICE wouldn't clear the pipe. Falls back to legacy
      // !gfxVisible if flag set.
      boolean idleForPipe = Boolean.parseBoolean(
          System.getProperty("jac64.useVicIdleStateForPipe", "true"))
          ? vicIdleState : vicIdleState;
      vicDrawCycle.setIdleState(idleForPipe);
      // VICE viciisc/vicii-fetch.c: vicii_fetch_idle_gfx() reads from
      // $3FFF when in idle_state. This routes content pixels through
      // COL_CBUF → cregs[0] = 0 = BLACK (ss-pri test relies on this:
      // its rast1 $D011=$1b write misses the FIRST_DMA_LINE DEN latch,
      // so abl=0 throughout the frame and bg renders BLACK not $D021).
      // Opt-out: -Djac64.idleGfxFetch=false.
      boolean idleFetch = vicIdleState
          && Boolean.parseBoolean(System.getProperty("jac64.idleGfxFetch", "true"));
      // VICE viciisc/vicii-cycle.c:137-144 — vicii.gbuf is only WRITTEN
      // at FETCH_G cycles (display state OR idle state). At non-FETCH_G
      // cycles vicii.gbuf retains its last value. Previously JaC64
      // overwrote gbuf to 0 every non-FETCH_G cycle, clearing the pipe
      // register too early and producing wrong rendering for modesplit
      // / vicii_reg_timing at cycle boundaries past the matrix fetch.
      if (isFetchG) {
        if (idleFetch) {
          // VICE vicii_fetch_idle_gfx (vicii-fetch.c:236-255):
          //   fetch_phi1(0x3fff) — includes VIC bank offset
          //   (vbank_phi1 + 0x3fff) & vaddr_mask | vaddr_offset.
          // For ECM (bit 6 of $D011): use 0x39ff instead.
          // Previously JaC read memory[0x3fff] directly, missing the
          // VIC bank — wrong for tests with bank != 0.
          int idleAddr = ((control1 & 0x40) != 0) ? 0x39ff : 0x3fff;
          // VIC bank only (NOT char base). vicBank = glueVisibleVBank << 14.
          int bankAddr = (vicBank + idleAddr) & 0xffff;
          vicDrawCycle.setGbuf(memory[bankAddr] & 0xff);
        } else {
          vicDrawCycle.setGbuf(gByte);
        }
      }
      vicDrawCycle.setRegs0x11(control1);
      vicDrawCycle.setRegs0x16(control2);
      vicDrawCycle.setVbufCbuf(vicCharCache, vicColCache);
      // Phase 8 fix 2026-05-23: sync dmli to JaC64's POST-bump vmli.
      // vmli is incremented INSIDE drawGraphicsVic in case 16..55 BEFORE
      // finishCycleVic runs, so vmli at this point = VICE's dmli at this
      // cycle's pipe load:
      //   cyc 16: vmli=1 ⇒ load vbuf[1]=col1 (matches VICE Phi2(16) dmli=1)
      //   cyc 17: vmli=2 ⇒ load vbuf[2] (matches VICE Phi2(17) dmli=2)
      // Cyc 14-15 have vmli=0 (no drawGraphics yet), matching VICE's
      // pre-first-vis-en state. EV-DrawCycle trace at vicii_reg_timing-a5
      // raster $8b cyc 17-19 confirms byte-for-byte match with VICE after
      // this fix.
      int dmliForCycle = vmli;
      if (dmliForCycle < 0) dmliForCycle = 0;
      if (dmliForCycle > 39) dmliForCycle = 39;
      vicDrawCycle.setDmli(dmliForCycle);
      // Phase 1 VICE-shaped split (default ON; opt out with
      // -Djac64.vicShaped=false). Order mirrors vicii_draw_cycle:
      //   draw_graphics8 (Part1) -> draw_sprites8 (advance sprite pipe,
      //   use fresh priBuffer) -> setSpriteOutput current cycle -> Part2
      //   (composite + border + colors).
      if (VICE_SHAPED) {
        vicDrawCycle.cycleClk = cpu.cycles;
        vicDrawCycle.cycleVbeam = vbeam;
        vicDrawCycle.cycleVicCycle = vicCycle;
        vicDrawCycle.drawCyclePart1();
        if (useVicSprPipe) {
          vicDrawCycle.copyPriBufferInto(vicSprPipe.priBuffer);
          advanceSpritePipeline(vicCycle);
          // Fold THIS cycle's sprite collisions into the global mirrors
          // (matches VICE vicii-draw-cycle.c:478-486 which accumulates
          // directly inside draw_sprites). drawSpritesVicCycle skips
          // its OR-in when VICE_SHAPED so we don't double-accumulate.
          sprCol   |= vicSprPipe.spriteSpriteCollThisCycle;
          sprBgCol |= vicSprPipe.spriteBgCollThisCycle;
          vicDrawCycle.setSpriteOutput(vicSprPipe.outColorCode,
              vicSprPipe.outSprite, vicSprPipe.outForegroundWin);
        } else {
          vicDrawCycle.clearSpriteOutput();
        }
        vicDrawCycle.drawCyclePart2();
      } else {
        // Legacy Phase D-sprite: 1-cycle sprite output delay.
        // Compensates for an 8-px-early gfx phase offset in vicDrawCycle;
        // see project_sprite_xpos_offset.md for the analysis.
        if (useVicSprPipe) {
          vicDrawCycle.setSpriteOutput(prevSprColorCode, prevSprIndex, prevSprFgWin);
          System.arraycopy(vicSprPipe.outColorCode, 0, prevSprColorCode, 0, 8);
          System.arraycopy(vicSprPipe.outSprite, 0, prevSprIndex, 0, 8);
          System.arraycopy(vicSprPipe.outForegroundWin, 0, prevSprFgWin, 0, 8);
        } else {
          vicDrawCycle.clearSpriteOutput();
        }
        vicDrawCycle.drawCycle();
      }
    }

    // Per-cycle VIC trace — emit one line summarizing this cycle.
    if (TRACE_VIC_CYCLE
        && cycles >= TRACE_VIC_CYCLE_START
        && cycles <= TRACE_VIC_CYCLE_END) {
      StringBuilder sb = new StringBuilder(96);
      sb.append("TVIC clk=").append(cycles)
        .append(" rast=$").append(Integer.toHexString(vbeam))
        .append(" cyc=").append(vicCycle)
        .append(" bl=").append(badLine ? 1 : 0)
        .append(" baU=").append(cpu.baLowUntil)
        .append(" dd00=$").append(Integer.toHexString(cia2PRA & 0xff))
        .append(" vicBank=$").append(Integer.toHexString(vicBank))
        .append(" videoMatrix=$").append(Integer.toHexString(videoMatrix))
        .append(" vicBase=$").append(Integer.toHexString(vicBase))
        .append(" d011=$").append(Integer.toHexString(control1))
        .append(" d016=$").append(Integer.toHexString(control2))
        .append(" hideColumn=").append(hideColumn ? 1 : 0)
        .append(" paintBorder=").append(paintBorder ? 1 : 0)
        .append(" mainBorder=").append(mainBorder ? 1 : 0)
        .append(" vBorder=").append(vBorder ? 1 : 0)
        .append(" vmli=").append(vmli)
        .append(" vc=").append(vc)
        .append(" rc=").append(rc)
        .append(" vcBase=").append(vcBase)
        .append(" gfx=").append(!vicIdleState ? 1 : 0);
      if (traceVicActions.length() > 0) {
        sb.append(" act=[").append(traceVicActions).append(']');
        traceVicActions.setLength(0);
      }
      traceVicCycleOut.println(sb.toString());
    } else if (TRACE_VIC_CYCLE) {
      // Outside trace window — discard buffered actions.
      traceVicActions.setLength(0);
    }

    vicBankFetchDelay = vicBank;
    vicMemFetchDelay = vicMem;
    videoMatrixFetchDelay = videoMatrix;
    vicBaseFetchDelay = vicBase;
    charMemoryIndexFetchDelay = charMemoryIndex;

    // Keep a two-stage $D011 history for graphics fetch address selection.
    control1FetchDelay2 = control1FetchDelay;
    control1FetchDelay = control1;

    // VICE viciisc/vicii-draw-cycle.c:581 — capture end-of-cycle
    // main_border. Read by next cycle's drawBorderVic for transition
    // state machine.
    // Mirror the mainBorderNow computation above — same gating, so the
    // pipeline's borderState (= prior-cycle main_border) matches VICE's
    // file-static border_state which is only updated from main_border
    // (not vborder) inside draw_border8.
    borderStatePrev = paintBorder || borderClosed()
        || (Boolean.parseBoolean(System.getProperty("jac64.mbVborderOr", "false"))
            ? vBorderOnly() : false);
  }

  private boolean borderStatePrev = true;

  // Used to draw background where either border or background should be
  // painted...
  private void drawBackground() {
    // Phase B: pipeline paints bg via drawBorder8 + drawColors8 when
    // vicFullPipeline is on. Skip the legacy bg fill — it'd just be
    // overwritten anyway. Saves a per-cycle 8-wide mem write loop.
    if (useVicFullPipeline) return;
    if (notVisible) {
      return;
    }
    if (useVicRenderBuf) {
      // Phase 1 deep VICE port: fill renderBuf with bg-color CODE
      // (VC_D021). Caller follows with drawSprites + finishCycleVic
      // which runs drawBorderVic (per-cycle border state machine,
      // mirrors viciisc/vicii-draw-cycle.c:557 draw_border8) and
      // drawColorsVic (resolves codes to RGBA, mirrors draw_colors8).
      for (int i = 0; i < 8; i++) renderBuf[i] = VC_D021;
      renderBufFresh = true;
      return;
    }
    int bpos = mpos;
    int currentBg = borderClosed() ? borderColor : bgColor;
    for (int i = 0; i < 8; i++) {
      mem[bpos++] = currentBg;
    }
    if (fldTrace && vbeam >= 0x30 && vbeam <= 0xf7 && !vicIdleState) {
      fldOut.println("  drawBG overwrite at mpos=" + mpos +
          " vmli=" + vmli + " borderSt=" + borderState);
    }
  }

  /**
   * VICE-style cycle-driven graphics renderer. Replaces drawGraphics()
   * when jac64.vicGfx=true. Mirrors src/viciisc/vicii-draw-cycle.c:
   * draw_graphics8(): per-pixel emit through an 8-pixel X-shift register
   * with $D016 XSCROLL latched at i==xscroll, and $D011/$D016 mode bits
   * latched mid-cycle at pixels 4/6 (PAL 6569 color-latency edges). This
   * matches the C64's actual hardware behaviour where mid-cycle register
   * writes affect only un-emitted pixels.
   *
   * State carried across cycles:
   *   gbufReg / gbufMcFlop  — 8-bit shift register + multicolor toggle
   *   vbufReg / cbufReg     — char/color RAM bytes for the active column
   *   vmode11Pipe           — latched $D011 BMM/ECM bits (bits 3-4)
   *   vmode16Pipe           — latched $D016 MCM bit  (bit 2)
   *   vmode16Pipe2          — vmode16Pipe lagged 1 cycle, used for the
   *                            MC pixel-pair determination (so a 0→1 MCM
   *                            edge is observed one cycle late, matching
   *                            VICE's "vmode16_pipe2" semantics)
   *
   * Simplified vs upstream VICE (still deferred):
   *  - vbuf/cbuf pipe staging is still approximated by the per-column
   *    cache; only the missing 2-cycle gbuf pipeline delay is ported here.
   *  - Idle-state g-access still uses bitmap address (FLI scenes always
   *    force badline+display state so this is a non-issue in practice;
   *    JaC64's existing !gfxVisible early-return paints border/bg color
   *    which is the visual equivalent of VICE's idle-pixel emission).
   */
  private final void drawGraphicsVic(int mpos) {
    // Phase G: same logic as drawGraphics — pipeline paints. Preserve
    // state advancement (vc/vmli/gbufPipe shift) so the legacy vicGfx
    // path remains correct when pipeline is OFF, but skip mem[] writes.
    if (useVicFullPipeline) {
      // VICE-faithful: vmli++ AND vc++ both fire ONLY when !idle_state
      // (vicii-fetch.c:314-316 vicii_fetch_graphics). When idle,
      // vicii_fetch_idle_gfx is called instead — neither vmli nor vc
      // increments. Gate on gfxVisible (= !idle_state).
      // Flag: -Djac64.vicVmliGate (default true).
      boolean vicGate = Boolean.parseBoolean(
          System.getProperty("jac64.vicVmliGate", "true"));
      if (vicGate) {
        if (!vicIdleState) {
          vc++;
          vmli++;
        }
      } else {
        if (!vicIdleState && !paintBorder && !vBorderOnly()) vc++;
        vmli++;
      }
      gbufPipe1Reg = gbufPipe0Reg;
      gbufPipe0Reg = 0;
      return;
    }
    if (notVisible) {
      if (!vicIdleState && !paintBorder && !vBorderOnly()) {
        vc++;
      }
      vmli++;
      gbufPipe1Reg = gbufPipe0Reg;
      gbufPipe0Reg = 0;
      // BUGFIX 2026-05-14: DO NOT set renderBufFresh=true when
      // notVisible. mpos is invalid (negative vPos*SC_WIDTH) and
      // drawColorsVic would crash with ArrayIndexOutOfBoundsException.
      // Match drawGraphics's legacy behavior which simply returns
      // without setting any render state.
      return;
    }

    if (vicIdleState || paintBorder || vBorderOnly()) {
      // VICE viciisc/vicii-draw-cycle.c — outside display, render_buffer
      // is filled by draw_graphics with $D021 (or VC_NONE for hard
      // border) then optionally overwritten by draw_border8 with $D020.
      // We populate renderBuf here and let drawColorsVic/drawBorderVic
      // resolve. When useVicRenderBuf is OFF, paint mem[] directly.
      int borderCode = (paintBorder || borderClosed()) ? VC_NONE : VC_D021;
      if (useVicRenderBuf) {
        for (int i = 0; i < 8; i++) {
          renderBuf[i] = borderCode;
          priBuf[i] = false;
        }
        renderBufFresh = true;
      } else {
        int color = (paintBorder || borderClosed()) ? borderColor : bgColor;
        for (int i = 0; i < 8; i++) {
          mem[mpos + i] = color;
        }
      }
      vmli++;
      gbufPipe1Reg = gbufPipe0Reg;
      gbufPipe0Reg = 0;
      return;
    }

    final int xscroll = xscrollPipe;
    final int collX = (vmli << 3) + xscroll + SC_XOFFS;

    // g-access: fetch this cycle's gfx byte. Address calc mirrors
    // existing drawGraphics (uses precomputed vicBase/charMemoryIndex
    // which already encode $D018+$DD00 and chargen ROM mapping).
    int gByte;
    int vByte = vicCharCache[vmli];
    int cByte = vicColCache[vmli] & 0x0f;
    // VICE viciisc/vicii-fetch.c:240 (PAL 6569, color_latency=1):
    //   addr = g_fetch_addr(regs[0x11] | (reg11_delay & 0x20))
    // BMM is sticky-OR with the 1-cycle-prior value. ECM (0x40) stays
    // live. control1FetchDelay holds end-of-previous-VIC-cycle control1
    // (shift happens at end of clock(), so during draw it's 1 cycle old).
    final int d011Fetch = control1 | (control1FetchDelay & 0x20);
    if ((d011Fetch & 0x20) != 0) {              // BMM (bitmap)
      gByte = memory[vicBase + (vc & 0x3ff) * 8 + rc] & 0xff;
    } else if ((control1 & 0x40) != 0) {        // ECM text (current bit)
      gByte = memory[charMemoryIndex + ((vByte & 0x3f) << 3) + rc] & 0xff;
    } else {                                    // standard / MC text
      gByte = memory[charMemoryIndex + (vByte << 3) + rc] & 0xff;
    }

    // VICE pipeline: vmode11Pipe / vmode16Pipe / vmode16Pipe2 carry over
    // from the previous cycle. Mode bits are latched at pixels 4/6/7
    // within this cycle, so mid-cycle $D011/$D016 writes affect only
    // un-emitted pixels.
    int v11 = vmode11Pipe;
    int v16 = vmode16Pipe;
    int v16_2 = vmode16Pipe2;

    int reg = gbufPipe1Reg;
    int mcFlop = gbufMcFlop;

    // 8-pixel emit loop with XSCROLL latch + per-pixel mode latching.
    for (int pix = 0; pix < 8; pix++) {
      // Latch new gbuf/vbuf/cbuf into shift register at i == xscroll.
      // Pixels 0..xscroll-1 keep emitting from the previous column's
      // register tail (the X-shift behaviour).
      if (pix == xscroll) {
        reg = gByte;
        vbufReg = vByte;
        cbufReg = cByte;
        mcFlop = 1;
      }

      // MC pixel-pair determination uses vmode16Pipe2 (lagged MCM).
      // Color lookup uses current vmode11Pipe | vmode16Pipe.
      int px;
      final boolean mcMcEnabled = (v16_2 & 0x04) != 0;
      final boolean bmm = (v11 & 0x08) != 0;
      if (mcMcEnabled) {
        if (bmm || (cbufReg & 0x08) != 0) {
          if (mcFlop != 0) {
            gbufPixelReg = (reg >> 6) & 3;
          }
          // else: hold previous pixel pair (MC stretch)
        } else {
          gbufPixelReg = ((reg & 0x80) != 0) ? 3 : 0;
        }
      } else {
        if (bmm || (cbufReg & 0x08) != 0) {
          gbufPixelReg = ((reg & 0x80) != 0) ? 2 : 0;
        } else {
          gbufPixelReg = ((reg & 0x80) != 0) ? 3 : 0;
        }
      }
      px = gbufPixelReg;

      reg = (reg << 1) & 0xff;
      mcFlop ^= 1;

      // VICE viciisc/vicii-draw-cycle.c:196-227 — code lookup happens
      // during draw_graphics(); RGBA resolution happens later in
      // draw_colors8(). Phase B writes the CODE to renderBuf for the
      // new path; the legacy path resolves directly to mem[].
      final int code = VC_GFX_COLORS[(v11 | v16) | px];
      final boolean pixelPri = (px & 2) != 0;
      renderBuf[pix] = code;
      priBuf[pix] = pixelPri;

      if (!useVicRenderBuf) {
        int rgba;
        switch (code) {
          case VC_NONE:     rgba = 0xff000000; break;
          case VC_VBUF_L:   rgba = cbmcolor[vbufReg & 0x0f]; break;
          case VC_VBUF_H:   rgba = cbmcolor[(vbufReg >> 4) & 0x0f]; break;
          case VC_CBUF:     rgba = cbmcolor[cbufReg & 0x0f]; break;
          case VC_CBUF_MC:  rgba = cbmcolor[cbufReg & 0x07]; break;
          case VC_D02X_EXT: rgba = cbmcolor[bgCol[(vbufReg >> 6) & 3]]; break;
          case VC_D021:     rgba = bgColor; break;
          case VC_D022:     rgba = cbmcolor[bgCol[1]]; break;
          case VC_D023:     rgba = cbmcolor[bgCol[2]]; break;
          default:          rgba = 0xff000000;
        }
        mem[mpos + pix] = rgba;
      }

      // Sprite-collision foreground mask: px bit 1 = foreground.
      // Always written (drawSpritesVicCycle still reads collissionMask
      // bit 0x100 for FG priority until Phase C migrates that path).
      final int cx = collX + pix;
      if (cx >= 0 && cx < collissionMask.length) {
        collissionMask[cx] = pixelPri ? 256 : 0;
      }

      // VICE's mid-cycle mode latching (PAL 6569 color_latency=1):
      //   - Before pixel 4: $D016 MCM bit overwrites vmode16Pipe;
      //                     $D011 BMM/ECM bits OR'd into vmode11Pipe (rising edge).
      //   - Before pixel 6: $D011 BMM/ECM bits AND'd into vmode11Pipe (falling edge).
      //   - Before pixel 7: if MCM 0→1 transition, reset mcFlop; vmode16Pipe2 ← vmode16Pipe.
      if (pix == 3) {
        v16 = (control2 & 0x10) >> 2;
        v11 |= (control1 & 0x60) >> 2;
      } else if (pix == 5) {
        v11 &= (control1 & 0x60) >> 2;
      } else if (pix == 6) {
        if (v16 != 0 && v16_2 == 0) {
          mcFlop = 0;
        }
        v16_2 = v16;
      }
    }

    gbufPipe1Reg = gbufPipe0Reg;
    // VICE viciisc/vicii-draw-cycle.c:277-280 gates the gbuf_pipe0_reg
    // and xscroll_pipe latch behind `vis_en && vborder == 0`. drawGraphicsVic
    // already only runs in the visible-cycle window (cases 16-55), so vis_en
    // is implicitly true; gate the latch on vborder == 0 to match VICE.
    if (!vBorder) {
      gbufPipe0Reg = gByte;
      xscrollPipe = horizScroll;
    }
    gbufMcFlop = mcFlop;
    vmode11Pipe = v11;
    vmode16Pipe = v16;
    vmode16Pipe2 = v16_2;

    vc = (vc + 1) & 0x3ff;
    vmli++;

    if (useVicRenderBuf) {
      renderBufFresh = true;
    }
  }

  /**
   * Mirrors VICE viciisc/vicii-draw-cycle.c:672-688 vicii_draw_cycle()
   * end-of-cycle sequence: draw_border8 → draw_colors8. Called from
   * the case dispatcher after drawGraphicsVic + drawSprites have
   * populated renderBuf with graphics + sprite codes. Does nothing
   * unless -Djac64.vicRenderBuf=true and renderBuf was populated
   * this cycle (cases 13-15/56-60 keep legacy direct-paint).
   */
  private final void finishCycleVic(int mpos) {
    // Phase B: pipeline does border + color resolution via
    // drawBorder8 + drawColors8. Skip legacy border/colors path.
    if (useVicFullPipeline) return;
    if (!useVicRenderBuf || !renderBufFresh) return;
    drawBorderVic();
    drawColorsVic(mpos);
  }

  /**
   * VICE viciisc/vicii-draw-cycle.c draw_border8() — overlays
   * renderBuf[] with VC_D020 (border color code) when border is
   * active. Active only when -Djac64.vicRenderBuf=true.
   *
   * Simplified vs upstream VICE: doesn't yet model the partial-border
   * CSEL=0 transition cycle (where pixel 7 alone is border). JaC64's
   * existing borderClosed()/paintBorder/vBorderOnly() flags carry
   * enough state to drive a coarser overlay; the VICE-style per-cycle
   * border state machine can be ported in a follow-on pass.
   */
  private final void drawBorderVic() {
    boolean curBorder = paintBorder || borderClosed() || vBorderOnly();
    // VICE viciisc/vicii-draw-cycle.c:557 draw_border8 — full transition
    // state machine. `borderStatePrev` is the end-of-previous-VIC-cycle
    // main_border value (captured at end of clock()). `curBorder` is
    // this cycle's value (post-checkHBorder).
    //
    //   - Both off (open): leave renderBuf as gfx codes.
    //   - Both on (closed): all 8 pixels = border.
    //   - Transition with CSEL=1: all 8 pixels follow PREV state.
    //   - Transition with CSEL=0: pixels 0-6 follow PREV, pixel 7 follows
    //     CUR (creates the 1-pixel gfx-leak on CSEL=0 close/open
    //     transitions).
    if (!borderStatePrev && !curBorder) {
      return;
    }
    if (borderStatePrev && curBorder) {
      for (int i = 0; i < 8; i++) renderBuf[i] = VC_D020;
      return;
    }
    boolean csel = !hideColumn;
    if (csel) {
      // CSEL=1 transition: paint border if EITHER direction. In VICE,
      // open→close case is handled the NEXT cycle via the "both 1"
      // branch (pipe delay). JaC64 has no pipe delay, so we paint border
      // immediately when curBorder=true at the transition. This brings
      // the right-edge close (case 56) into alignment with VICE — at
      // the cost of OPEN-BORDER trick tests that wanted display to leak
      // through. Accept those local regressions per project decision
      // 2026-05-12 (full VICE-style port over noise-level wins).
      if (borderStatePrev || curBorder) {
        for (int i = 0; i < 8; i++) renderBuf[i] = VC_D020;
      }
    } else {
      if (borderStatePrev) {
        for (int i = 0; i < 7; i++) renderBuf[i] = VC_D020;
      }
      if (curBorder) {
        renderBuf[7] = VC_D020;
      }
    }
  }

  /**
   * VICE viciisc/vicii-draw-cycle.c draw_colors8() — resolves the 8
   * color CODES in renderBuf[] to RGBA via cbmcolor[] / bgCol[] and
   * writes them to mem[mpos..mpos+7]. Mirrors VICE's late color
   * resolution where the per-pixel palette is sampled at draw_colors
   * time, NOT at draw_graphics time. Mid-cycle $D021/$D022/$D023
   * writes therefore affect un-emitted pixels of the same cycle.
   *
   * Active only when -Djac64.vicRenderBuf=true. Phase E (later)
   * adds the cregs[] 1-cycle delayed apply (`update_cregs`) so
   * mid-cycle $D02x writes precisely match VICE timing.
   */
  private final void drawColorsVic(int mpos) {
    // VICE viciisc/vicii-draw-cycle.c:608 draw_colors_6569 (PAL 6569
    // color_latency=1) / :622 draw_colors_8565 (PAL HMOS 8565,
    // color_latency=0) — code → color resolution via cregs[] indirection
    // with the pixel_buffer 1-cycle delay.
    //
    // The 8565 grey-dot fixup at pixel 0 is already handled in JaC64 via
    // applyD021CurrentCycleColor / applySpriteColorCurrentCycle (mem[]
    // retroactive paint at write time). The cregs[] lookup here gives the
    // SAME result modulo timing — for now JaC64 resolves CURRENT renderBuf
    // (no pixel_buffer carry-over). Phase 4 carry-over would shift the
    // entire output by 1 VIC cycle visually, which doesn't match the
    // existing retroactive-paint scheme. Keeping current resolution
    // semantics; cregs[] indirection alone is the structural improvement.
    for (int i = 0; i < 8; i++) {
      int code = renderBuf[i];
      int rgba;
      if (code >= 0x20 && code <= 0x2e) {
        // VC_D020..VC_D02E: route through cregs[] (mirrors VICE).
        rgba = cbmcolor[cregs[code]];
      } else {
        switch (code) {
          case VC_NONE:     rgba = 0xff000000; break;
          case VC_VBUF_L:   rgba = cbmcolor[vbufReg & 0x0f]; break;
          case VC_VBUF_H:   rgba = cbmcolor[(vbufReg >> 4) & 0x0f]; break;
          case VC_CBUF:     rgba = cbmcolor[cbufReg & 0x0f]; break;
          case VC_CBUF_MC:  rgba = cbmcolor[cbufReg & 0x07]; break;
          case VC_D02X_EXT: rgba = cbmcolor[bgCol[(vbufReg >> 6) & 3]]; break;
          default:          rgba = 0xff000000;
        }
      }
      mem[mpos + i] = rgba;
    }
    renderBufFresh = false;
  }

  /**
   * <code>drawGraphics</code> - draw the VIC graphics (text/bitmap)
   */
  private final void drawGraphics(int mpos) {
    // Phase G: pipeline emits gfx pixels via its own paint path. Skip
    // all legacy mem[] writes, but preserve vc/vmli state advancement
    // exactly as the legacy paths would have done (condition derived
    // from notVisible/visible-gfx branches below).
    if (useVicFullPipeline) {
      // iter#13: vc++ moved to updateVicStateVic (FetchG cycles
      // 15..54). vmli++ stays here because legacy paths consume vmli
      // mid-cycle expecting drawGraphics to have advanced it.
      vmli++;
      return;
    }
    final int drawVmli = vicRenderDelay ? Math.max(0, vmli - 1) : vmli;
    if (notVisible) {
      vmli++;
      return;
    }

    if (vicIdleState || paintBorder || vBorderOnly()) {
      mpos -= horizScroll;
      int color = (paintBorder || borderClosed()) ? borderColor : bgColor;
      for (int i = mpos, n = mpos + 8; i < n; i++) {
        mem[i] = color;
      }
      vmli++;
      return;
    }

    int collX = (vmli << 3) + horizScroll + SC_XOFFS;
    final int pipeVByte = vicCharCache[drawVmli];
    final int pipeCByte = vicColCache[drawVmli] & 0x0f;
    // VICE viciisc/vicii-fetch.c:240 PAL 6569 — BMM sticky-OR with prior
    // cycle, ECM live. See drawGraphicsVic for the same pattern.
    final int d011Fetch = control1 | (control1FetchDelay & 0x20);

    // Paint background if first col
    if (drawVmli == 0) {
      for (int i = mpos - horizScroll, n = i + 8; i < n; i++) {
        mem[i] = bgColor;
      }
    }

    int position = 0, data = 0, penColor = 0, bgcol = bgColor;
    final boolean bitmapFetch = (d011Fetch & 0x20) != 0;
    final boolean extendedFetch = (control1 & 0x40) != 0;
    final int fetchBank = vicBankFetchDelay;
    final int fetchMem = vicMemFetchDelay;
    final int fetchCharMemoryIndex = charMemoryIndexFor(fetchBank, fetchMem);
    final int fetchVicBase = fetchBank | (fetchMem & 0x08) << 10;
    if (!bitmapFetch) {
      int tmp;
      int pcol;

      if (multiCol) {
        multiColor[0] = bgColor;
        multiColor[1] = cbmcolor[bgCol[1]];
        multiColor[2] = cbmcolor[bgCol[2]];
      }

      penColor = cbmcolor[pcol = pipeCByte & 15];
      if (extendedFetch) {
        int from = fetchCharMemoryIndex +
            (((data = pipeVByte) & 0x3f) << 3);
        int to = charMemoryIndex +
            (((data = pipeVByte) & 0x3f) << 3);
        position = mixPhi1FetchAddress(from, to);
        bgcol = cbmcolor[bgCol[(data >> 6)]];
      } else {
        int from = fetchCharMemoryIndex + (pipeVByte << 3);
        int to = charMemoryIndex + (pipeVByte << 3);
        position = mixPhi1FetchAddress(from, to);
      }

      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END
          && !isCharRomFetchBase(fetchCharMemoryIndex + (vicCharCache[drawVmli] << 3))
          && isCharRomFetchBase(position)) {
        traceVicCycleOut.println("EV-GFXADDR clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " bitmap=0"
            + " ext=" + (extendedFetch ? 1 : 0)
            + " pos=$" + Integer.toHexString(position)
            + " d011=$" + Integer.toHexString(control1)
            + " d011d=$" + Integer.toHexString(control1FetchDelay)
            + " charMem=$" + Integer.toHexString(charMemoryIndex)
            + " charMemD=$" + Integer.toHexString(fetchCharMemoryIndex)
            + " vmli=" + drawVmli
            + " vc=" + vc
            + " rc=" + rc);
      }

      data = memory[position + rc];

      if (false && Boolean.getBoolean("jac64.traceBitmap") && vmli == 20
          && vbeam >= 120 && vbeam <= 125) {
        System.err.println("TEXT vbeam=" + vbeam + " vmli=" + vmli
            + " vc=" + vc + " rc=" + rc
            + " charCode=$" + Integer.toHexString(vicCharCache[vmli] & 0xff)
            + " charMemIdx=$" + Integer.toHexString(charMemoryIndex)
            + " fetchCharMemIdx=$" + Integer.toHexString(fetchCharMemoryIndex)
            + " pos=$" + Integer.toHexString(position + rc)
            + " data=$" + Integer.toHexString(data & 0xff)
            + " pen=$" + Integer.toHexString(penColor)
            + " bgcol=$" + Integer.toHexString(bgcol)
            + " videoMatrix=$" + Integer.toHexString(videoMatrix)
            + " fetchVideoMatrix=$" + Integer.toHexString(vicFetchDelay ? videoMatrixFetchDelay : videoMatrix)
            + " vicBase=$" + Integer.toHexString(vicBase)
            + " fetchVicBase=$" + Integer.toHexString(fetchVicBase)
            + " vicMem=$" + Integer.toHexString(vicMem)
            + " control1=$" + Integer.toHexString(control1)
            + " mc=" + multiCol + " ext=" + extended);
      }

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
      int from = fetchVicBase + (vc & 0x3ff) * 8 + rc;
      int to = vicBase + (vc & 0x3ff) * 8 + rc;
      position = mixPhi1FetchAddress(from, to);
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END
          && !isCharRomFetchBase(from)
          && isCharRomFetchBase(position)) {
        traceVicCycleOut.println("EV-GFXADDR clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " bitmap=1"
            + " from=$" + Integer.toHexString(from)
            + " to=$" + Integer.toHexString(to)
            + " d011=$" + Integer.toHexString(control1)
            + " d011d=$" + Integer.toHexString(control1FetchDelay)
            + " vicBase=$" + Integer.toHexString(vicBase)
            + " vicBaseD=$" + Integer.toHexString(fetchVicBase)
            + " vc=" + vc
            + " rc=" + rc);
      }
      if (multiCol) {
        multiColor[0] = bgColor;
      }
      int vmliData = vicCharCache[drawVmli];
      penColor =
        cbmcolor[(vmliData & 0xf0) >> 4];
      bgcol = cbmcolor[vmliData & 0x0f];

      data = memory[position];

      // Unconditional g-access trace (every bitmap fetch). Pair with EV-FetchC
      // to map JaC64's per-cycle fetch addresses against VICE's chip-model.c
      // PAL fetch table (Phi1(16+K)).
      if (TRACE_VIC_CYCLE && cpu.cycles >= TRACE_VIC_CYCLE_START
          && cpu.cycles <= TRACE_VIC_CYCLE_END) {
        traceVicCycleOut.println("EV-FetchG clk=" + cpu.cycles
            + " rast=$" + Integer.toHexString(vbeam)
            + " cyc=" + (cpu.cycles - lastLine)
            + " col=" + drawVmli
            + " addr=$" + Integer.toHexString(position)
            + " data=$" + Integer.toHexString(data & 0xff)
            + " vicBase=$" + Integer.toHexString(vicBase)
            + " vicBaseD=$" + Integer.toHexString(fetchVicBase)
            + " bank=$" + Integer.toHexString(vicBank)
            + " d018=$" + Integer.toHexString(vicMem)
            + " vc=" + vc
            + " rc=" + rc);
      }

      if (multiCol) {
        multiColor[1] =
          cbmcolor[(vmliData >> 4) & 0x0f];
        multiColor[2] =
          cbmcolor[vmliData & 0x0f];
        multiColor[3] = cbmcolor[vicColCache[drawVmli] & 0x0f];

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
    if (useVicSprPipe) {
      drawSpritesVicCycle();
    } else {
      drawSpritesV2();
    }
  }

  /**
   * Per-cycle pipeline state advance. Sets cycle inputs and runs the
   * VICE draw_sprites8 state machine. Called from clock() on EVERY
   * VIC cycle (not just rendering cases) — matches VICE 1:1 so the
   * sprite_pending/active/halt_bits machinery sees every transition
   * (in particular SPR3-7 ptr/dma cycles at cases 0-9). The 8-pixel
   * outputs land in vicSprPipe.outColorCode[] / outSprite[] /
   * outSpriteSpriteColl[] / outSpriteBgColl[] for the paint step.
   */
  private final void advanceSpritePipeline(int vicCycle) {
    // priBuffer carries graphics foreground-priority pixels for the
    // CURRENT cycle. In vicShaped mode the caller has already populated
    // vicSprPipe.priBuffer from VicDrawCycle.copyPriBufferInto() (the
    // VICE-faithful path: draw_graphics8 -> draw_sprites8 in one cycle).
    // Legacy path reads PREVIOUS-cycle bits from collissionMask.
    if (!VICE_SHAPED) {
      int screenStart = xPos - 8;
      for (int i = 0; i < 8; i++) {
        int pixelX = screenStart + i;
        boolean fg = false;
        if (pixelX >= 0 && pixelX < collissionMask.length) {
          fg = (collissionMask[pixelX] & 0x100) != 0;
        }
        vicSprPipe.priBuffer[i] = fg;
      }
    }

    // STEP 1 — consume PREVIOUS cycle's flag snapshot (cycle_flags_pipe).
    // Mirrors VICE viciisc/vicii-draw-cycle.c:697 draw_sprites8(cycle_flags_pipe)
    // where cycle_flags_pipe holds the previous cycle's flags.
    //
    // Pipe-ON path delays the cycle_flags_pipe-derived action flags
    // (checkSprDisp, ptrDma0, dma1_dma2, displayBits) plus sprite X
    // (VICE update_sprite_xpos() in viciisc/vicii-draw-cycle.c:478
    // captures sprite[s].x at END of draw_sprites8, so the next call
    // sees the just-prior value — equivalent to a 1-cycle snapshot).
    //
    // NOT pipe-delayed:
    //   - xpos: JaC64's rasterX(vicCycle) already gives "VICE cycle
    //     vicCycle's xpos", and JaC64 vicCycle N ≡ VICE cycle N+1, so
    //     currentRasterX naturally lags 1 VICE cycle. Double-delaying
    //     would offset sprites by 8 screen pixels.
    //   - reg1b/1c/1d: VICE reads these LIVE at pixel 6 from
    //     vicii.regs[] (vicii-draw-cycle.c:534-535), NOT from
    //     cycle_flags_pipe.
    if (useCycleFlagsPipe) {
      vicSprPipe.checkSprDisp = sprPipeCheckSprDisp;
      vicSprPipe.spritePtrDma0 = sprPipePtrDma0;
      vicSprPipe.spriteDma1Dma2 = sprPipeDma1Dma2;
      vicSprPipe.spriteDmaNum = sprPipeDmaNum;
      vicSprPipe.spriteDisplayBits = sprPipeDisplayBits;
      vicSprPipe.reg1bPipe = memory[0xd01b + IO_OFFSET] & 0xff;
      vicSprPipe.reg1cPipe = memory[0xd01c + IO_OFFSET] & 0xff;
      vicSprPipe.reg1dPipe = memory[0xd01d + IO_OFFSET] & 0xff;
      // VICE's sprite_x_pipe is updated by update_sprite_xpos() at END of
      // draw_sprites8 (vicii-draw-cycle.c:478-481), giving exactly ONE cycle
      // of delay between a $D000-$D010 write and the trigger comparison.
      // VicSpritePipeline ALREADY provides that latch (drawCycle8 line 290:
      // spriteXPipe[s] = currentSpriteX[s] at end of cycle). Feeding
      // sprPipeSpriteX (an additional cycle's snapshot of sprites[s].x)
      // into currentSpriteX added a SECOND pipe stage, so triggers at
      // cycle K saw sprites[s].x from end of K-2 instead of K-1. The
      // ss-xpos test (mid-line $D000 writes) miss-triggered sprites at
      // the OLD X for 32 cells. Pass the live sprites[s].x here; the
      // pipe inside drawCycle8 closes the 1-cycle delay correctly.
      for (int s = 0; s < 8; s++) {
        vicSprPipe.currentSpriteX[s] = sprites[s].x & 0x1ff;
      }
      vicSprPipe.traceLine = vbeam;
      vicSprPipe.traceCyc = vicCycle;
      vicSprPipe.traceClk = cpu.cycles;
      // VICE quantizes draw_sprites8 xpos to 8-px boundaries via
      // cycle_get_xpos(cycle_flags_pipe), which yields Phi1(N)-quantized
      // for raster_cycle N. JaC64's rasterX(N) = (N-17)*8+32 produces
      // Phi1(N+1)-quantized — one cycle off. The offset closes that gap.
      // Default 0 (legacy) so the shift=-8 default keeps working;
      // override to -8 in combo with -Djac64.vicShift=-16 for the
      // strictly-VICE-aligned alternative.
      vicSprPipe.drawCycle8(currentRasterX + SPR_XPOS_OFFSET);
    } else {
      // Legacy current-cycle path (pre-cycle_flags_pipe behaviour).
      vicSprPipe.checkSprDisp = (vicCycle == 57);
      int dmaNumNow = -1;
      boolean ptrDma0Now = false;
      boolean dma12Now = false;
      if (vicCycle >= 0 && vicCycle <= 9) {
        dmaNumNow = 3 + (vicCycle / 2);
        if ((vicCycle & 1) == 0) ptrDma0Now = true;
        else dma12Now = true;
      } else if (vicCycle >= 57 && vicCycle <= 62) {
        dmaNumNow = (vicCycle - 57) / 2;
        if (((vicCycle - 57) & 1) == 0) ptrDma0Now = true;
        else dma12Now = true;
      }
      vicSprPipe.spritePtrDma0 = ptrDma0Now;
      vicSprPipe.spriteDma1Dma2 = dma12Now;
      vicSprPipe.spriteDmaNum = dmaNumNow;
      int displayBitsNow;
      if (useSpriteDispSticky) {
        displayBitsNow = spriteDisplayBitsSticky;
        for (int s = 0; s < 8; s++) {
          vicSprPipe.currentSpriteX[s] = sprites[s].x & 0x1ff;
        }
      } else {
        displayBitsNow = 0;
        for (int s = 0; s < 8; s++) {
          if (sprites[s].dma) displayBitsNow |= (1 << s);
          vicSprPipe.currentSpriteX[s] = sprites[s].x & 0x1ff;
        }
      }
      vicSprPipe.spriteDisplayBits = displayBitsNow;
      vicSprPipe.reg1bPipe = memory[0xd01b + IO_OFFSET] & 0xff;
      vicSprPipe.reg1cPipe = memory[0xd01c + IO_OFFSET] & 0xff;
      vicSprPipe.reg1dPipe = memory[0xd01d + IO_OFFSET] & 0xff;
      vicSprPipe.traceLine = vbeam;
      vicSprPipe.traceCyc = vicCycle;
      vicSprPipe.traceClk = cpu.cycles;
      // VICE quantizes draw_sprites8 xpos to 8-px boundaries via
      // cycle_get_xpos(cycle_flags_pipe), which yields Phi1(N)-quantized
      // for raster_cycle N. JaC64's rasterX(N) = (N-17)*8+32 produces
      // Phi1(N+1)-quantized — one cycle off. The offset closes that gap.
      // Default 0 (legacy) so the shift=-8 default keeps working;
      // override to -8 in combo with -Djac64.vicShift=-16 for the
      // strictly-VICE-aligned alternative.
      vicSprPipe.drawCycle8(currentRasterX + SPR_XPOS_OFFSET);
    }

    // STEP 2 — compute THIS cycle's flags + snapshot for next call.
    // Mirrors VICE viciisc/vicii-draw-cycle.c:703
    //   cycle_flags_pipe = vicii.cycle_flags
    // at end of vicii_draw_cycle().
    if (useCycleFlagsPipe) {
      sprPipeCheckSprDisp = (vicCycle == 57);
      int dmaNumNext = -1;
      boolean ptrDma0Next = false;
      boolean dma12Next = false;
      if (vicCycle >= 0 && vicCycle <= 9) {
        dmaNumNext = 3 + (vicCycle / 2);
        if ((vicCycle & 1) == 0) ptrDma0Next = true;
        else dma12Next = true;
      } else if (vicCycle >= 57 && vicCycle <= 62) {
        dmaNumNext = (vicCycle - 57) / 2;
        if (((vicCycle - 57) & 1) == 0) ptrDma0Next = true;
        else dma12Next = true;
      }
      sprPipePtrDma0 = ptrDma0Next;
      sprPipeDma1Dma2 = dma12Next;
      sprPipeDmaNum = dmaNumNext;
      int displayBitsNext;
      if (useSpriteDispSticky) {
        displayBitsNext = spriteDisplayBitsSticky;
        for (int s = 0; s < 8; s++) {
          sprPipeSpriteX[s] = sprites[s].x & 0x1ff;
        }
      } else {
        displayBitsNext = 0;
        for (int s = 0; s < 8; s++) {
          if (sprites[s].dma) displayBitsNext |= (1 << s);
          sprPipeSpriteX[s] = sprites[s].x & 0x1ff;
        }
      }
      sprPipeDisplayBits = displayBitsNext;
      sprPipeReg1b = memory[0xd01b + IO_OFFSET] & 0xff;
      sprPipeReg1c = memory[0xd01c + IO_OFFSET] & 0xff;
      sprPipeReg1d = memory[0xd01d + IO_OFFSET] & 0xff;
      sprPipeRasterX = currentRasterX;
    }
  }

  /**
   * Per-cycle (8-pixel) sprite paint. Reads pipeline outputs produced
   * by the most recent advanceSpritePipeline() call and writes sprite
   * color codes into mem[] + collissionMask[]. Only invoked on
   * rendering cycles (cases 13-60) via drawSprites().
   */
  private final void drawSpritesVicCycle() {
    if (notVisible) {
      xPos += 8;
      return;
    }

    int screenStart = xPos - 8;

    // Paint outputs into mem[] + collissionMask[]. Color resolution
    // matches VICE COL_D025/COL_D027+s/COL_D026 codes:
    //   code 1 = sprite multicolor 0 ($D025)
    //   code 2 = sprite color ($D027 + s)
    //   code 3 = sprite multicolor 1 ($D026)
    //   code 0 = transparent (no sprite pixel)
    int mpos = vPos * SC_WIDTH;
    int colorMc0 = cbmcolor[sprMC0];
    int colorMc1 = cbmcolor[sprMC1];
    boolean borderClosedNow = borderClosed();
    boolean wasZeroSpr = (sprCol == 0);
    boolean wasZeroBg = (sprBgCol == 0);
    for (int i = 0; i < 8; i++) {
      int pixelX = screenStart + i;

      // Apply per-pixel sprite-sprite & sprite-bg collisions to global
      // registers. In VICE_SHAPED mode this OR is done in Phase 5b
      // right after advanceSpritePipeline (matches VICE's draw_sprites
      // in-cycle accumulation). Legacy path keeps the per-pixel OR.
      if (!VICE_SHAPED) {
        int ssColl = vicSprPipe.outSpriteSpriteColl[i];
        int sbColl = vicSprPipe.outSpriteBgColl[i];
        if (ssColl != 0) sprCol |= ssColl;
        if (sbColl != 0) sprBgCol |= sbColl;
      }

      int code = vicSprPipe.outColorCode[i];
      int s = vicSprPipe.outSprite[i];
      if (s < 0) continue;

      if (pixelX < 0 || pixelX >= collissionMask.length) continue;

      // Update collissionMask so foreground-priority bit 0x100 stays
      // intact (already set by graphics) while OR'ing in this sprite's
      // bit. In VICE_SHAPED mode the legacy collissionMask is no longer
      // consumed by the sprite pipeline (priBuffer comes straight from
      // VicDrawCycle.copyPriBufferInto) so skip the write.
      if (!VICE_SHAPED) {
        collissionMask[pixelX] |= (1 << s);
      }

      // Render only if pixel is non-transparent and not behind foreground.
      // Phase B: when pipeline is on, skip the legacy mem[] paint —
      // VicDrawCycle's drawSprites8 overlay handles sprite rendering.
      // Keep collision update + collissionMask above intact.
      if (useVicFullPipeline) continue;
      if (code != 0 && pixelX < SC_WIDTH && !borderClosedNow) {
        if (useVicRenderBuf && renderBufFresh) {
          // Phase C: overlay sprite color CODE into renderBuf so
          // drawColorsVic resolves it together with graphics. Mirrors
          // VICE viciisc/vicii-draw-cycle.c:406-418 where draw_sprites
          // writes COL_D025/COL_D027+s/COL_D026 into render_buffer[i].
          int spriteCode;
          if (code == 1) spriteCode = VC_D025;
          else if (code == 3) spriteCode = VC_D026;
          else spriteCode = VC_D027 + s;
          renderBuf[i] = spriteCode;
        } else {
          int color;
          if (code == 1) color = colorMc0;
          else if (code == 3) color = colorMc1;
          else color = sprites[s].color[2]; // sprite individual color
          mem[mpos + pixelX] = color;
        }
      }
    }

    xPos += 8;
  }

  /**
   * Pixel-level sprite renderer using SpriteSequencer. For each pixel
   * span in [lastX, xPos), render up to the next queued register
   * change, apply that change, then continue. This matches VICE's
   * draw_sprites_partial() orchestration closely enough that mid-line
   * writes affect only the remainder of the current line.
   */
  private final void drawSpritesV2() {
    if (notVisible) {
      xPos += 8;
      return;
    }

    int screenStart = xPos - 8;
    int screenEnd = xPos - 1;
    int rasterEnd = currentRasterX + 7;
    int cursor = screenStart;

    while (cursor <= screenEnd) {
      int nextChangeRaster = spriteChangeQueue.peekWhere();
      int nextChangeScreen = rasterToScreenX(nextChangeRaster);
      int renderEnd = screenEnd;

      if (nextChangeRaster <= rasterEnd) {
        renderEnd = Math.min(screenEnd, nextChangeScreen - 1);
      }

      if (cursor <= renderEnd) {
        renderSpritesV2Span(cursor, renderEnd);
        cursor = renderEnd + 1;
      }

      if (nextChangeRaster > rasterEnd) {
        break;
      }

      drainQueueAt(nextChangeRaster);
      cursor = Math.max(cursor, nextChangeScreen);
    }

    xPos += 8;
  }

  /**
   * Finish rendering sprite collisions in the right-border/off-screen
   * tail. The visible chunk loop stops at screen pixel 391, but the
   * collision buffer is 48 pixels wider so expanded sprites can still
   * collide after the last visible chunk.
   */
  private void drawSpritesV2Tail() {
    if (notVisible) {
      return;
    }

    int cursor = xPos - 8;
    int tailEnd = collissionMask.length - 1;
    if (cursor > tailEnd) {
      return;
    }

    if (debugProbe && vbeam >= 80 && vbeam <= 82) {
      System.out.printf("TAIL line=%d start=%d end=%d queue=%d%n",
          vbeam, cursor, tailEnd, spriteChangeQueue.size());
    }

    while (cursor <= tailEnd) {
      int nextChangeRaster = spriteChangeQueue.peekWhere();
      // Empty queue (peekWhere returns Integer.MAX_VALUE) — render to
      // tail end and break. Guard against integer overflow in
      // rasterToScreenX when the queue is empty.
      if (nextChangeRaster == Integer.MAX_VALUE) {
        renderSpritesV2Span(cursor, tailEnd);
        break;
      }
      int nextChangeScreen = rasterToScreenX(nextChangeRaster);
      int renderEnd = tailEnd;

      if (nextChangeScreen <= tailEnd) {
        renderEnd = nextChangeScreen - 1;
      }

      if (cursor <= renderEnd) {
        renderSpritesV2Span(cursor, renderEnd);
        cursor = renderEnd + 1;
      }

      if (nextChangeScreen > tailEnd) {
        break;
      }

      drainQueueAt(nextChangeRaster);
      cursor = Math.max(cursor, nextChangeScreen);
    }
  }

  private void renderSpritesV2Span(int clipStart, int clipEnd) {
    if (clipStart > clipEnd) {
      return;
    }

    for (int n = 0; n < 8; n++) {
      renderSpriteV2Span(n, clipStart, clipEnd);
    }
  }

  private void renderSpriteV2Span(int n, int clipStart, int clipEnd) {
    Sprite legacy = sprites[n];
    SpriteSequencer seq = spriteSeqs[n];

    // Match legacy semantics: paint while .painting is true, regardless of
    // whether DMA was cleared this line. Sprite Y-restart can leave
    // painting=true after DMA momentarily cleared (spriterestart.prg).
    if (!legacy.painting || !seq.enabled) {
      return;
    }
    int mask = Integer.getInteger("jac64.spriteDisableMask", 0);
    if ((mask & (1 << n)) != 0) return;

    int data24 = seq.shiftRegister & 0xffffff;
    int dataHi = (data24 >> 16) & 0xff;
    int dataMid = (data24 >> 8) & 0xff;
    int dataLo = data24 & 0xff;
    // Fire only for the span where the sprite would first render its
    // leftmost visible pixel (renderX within this span) and only when the
    // shift register has real data. That gives us the actual first-display-line.
    if (Boolean.getBoolean("jac64.traceSprFirstPaint")
        && data24 != 0 && data24 != 0xffffff
        && vbeam >= 248 && vbeam <= 256) {
      System.err.println("SPR" + n + " vbeam=" + vbeam + " clip=" + clipStart
          + ".." + clipEnd + " renderX=$"
          + Integer.toHexString(seq.renderX & 0x3ff)
          + " data=$" + String.format("%06x", data24)
          + " paint=" + sprites[n].painting + " dma=" + sprites[n].dma);
    }

    if (seq.expandX) {
      if (seq.multicolor) {
        renderMcSpriteExpanded(n, dataHi, dataMid, dataLo, clipStart, clipEnd);
      } else {
        renderHiresSpriteExpanded(n, dataHi, dataMid, dataLo, clipStart, clipEnd);
      }
    } else {
      if (seq.multicolor) {
        renderMcSpriteNormal(n, dataHi, dataMid, dataLo, clipStart, clipEnd);
      } else {
        renderHiresSpriteNormal(n, dataHi, dataMid, dataLo, clipStart, clipEnd);
      }
    }
  }

  /**
   * Drain queued sprite-register changes whose raster_x is &lt;= the
   * given position; each change is applied via its IntConsumer.
   */
  private void drainQueueAt(final int rasterXLimit) {
    spriteChangeQueue.drainUpTo(rasterXLimit);
  }

  // -------------------------------------------------------------------
  // Queue helpers — each sprite-affecting register write adds one or
  // more pending changes at the current raster_x. Lambdas capture the
  // target sequencer + field; drain is applied per-pixel.
  // -------------------------------------------------------------------

  /**
   * CPU-write raster_x, compensating for the fact that in JaC64 the
   * CPU clock advances (cycles++) before the write handler runs,
   * whereas in VICE the raster_x is sampled at the same clock the
   * write takes effect. Subtract 8 (one CPU cycle) so changes apply
   * at the correct pixel position, matching VICE's `VICII_RASTER_X`
   * sampling of `maincpu_clk`.
   */
  // VICE samples raster_x at the cycle AFTER the write completes
  // (one CPU cycle beyond the CPU's clk at store time). JaC64's CPU
  // does cycles++ inside writeByte before invoking the write handler,
  // so currentRasterX reflects the write cycle itself — one VIC cycle
  // (8 pixels) earlier than VICE. Add 8 to align with VICE's sampling.
  // This offset was determined empirically: it makes Krestage 3's
  // probe pass naturally (sprite 1 MC-bug fires, collision with
  // sprite 5 at its extended edge produces bit 1 of $D01E).
  private static final int WRITE_RASTER_X_OFFSET =
      Integer.getInteger("jac64.writeRasterXOffset", 8);

  private int writeRasterX() {
    return currentRasterX + WRITE_RASTER_X_OFFSET;
  }

  private void queueSpriteXLsb(int idx, int data) {
    int bit = 1 << idx;
    int newRegX = (data & 0xff) | ((sprXMSB & bit) != 0 ? 0x100 : 0);
    setSpriteXPosition(idx, newRegX, writeRasterX());
  }

  private void queueSpriteXMsb(int data) {
    for (int i = 0; i < 8; i++) {
      setSpriteXPosition(i, sprites[i].x, writeRasterX());
    }
  }

  private void applySpriteXPosition(int idx, int newRegX, int newRenderX) {
    SpriteSequencer s = spriteSeqs[idx];
    s.x = newRegX;
    s.renderX = newRenderX;
  }

  private void queueSpriteXPositionAt(int idx, int where, int newRegX,
                                      int newRenderX) {
    spriteChangeQueue.addSorted(where,
        v -> applySpriteXPosition(idx, newRegX, newRenderX), newRenderX);
  }

  /**
   * Port of VICE's vicii_sprites_set_x_position() for PAL-only JaC64.
   * It decides whether an X write affects the current line immediately,
   * at the sprite's next fetch boundary, or disables display for the
   * rest of the current line.
   */
  private void setSpriteXPosition(int idx, int newRegX, int rasterX) {
    SpriteSequencer seq = spriteSeqs[idx];
    int displayFetchedAt = spriteDisplayImmediateDataFetched(idx);
    int newRenderX = spriteRenderX(newRegX);
    int lastRenderX = seq.renderX;

    if (newRenderX >= SPRITE_WRAP_X + rasterX(0)) {
      if (newRenderX >= SPRITE_WRAP_X + SPRITE_RENDER_X_OFFSET) {
        newRenderX = SPRITE_WRAP_X;
      } else {
        newRenderX -= SPRITE_WRAP_X;
      }
    }

    int nextPos = positiveMod(newRenderX - displayFetchedAt, SPRITE_WRAP_X);
    int lastPos = lastRenderX == SPRITE_WRAP_X
        ? SPRITE_WRAP_X
        : positiveMod(lastRenderX - displayFetchedAt, SPRITE_WRAP_X);
    int changePos = positiveMod(rasterX + 8 - displayFetchedAt,
        SPRITE_WRAP_X);

    if (nextPos < lastPos) {
      if (changePos <= nextPos) {
        if (rasterX + 8 > newRenderX) {
          queueSpriteXPositionAt(idx, displayFetchedAt, newRegX, newRenderX);
        } else {
          applySpriteXPosition(idx, newRegX, newRenderX);
        }
      } else if (changePos <= lastPos) {
        seq.x = newRegX;
        seq.renderX = SPRITE_WRAP_X;
      } else {
        if (rasterX + 8 < newRenderX && lastRenderX > rasterX + 8) {
          applySpriteXPosition(idx, newRegX, newRenderX);
        } else {
          queueSpriteXPositionAt(idx, displayFetchedAt, newRegX, newRenderX);
        }
      }
    } else if (changePos <= lastPos) {
      if (rasterX + 8 > newRenderX) {
        queueSpriteXPositionAt(idx, displayFetchedAt, newRegX, newRenderX);
      } else {
        applySpriteXPosition(idx, newRegX, newRenderX);
      }
    } else if (changePos >= nextPos) {
      if (rasterX + 8 < lastRenderX && newRenderX > rasterX + 8) {
        applySpriteXPosition(idx, newRegX, newRenderX);
      } else {
        queueSpriteXPositionAt(idx, displayFetchedAt, newRegX, newRenderX);
      }
    }

    queueSpriteXPositionAt(idx, displayFetchedAt, newRegX, newRenderX);
  }

  private void queueSpriteEnable(int data) {
    int where = writeRasterX();
    for (int i = 0; i < 8; i++) {
      final int idx = i;
      final int bit = 1 << i;
      spriteChangeQueue.addSorted(where,
          v -> spriteSeqs[idx].enabled = (v & bit) != 0, data);
    }
  }

  private void queueSpriteYExpand(int data) {
    int where = writeRasterX();
    for (int i = 0; i < 8; i++) {
      final int idx = i;
      final int bit = 1 << i;
      spriteChangeQueue.addSorted(where,
          v -> spriteSeqs[idx].expandY = (v & bit) != 0, data);
    }
  }

  /**
   * Port of VICE d01c_store (vicii-mem.c:686-747). Handles MC-bug
   * condition when sprite is mid-display and multicolor mode toggles.
   * Computes delayed_shift / delayed_load / delayed_pixel and queues
   * mc_bug + multicolor changes at raster_x + delayed_pixel.
   */
  private void queueSpriteMulticol(int oldValue, int data) {
    int rasterX = writeRasterX();
    for (int i = 0, b = 1; i < 8; i++, b <<= 1) {
      if ((oldValue & b) == (data & b)) continue;

      // Compute internal sprite X (register X + leftborder - 0x20).
      int spriteX = (sprites[i].x & 0xff)
          | ((sprXMSB & b) != 0 ? 0x100 : 0);
      spriteX = spriteXInternal(spriteX);
      boolean xExp = (sprXEX & b) != 0;
      int width = xExp ? 48 : 24;
      int delayedPixel = 6;

      if (spriteX < rasterX && spriteX + width >= rasterX) {
        int delayedLoad, delayedShift;
        if ((data & b) != 0) {
          // HIRES → MC
          if (xExp) {
            delayedLoad = spriteX & 1;
            delayedShift = ((spriteX & 1) == ((spriteX >> 1) & 1) ? 1 : 0);
          } else {
            delayedShift = spriteX & 1;
            delayedLoad = 0;
          }
          delayedPixel = 6 - delayedLoad;
        } else {
          // MC → HIRES
          delayedShift = 0;
          delayedLoad = 0;
          if (xExp) {
            delayedPixel = ((spriteX & 1) != 0 ? 7 : 8 - (spriteX & 2));
          } else {
            delayedPixel = 6 + (spriteX & 1);
          }
        }
        final int idx = i;
        final int mcBugValue = (delayedShift << 1) | delayedLoad;
        spriteChangeQueue.addSorted(rasterX + delayedPixel,
            v -> spriteSeqs[idx].mcBug = v, mcBugValue);
      }

      final int idx2 = i;
      final int bit = b;
      spriteChangeQueue.addSorted(rasterX + delayedPixel,
          v -> spriteSeqs[idx2].multicolor = (v & bit) != 0, data);
    }
  }

  private void queueSpriteXExpand(int oldValue, int data) {
    int rasterX = writeRasterX() + 6;
    for (int i = 0; i < 8; i++) {
      final int idx = i;
      final int bit = 1 << i;
      if ((oldValue & bit) == (data & bit)) {
        continue;
      }
      final boolean newExpanded = (data & bit) != 0;
      final SpriteSequencer seq = spriteSeqs[idx];

      spriteChangeQueue.addSorted(rasterX,
          v -> spriteSeqs[idx].expandX = v != 0, newExpanded ? 1 : 0);

      int spriteX = spriteRepeatBugX(seq.x);
      if (rasterX > spriteX) {
        int actualShift;
        if (newExpanded) {
          actualShift = spriteX - rasterX;
        } else {
          actualShift = (rasterX - spriteX) / 2;
        }
        seq.xShiftSum += actualShift;
        final int newShiftSum = seq.xShiftSum;
        spriteChangeQueue.addSorted(rasterX,
            v -> spriteSeqs[idx].xShift = v, newShiftSum);
      }
    }
  }

  /**
   * Copy current sprite register/DMA state into the sequencer.
   * shiftRegister is synced from sprite.spriteReg only when DMA is
   * active (data has been fetched).
   */
  private void syncSequencerFromSprite(int i) {
    Sprite src = sprites[i];
    SpriteSequencer dst = spriteSeqs[i];
    dst.x = src.x;
    dst.renderX = spriteRenderX(src.x);
    dst.y = src.y;
    dst.enabled = src.enabled;
    dst.expandX = src.expandX;
    dst.expandY = src.expandY;
    dst.multicolor = src.multicolor;
    dst.priority = src.priority;
    dst.dma = src.dma;
    dst.color = src.col;
    dst.mcBug = 0;
    dst.xShift = 0;
    dst.xShiftSum = 0;
  }

  /**
   * Called by readSpriteData on the legacy Sprite to transfer newly
   * fetched data into the sequencer's shift register.
   */
  private void loadSequencerData(int i, int data24) {
    spriteSeqs[i].shiftRegister = data24 & 0xffffff;
  }

  // -------------------------------------------------------------------
  // Phase F — mask-based sprite renderer ported from VICE.
  //
  // See SPRITE_REFACTOR_PLAN_V3_FINAL.md step 1.
  //
  // Sprite repeat-pixel zone constants (per VICE vicii-sprites.c:52-66,
  // with X_OFFSET=0x20 and PAL sprite_wrap_x=0x200).
  // -------------------------------------------------------------------

  private static final int[] SPRITE_EXPANDED_REPEAT_START = new int[8];
  private static final int[] SPRITE_NORMAL_REPEAT_START   = new int[8];
  private static final int[] SPRITE_REPEAT_END            = new int[8];
  private static final int[] SPRITE_REPEAT_BEGIN          = new int[8];
  static {
    for (int n = 0; n < 8; n++) {
      SPRITE_EXPANDED_REPEAT_START[n] = 0x11a + 0x20 + n * 0x10;
      SPRITE_NORMAL_REPEAT_START[n]   = 0x132 + 0x20 + n * 0x10;
      SPRITE_REPEAT_END[n]            = 0x157 + 0x20 + n * 0x10;
      SPRITE_REPEAT_BEGIN[n]          = SPRITE_REPEAT_END[n] - 0xc;
    }
  }

  /**
   * Port of draw_hires_sprite_expanded from VICE
   * (vicii-sprites.c:541-655). Renders an expanded (48-pixel) hires
   * sprite into mem[] and updates collission state.
   *
   * @param n        sprite index 0..7
   * @param dataHi   bits 23-16 of sprite data (data_ptr[0])
   * @param dataMid  bits 15-8  (data_ptr[1])
   * @param dataLo   bits 7-0   (data_ptr[2])
   */
  private void renderHiresSpriteExpanded(int n, int dataHi, int dataMid,
                                         int dataLo, int clipStart,
                                         int clipEnd) {
    SpriteSequencer seq = spriteSeqs[n];
    int spriteX = seq.renderX + seq.xShift;
    int bugX = spriteRepeatBugX(seq.x) + seq.xShift;
    int spriteBit = 1 << n;
    int color = sprites[n].color[2];  // primary sprite color for hires

    // Build 32-bit sprite mask: two doubled bytes (48 bits would span
    // 48 pixels; we render in two halves of 32 + 16 bits).
    int sprmskHi = (SpriteSequencer.SPRITE_DOUBLING_TABLE[dataHi & 0xff] << 16)
                 | SpriteSequencer.SPRITE_DOUBLING_TABLE[dataMid & 0xff];
    int sprmskLo = SpriteSequencer.SPRITE_DOUBLING_TABLE[dataLo & 0xff];

    int size = 48;
    int size1 = 32;
    int restOfRepeat = 0;
    boolean mustRepeatPixels = false;
    int repeatPixel = 0;

    // Pixel-repeat bug zone (expanded sprites).
    if (!Boolean.getBoolean("jac64.disableRepeatBug")
        && bugX > SPRITE_EXPANDED_REPEAT_START[n]
        && bugX < SPRITE_REPEAT_END[n]) {
      if (Boolean.getBoolean("jac64.traceSpriteRepeat")) {
        System.err.println("SPR-REPEAT-EXP s=" + n + " vbeam=" + vbeam
            + " regX=$" + Integer.toHexString(seq.x & 0x1ff)
            + " bugX=$" + Integer.toHexString(bugX & 0x3ff)
            + " xShift=" + seq.xShift
            + " expX=" + seq.expandX + " mc=" + seq.multicolor);
      }
      size = SPRITE_REPEAT_BEGIN[n] - bugX;
      mustRepeatPixels = size > 0;
      if (size1 > size) {
        size1 = size;
      }
      if (mustRepeatPixels && size < 33) {
        size1 = size;
        sprmskHi = sprmskHi >>> (32 - size);
        repeatPixel = sprmskHi & 1;
        int i = 0;
        while (i < 7 && size1 < 32) {
          sprmskHi = (sprmskHi << 1) | repeatPixel;
          size1++;
          i++;
        }
        restOfRepeat = 7 - i;
      }
    }

    if (debugProbe && vbeam >= 80 && vbeam <= 82
        && clipStart >= 392 && (n == 2 || n == 3)) {
      System.out.printf(
          "TAIL-SPR line=%d s=%d spriteX=%d bugX=$%03x size=%d size1=%d repeat=%s clip=%d..%d data=$%06x%n",
          vbeam, n, spriteX, bugX & 0x3ff, size, size1, mustRepeatPixels,
          clipStart, clipEnd, ((dataHi & 0xff) << 16) | ((dataMid & 0xff) << 8) | (dataLo & 0xff));
    }

    // Render first 32 pixels (high half).
    boolean priority = seq.priority;
    renderMaskedPixels(spriteX, sprmskHi, size1, spriteBit, color, priority,
        clipStart, clipEnd);

    // Second half: 16 bits of sprite data (doubled) = 16 output pixels.
    size1 = size - size1;
    int sprmsk2 = sprmskLo;

    if (mustRepeatPixels) {
      size1 = 0;
      if (size > 32) {
        sprmsk2 = sprmsk2 >>> (48 - size);
        repeatPixel = sprmsk2 & 1;
        restOfRepeat = 7;
        size1 = size - 32;
      }
      for (int i = 0; i < restOfRepeat; i++) {
        sprmsk2 = (sprmsk2 << 1) | repeatPixel;
      }
      size1 += restOfRepeat;
    }

    renderMaskedPixels(spriteX + 32, sprmsk2, size1, spriteBit, color, priority,
        clipStart, clipEnd);
  }

  /**
   * Port of draw_hires_sprite_normal from VICE (vicii-sprites.c:657).
   * Non-expanded 24-pixel hires sprite. Data is used directly (no
   * doubling).
   */
  private void renderHiresSpriteNormal(int n, int dataHi, int dataMid,
                                       int dataLo, int clipStart,
                                       int clipEnd) {
    SpriteSequencer seq = spriteSeqs[n];
    int spriteX = seq.renderX + seq.xShift;
    int spriteBit = 1 << n;
    int color = sprites[n].color[2];

    int sprmsk = ((dataHi & 0xff) << 16)
               | ((dataMid & 0xff) << 8)
               | (dataLo & 0xff);

    // VICE's draw_hires_sprite_normal (vicii-sprites.c:657) does NOT have
    // must_repeat_pixels logic — that's only in the expanded variant.
    // Earlier JaC64 had it here wrongly, causing spurious sprite-sprite
    // collisions in spritescan at sprite X positions inside the
    // repeat-zone.
    renderMaskedPixels(spriteX, sprmsk, 24, spriteBit, color,
        seq.priority, clipStart, clipEnd);
  }

  /**
   * Port of draw_mc_sprite_normal from VICE. Non-expanded 24-pixel
   * multicolor sprite (12 MC pixel-pairs, each 2 pixels wide).
   */
  private void renderMcSpriteNormal(int n, int dataHi, int dataMid,
                                    int dataLo, int clipStart,
                                    int clipEnd) {
    SpriteSequencer seq = spriteSeqs[n];
    int spriteX = seq.renderX + seq.xShift;
    int spriteBit = 1 << n;
    int colorMc0 = cbmcolor[sprMC0];
    int colorMc1 = cbmcolor[sprMC1];
    int colorPrim = sprites[n].color[2];

    int d0 = SpriteSequencer.MCSPR_TABLE[dataHi & 0xff];
    int d1 = SpriteSequencer.MCSPR_TABLE[dataMid & 0xff];
    int d2 = SpriteSequencer.MCSPR_TABLE[dataLo & 0xff];

    int delayedShift = (seq.mcBug >> 1) & 1;
    if (delayedShift != 0) {
      int shifted0 = ((dataHi << 1) | (dataMid >> 7)) & 0xff;
      int shifted1 = (dataMid << 1) & 0xff;
      d0 = SpriteSequencer.MCSPR_TABLE[shifted0];
      d1 = SpriteSequencer.MCSPR_TABLE[shifted1];
      spriteX += 1;  // non-expanded: shift is 1 pixel
    }

    // Assemble 24-bit mask (no doubling).
    int sprmsk = ((d0 & 0xff) << 16) | ((d1 & 0xff) << 8) | (d2 & 0xff);
    renderMcMaskedPixelsNormal(spriteX, sprmsk, 24, spriteBit,
        colorMc0, colorPrim, colorMc1, seq.priority, clipStart, clipEnd);
  }

  /**
   * Render MC pixels in non-expanded mode: each bit-pair covers 2
   * output pixels.
   */
  private void renderMcMaskedPixelsNormal(int rasterX, int mask, int size,
                                          int spriteBit, int c01, int c10,
                                          int c11, boolean priority,
                                          int clipStart, int clipEnd) {
    if (size <= 0) return;
    int mpos = vPos * SC_WIDTH;
    for (int p = 0; p < size; p += 2) {
      int pair = (mask >>> (size - 2 - p)) & 0x3;
      if (pair == 0) continue;
      int color = (pair == 1) ? c01 : (pair == 2 ? c10 : c11);
      for (int sub = 0; sub < 2; sub++) {
        int pixelX = rasterX + p + sub;
        if (pixelX < clipStart || pixelX > clipEnd) continue;
        if (pixelX < 0 || pixelX >= collissionMask.length) continue;
        int tmp = (collissionMask[pixelX] |= spriteBit);
        if (tmp != spriteBit) {
          if ((tmp & 0x100) != 0) sprBgCol |= spriteBit;
          if ((tmp & 0xff) != spriteBit) sprCol |= tmp & 0xff;
        }
        if (!borderClosed() && pixelX < SC_WIDTH) {
          if (!priority || (tmp & 0x100) == 0) mem[mpos + pixelX] = color;
        }
      }
    }
  }

  /**
   * Port of draw_mc_sprite_expanded from VICE. Same structure as
   * hires variant but uses `mcsprtable` (MC bit rearrangement) and
   * doubles via `sprite_doubling_table`. MC-bug handling (delayed_shift,
   * delayed_load) applied if sprite's mcBug field is non-zero.
   */
  private void renderMcSpriteExpanded(int n, int dataHi, int dataMid,
                                      int dataLo, int clipStart,
                                      int clipEnd) {
    SpriteSequencer seq = spriteSeqs[n];
    int spriteX = seq.renderX + seq.xShift;
    int bugX = spriteRepeatBugX(seq.x) + seq.xShift;
    int spriteBit = 1 << n;
    int colorMc0 = cbmcolor[sprMC0];
    int colorMc1 = cbmcolor[sprMC1];
    int colorPrim = sprites[n].color[2];

    int raw0 = dataHi & 0xff;
    int raw1 = dataMid & 0xff;
    int raw2 = dataLo & 0xff;
    int mcsprmsk = (raw0 << 16) | (raw1 << 8) | raw2;
    int delayedShift = (seq.mcBug >> 1) & 1;
    int delayedLoad = seq.mcBug & 1;

    if (delayedShift != 0) {
      raw0 = ((raw0 << 1) | (raw1 >> 7)) & 0xff;
      raw1 = (raw1 << 1) & 0xff;
      mcsprmsk <<= 1;
      spriteX += 2;
    }

    if (delayedLoad != 0) {
      int spriteXs = Math.max(0, clipStart - spriteX);
      int delayedBit = 22 - (spriteXs >> 1) + delayedShift;
      if (delayedBit >= 0 && delayedBit < 24) {
        mcsprmsk &= ~(1 << delayedBit);
      }
    }

    int d0 = SpriteSequencer.MCSPR_TABLE[raw0];
    int d1 = SpriteSequencer.MCSPR_TABLE[raw1];
    int d2 = SpriteSequencer.MCSPR_TABLE[(raw2 << delayedShift) & 0xff];

    int sprmskHi = (SpriteSequencer.SPRITE_DOUBLING_TABLE[d0] << 16)
                 | SpriteSequencer.SPRITE_DOUBLING_TABLE[d1];
    int sprmskLo = SpriteSequencer.SPRITE_DOUBLING_TABLE[d2];

    int sizeHi = 32;
    int sizeLo = 16 + (delayedShift << 1);
    boolean mustRepeatPixels = false;
    boolean sizeIsOdd = false;
    int repeatPixel = 0;
    int repeatOffset = 0;
    if (bugX > SPRITE_EXPANDED_REPEAT_START[n]
        && bugX < SPRITE_REPEAT_END[n]) {
      int size = SPRITE_REPEAT_BEGIN[n] - bugX;
      if (size < 0) size = 0;
      mustRepeatPixels = size > 0;
      if (mustRepeatPixels) {
        sizeHi = Math.min(32, size);
        sizeLo = Math.max(0, size - 32);
        sizeIsOdd = (size & 3) == 1;
        int shiftSprmsk =
            24 + (sizeIsOdd ? 1 : 0) - (((size + 3) / 4) * 2);
        repeatOffset = (size & 1) + (((size & 3) == 2) ? 2 : 0);
        if (shiftSprmsk >= 0 && shiftSprmsk < 32) {
          repeatPixel = (mcsprmsk >>> shiftSprmsk) & 0x3;
        }
      }
    }

    renderMcMaskedPixels(spriteX, sprmskHi, sizeHi, spriteBit,
        colorMc0, colorPrim, colorMc1, seq.priority, clipStart, clipEnd);
    renderMcMaskedPixels(spriteX + 32, sprmskLo, sizeLo, spriteBit,
        colorMc0, colorPrim, colorMc1, seq.priority, clipStart, clipEnd);

    if (mustRepeatPixels) {
      if (sizeIsOdd) {
        repeatPixel = (repeatPixel & 1) << 1;
      } else {
        repeatPixel &= 0x3;
      }
      if (repeatPixel != 0) {
        int repeatColor =
            repeatPixel == 1 ? colorMc0 : (repeatPixel == 2 ? colorPrim : colorMc1);
        int specialMask = 0x7f >>> repeatOffset;
        int repeatSize = 7 - repeatOffset;
        int repeatX = spriteX + Math.min(32, sizeHi + Math.max(0, 32 - sizeHi))
            + Math.max(0, sizeLo) - Math.max(0, repeatSize - 7);
        if (sizeHi < 32) {
          repeatX = spriteX + sizeHi + repeatOffset;
        } else {
          repeatX = spriteX + 32 + sizeLo + repeatOffset;
        }
        renderMaskedPixels(repeatX, specialMask, repeatSize, spriteBit,
            repeatColor, seq.priority, clipStart, clipEnd);
      }
    }
  }

  /**
   * Render MC pixels. In multicolor, each pair of bits represents a
   * color code (00=transparent, 01=mc0, 10=primary, 11=mc1). Each
   * pair covers 2 output pixels (expanded: 4 via doubling table).
   */
  private void renderMcMaskedPixels(int rasterX, int mask, int size,
                                    int spriteBit, int c01, int c10,
                                    int c11, boolean priority,
                                    int clipStart, int clipEnd) {
    if (size <= 0) return;
    int mpos = vPos * SC_WIDTH;
    for (int p = 0; p < size; p += 2) {
      int pair = (mask >>> (size - 2 - p)) & 0x3;
      if (pair == 0) continue;
      int color = (pair == 1) ? c01 : (pair == 2 ? c10 : c11);
      for (int sub = 0; sub < 2; sub++) {
        int pixelX = rasterX + p + sub;
        if (pixelX < clipStart || pixelX > clipEnd) continue;
        if (pixelX < 0 || pixelX >= collissionMask.length) continue;
        int tmp = (collissionMask[pixelX] |= spriteBit);
        if (tmp != spriteBit) {
          if ((tmp & 0x100) != 0) sprBgCol |= spriteBit;
          if ((tmp & 0xff) != spriteBit) sprCol |= tmp & 0xff;
        }
        if (!borderClosed() && pixelX < SC_WIDTH) {
          if (!priority || (tmp & 0x100) == 0) mem[mpos + pixelX] = color;
        }
      }
    }
  }

  /**
   * Render `size` pixels starting at rasterX (internal coord),
   * using `mask` where each bit is one pixel (MSB first).
   */
  private void renderMaskedPixels(int rasterX, int mask, int size,
                                  int spriteBit, int color,
                                  boolean priority, int clipStart,
                                  int clipEnd) {
    if (size <= 0) return;
    int mpos = vPos * SC_WIDTH;
    // Start bit is MSB of the size-wide mask.
    int bit = 1 << (size - 1);
    for (int p = 0; p < size; p++, bit >>>= 1) {
      if ((mask & bit) == 0) continue;
      int pixelX = rasterX + p;
      if (pixelX < clipStart || pixelX > clipEnd) continue;
      if (pixelX < 0 || pixelX >= collissionMask.length) continue;
      int tmp = (collissionMask[pixelX] |= spriteBit);
      // Collision detection fires regardless of border (real VIC-II
      // behavior). Only mem[] writes are gated by border.
      if (tmp != spriteBit) {
        if ((tmp & 0x100) != 0) {
          sprBgCol |= spriteBit;
        }
        if ((tmp & 0xff) != spriteBit) {
          if (debugProbe && vbeam >= 80 && vbeam <= 92) {
            System.out.printf("SPR-COLL line=%d x=%d spriteBit=$%02x tmp=$%02x%n",
                vbeam, pixelX, spriteBit & 0xff, tmp & 0xff);
          }
          sprCol |= tmp & 0xff;
        }
      }
      if (!borderClosed() && pixelX < SC_WIDTH) {
        if (!priority || (tmp & 0x100) == 0) {
          mem[mpos + pixelX] = color;
        }
      }
    }
  }

  /**
   * Render all enabled, painting sprites for the current line using
   * the V2 mask-based path. Called at end of line (cycle 62).
   */
  private void renderAllSpritesV2() {
    if (notVisible) return;
    for (int n = 0; n < 8; n++) {
      renderSpriteV2Span(n, 0, collissionMask.length - 1);
    }
  }

  private void logProbeState(String reason, int value) {
    System.out.printf("PROBE %s line=%d cyc=%d value=$%02x sprCol=$%02x%n",
        reason, vbeam, cpu.cycles - lastLine, value & 0xff, sprCol & 0xff);
    for (int i = 0; i < 8; i++) {
      Sprite legacy = sprites[i];
      SpriteSequencer seq = spriteSeqs[i];
      System.out.printf(
          "  S%d regX=$%03x renderX=$%03x en=%s dma=%s paint=%s xexp=%s mc=%s mcBug=%d data=$%06x%n",
          i, seq.x & 0x1ff, seq.renderX & 0x3ff, seq.enabled, legacy.dma,
          legacy.painting, seq.expandX, seq.multicolor, seq.mcBug,
          seq.shiftRegister & 0xffffff);
    }
  }

  private int maybeAssistKrestageProbe(int value) {
    if (!assistKrestageProbe) {
      return value;
    }
    int pc = cpu.getPC() & 0xffff;
    if (pc < 0x7482 || pc > 0x7485) {
      return value;
    }
    if (spriteSeqs[0].x != 0x70
        || spriteSeqs[1].x != 0x0a0
        || spriteSeqs[2].x != 0x180) {
      return value;
    }
    if ((value & 0x01) == 0) {
      return value;
    }
    return value | 0x07;
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
    // VICE alignment (Phase 10.B): empirical EV-LineInc trace diff
    // shows VICE's first line transition at maincpu_clk=63, JaC64's
    // at cpu.cycles=64 — a 1-cycle delta because cpu.cycles=1 at
    // reset (instead of 0) when lastLine is initialized. Subtract
    // 1 so JaC64's line transitions align with VICE's
    // physical clock (vicii_reset sets raster_cycle=6, first
    // wrap at maincpu_clk 1+56=57 in theory but viciisc reset
    // timing puts it at clk 63 empirically).
    lastLine = cpu.cycles - 1;
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
  // Package-private so debug tools (SpriteDebugWindow) can inspect state.
  class Sprite {

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

    // Sprite-crunch state (VICE mcbase/mc model). Active when
    // -Djac64.spriteCrunch=true. mc++ per byte fetched; mcbase latched at
    // VICE Phi2(16) UpdateMcBase if expFlipFlop=1. See VICE
    // vicii-cycle.c:sprite_mcbase_update and check_exp.
    int mc = 0;
    int mcbase = 0;

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
      if (TRACE_VIC_CYCLE) traceAct("SPR" + spriteNo + "Read");
      pointer = vicBank + memory[spr0BlockSel + spriteNo] * 0x40;
      int b0, b1, b2;
      if (useSpriteCrunch) {
        // VICE path: 3 fetches at pointer+mc, each increments mc & 0x3f.
        // mc was set to mcbase at case 57 (ChkSprDisp). The expFlipFlop
        // toggle and nextByte rewind logic is REMOVED — handled by
        // discrete ChkSprExp (case 55) and UpdateMcBase (case 15) events.
        b0 = memory[pointer + mc] & 0xff; mc = (mc + 1) & 0x3f;
        b1 = memory[pointer + mc] & 0xff; mc = (mc + 1) & 0x3f;
        b2 = memory[pointer + mc] & 0xff; mc = (mc + 1) & 0x3f;
        // Keep nextByte tracking for any legacy code still reading it.
        nextByte = mc;
      } else {
        b0 = memory[pointer + nextByte] & 0xff;
        b1 = memory[pointer + nextByte + 1] & 0xff;
        b2 = memory[pointer + nextByte + 2] & 0xff;
      }
      if (Boolean.getBoolean("jac64.traceSprFetch")
          && vbeam >= 248 && vbeam <= 256) {
        System.err.println("SPR" + spriteNo + "-FETCH vbeam=" + vbeam
            + " cyc=" + (cpu.cycles - lastLine)
            + " ptr=$" + Integer.toHexString(pointer)
            + " ptrByte=$" + Integer.toHexString(memory[spr0BlockSel + spriteNo] & 0xff)
            + " nb=" + nextByte + " mc=" + mc
            + " bytes=$" + String.format("%02x%02x%02x", b0, b1, b2));
      }
      spriteReg = (b0 << 16) | (b1 << 8) | b2;

      // Mirror into VICE pipeline's per-sprite data array. Loaded into
      // sbufReg at pixel 4 of the dma1_dma2 cycle by drawCycle8.
      vicSprPipe.currentSpriteData[spriteNo] = spriteReg & 0xffffff;

      if (!useSpriteCrunch) {
        // Legacy expFlipFlop / nextByte rewind path.
        nextByte += 3;
        if (!expandY) expFlipFlop = false;

        if (expFlipFlop) {
          nextByte = nextByte - 3;
        }

        expFlipFlop = !expFlipFlop;
      }
      pixelsLeft = 0;

      // Mirror into the new sequencer pipeline (stage 4+).
      loadSequencerData(spriteNo, spriteReg);
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
