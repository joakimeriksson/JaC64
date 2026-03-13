package com.dreamfabric.jac64.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.dreamfabric.jac64.Keyboard;

/**
 * On-screen virtual C64 keyboard.
 * Renders a simplified C64 keyboard layout and maps touches to key events.
 */
public class VirtualKeyboardView extends View {

    // C64 keyboard layout - rows of key labels and their keycodes
    private static final String[][] KEY_LABELS = {
        {"<-", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "+", "-", "\u00a3", "HOME", "DEL"},
        {"CTRL", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "@", "*", "^", "RSTR"},
        {"R/S", "SL", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", ";", "=", "RETURN"},
        {"C=", "SHFT", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "SHFT", "U", "D"},
        {"", "", "", "SPACE", "", "", "", "", "", "", "", "", "L", "", "R"}
    };

    // Key codes matching Keyboard.java constants
    // Using the same int values as java.awt.event.KeyEvent VK_* constants
    private static final int[][] KEY_CODES = {
        {192, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 45, 61, 92, Keyboard.VK_HOME, Keyboard.VK_BACK_SPACE},
        {Keyboard.VK_TAB, 81, 87, 69, 82, 84, 89, 85, 73, 79, 80, 91, 93, -1, Keyboard.VK_PAGE_UP},
        {Keyboard.VK_ESCAPE, -1, 65, 83, 68, 70, 71, 72, 74, 75, 76, 59, 39, -1, Keyboard.VK_ENTER},
        {Keyboard.VK_CONTROL, Keyboard.VK_SHIFT, 90, 88, 67, 86, 66, 78, 77, 44, 46, 47, Keyboard.VK_CAPS_LOCK, Keyboard.VK_UP, Keyboard.VK_DOWN},
        {-1, -1, -1, 32, -1, -1, -1, -1, -1, -1, -1, -1, Keyboard.VK_LEFT, -1, Keyboard.VK_RIGHT}
    };

    // Width weight for each key in each row (wider keys get more weight)
    private static final float[][] KEY_WIDTHS = {
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5f, 1.5f},
        {1.5f, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5f},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2},
        {1.5f, 1.5f, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5f, 1, 1},
        {1, 1, 1, 8, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
    };

    private Paint keyPaint;
    private Paint keyPressedPaint;
    private Paint textPaint;
    private Paint bgPaint;

    private Keyboard keyboard;
    private RectF[][] keyRects;
    private boolean[][] keyPressed;
    private int activePointerId = -1;

    public VirtualKeyboardView(Context context) {
        this(context, null);
    }

    public VirtualKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint = new Paint();
        bgPaint.setColor(0xCC333333);

        keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        keyPaint.setColor(0xCC555555);
        keyPaint.setStyle(Paint.Style.FILL);

        keyPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        keyPressedPaint.setColor(0xCC999999);
        keyPressedPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xCCCCCCCC);
        textPaint.setTextAlign(Paint.Align.CENTER);

        keyPressed = new boolean[KEY_LABELS.length][];
        for (int r = 0; r < KEY_LABELS.length; r++) {
            keyPressed[r] = new boolean[KEY_LABELS[r].length];
        }
    }

    public void setKeyboard(Keyboard keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildKeyRects(w, h);
    }

    private void buildKeyRects(int w, int h) {
        float padding = 2;
        float rowHeight = h / (float) KEY_LABELS.length;
        textPaint.setTextSize(rowHeight * 0.35f);

        keyRects = new RectF[KEY_LABELS.length][];

        for (int r = 0; r < KEY_LABELS.length; r++) {
            keyRects[r] = new RectF[KEY_LABELS[r].length];
            float totalWeight = 0;
            for (float wt : KEY_WIDTHS[r]) totalWeight += wt;

            float x = 0;
            float y = r * rowHeight;
            for (int c = 0; c < KEY_LABELS[r].length; c++) {
                float keyW = (KEY_WIDTHS[r][c] / totalWeight) * w;
                keyRects[r][c] = new RectF(
                    x + padding, y + padding,
                    x + keyW - padding, y + rowHeight - padding
                );
                x += keyW;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Background
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        if (keyRects == null) return;

        for (int r = 0; r < KEY_LABELS.length; r++) {
            for (int c = 0; c < KEY_LABELS[r].length; c++) {
                if (KEY_LABELS[r][c].isEmpty() || KEY_CODES[r][c] == -1) continue;

                RectF rect = keyRects[r][c];
                Paint p = keyPressed[r][c] ? keyPressedPaint : keyPaint;

                // Draw key background with rounded corners
                canvas.drawRoundRect(rect, 4, 4, p);

                // Draw key label
                float textY = rect.centerY() + textPaint.getTextSize() * 0.35f;
                canvas.drawText(KEY_LABELS[r][c], rect.centerX(), textY, textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (keyboard == null || keyRects == null) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE:
                // Clear all and re-evaluate from all pointers
                clearAllKeys();
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float x = event.getX(i);
                    float y = event.getY(i);
                    pressKeyAt(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                clearAllKeys();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                clearAllKeys();
                int upIndex = event.getActionIndex();
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i != upIndex) {
                        pressKeyAt(event.getX(i), event.getY(i));
                    }
                }
                break;
        }

        invalidate();
        return true;
    }

    private void pressKeyAt(float x, float y) {
        for (int r = 0; r < keyRects.length; r++) {
            for (int c = 0; c < keyRects[r].length; c++) {
                if (keyRects[r][c].contains(x, y)) {
                    int keyCode = KEY_CODES[r][c];
                    if (keyCode != -1 && !keyPressed[r][c]) {
                        keyPressed[r][c] = true;
                        keyboard.keyPressed(keyCode, 0);
                    }
                    return;
                }
            }
        }
    }

    private void clearAllKeys() {
        for (int r = 0; r < keyPressed.length; r++) {
            for (int c = 0; c < keyPressed[r].length; c++) {
                if (keyPressed[r][c]) {
                    keyPressed[r][c] = false;
                    int keyCode = KEY_CODES[r][c];
                    if (keyCode != -1) {
                        keyboard.keyReleased(keyCode, 0);
                    }
                }
            }
        }
    }
}
