package com.dreamfabric.jac64.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.dreamfabric.jsidplay.PSIDFile;
import com.dreamfabric.jsidplay.SIDStateSnapshot;

/**
 * Real-time SID visualization view for Android.
 * Renders oscilloscope waveforms, ADSR envelopes, piano roll, and filter state.
 * Port of the desktop SIDVisualizationPanel to Android Canvas.
 */
public class SIDVisualizationView extends View {

    // C64-inspired color palette
    private static final int BG_DARK = 0xFF16163B;
    private static final int BG_PANEL = 0xFF1E1E50;
    private static final int BG_SCOPE = 0xFF0A0A2A;
    private static final int GRID_COLOR = 0xFF2A2A60;
    private static final int TEXT_PRIMARY = 0xFF7B71D5;
    private static final int TEXT_DIM = 0xFF4A448A;
    private static final int TEXT_BRIGHT = 0xFFA098F0;
    private static final int BORDER_COLOR = 0xFF3A3480;
    private static final int METADATA_BG = 0xFF121234;

    // Per-voice colors
    private static final int[] VOICE_COLORS = {
        0xFFF05040,  // Voice 1: Red-orange
        0xFF50D0E0,  // Voice 2: Cyan
        0xFF60D050,  // Voice 3: Green
    };
    private static final int[] VOICE_COLORS_DIM = {
        0xFF803028, 0xFF307078, 0xFF387030,
    };
    private static final int[] VOICE_COLORS_GLOW = {
        0x60FF8070, 0x6080F0FF, 0x6090FF80,
    };

    // Filter mode colors
    private static final int FILTER_LP = 0xFF5080F0;
    private static final int FILTER_BP = 0xFFF0D030;
    private static final int FILTER_HP = 0xFFF05050;

    // Piano roll colors
    private static final int KEY_WHITE = 0xFFD0D0D0;
    private static final int KEY_BLACK = 0xFF202030;
    private static final int KEY_BG = 0xFF101028;

    // Digi colors
    private static final int DIGI_COLOR = 0xFFF0C030;
    private static final int DIGI_GLOW = 0x60F0D040;
    private static final int MIX_COLOR = 0xFFA098F0;
    private static final int MIX_GLOW = 0x50A098F0;

    // Layout proportions
    private static final float METADATA_RATIO = 0.10f;
    private static final float SCOPE_RATIO = 0.38f;
    private static final float ENVELOPE_RATIO = 0.14f;
    private static final float PIANO_RATIO = 0.22f;
    // remaining = filter bar

    private float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scopePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rectF = new RectF();

    private volatile SIDStateSnapshot snapshot;
    private PSIDFile psidInfo;
    private int currentSong = 1;

    public SIDVisualizationView(Context context) {
        super(context);
        init();
    }

    public SIDVisualizationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SIDVisualizationView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        scopePaint.setStyle(Paint.Style.STROKE);
        scopePaint.setStrokeCap(Paint.Cap.ROUND);
        scopePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setSnapshot(SIDStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void setPSIDInfo(PSIDFile psid) {
        this.psidInfo = psid;
    }

    public void setCurrentSong(int song) {
        this.currentSong = song;
    }

    private int dp(float dp) { return (int) (dp * density); }
    private float sp(float sp) { return sp * density; }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        canvas.drawColor(BG_DARK);

        int pad = dp(4);
        int metaH = (int) (h * METADATA_RATIO);
        int filterH = dp(52);
        int contentH = h - metaH - filterH;
        int scopeH = (int) (contentH * SCOPE_RATIO);
        int envH = (int) (contentH * ENVELOPE_RATIO);
        int pianoH = contentH - scopeH - envH;

        int y = 0;

        // 1. Metadata bar
        drawMetadata(canvas, 0, y, w, metaH);
        y += metaH;

        // 2. Oscilloscopes - 2x2 grid
        int halfW = (w - pad * 3) / 2;
        int halfScopeH = (scopeH - pad * 3) / 2;
        drawOscilloscope(canvas, 0, pad, y + pad, halfW, halfScopeH);
        drawOscilloscope(canvas, 1, pad * 2 + halfW, y + pad, halfW, halfScopeH);
        drawOscilloscope(canvas, 2, pad, y + pad * 2 + halfScopeH, halfW, halfScopeH);
        drawDigiScope(canvas, pad * 2 + halfW, y + pad * 2 + halfScopeH, halfW, halfScopeH);
        y += scopeH;

        // 3. Envelope displays
        int envW = (w - pad * 4) / 3;
        for (int i = 0; i < 3; i++) {
            drawEnvelope(canvas, i, pad + i * (envW + pad), y + pad / 2, envW, envH - pad);
        }
        y += envH;

        // 4. Piano roll
        drawPianoRoll(canvas, pad, y + pad / 2, w - pad * 2, pianoH - pad);
        y += pianoH;

        // 5. Filter bar
        drawFilterBar(canvas, 0, y, w, filterH);
    }

