package com.dreamfabric.jac64;

/**
 * Faithful port of VICE viciisc/vicii-draw-cycle.c sprite rendering.
 * Cycle-exact (x64sc / MOS8565) per-pixel shift register model.
 *
 * Replaces the previous V2 sprite span renderer (renderMcSpriteExpanded
 * etc.) that was ported from the fast/inaccurate vicii/vicii-sprites.c.
 *
 * Source-line citations refer to:
 *   /Users/joakimeriksson/work/vice-emu/vice/src/viciisc/vicii-draw-cycle.c
 *
 * The model:
 *   - Each sprite has a 24-bit shift register (sbufReg[s]) stored in the
 *     top 24 bits of a 32-bit word.
 *   - Per-sprite expand-X flip-flop (sbufExpxFlops bit s): when set,
 *     the buffer shifts on this pixel; cleared by toggle when expandX
 *     is enabled (so shift only every other pixel).
 *   - Per-sprite multicolor flip-flop (sbufMcFlops bit s): when set,
 *     a new 2-bit pixel is fetched; toggled per pixel in MC mode.
 *   - Global pipeline bits drive activation/halt:
 *       spritePendingBits  — sprites waiting to start drawing this line
 *       spriteActiveBits   — sprites currently drawing
 *       spriteHaltBits     — sprites halted (during DMA cycles)
 *   - Per-pixel processing: trigger_sprites() then draw_sprites().
 *   - Per-cycle (8 pixels): state transitions at specific sub-pixels:
 *       pixel 2: clear active bits for DMA1/DMA2 cycle (halt)
 *       pixel 3: set halt bits for DMA0 (PTR) cycle
 *       pixel 4: latch sprite_display_bits if check_spr_disp; load shift
 *                register if dma1_dma2 cycle
 *       pixel 6: latch sprite_pri_bits ($D01B), sprite_expx_bits ($D01D);
 *                update_sprite_mc_bits_8565 (no color latency)
 *       pixel 7: clear halt bits for DMA1/DMA2 cycle
 *
 * Cycle-flag mapping from JaC64 case N to VICE conditions:
 *   - case N corresponds to VICE raster cycle N+1.
 *   - SprPtr/SprDma0 (sprite ptr + byte 0) cycles:
 *       sprite 3: case 0 (VICE cycle 1)
 *       sprite 4: case 2 (VICE cycle 3)
 *       sprite 5: case 4 (VICE cycle 5)
 *       sprite 6: case 6 (VICE cycle 7)
 *       sprite 7: case 8 (VICE cycle 9)
 *       sprite 0: case 57 (VICE cycle 58)
 *       sprite 1: case 59 (VICE cycle 60)
 *       sprite 2: case 61 (VICE cycle 62)
 *   - SprDma1/SprDma2 (sprite bytes 1+2) cycles: ptr cycle + 1.
 *   - check_sprite_display: VICE cycle 58 = JaC64 case 57.
 */
public final class ViceSpritePipeline {

  // Static trace gate evaluated once at class load — zero per-pixel cost
  // when off. Enable with -Djac64.traceSpriteDraw=true and optional
  // -Djac64.traceSpriteLineLo / -Djac64.traceSpriteLineHi to narrow range.
  private static final boolean TRACE_SPR_DRAW =
      Boolean.getBoolean("jac64.traceSpriteDraw");
  private static final int TRACE_SPR_LINE_LO =
      Integer.getInteger("jac64.traceSpriteLineLo", 0);
  private static final int TRACE_SPR_LINE_HI =
      Integer.getInteger("jac64.traceSpriteLineHi", 0x1ff);

  /** Set by caller before drawCycle8 — current raster line / cycle. */
  public int traceLine = 0;
  public int traceCyc = 0;

  // ==== State (mirror of vicii-draw-cycle.c statics) ====

  /** Pipelined sprite X positions; latched at end of cycle. Port of sprite_x_pipe[8]. */
  private final int[] spriteXPipe = new int[8];

  /** Sprite priority bits ($D01B). Port of sprite_pri_bits. */
  private int spritePriBits = 0;

  /** Sprite multicolor bits ($D01C). Port of sprite_mc_bits. */
  private int spriteMcBits = 0;

  /** Sprite X-expand bits ($D01D). Port of sprite_expx_bits. */
  private int spriteExpxBits = 0;

  /** Sprites enabled and waiting to start. Port of sprite_pending_bits. */
  private int spritePendingBits = 0;

  /** Sprites currently drawing. Port of sprite_active_bits. */
  private int spriteActiveBits = 0;

  /** Sprites halted (during DMA). Port of sprite_halt_bits. */
  private int spriteHaltBits = 0;

