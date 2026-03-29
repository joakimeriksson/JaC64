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

    // C64 keyboard layout - rows of key labels
    private static final String[][] KEY_LABELS = {
        {"<-", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "+", "-", "\u00a3", "HOME", "DEL"},
        {"CTRL", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "@", "*", "\u2191", "RSTR"},
        {"R/S", "SL", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", ";", "=", "RETURN"},
        {"C=", "SHFT", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "SHFT", "U", "D"},
        {"", "", "", "SPACE", "", "", "", "", "", "", "", "", "L", "", "R"}
    };

    // C64 keyboard matrix positions: {row, col, flags}
    // row=-1: inactive, row=-2: RESTORE key, flags=1: auto-shift
    private static final int[][][] KEY_MATRIX = {
        // <-, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, +, -, £, HOME, DEL
        {{7,1,0}, {7,0,0}, {7,3,0}, {1,0,0}, {1,3,0}, {2,0,0}, {2,3,0}, {3,0,0}, {3,3,0}, {4,0,0}, {4,3,0}, {5,0,0}, {5,3,0}, {6,0,0}, {6,3,0}, {0,0,0}},
        // CTRL, Q, W, E, R, T, Y, U, I, O, P, @, *, up-arrow, RESTORE
        {{7,2,0}, {7,6,0}, {1,1,0}, {1,6,0}, {2,1,0}, {2,6,0}, {3,1,0}, {3,6,0}, {4,1,0}, {4,6,0}, {5,1,0}, {5,6,0}, {6,1,0}, {6,6,0}, {-2,0,0}},
        // R/S, SL(inactive), A, S, D, F, G, H, J, K, L, :, ;, =, RETURN
        {{7,7,0}, {-1,0,0}, {1,2,0}, {1,5,0}, {2,2,0}, {2,5,0}, {3,2,0}, {3,5,0}, {4,2,0}, {4,5,0}, {5,2,0}, {5,5,0}, {6,2,0}, {6,5,0}, {0,1,0}},
        // C=, LSHIFT, Z, X, C, V, B, N, M, comma, period, /, RSHIFT, CRS-UP, CRS-DOWN
        {{7,5,0}, {1,7,0}, {1,4,0}, {2,7,0}, {2,4,0}, {3,7,0}, {3,4,0}, {4,7,0}, {4,4,0}, {5,7,0}, {5,4,0}, {6,7,0}, {6,4,0}, {0,7,1}, {0,7,0}},
        // (empty x3), SPACE, (empty x8), CRS-LEFT, (empty), CRS-RIGHT
        {{-1,0,0}, {-1,0,0}, {-1,0,0}, {7,4,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {0,2,1}, {-1,0,0}, {0,2,0}}
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
                if (KEY_LABELS[r][c].isEmpty() || KEY_MATRIX[r][c][0] == -1) continue;

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
                    int[] matrix = KEY_MATRIX[r][c];
                    if (matrix[0] >= 0 && !keyPressed[r][c]) {
                        keyPressed[r][c] = true;
                        if (matrix[2] == 1) {
                            keyboard.pressC64Key(1, 7); // auto-shift
                        }
                        keyboard.pressC64Key(matrix[0], matrix[1]);
                    } else if (matrix[0] == -2 && !keyPressed[r][c]) {
                        keyPressed[r][c] = true;
                        keyboard.pressRestoreKey();
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
                    int[] matrix = KEY_MATRIX[r][c];
                    if (matrix[0] >= 0) {
                        keyboard.releaseC64Key(matrix[0], matrix[1]);
                        if (matrix[2] == 1) {
                            keyboard.releaseC64Key(1, 7); // auto-shift
                        }
                    } else if (matrix[0] == -2) {
                        keyboard.releaseRestoreKey();
                    }
                }
            }
        }
    }
}