    // ---- Metadata bar ----

    private void drawMetadata(Canvas canvas, int x, int y, int w, int h) {
        paint.setColor(METADATA_BG);
        canvas.drawRect(x, y, x + w, y + h, paint);

        paint.setColor(BORDER_COLOR);
        canvas.drawLine(x, y + h - 1, x + w, y + h - 1, paint);

        SIDStateSnapshot snap = this.snapshot;

        if (psidInfo == null) {
            paint.setColor(TEXT_DIM);
            paint.setTextSize(sp(14));
            paint.setTypeface(Typeface.MONOSPACE);
            String msg = "SIDPlay - Load a .sid file to begin";
            float tw = paint.measureText(msg);
            canvas.drawText(msg, x + (w - tw) / 2, y + h / 2 + sp(5), paint);
            return;
        }

        int textY = y + dp(16);

        // Title
        paint.setTextSize(sp(14));
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setColor(TEXT_BRIGHT);
        canvas.drawText(psidInfo.title, x + dp(10), textY, paint);

        // Author
        paint.setTextSize(sp(10));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(TEXT_PRIMARY);
        canvas.drawText(psidInfo.author, x + dp(10), textY + dp(14), paint);

        // Copyright
        paint.setColor(TEXT_DIM);
        canvas.drawText(psidInfo.copyright, x + w / 2, textY, paint);

        // Song counter
        String songInfo = "Song " + currentSong + "/" + psidInfo.songs;
        paint.setTextSize(sp(11));
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setColor(TEXT_BRIGHT);
        float sw = paint.measureText(songInfo);
        canvas.drawText(songInfo, x + w - sw - dp(12), textY, paint);

        // Volume meter
        if (snap != null) {
            int volBarW = dp(50);
            int volBarH = dp(6);
            int volX = (int) (x + w - sw - dp(12) - volBarW - dp(16));
            int volY = textY + dp(6);
            paint.setColor(BG_SCOPE);
            canvas.drawRect(volX, volY, volX + volBarW, volY + volBarH, paint);
            int volFill = (snap.masterVolume * volBarW) / 15;
            paint.setColor(TEXT_PRIMARY);
            canvas.drawRect(volX, volY, volX + volFill, volY + volBarH, paint);
            paint.setTextSize(sp(9));
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setColor(TEXT_DIM);
            canvas.drawText("VOL", volX - dp(24), volY + volBarH, paint);
        }
    }

    // ---- Oscilloscope ----

    private void drawOscilloscope(Canvas canvas, int voiceIndex, int x, int y, int w, int h) {
        // Background
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BG_SCOPE);
        canvas.drawRoundRect(rectF, dp(4), dp(4), paint);

        // Grid lines
        paint.setColor(GRID_COLOR);
        paint.setStrokeWidth(dp(0.5f));
        int midY = y + h / 2;
        canvas.drawLine(x + dp(3), midY, x + w - dp(3), midY, paint);
        for (int i = 1; i < 4; i++) {
            int gy = y + (h * i) / 4;
            canvas.drawLine(x + dp(3), gy, x + w - dp(3), gy, paint);
        }
        for (int i = 1; i < 4; i++) {
            int gx = x + (w * i) / 4;
            canvas.drawLine(gx, y + dp(3), gx, y + h - dp(3), paint);
        }

        // Voice label
        paint.setTextSize(sp(9));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(VOICE_COLORS_DIM[voiceIndex]);
        canvas.drawText("VOICE " + (voiceIndex + 1), x + dp(4), y + dp(10), paint);