  /**
   * Per-sprite shift registers. Port of sbuf_reg[8].
   * Data lives in bits 23..0 of each int (byte0 at bits 23..16,
   * byte1 at 15..8, byte2 at 7..0). Bit 23 = leftmost rendered pixel.
   * Shifts left during rendering; data eventually shifts past bit 31
   * and is lost.
   */
  private final int[] sbufReg = new int[8];

  /** Per-sprite current pixel value (0..3 for MC, 0/2 for hires). Port of sbuf_pixel_reg[8]. */
  private final int[] sbufPixelReg = new int[8];

  /** Per-sprite expand-X flip-flop. Port of sbuf_expx_flops. */
  private int sbufExpxFlops = 0;

  /** Per-sprite multicolor flip-flop. Port of sbuf_mc_flops. */
  private int sbufMcFlops = 0;

  /** Mask of sprites that produced a non-zero pixel this 8-pixel cycle. */
  private int collisionThisCycle = 0;

  // Output of the 8-pixel cycle: per-pixel sprite color index (0 = transparent)
  // and the sprite that "won" priority (-1 if none).
  // Color index: 1 = COL_D025 (MC0), 2 = COL_D027+s (sprite color), 3 = COL_D026 (MC1).
  /** Per-pixel color "code" output; 0 = transparent. */
  public final int[] outColorCode = new int[8];
  /** Per-pixel winning sprite index (0..7), or -1 if no sprite. */
  public final int[] outSprite = new int[8];
  /** Per-pixel: did the foreground (graphics priority) win the pixel? */
  public final boolean[] outForegroundWin = new boolean[8];
  /** Per-pixel sprite-sprite collision mask (set if 2+ sprites at pixel). */
  public final int[] outSpriteSpriteColl = new int[8];
  /** Per-pixel sprite-bg collision mask (sprite over foreground graphics). */
  public final int[] outSpriteBgColl = new int[8];

  // ==== Per-call inputs (set by C64Screen each cycle) ====

  /** True if this cycle has check_sprite_display active (VICE cycle 58 = case 57). */
  public boolean checkSprDisp = false;
  /** True if this cycle is a sprite ptr+dma0 cycle. */
  public boolean spritePtrDma0 = false;
  /** True if this cycle is a sprite dma1+dma2 cycle. */
  public boolean spriteDma1Dma2 = false;
  /** Sprite number for the dma cycle, if any (0..7), else -1. */
  public int spriteDmaNum = -1;
  /** Snapshot of sprite_display_bits (1<<n if sprite n is dma'd). */
  public int spriteDisplayBits = 0;
  /** Snapshot of $D01B (sprite priority register, set at pixel 6). */
  public int reg1bPipe = 0;
  /** Snapshot of $D01C (sprite multicolor register, set at pixel 6 for 8565). */
  public int reg1cPipe = 0;
  /** Snapshot of $D01D (sprite expand-X register, set at pixel 6). */
  public int reg1dPipe = 0;
  /** Snapshot of sprite[i].x for X-pipe update. */
  public final int[] currentSpriteX = new int[8];
  /** Snapshot of sprite[i].data (24-bit) loaded at pixel 4 of dma1_dma2. */
  public final int[] currentSpriteData = new int[8];
  /** Background priority (foreground was drawn) per pixel — pri_buffer[i] in VICE. */
  public final boolean[] priBuffer = new boolean[8];

  // ==== Public API ====

  public void reset() {
    for (int i = 0; i < 8; i++) {
      spriteXPipe[i] = 0;
      sbufReg[i] = 0;
      sbufPixelReg[i] = 0;
      currentSpriteData[i] = 0;
      currentSpriteX[i] = 0;
    }
    spritePriBits = spriteMcBits = spriteExpxBits = 0;
    spritePendingBits = spriteActiveBits = spriteHaltBits = 0;
    sbufExpxFlops = sbufMcFlops = 0;
    collisionThisCycle = 0;
  }

