package com.dreamfabric.jac64.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.dreamfabric.jac64.Keyboard;

/**
 * On-screen virtual joystick with directional control and fire button.
 */
public class VirtualJoystickView extends View {

    private Paint bgPaint;
    private Paint activePaint;
    private Paint firePaint;
    private Paint fireActivePaint;
    private Paint spacePaint;
    private Paint spaceActivePaint;

    private Keyboard keyboard;

    private boolean up, down, left, right, fire, space;
    private float centerX, centerY, radius;
    private float fireCenterX, fireCenterY, fireRadius;
    private float spaceCenterX, spaceCenterY, spaceRadius;

    public VirtualJoystickView(Context context) {
        this(context, null);
    }

    public VirtualJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0x40FFFFFF);
        bgPaint.setStyle(Paint.Style.FILL);

        activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        activePaint.setColor(0x80FFFFFF);
        activePaint.setStyle(Paint.Style.FILL);

        firePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        firePaint.setColor(0x40FF4444);
        firePaint.setStyle(Paint.Style.FILL);

        fireActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fireActivePaint.setColor(0xC0FF4444);
        fireActivePaint.setStyle(Paint.Style.FILL);

        spacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spacePaint.setColor(0x404488FF);
        spacePaint.setStyle(Paint.Style.FILL);

        spaceActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spaceActivePaint.setColor(0xC04488FF);
        spaceActivePaint.setStyle(Paint.Style.FILL);
    }

    public void setKeyboard(Keyboard keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // D-pad on left side of screen
        float dpadSize = Math.min(h * 0.95f, w * 0.25f);
        centerX = dpadSize / 2 + 15;
        centerY = h / 2f;
        radius = dpadSize / 2 - 5;

        // Fire button on right side, space button above it
        fireRadius = Math.min(w * 0.08f, h * 0.25f);
        fireCenterX = w - fireRadius - 20;
        fireCenterY = h / 2f + fireRadius + 10;

        spaceRadius = fireRadius * 0.75f;
        spaceCenterX = fireCenterX;
        spaceCenterY = h / 2f - spaceRadius - 10;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw D-pad background circle
        canvas.drawCircle(centerX, centerY, radius, bgPaint);

        // Draw active directions
        if (up) drawDirectionArc(canvas, 0, -1);
        if (down) drawDirectionArc(canvas, 0, 1);
        if (left) drawDirectionArc(canvas, -1, 0);
        if (right) drawDirectionArc(canvas, 1, 0);

        // Draw cross lines on d-pad
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0x30FFFFFF);
        linePaint.setStrokeWidth(2);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, linePaint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, linePaint);

        // Draw fire button
        canvas.drawCircle(fireCenterX, fireCenterY, fireRadius, fire ? fireActivePaint : firePaint);

        // Draw "FIRE" text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xCCFFFFFF);
        textPaint.setTextSize(fireRadius * 0.5f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("FIRE", fireCenterX, fireCenterY + fireRadius * 0.15f, textPaint);

        // Draw space button
        canvas.drawCircle(spaceCenterX, spaceCenterY, spaceRadius, space ? spaceActivePaint : spacePaint);
        textPaint.setTextSize(spaceRadius * 0.5f);
        canvas.drawText("SPACE", spaceCenterX, spaceCenterY + spaceRadius * 0.15f, textPaint);
    }

    private void drawDirectionArc(Canvas canvas, int dx, int dy) {
        float size = radius * 0.4f;
        float ox = centerX + dx * radius * 0.5f;
        float oy = centerY + dy * radius * 0.5f;
        canvas.drawCircle(ox, oy, size, activePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean oldUp = up, oldDown = down, oldLeft = left, oldRight = right, oldFire = fire, oldSpace = space;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE:
                up = down = left = right = fire = space = false;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float x = event.getX(i);
                    float y = event.getY(i);
                    processTouch(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                up = down = left = right = fire = space = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                up = down = left = right = fire = space = false;
                int upIndex = event.getActionIndex();
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i != upIndex) {
                        processTouch(event.getX(i), event.getY(i));
                    }
                }
                break;
        }

        if (up != oldUp || down != oldDown || left != oldLeft ||
            right != oldRight || fire != oldFire || space != oldSpace) {
            updateKeyboard();
            invalidate();
        }
        return true;
    }

    private void processTouch(float x, float y) {
        // Check fire button
        float fdx = x - fireCenterX;
        float fdy = y - fireCenterY;
        if (fdx * fdx + fdy * fdy <= fireRadius * fireRadius * 1.5f) {
            fire = true;
            return;
        }

        // Check space button
        float sdx = x - spaceCenterX;
        float sdy = y - spaceCenterY;
        if (sdx * sdx + sdy * sdy <= spaceRadius * spaceRadius * 1.5f) {
            space = true;
            return;
        }

        // Check D-pad
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < radius * 1.2f && dist > radius * 0.15f) {
            float angle = (float) Math.atan2(dy, dx);
            float deadZone = (float) Math.PI / 8;

            if (angle > -Math.PI + deadZone && angle < -deadZone) up = true;
            if (angle > deadZone && angle < Math.PI - deadZone) down = true;
            if (Math.abs(angle) > Math.PI / 2 + deadZone) left = true;
            if (Math.abs(angle) < Math.PI / 2 - deadZone) right = true;
        }
    }

    private boolean prevSpace = false;

    private void updateKeyboard() {
        if (keyboard == null) return;

        int joy = 0xff;
        if (up) joy &= ~Keyboard.STICK_UP;
        if (down) joy &= ~Keyboard.STICK_DOWN;
        if (left) joy &= ~Keyboard.STICK_LEFT;
        if (right) joy &= ~Keyboard.STICK_RIGHT;
        if (fire) joy &= ~Keyboard.STICK_FIRE;
        keyboard.setJoystickState(joy);

        // Press/release Space key on the C64 keyboard matrix
        if (space && !prevSpace) {
            keyboard.keyPressed(32, 0); // 32 = space
        } else if (!space && prevSpace) {
            keyboard.keyReleased(32, 0);
        }
        prevSpace = space;
    }
}
