package com.dreamfabric.jac64;

/**
 * Per-sprite pixel-level sequencer, modeled on VICE's
 * raster_sprite_status_t + sprite rendering loop in vicii-sprites.c.
 *
 * On each VIC pixel call {@link #nextPixel(int)} which returns the
 * multicolor index (0 = transparent, 1..3 = sprite color lookup) for
 * this pixel, or 0 if sprite is not emitting here.
 *
 * Sprite X is compared to rasterX pixel-by-pixel. When they match and
 * the sprite has DMA data ready, display starts and runs until the
 * shift register is drained. Mid-line changes to sprite.x affect only
 * sprites that have not yet matched (i.e., still waiting to start).
 */
public final class SpriteSequencer {

  // Pre-computed doubling table: each bit in a byte becomes 2 identical
  // bits in a 16-bit word (used for expanded sprites).
  // See vicii-sprites.c:395-401.
  public static final int[] SPRITE_DOUBLING_TABLE = new int[256];

  // Pre-computed MC rearrangement table: groups bit pairs.
  // See vicii-sprites.c:395-396.
  public static final int[] MCSPR_TABLE = new int[256];

  static {
    int w = 0;
    for (int i = 0; i <= 0xff; i++) {
      MCSPR_TABLE[i] = i | ((i & 0x55) << 1) | ((i & 0xaa) >> 1);
      SPRITE_DOUBLING_TABLE[i] = w;
      w++;
      w |= (w & 0x5555) << 1;
    }
  }

  private final int index;
  private final int spriteBit;   // 1 << index

  // Register state (updated by C64Screen on CPU writes / raster-change queue drain).
  public int x;                  // 0..511 (9 bits: lsb + msb)
  public int y;
  public int color;              // 0..15
  public boolean enabled;        // $D015 bit
  public boolean expandX;        // $D01D bit
  public boolean expandY;        // $D017 bit
  public boolean multicolor;     // $D01C bit
  public boolean priority;       // $D01B bit (sprite behind bg)
  public boolean dma;            // internally managed by VIC

  // Sprite data shift register (24 bits, high bit emits first).
  // Loaded by readSpriteData via C64Screen.loadSequencerData. VICE
  // uses a double-buffer (sprite_data / new_sprite_data) but for the
  // per-pixel API in JaC64 a single register suffices since fetch
  // and display phases are serialized by the cycle dispatcher.
  public int shiftRegister;

  // MC-bug state — tracks shift/load offset when multicolor mode
  // toggles mid-sprite. See vicii-mem.c:709-738 for d01c_store logic.
  // Encoded as (delayed_shift << 1) | delayed_load.
  public int mcBug;

  // Display state.
  private boolean displaying;    // currently emitting pixels
  private int remainingPixels;   // non-expanded: 24, expanded: 48
  private boolean expandXFlip;   // per-pixel doubler for expand-X
  private int currentPixel;      // multicolor index (0..3) being emitted
  private int pixelRepeat;       // how many more output pixels to emit at currentPixel

  // Per-line "has this sprite already started displaying?" — prevents
  // re-start if X changes after display started.
  private boolean startedThisLine;

  // True if DMA just activated on the previous line transition — on a
  // real VIC-II, sprite data fetched on line N becomes visible on
  // line N+1, so we skip display on the fetch line.
  private boolean justDmaActivated;
  private boolean prevDma;

  public SpriteSequencer(int index) {
    this.index = index;
    this.spriteBit = 1 << index;
  }

  public int getIndex() {
    return index;
  }

  public int getSpriteBit() {
    return spriteBit;
  }

  public boolean isDisplaying() {
    return displaying;
  }

  public boolean hasStartedThisLine() {
    return startedThisLine;
  }

  public int debugRemainingPixels() {
    return remainingPixels;
  }

  /**
   * Called at start of each raster line. Resets per-line state.
   * Does NOT clear shiftRegister (that's done by CPU or VIC at fetch
   * time depending on DMA). Does not stop an in-progress emission —
   * on real VIC, sprites complete shifting within a single line.
   */
  public void onLineStart() {
    startedThisLine = false;
    displaying = false;
    remainingPixels = 0;
    expandXFlip = false;
    currentPixel = 0;
    pixelRepeat = 0;

    // Detect DMA rising edge; on the line where DMA just became
    // active the sprite should NOT display yet (data is being fetched
    // this line on real VIC-II, display starts next line).
    justDmaActivated = dma && !prevDma;
    prevDma = dma;
  }

  /**
   * Called when DMA activates for this sprite. Resets expandXFlip
   * which tracks the 2-pixel double pattern within one "logical"
   * sprite pixel.
   */
  public void onDmaStart() {
    expandXFlip = false;
  }

  /**
   * Emit the pixel (or transparent if not displaying) at the given
   * raster X. Must be called in monotonically-increasing rasterX
   * order per line.
   *
   * Returns 0 (transparent) or the multicolor index (1..3 for MC,
   * 0 or 1 for hi-res). The caller translates to an actual color.
   */
  // Snapshot of mcBug at the moment the sprite started display —
  // used to decide whether to apply MC-bug shift to the shift register.
  private int mcBugAppliedAtStart;

  public int nextPixel(int rasterX) {
    // Start display on X match (only once per line, and only if
    // DMA'd). Skip the line where DMA just activated — on real VIC,
    // data is being fetched this line and becomes visible next line.
    if (!startedThisLine && !displaying && dma && !justDmaActivated
        && enabled && rasterX == x) {
      displaying = true;
      startedThisLine = true;
      // Output pixel count: 24 normal, 48 expanded. Decrements per
      // call regardless of shift vs repeat.
      remainingPixels = expandX ? 48 : 24;
      pixelRepeat = 0;
      currentPixel = 0;
      mcBugAppliedAtStart = mcBug;
    }

    if (!displaying) {
      return 0;
    }

    // Detect mid-sprite mcBug change: if mcBug differs from what we
    // snapshotted at display start, apply VICE's MC-bug effects
    // (vicii-sprites.c:782-796). delayed_shift pushes sprite 2
    // pixels to the right AND shifts data 1 bit; net effect is the
    // sprite covers 2 extra output pixels at the end. delayed_load
    // masks a specific pixel off.
    if (mcBug != mcBugAppliedAtStart) {
      int delayedShift = (mcBug >> 1) & 1;
      // Extend sprite width by the delayed_shift amount — VICE's
      // `trim_size += 2` effect. Clamp to avoid runaway.
      if (delayedShift != 0 && remainingPixels > 0
          && remainingPixels < (expandX ? 50 : 26)) {
        remainingPixels += 2;
      }
      mcBugAppliedAtStart = mcBug;
    }

    int out;
    if (pixelRepeat > 0) {
      pixelRepeat--;
      out = currentPixel;
    } else {
      // Shift next pixel out of register.
      if (multicolor) {
        currentPixel = (shiftRegister >> 22) & 0x3;
        shiftRegister = (shiftRegister << 2) & 0xffffff;
        // Each MC pixel covers 2 output pixels (non-expanded) or 4
        // (expanded). We've emitted 1 of them here.
        pixelRepeat = expandX ? 3 : 1;
      } else {
        currentPixel = (shiftRegister >> 23) & 0x1;
        shiftRegister = (shiftRegister << 1) & 0xffffff;
        pixelRepeat = expandX ? 1 : 0;
      }
      out = currentPixel;
    }

    // Each nextPixel call emits one output pixel — count them.
    remainingPixels--;
    if (remainingPixels <= 0) {
      displaying = false;
    }
    return out;
  }
}