  /**
   * Run one VIC cycle = 8 pixels. Faithful port of draw_sprites8()
   * from vicii-draw-cycle.c:469-532.
   *
   * Input fields (checkSprDisp, spritePtrDma0, spriteDma1Dma2,
   *   spriteDmaNum, spriteDisplayBits, reg1bPipe, reg1cPipe, reg1dPipe,
   *   currentSpriteX[], currentSpriteData[], priBuffer[]) must be set
   *   by the caller before invoking.
   *
   * @param xpos  PAL VICE xpos for pixel 0 of this cycle.
   */
  public void drawCycle8(int xpos) {
    int dmaCycle0 = 0;
    int dmaCycle2 = 0;

    if (spritePtrDma0 && spriteDmaNum >= 0) {
      dmaCycle0 = 1 << spriteDmaNum;
    }
    if (spriteDma1Dma2 && spriteDmaNum >= 0) {
      dmaCycle2 = 1 << spriteDmaNum;
    }

    int candidateBits = getTriggerCandidates(xpos);
    collisionThisCycle = 0;

    // pixel 0
    triggerSprites(xpos + 0, candidateBits);
    drawSprites(0);
    // pixel 1
    triggerSprites(xpos + 1, candidateBits);
    drawSprites(1);
    // pixel 2: halt for DMA1/DMA2 cycle
    spriteActiveBits &= ~dmaCycle2;
    triggerSprites(xpos + 2, candidateBits);
    drawSprites(2);
    // pixel 3: set halt for DMA0 (ptr) cycle
    spriteHaltBits |= dmaCycle0;
    triggerSprites(xpos + 3, candidateBits);
    drawSprites(3);
    // pixel 4: latch display bits, load shift register
    if (checkSprDisp) {
      spritePendingBits = spriteDisplayBits;
    }
    if (spriteDma1Dma2 && spriteDmaNum >= 0) {
      // VICE viciisc/vicii-draw-cycle.c:455 — sbuf_reg = sprite.data
      // directly. Data is laid out in bits 23..0 (byte0 at 23..16,
      // byte1 at 15..8, byte2 at 7..0). Per-pixel extraction in
      // drawSprites uses (sbufReg >> 23) for hires / (sbufReg >> 22)
      // for MC, which only matches if the data lives in bits 23..0.
      sbufReg[spriteDmaNum] = currentSpriteData[spriteDmaNum] & 0xffffff;
    }
    triggerSprites(xpos + 4, candidateBits);
    drawSprites(4);
    // pixel 5
    triggerSprites(xpos + 5, candidateBits);
    drawSprites(5);
    // pixel 6: 8565 model latches priority/expandX/mc here (no color latency)
    updateSpriteMcBits8565();
    spritePriBits = reg1bPipe & 0xff;
    spriteExpxBits = reg1dPipe & 0xff;
    triggerSprites(xpos + 6, candidateBits);
    drawSprites(6);
    // pixel 7: release halt for DMA1/DMA2
    spriteHaltBits &= ~dmaCycle2;
    triggerSprites(xpos + 7, candidateBits);
    drawSprites(7);

    // pipe x-positions for next cycle
    for (int s = 0; s < 8; s++) {
      spriteXPipe[s] = currentSpriteX[s];
    }
  }

  /**
   * Get the sprite-sprite collision mask accumulated this cycle.
   * Bit s set if sprite s emitted a non-transparent pixel.
   * Caller checks count >= 2 for sprite-sprite collision.
   */
  public int getCollisionMaskThisCycle() {
    return collisionThisCycle;
  }

  // ==== Internal helpers (faithful ports) ====

  /**
   * Port of get_trigger_candidates (vicii-draw-cycle.c:304-316).
   * Returns sprite-bit mask of sprites whose X is in this 8-pixel cycle.
   */
  private int getTriggerCandidates(int xpos) {
    int candidateBits = 0;
    int xposHi = xpos & 0x1f8;
    for (int s = 0; s < 8; s++) {
      if (xposHi == (spriteXPipe[s] & 0x1f8)) {
        candidateBits |= 1 << s;
      }
    }
    return candidateBits;
  }

  /**
   * Port of trigger_sprites (vicii-draw-cycle.c:318-340).
   * Activates sprites whose X exactly matches xpos.
   */
  private void triggerSprites(int xpos, int candidateBits) {
    if (candidateBits == 0 || spritePendingBits == 0) return;
    for (int s = 0; s < 8; s++) {
      int m = 1 << s;
      if ((candidateBits & m) != 0
          && (spritePendingBits & m) != 0
          && (spriteActiveBits & m) == 0
          && (spriteHaltBits & m) == 0) {
        if (xpos == spriteXPipe[s]) {
          sbufExpxFlops |= m;
          sbufMcFlops |= m;
          spriteActiveBits |= m;
          if (TRACE && s == 0) {
            traceSink.onTrigger(xpos, sbufReg[s], spriteActiveBits,
                spritePendingBits, spriteHaltBits, currentSpriteData[s]);
          }
        }
      }
    }
  }

  /**
   * Trace hook for cross-checking the pipeline against VICE x64sc.
   * Mirror of the JaC64 trace patch in viciisc/vicii-draw-cycle.c
   * (trigger_sprites). Enabled at startup via {@link #enableTrace}.
   */
  public interface PipelineTrace {
    void onTrigger(int xpos, int sbufReg, int active, int pending, int halt, int data);
  }
  /** Snapshot getters used by the C64Screen probe path. */
  public int peekSpriteXPipe(int s) { return spriteXPipe[s]; }
  public int peekSbufReg(int s) { return sbufReg[s]; }
  public int peekActive() { return spriteActiveBits; }
  public int peekPending() { return spritePendingBits; }
  public int peekHalt() { return spriteHaltBits; }
  private PipelineTrace traceSink;
  private boolean TRACE = false;
  public void enableTrace(PipelineTrace sink) {
    this.traceSink = sink;
    this.TRACE = sink != null;
  }

