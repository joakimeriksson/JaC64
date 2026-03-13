# JaC64 Android - Full 50 FPS Rendering Plan

## Problem

The emulator runs at correct C64 speed (audio-synced), but the Android
screen rendering drops frames. The render thread polls every 18ms with
`Thread.sleep(18)` independently of actual frame completion — it doesn't
know when a new frame is ready.

## Current Architecture

```
CPU Thread (no sleep, runs full speed)
  └─> emulateOp() increments cycles
  └─> schedule(cycles) calls C64Screen.clock()
        └─> VIC-II renders scan lines into int[] mem (384x284 ARGB)
        └─> At vPos == 285: screenRefreshListener.onFrameReady()  [UNUSED!]
        └─> SIDMixer.updateSound() writes audio to AudioTrack
              └─> Sleeps to match 50 Hz via AudioDriver.getMicros()
              └─> This is the actual timing master

Render Thread (independent, decoupled)
  └─> Thread.sleep(18)
  └─> screen.getPixelBuffer() → reads int[] mem directly
  └─> screenBitmap.setPixels() + canvas.drawBitmap()
  └─> Repeat
```

**Key problem:** The render thread and emulation are completely unsynchronized.
The render thread may read the pixel buffer mid-scanline (tearing), draw the
same frame twice, or skip frames entirely.

## Root Cause Analysis

1. `ScreenRefreshListener.onFrameReady()` exists in C64Screen but is never
   connected — EmulatorSurfaceView doesn't register as a listener
2. The render thread uses blind 18ms polling instead of waiting for frame signals
3. No double-buffering — the render thread reads the same `int[] mem` array
   that the VIC-II is actively writing to (potential tearing)
4. `Bitmap.setPixels()` copies the entire 384x284 buffer every frame even
   if nothing changed

## Plan

### Phase 1: Signal-driven rendering (biggest impact)

**Goal:** Render thread draws exactly when a frame is ready, not on a timer.

**Files to change:**
- `EmulatorSurfaceView.java`
- `MainActivity.java`

**Steps:**

1. Have EmulatorSurfaceView implement `C64Screen.ScreenRefreshListener`
2. In `onFrameReady()` (called from CPU thread at vPos==285), signal the
   render thread via `Object.notify()` or a `CountDownLatch`
3. Replace `Thread.sleep(18)` in the render loop with `wait()` on the
   frame-ready signal (with a timeout fallback of ~25ms)
4. Register the listener: `screen.setScreenRefreshListener(emulatorView)`
   in MainActivity after connecting the screen

**Expected result:** Every emulated frame gets rendered exactly once.
No skipped frames, no redundant draws.

```java
// EmulatorSurfaceView - new approach
private final Object frameLock = new Object();
private volatile boolean frameReady = false;

// Called from CPU thread when VIC-II completes a frame
public void onFrameReady() {
    synchronized (frameLock) {
        frameReady = true;
        frameLock.notify();
    }
}

// Render thread
while (running) {
    synchronized (frameLock) {
        if (!frameReady) {
            frameLock.wait(25); // timeout prevents deadlock
        }
        frameReady = false;
    }
    Canvas canvas = surfaceHolder.lockCanvas();
    if (canvas != null) {
        drawFrame(canvas);
        surfaceHolder.unlockCanvasAndPost(canvas);
    }
}
```

### Phase 2: Double-buffered pixel data (eliminates tearing)

**Goal:** VIC-II writes to one buffer while the render thread reads from another.

**Files to change:**
- `C64Screen.java`

**Steps:**

1. Add a second pixel buffer: `int[] memBack = new int[SC_WIDTH * (SC_HEIGHT + 10)]`
2. VIC-II renders into `memBack` (the back buffer)
3. At frame completion (vPos==285), swap the buffers:
   ```java
   int[] tmp = mem;
   mem = memBack;
   memBack = tmp;
   ```
4. `getPixelBuffer()` returns the front buffer (`mem`) which is now stable
5. The swap is a single reference assignment — effectively free

**Expected result:** No tearing artifacts. The render thread always reads
a complete, stable frame.

### Phase 3: Optimize the bitmap path (reduces per-frame cost)

**Goal:** Minimize the cost of getting pixels to the screen.

**Option A: Direct Bitmap pixel access (simpler)**
- Use `Bitmap.copyPixelsFromBuffer()` with an IntBuffer wrapping the pixel
  array — can be faster than `setPixels()` on some devices
- Benchmark both approaches

**Option B: OpenGL ES / GLSurfaceView (maximum performance)**
- Replace SurfaceView with GLSurfaceView
- Upload pixel buffer as a texture via `glTexImage2D()` or `glTexSubImage2D()`
- Draw a full-screen textured quad
- GPU handles scaling with hardware bilinear filtering
- This is the approach used by most high-performance emulators (RetroArch, etc.)
- More complex but gives the best results and enables shader effects later
  (CRT scanlines, phosphor glow, etc.)

**Option C: Hardware-accelerated Canvas (middle ground)**
- Enable hardware acceleration on the SurfaceView
- Use `BitmapShader` + `drawRect()` instead of `drawBitmap()` for
  potentially better GPU utilization
- Simpler than OpenGL but less control

**Recommendation:** Start with Option A, measure. If still not enough, go
to Option B.

### Phase 4: Choreographer sync (polish)

**Goal:** Align frame presentation with Android's display vsync.

**Steps:**

1. Use `Choreographer.getInstance().postFrameCallback()` to time draws
   to the display's vsync signal
2. This prevents presenting frames at odd times that cause visible judder
3. If the C64's 50 Hz doesn't divide evenly into the display's refresh
   rate (e.g., 60 Hz), implement frame pacing:
   - Show most frames for 1 vsync period
   - Every 5th frame, show for 2 vsync periods (to match 50→60 conversion)
   - Or better: request 50 Hz display mode if supported (Android 11+)

### Phase 5: Frame skip / fast-forward (nice to have)

**Goal:** Allow the emulation to run faster than real-time when needed.

- If the render thread can't keep up, skip rendering but keep emulating
- Add a "fast forward" button that disables audio sync and renders every
  Nth frame
- Useful for loading games from disk (LOAD "*",8,1 takes ~30 seconds)

## Priority Order

| Phase | Impact | Effort | Priority |
|-------|--------|--------|----------|
| 1. Signal-driven rendering | High | Low | Do first |
| 2. Double buffering | Medium | Low | Do second |
| 3. Optimize bitmap path | Medium | Medium | Do third |
| 4. Choreographer sync | Low | Medium | Polish |
| 5. Frame skip | Low | Low | Nice to have |

## Key Files Reference

| File | What to change |
|------|----------------|
| `EmulatorSurfaceView.java` | Implement ScreenRefreshListener, replace sleep with wait/notify |
| `C64Screen.java:1346` | onFrameReady() call site, add buffer swap |
| `C64Screen.java:189` | Pixel buffer declaration, add back buffer |
| `C64Screen.java:236` | getPixelBuffer() — return front buffer |
| `MainActivity.java` | Register screen refresh listener on EmulatorSurfaceView |
| `AudioDriverAndroid.java:87` | getMicros() — timing reference (no changes needed) |
| `SIDMixer.java:540` | Audio sync sleep (no changes needed, this is correct) |

## Notes

- The C64 emulation timing is correct — it's driven by SID audio output
  at 50 Hz PAL rate. Don't change the emulation timing.
- The CPU loop has no sleep — it runs as fast as needed, throttled by
  SIDMixer.updateSound() which sleeps to maintain real-time audio sync.
- Phase 1 alone should get us to solid 50 FPS with minimal code changes.
