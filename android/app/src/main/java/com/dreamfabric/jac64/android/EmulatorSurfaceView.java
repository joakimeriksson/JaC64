package com.dreamfabric.jac64.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.IntBuffer;

import com.dreamfabric.jac64.C64Screen;
import com.dreamfabric.jac64.CPU;

/**
 * SurfaceView that renders the C64 screen at 50Hz (PAL).
 * Listens for frame-ready signals from the VIC-II emulation
 * and renders each completed frame exactly once.
 */
public class EmulatorSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, C64Screen.ScreenRefreshListener {

    private static final int SCREEN_WIDTH = 384;
    private static final int SCREEN_HEIGHT = 284;

    private C64Screen screen;
    private CPU cpu;
    private RenderThread renderThread;
    private Bitmap screenBitmap;
    private final Paint paint;
    private final Rect srcRect;
    private final Rect dstRect;

    // Frame signaling from emulation thread to render thread
    private final Object frameLock = new Object();
    private volatile boolean frameReady = false;

    // FPS counter
    private int frameCount = 0;
    private long fpsStartTime = 0;
    private String fpsText = "";
    private final Paint fpsPaint = new Paint();

    // Drive LED indicator
    private final Paint ledOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledOffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Touch-to-paddle mapping
    private boolean touchEnabled = false;
    private float touchX = -1, touchY = -1;
    private boolean touching = false;
    private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EmulatorSurfaceView(Context context) {
        this(context, null);
    }

    public EmulatorSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        paint = new Paint();
        paint.setFilterBitmap(true);
        srcRect = new Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        dstRect = new Rect();
        screenBitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888);
        fpsPaint.setColor(Color.YELLOW);
        fpsPaint.setTextSize(28);
        fpsPaint.setAntiAlias(true);

        ledOnPaint.setColor(0xFF00CC00);  // bright green
        ledOffPaint.setColor(0xFF003300); // dim green
        touchPaint.setColor(0x60FFFFFF);
        touchPaint.setStyle(Paint.Style.STROKE);
        touchPaint.setStrokeWidth(3);
    }

    public void setScreen(C64Screen screen) {
        this.screen = screen;
        screen.setScreenRefreshListener(this);
    }

    public void setCPU(CPU cpu) {
        this.cpu = cpu;
    }

    /**
     * Called from the CPU/VIC-II thread when a frame is complete (vPos == 285).
     * Wakes the render thread to draw the frame.
     */
    @Override
    public void onFrameReady() {
        synchronized (frameLock) {
            frameReady = true;
            frameLock.notify();
        }
    }

    public void setTouchEnabled(boolean enabled) {
        this.touchEnabled = enabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!touchEnabled || screen == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
                touching = true;
                // Map touch position within the screen rect to paddle (0-255)
                int px, py;
                if (!dstRect.isEmpty() && dstRect.width() > 0) {
                    px = (int) (((touchX - dstRect.left) / dstRect.width()) * 255);
                    py = (int) (((touchY - dstRect.top) / dstRect.height()) * 255);
                } else {
                    px = (int) ((touchX / getWidth()) * 255);
                    py = (int) ((touchY / getHeight()) * 255);
                }
                px = Math.max(0, Math.min(255, px));
                py = Math.max(0, Math.min(255, py));
                screen.setPointerPosition(px, py);
                screen.setPointerButton(1, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                screen.setPointerButton(1, false);
                return true;
        }
        return false;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderThread = new RenderThread(holder);
        renderThread.setRunning(true);
        renderThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        float scaleX = (float) width / SCREEN_WIDTH;
        float scaleY = (float) height / SCREEN_HEIGHT;
        float scale = Math.min(scaleX, scaleY);

        int scaledWidth = (int) (SCREEN_WIDTH * scale);
        int scaledHeight = (int) (SCREEN_HEIGHT * scale);
        int offsetX = (width - scaledWidth) / 2;
        int offsetY = (height - scaledHeight) / 2;
        dstRect.set(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (renderThread != null) {
            renderThread.setRunning(false);
            // Wake the thread in case it's waiting
            synchronized (frameLock) {
                frameLock.notify();
            }
            boolean retry = true;
            while (retry) {
                try {
                    renderThread.join();
                    retry = false;
                } catch (InterruptedException e) {
                    // retry
                }
            }
            renderThread = null;
        }
    }

    private class RenderThread extends Thread {
        private final SurfaceHolder surfaceHolder;
        private volatile boolean running;

        RenderThread(SurfaceHolder holder) {
            this.surfaceHolder = holder;
            setName("C64-Render");
        }

        void setRunning(boolean run) {
            this.running = run;
        }

        @Override
        public void run() {
            while (running) {
                // Wait for frame-ready signal from VIC-II
                boolean render;
                synchronized (frameLock) {
                    if (!frameReady) {
                        try {
                            frameLock.wait(25); // timeout prevents deadlock
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    render = frameReady;
                    frameReady = false;
                }
                if (!render) continue;

                Canvas canvas = null;
                try {
                    canvas = surfaceHolder.lockHardwareCanvas();
                    if (canvas != null) {
                        drawFrame(canvas);
                    }
                } catch (Exception e) {
                    // Surface may have been destroyed
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }
        }

        private void drawFrame(Canvas canvas) {
            canvas.drawColor(Color.BLACK);

            if (screen == null) return;

            int[] pixels = screen.getPixelBuffer();
            if (pixels == null) return;

            try {
                screenBitmap.setPixels(pixels, 0, SCREEN_WIDTH, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            } catch (Exception e) {
                return;
            }

            if (!dstRect.isEmpty()) {
                canvas.drawBitmap(screenBitmap, srcRect, dstRect, paint);
            }

            // FPS counter
            frameCount++;
            long now = System.nanoTime();
            if (fpsStartTime == 0) fpsStartTime = now;
            long elapsed = now - fpsStartTime;
            if (elapsed >= 1_000_000_000L) {
                fpsText = frameCount + " FPS";
                frameCount = 0;
                fpsStartTime = now;
            }
            if (!fpsText.isEmpty()) {
                canvas.drawText(fpsText, 10, 30, fpsPaint);
            }

            // Touch paddle indicator
            if (touchEnabled && touching) {
                canvas.drawCircle(touchX, touchY, 30, touchPaint);
                // Draw vertical line showing paddle X position
                touchPaint.setStrokeWidth(1);
                canvas.drawLine(touchX, dstRect.top, touchX, dstRect.bottom, touchPaint);
                touchPaint.setStrokeWidth(3);
            }

            // Drive LED indicator (bottom-center)
            if (cpu != null) {
                boolean led = cpu.getDrive().chips.ledOn;
                float ledX = canvas.getWidth() / 2f;
                float ledY = canvas.getHeight() - 12;
                canvas.drawCircle(ledX, ledY, 6, led ? ledOnPaint : ledOffPaint);
            }
        }
    }
}