  /**
   * Port of draw_sprites (vicii-draw-cycle.c:342-430).
   * Per-pixel: shifts/fetches sprite pixels, picks priority winner,
   * accumulates collision mask. Writes color code to outColorCode[i].
   */
  private void drawSprites(int i) {
    outColorCode[i] = 0;
    outSprite[i] = -1;
    outForegroundWin[i] = priBuffer[i];
    outSpriteSpriteColl[i] = 0;
    outSpriteBgColl[i] = 0;

    if (spriteActiveBits == 0) return;

    int activeSprite = -1;
    int collisionMask = 0;

    // VICE iterates 7..0 — highest sprite number = lowest priority.
    // The last assigned activeSprite wins, so iterating in this order
    // and overwriting yields the lowest-numbered (highest-priority)
    // sprite as the final winner.
    for (int s = 7; s >= 0; --s) {
      int m = 1 << s;
      if ((spriteActiveBits & m) == 0) continue;

      // Render pixels if shift register or pixel reg still has data.
      if (sbufReg[s] != 0 || sbufPixelReg[s] != 0) {
        if ((spriteHaltBits & m) == 0) {
          if ((sbufExpxFlops & m) != 0) {
            if ((spriteMcBits & m) != 0) {
              if ((sbufMcFlops & m) != 0) {
                // fetch 2 bits
                sbufPixelReg[s] = (sbufReg[s] >>> 22) & 0x03;
              }
              sbufMcFlops ^= m;
            } else {
              // fetch 1 bit, make it 0 or 2 (foreground priority)
              sbufPixelReg[s] = ((sbufReg[s] >>> 23) & 0x01) << 1;
            }
          }
          // shift the sprite buffer if expx-flop is set
          if ((sbufExpxFlops & m) != 0) {
            sbufReg[s] = (sbufReg[s] << 1) & 0xffffffff;
          }
          // toggle or set expx-flop based on $D01D
          if ((spriteExpxBits & m) != 0) {
            sbufExpxFlops ^= m;
          } else {
            sbufExpxFlops |= m;
          }
        }

        // Track winning sprite + collision
        if (sbufPixelReg[s] != 0) {
          activeSprite = s;
          collisionMask |= m;
        }
      } else {
        // No data left — deactivate
        spriteActiveBits &= ~m;
      }
    }

    // Per-pixel sprite-0 shift trace — mirrors VICE-DRAW-S0 in
    // vicii-draw-cycle.c:412. Gated by static flag so zero cost when off.
    if (TRACE_SPR_DRAW
        && (collisionMask & 1) != 0
        && traceLine >= TRACE_SPR_LINE_LO
        && traceLine <= TRACE_SPR_LINE_HI) {
      System.err.println("JAC-DRAW-S0 line=" + traceLine
          + " cyc=" + traceCyc + " pix=" + i
          + " sbufReg=$" + Integer.toHexString(sbufReg[0])
          + " px=$" + Integer.toHexString(sbufPixelReg[0]));
    }

    if (collisionMask != 0) {
      boolean pixelPri = priBuffer[i];
      int as = activeSprite;
      boolean spri = (spritePriBits & (1 << as)) != 0;
      if (!(pixelPri && spri)) {
        // Color code matches VICE COL_D025/COL_D027+s/COL_D026 cases
        int pixelVal = sbufPixelReg[as];
        outColorCode[i] = pixelVal;  // 1, 2, or 3
        outSprite[i] = as;
      } else {
        // Sprite is hidden behind foreground graphics
        outSprite[i] = as;
      }
      // Per-pixel sprite-sprite collision: 2+ sprites at this pixel.
      // Port of VICE vicii-draw-cycle.c:427-429.
      if ((collisionMask & (collisionMask - 1)) != 0) {
        outSpriteSpriteColl[i] = collisionMask;
      }
      // Per-pixel sprite-bg collision: any sprite over foreground.
      // Port of VICE vicii-draw-cycle.c:421-423.
      if (pixelPri) {
        outSpriteBgColl[i] = collisionMask;
      }
      collisionThisCycle |= collisionMask;
    }
  }

  /**
   * Port of update_sprite_mc_bits_8565 (vicii-draw-cycle.c:442-449).
   * Called at pixel 6 for 8565 (no color latency).
   */
  private void updateSpriteMcBits8565() {
    int nextMcBits = reg1cPipe & 0xff;
    int toggled = nextMcBits ^ spriteMcBits;
    sbufMcFlops ^= toggled & (~sbufExpxFlops & 0xff);
    spriteMcBits = nextMcBits;
  }
}