        SIDStateSnapshot snap = this.snapshot;
        if (snap == null || snap.numSamples == 0) {
            drawScopeBorder(canvas, x, y, w, h);
            return;
        }

        // Waveform name and note
        String waveName = SIDStateSnapshot.WAVEFORM_NAMES[snap.waveform[voiceIndex] & 0xF];
        paint.setColor(VOICE_COLORS[voiceIndex]);
        paint.setTextSize(sp(9));
        float labelW = paint.measureText(waveName);
        canvas.drawText(waveName, x + w - labelW - dp(4), y + dp(10), paint);

        if (snap.gate[voiceIndex]) {
            String noteName = SIDStateSnapshot.midiNoteToName(snap.midiNote[voiceIndex]);
            paint.setTextSize(sp(10));
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            canvas.drawText(noteName, x + w / 2 - dp(8), y + dp(10), paint);
        }

        // Draw waveform
        drawWaveform(canvas, snap.voiceSamples[voiceIndex], snap.numSamples,
                VOICE_COLORS[voiceIndex], VOICE_COLORS_GLOW[voiceIndex],
                x + dp(4), y + dp(14), w - dp(8), h - dp(18));

        drawScopeBorder(canvas, x, y, w, h);
    }

    private void drawDigiScope(Canvas canvas, int x, int y, int w, int h) {
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BG_SCOPE);
        canvas.drawRoundRect(rectF, dp(4), dp(4), paint);

        // Grid
        paint.setColor(GRID_COLOR);
        paint.setStrokeWidth(dp(0.5f));
        canvas.drawLine(x + dp(3), y + h / 2, x + w - dp(3), y + h / 2, paint);

        // Label
        paint.setTextSize(sp(9));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(0xFF6058A0);
        canvas.drawText("OUTPUT", x + dp(4), y + dp(10), paint);

        SIDStateSnapshot snap = this.snapshot;
        if (snap == null || snap.numSamples == 0) {
            drawScopeBorder(canvas, x, y, w, h);
            return;
        }

        // Mixed output waveform
        drawWaveform(canvas, snap.mixedSamples, snap.numSamples,
                MIX_COLOR, MIX_GLOW,
                x + dp(4), y + dp(14), w - dp(8), h - dp(18));

        // Digi overlay
        if (snap.digiDetected) {
            paint.setTextSize(sp(9));
            paint.setColor(DIGI_COLOR);
            String digiLabel = "DIGI";
            float dlw = paint.measureText(digiLabel);
            canvas.drawText(digiLabel, x + w - dlw - dp(4), y + dp(10), paint);

            drawDigiWaveform(canvas, snap.digiSamples, snap.numSamples,
                    x + dp(4), y + dp(14), w - dp(8), h - dp(18));
        }

        drawScopeBorder(canvas, x, y, w, h);
    }

    private void drawWaveform(Canvas canvas, int[] samples, int numSamples,
                              int color, int glowColor, int x, int y, int w, int h) {
        int displaySamples = Math.min(numSamples, w * 2);
        if (displaySamples < 2) return;

        // AC coupling: compute DC offset
        long sum = 0;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * numSamples) / displaySamples;
            sum += samples[idx];
        }
        int dcOffset = (int) (sum / displaySamples);

        // Auto-scale amplitude
        int maxAmp = 1;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * numSamples) / displaySamples;
            int amp = Math.abs(samples[idx] - dcOffset);
            if (amp > maxAmp) maxAmp = amp;
        }

        // Build path
        int drawW = Math.min(displaySamples, w);
        path.reset();
        for (int i = 0; i < drawW; i++) {
            int idx = (i * numSamples) / drawW;
            if (idx >= numSamples) idx = numSamples - 1;
            float px = x + (float) (i * w) / drawW;
            float val = (float) (samples[idx] - dcOffset) / maxAmp;
            float py = y + h / 2f - val * h * 0.45f;
            py = Math.max(y, Math.min(y + h, py));
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }

        // Glow
        scopePaint.setColor(glowColor);
        scopePaint.setStrokeWidth(dp(3));
        canvas.drawPath(path, scopePaint);

        // Sharp line
        scopePaint.setColor(color);
        scopePaint.setStrokeWidth(dp(1.2f));
        canvas.drawPath(path, scopePaint);
    }

    private void drawDigiWaveform(Canvas canvas, int[] samples, int numSamples,
                                   int x, int y, int w, int h) {
        int displaySamples = Math.min(numSamples, w * 2);
        if (displaySamples < 2) return;

        int drawW = Math.min(displaySamples, w);
        path.reset();
        for (int i = 0; i < drawW; i++) {
            int idx = (i * numSamples) / drawW;
            if (idx >= numSamples) idx = numSamples - 1;
            float px = x + (float) (i * w) / drawW;
            float val = (samples[idx] - 7.5f) / 7.5f;
            float py = y + h / 2f - val * h * 0.45f;
            py = Math.max(y, Math.min(y + h, py));
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }

        scopePaint.setColor(DIGI_GLOW);
        scopePaint.setStrokeWidth(dp(3));
        canvas.drawPath(path, scopePaint);

        scopePaint.setColor(DIGI_COLOR);
        scopePaint.setStrokeWidth(dp(1.2f));
        canvas.drawPath(path, scopePaint);
    }

    private void drawScopeBorder(Canvas canvas, int x, int y, int w, int h) {
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BORDER_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rectF, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- Envelope ----

    private void drawEnvelope(Canvas canvas, int voiceIndex, int x, int y, int w, int h) {
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BG_PANEL);
        canvas.drawRoundRect(rectF, dp(3), dp(3), paint);

        SIDStateSnapshot snap = this.snapshot;

        // Label
        paint.setTextSize(sp(9));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(VOICE_COLORS_DIM[voiceIndex]);
        canvas.drawText("ENV " + (voiceIndex + 1), x + dp(4), y + dp(10), paint);

        if (snap == null) {
            drawEnvelopeBorder(canvas, x, y, w, h);
            return;
        }

        // ADSR values
        String adsrStr = String.format("A%X D%X S%X R%X",
                snap.attack[voiceIndex], snap.decay[voiceIndex],
                snap.sustain[voiceIndex], snap.release[voiceIndex]);
        paint.setColor(TEXT_DIM);
        float adsrW = paint.measureText(adsrStr);
        canvas.drawText(adsrStr, x + w - adsrW - dp(4), y + dp(10), paint);

        // Envelope level bar
        int barX = x + dp(36);
        int barW = w - dp(46);
        int barH = h - dp(20);
        int barY = y + dp(16);

        paint.setColor(BG_SCOPE);
        canvas.drawRect(barX, barY, barX + barW, barY + barH, paint);

        int level = snap.envelopeLevel[voiceIndex];
        int fillW = (level * barW) / 255;

        if (fillW > 0) {
            // Gradient fill
            LinearGradient gradient = new LinearGradient(
                    barX, barY, barX + fillW, barY,
                    VOICE_COLORS[voiceIndex], VOICE_COLORS_DIM[voiceIndex],
                    Shader.TileMode.CLAMP);
            paint.setShader(gradient);
            canvas.drawRect(barX, barY, barX + fillW, barY + barH, paint);
            paint.setShader(null);
        }

        // State label
        paint.setTextSize(sp(9));
        paint.setColor(TEXT_PRIMARY);
        String stateStr = snap.gate[voiceIndex] ? "GATE" : "----";
        if (snap.envelopeState[voiceIndex] != null) {
            switch (snap.envelopeState[voiceIndex]) {
                case ATTACK: stateStr = "ATK"; break;
                case DECAY_SUSTAIN: stateStr = snap.gate[voiceIndex] ? "D/S" : "REL"; break;
                case RELEASE: stateStr = "REL"; break;
            }
        }
        canvas.drawText(stateStr, x + dp(4), barY + barH / 2 + sp(3), paint);

        // Level value
        paint.setColor(TEXT_DIM);
        canvas.drawText(String.format("%3d", level), barX + barW + dp(2), barY + barH / 2 + sp(3), paint);

        drawEnvelopeBorder(canvas, x, y, w, h);
    }

    private void drawEnvelopeBorder(Canvas canvas, int x, int y, int w, int h) {
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BORDER_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rectF, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- Piano Roll ----

    private void drawPianoRoll(Canvas canvas, int x, int y, int w, int h) {
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BG_PANEL);
        canvas.drawRoundRect(rectF, dp(4), dp(4), paint);

        // Piano spanning ~5 octaves (C1 to C6, MIDI 24..96)
        int startNote = 24;
        int endNote = 96;
        int numNotes = endNote - startNote;
        int keyboardH = Math.min(h - dp(16), dp(30));
        int pianoY = y + h - keyboardH - dp(4);
        int pianoW = w - dp(8);
        int pianoX = x + dp(4);

        int noteAreaH = h - keyboardH - dp(12);
        int noteAreaY = y + dp(6);

        boolean[] isBlack = {false, true, false, true, false, false, true, false, true, false, true, false};
        float keyW = (float) pianoW / numNotes;

        // Background
        paint.setColor(KEY_BG);
        canvas.drawRect(pianoX, pianoY, pianoX + pianoW, pianoY + keyboardH, paint);

        // White keys
        for (int n = 0; n < numNotes; n++) {
            int note = (startNote + n) % 12;
            if (!isBlack[note]) {
                float kx = pianoX + n * keyW;
                paint.setColor(KEY_WHITE);
                canvas.drawRect(kx, pianoY, kx + Math.max(1, keyW - 1), pianoY + keyboardH, paint);
            }
        }
        // Black keys
        for (int n = 0; n < numNotes; n++) {
            int note = (startNote + n) % 12;
            if (isBlack[note]) {
                float kx = pianoX + n * keyW;
                paint.setColor(KEY_BLACK);
                canvas.drawRect(kx, pianoY, kx + Math.max(1, keyW), pianoY + keyboardH * 2 / 3, paint);
            }
        }

        // Octave markers
        paint.setTextSize(sp(8));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(TEXT_DIM);
        for (int n = 0; n < numNotes; n++) {
            if ((startNote + n) % 12 == 0) {
                int octave = ((startNote + n) / 12) - 1;
                float kx = pianoX + n * keyW;
                canvas.drawText("C" + octave, kx + dp(1), pianoY + keyboardH - dp(2), paint);
            }
        }

        // Active notes
        SIDStateSnapshot snap = this.snapshot;
        if (snap != null) {
            for (int v = 0; v < 3; v++) {
                if (snap.envelopeLevel[v] < 2) continue;
                int midi = snap.midiNote[v];
                if (midi < startNote || midi >= endNote) continue;

                int noteIdx = midi - startNote;
                float kx = pianoX + noteIdx * keyW;
                float alpha = snap.envelopeLevel[v] / 255f;
                int vc = VOICE_COLORS[v];
                int r = (vc >> 16) & 0xFF;
                int g = (vc >> 8) & 0xFF;
                int b = vc & 0xFF;

                // Highlight on piano
                paint.setColor(((int) (alpha * 200) << 24) | (r << 16) | (g << 8) | b);
                canvas.drawRect(kx, pianoY, kx + Math.max(dp(2), keyW), pianoY + keyboardH, paint);

                // Note bar above piano
                int barH = (int) (noteAreaH * alpha * 0.8f);
                int barY = noteAreaY + noteAreaH - barH;
                paint.setColor(((int) (alpha * 140) << 24) | (r << 16) | (g << 8) | b);
                canvas.drawRect(kx, barY, kx + Math.max(dp(2), keyW * 1.5f), barY + barH, paint);

                // Glow
                paint.setColor(((int) (alpha * 50) << 24) | (r << 16) | (g << 8) | b);
                canvas.drawRect(kx - keyW, barY - dp(1),
                        kx + Math.max(dp(4), keyW * 3.5f), barY + barH + dp(1), paint);

                // Note name
                String noteName = SIDStateSnapshot.midiNoteToName(midi);
                paint.setTextSize(sp(8));
                paint.setColor(((int) (alpha * 255) << 24) | (r << 16) | (g << 8) | b);
                canvas.drawText(noteName, kx - dp(1), barY - dp(2), paint);
            }
        }

        // Border
        rectF.set(x, y, x + w, y + h);
        paint.setColor(BORDER_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rectF, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.FILL);

        // Label
        paint.setTextSize(sp(8));
        paint.setColor(TEXT_DIM);
        canvas.drawText("NOTES", x + dp(6), y + dp(10), paint);
    }

    // ---- Filter Bar ----

    private void drawFilterBar(Canvas canvas, int x, int y, int w, int h) {
        paint.setColor(METADATA_BG);
        canvas.drawRect(x, y, x + w, y + h, paint);
        paint.setColor(BORDER_COLOR);
        canvas.drawLine(x, y, x + w, y, paint);

        SIDStateSnapshot snap = this.snapshot;
        if (snap == null) {
            paint.setTextSize(sp(10));
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setColor(TEXT_DIM);
            canvas.drawText("FILTER", x + dp(8), y + h / 2 + sp(4), paint);
            return;
        }

        int pad = dp(8);
        int textY = y + dp(16);

        paint.setTextSize(sp(10));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(TEXT_DIM);
        canvas.drawText("FILTER", x + pad, textY, paint);

        // Cutoff bar
        int cutoffBarX = x + pad + dp(50);
        int cutoffBarW = dp(120);
        int cutoffBarH = dp(8);
        paint.setColor(BG_SCOPE);
        canvas.drawRect(cutoffBarX, textY - dp(7), cutoffBarX + cutoffBarW, textY - dp(7) + cutoffBarH, paint);
        int cutoffFill = (snap.filterCutoff * cutoffBarW) / 2047;
        paint.setColor(FILTER_LP);
        canvas.drawRect(cutoffBarX, textY - dp(7), cutoffBarX + cutoffFill, textY - dp(7) + cutoffBarH, paint);

        paint.setTextSize(sp(9));
        paint.setColor(TEXT_PRIMARY);
        canvas.drawText("FC:" + snap.filterCutoff, cutoffBarX + cutoffBarW + dp(4), textY, paint);

        // Resonance
        int resX = cutoffBarX + cutoffBarW + dp(60);
        paint.setColor(TEXT_DIM);
        canvas.drawText("Q:" + snap.filterResonance, resX, textY, paint);

        // Filter modes
        int modeX = resX + dp(36);
        drawFilterMode(canvas, "LP", snap.filterLP, FILTER_LP, modeX, textY);
        drawFilterMode(canvas, "BP", snap.filterBP, FILTER_BP, modeX + dp(28), textY);
        drawFilterMode(canvas, "HP", snap.filterHP, FILTER_HP, modeX + dp(56), textY);

        // Voice routing
        int routeX = modeX + dp(90);
        paint.setColor(TEXT_DIM);
        paint.setTextSize(sp(9));
        canvas.drawText("Rt:", routeX, textY, paint);
        for (int v = 0; v < 3; v++) {
            boolean routed = (snap.filterRouting & (1 << v)) != 0;
            paint.setColor(routed ? VOICE_COLORS[v] : TEXT_DIM);
            canvas.drawCircle(routeX + dp(24) + v * dp(14), textY - dp(4), dp(4), paint);
            paint.setColor(BORDER_COLOR);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawCircle(routeX + dp(24) + v * dp(14), textY - dp(4), dp(4), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // Second row: per-voice info
        int row2Y = textY + dp(16);
        paint.setTextSize(sp(8));
        int colW = w / 3;
        for (int v = 0; v < 3; v++) {
            int vx = x + pad + v * colW;
            paint.setColor(VOICE_COLORS[v]);
            String noteStr = SIDStateSnapshot.midiNoteToName(snap.midiNote[v]);
            String freqStr = String.format("V%d:%s %5.0fHz %s E:%3d",
                    v + 1, noteStr, snap.noteFreqHz[v],
                    SIDStateSnapshot.WAVEFORM_NAMES[snap.waveform[v] & 0xF],
                    snap.envelopeLevel[v]);
            canvas.drawText(freqStr, vx, row2Y, paint);
        }
    }

    private void drawFilterMode(Canvas canvas, String label, boolean active, int activeColor, int x, int y) {
        paint.setTextSize(sp(9));
        if (active) {
            paint.setColor(activeColor);
            rectF.set(x - dp(1), y - dp(9), x + dp(22), y + dp(2));
            canvas.drawRoundRect(rectF, dp(2), dp(2), paint);
            paint.setColor(BG_DARK);
            canvas.drawText(label, x + dp(3), y, paint);
        } else {
            paint.setColor(TEXT_DIM);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            rectF.set(x - dp(1), y - dp(9), x + dp(22), y + dp(2));
            canvas.drawRoundRect(rectF, dp(2), dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(label, x + dp(3), y, paint);
        }
    }
}
