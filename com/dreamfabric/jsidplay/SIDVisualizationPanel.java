package com.dreamfabric.jsidplay;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time visualization panel for SID playback.
 * Renders oscilloscope waveforms, ADSR envelopes, piano roll, and filter state.
 * Styled with C64-inspired retro colors.
 */
public class SIDVisualizationPanel extends JPanel {

    // C64-inspired color palette
    private static final Color BG_DARK = new Color(0x16, 0x16, 0x3B);
    private static final Color BG_PANEL = new Color(0x1E, 0x1E, 0x50);
    private static final Color BG_SCOPE = new Color(0x0A, 0x0A, 0x2A);
    private static final Color GRID_COLOR = new Color(0x2A, 0x2A, 0x60);
    private static final Color TEXT_PRIMARY = new Color(0x7B, 0x71, 0xD5);
    private static final Color TEXT_DIM = new Color(0x4A, 0x44, 0x8A);
    private static final Color TEXT_BRIGHT = new Color(0xA0, 0x98, 0xF0);
    private static final Color BORDER_COLOR = new Color(0x3A, 0x34, 0x80);
    private static final Color METADATA_BG = new Color(0x12, 0x12, 0x34);

    // Per-voice colors (inspired by C64 palette)
    private static final Color[] VOICE_COLORS = {
        new Color(0xF0, 0x50, 0x40),  // Voice 1: Red-orange
        new Color(0x50, 0xD0, 0xE0),  // Voice 2: Cyan
        new Color(0x60, 0xD0, 0x50),  // Voice 3: Green
    };
    private static final Color[] VOICE_COLORS_DIM = {
        new Color(0x80, 0x30, 0x28),
        new Color(0x30, 0x70, 0x78),
        new Color(0x38, 0x70, 0x30),
    };
    private static final Color[] VOICE_COLORS_GLOW = {
        new Color(0xFF, 0x80, 0x70, 0x60),
        new Color(0x80, 0xF0, 0xFF, 0x60),
        new Color(0x90, 0xFF, 0x80, 0x60),
    };

    // Filter mode colors
    private static final Color FILTER_LP_COLOR = new Color(0x50, 0x80, 0xF0);
    private static final Color FILTER_BP_COLOR = new Color(0xF0, 0xD0, 0x30);
    private static final Color FILTER_HP_COLOR = new Color(0xF0, 0x50, 0x50);

    // Piano roll colors
    private static final Color KEY_WHITE = new Color(0xD0, 0xD0, 0xD0);
    private static final Color KEY_BLACK = new Color(0x20, 0x20, 0x30);
    private static final Color KEY_BG = new Color(0x10, 0x10, 0x28);

    private final AtomicReference<SIDStateSnapshot> snapshotRef;
    private PSIDFile psidInfo;

    private static final Font TITLE_FONT = new Font("Monospaced", Font.BOLD, 16);
    private static final Font LABEL_FONT = new Font("Monospaced", Font.PLAIN, 11);
    private static final Font SMALL_FONT = new Font("Monospaced", Font.PLAIN, 10);
    private static final Font NOTE_FONT = new Font("Monospaced", Font.BOLD, 12);

    // Layout proportions
    private static final int METADATA_HEIGHT = 50;
    private static final int FILTER_HEIGHT = 60;
    private static final double SCOPE_RATIO = 0.40;
    private static final double ENVELOPE_RATIO = 0.18;
    private static final double PIANO_RATIO = 0.22;

    public SIDVisualizationPanel(AtomicReference<SIDStateSnapshot> snapshotRef) {
        this.snapshotRef = snapshotRef;
        setBackground(BG_DARK);
        setPreferredSize(new Dimension(900, 600));
    }

    public void setPSIDInfo(PSIDFile psid) {
        this.psidInfo = psid;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth();
        int h = getHeight();
        int pad = 6;

        // Fill background
        g2.setColor(BG_DARK);
        g2.fillRect(0, 0, w, h);

        // Layout regions
        int y = 0;
        int contentH = h - METADATA_HEIGHT - FILTER_HEIGHT;
        int scopeH = (int)(contentH * SCOPE_RATIO);
        int envH = (int)(contentH * ENVELOPE_RATIO);
        int pianoH = contentH - scopeH - envH;

        // 1. Metadata bar
        drawMetadata(g2, 0, y, w, METADATA_HEIGHT);
        y += METADATA_HEIGHT;

        // 2. Oscilloscopes - 2x2 grid (3 voices + digi/mixed channel)
        int halfW = (w - pad * 3) / 2;
        int halfScopeH = (scopeH - pad * 3) / 2;

        drawOscilloscope(g2, 0, pad, y + pad, halfW, halfScopeH);
        drawOscilloscope(g2, 1, pad * 2 + halfW, y + pad, halfW, halfScopeH);
        drawOscilloscope(g2, 2, pad, y + pad * 2 + halfScopeH, halfW, halfScopeH);
        drawDigiScope(g2, pad * 2 + halfW, y + pad * 2 + halfScopeH, halfW, halfScopeH);
        y += scopeH;

        // 3. Envelope displays (3 columns)
        int envW = (w - pad * 4) / 3;
        for (int i = 0; i < 3; i++) {
            int sx = pad + i * (envW + pad);
            drawEnvelope(g2, i, sx, y + pad / 2, envW, envH - pad);
        }
        y += envH;

        // 4. Piano roll (full width)
        drawPianoRoll(g2, pad, y + pad / 2, w - pad * 2, pianoH - pad);
        y += pianoH;

        // 5. Filter & volume bar
        drawFilterBar(g2, 0, y, w, FILTER_HEIGHT);

        g2.dispose();
    }

    private void drawMetadata(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(METADATA_BG);
        g2.fillRect(x, y, w, h);
        g2.setColor(BORDER_COLOR);
        g2.drawLine(x, y + h - 1, x + w, y + h - 1);

        SIDStateSnapshot snap = snapshotRef.get();
        if (psidInfo == null) {
            g2.setColor(TEXT_DIM);
            g2.setFont(TITLE_FONT);
            String msg = "JSIDPlay - Load a .sid file to begin";
            int tw = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, x + (w - tw) / 2, y + h / 2 + 5);
            return;
        }

        int textY = y + 20;
        int col1 = x + 12;
        int col2 = x + w / 2;

        g2.setFont(TITLE_FONT);
        g2.setColor(TEXT_BRIGHT);
        g2.drawString(psidInfo.title, col1, textY);

        g2.setFont(LABEL_FONT);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(psidInfo.author, col1, textY + 18);

        g2.setColor(TEXT_DIM);
        g2.drawString(psidInfo.copyright, col2, textY);

        // Song counter
        String songInfo = "Song " + currentSong + "/" + psidInfo.songs;
        g2.setFont(NOTE_FONT);
        g2.setColor(TEXT_BRIGHT);
        int sw = g2.getFontMetrics().stringWidth(songInfo);
        g2.drawString(songInfo, x + w - sw - 16, textY);

        // Volume meter
        if (snap != null) {
            int volBarW = 60;
            int volBarH = 8;
            int volX = x + w - sw - 16 - volBarW - 20;
            int volY = textY + 8;
            g2.setColor(TEXT_DIM);
            g2.setFont(SMALL_FONT);
            g2.drawString("VOL", volX - 28, volY + 7);
            g2.setColor(BG_SCOPE);
            g2.fillRect(volX, volY, volBarW, volBarH);
            int volFill = (snap.masterVolume * volBarW) / 15;
            g2.setColor(TEXT_PRIMARY);
            g2.fillRect(volX, volY, volFill, volBarH);
        }
    }

    private int currentSong = 1;

    public void setCurrentSong(int song) {
        this.currentSong = song;
    }

    private void drawOscilloscope(Graphics2D g2, int voiceIndex, int x, int y, int w, int h) {
        // Panel background
        g2.setColor(BG_SCOPE);
        g2.fillRoundRect(x, y, w, h, 8, 8);

        // Grid lines
        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        int midY = y + h / 2;
        g2.drawLine(x + 4, midY, x + w - 4, midY);
        for (int i = 1; i < 4; i++) {
            int gy = y + (h * i) / 4;
            g2.drawLine(x + 4, gy, x + w - 4, gy);
        }
        // Vertical grid
        for (int i = 1; i < 4; i++) {
            int gx = x + (w * i) / 4;
            g2.drawLine(gx, y + 4, gx, y + h - 4);
        }

        // Voice label
        g2.setFont(SMALL_FONT);
        g2.setColor(VOICE_COLORS_DIM[voiceIndex]);
        g2.drawString("VOICE " + (voiceIndex + 1), x + 6, y + 12);

        SIDStateSnapshot snap = snapshotRef.get();
        if (snap == null || snap.numSamples == 0) {
            g2.setColor(BORDER_COLOR);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w, h, 8, 8);
            return;
        }

        // Waveform name and note
        String waveName = SIDStateSnapshot.WAVEFORM_NAMES[snap.waveform[voiceIndex] & 0xF];
        String noteName = SIDStateSnapshot.midiNoteToName(snap.midiNote[voiceIndex]);
        g2.setColor(VOICE_COLORS[voiceIndex]);
        g2.setFont(SMALL_FONT);
        int labelW = g2.getFontMetrics().stringWidth(waveName);
        g2.drawString(waveName, x + w - labelW - 6, y + 12);

        if (snap.gate[voiceIndex]) {
            g2.setFont(NOTE_FONT);
            g2.drawString(noteName, x + w / 2 - 10, y + 12);
        }

        // Draw waveform
        int[] samples = snap.voiceSamples[voiceIndex];
        int numSamples = snap.numSamples;
        int drawW = w - 12;
        int drawH = h - 24;
        int drawX = x + 6;
        int drawY = y + 16;

        // Show a few cycles of the waveform for detail (not all 880 samples)
        int displaySamples = Math.min(numSamples, drawW * 2);

        // AC coupling: compute mean and subtract to remove DC offset from Voice.output()
        long sum = 0;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * numSamples) / displaySamples;
            sum += samples[idx];
        }
        int dcOffset = (int)(sum / displaySamples);

        // Auto-scale amplitude after DC removal
        int maxAmp = 1;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * numSamples) / displaySamples;
            int amp = Math.abs(samples[idx] - dcOffset);
            if (amp > maxAmp) maxAmp = amp;
        }

        // Build waveform path
        GeneralPath wavePath = new GeneralPath();
        boolean started = false;
        for (int i = 0; i < Math.min(displaySamples, drawW); i++) {
            int idx = (i * numSamples) / drawW;
            if (idx >= numSamples) idx = numSamples - 1;
            float px = drawX + (float)(i * drawW) / Math.min(displaySamples, drawW);
            float val = (float)(samples[idx] - dcOffset) / maxAmp;
            float py = drawY + drawH / 2f - val * drawH * 0.45f;
            py = Math.max(drawY, Math.min(drawY + drawH, py));
            if (!started) {
                wavePath.moveTo(px, py);
                started = true;
            } else {
                wavePath.lineTo(px, py);
            }
        }

        if (started) {
            // Draw glow effect
            g2.setColor(VOICE_COLORS_GLOW[voiceIndex]);
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(wavePath);

            // Draw sharp waveform
            g2.setColor(VOICE_COLORS[voiceIndex]);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(wavePath);
        }

        // Border
        g2.setColor(BORDER_COLOR);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 8, 8);
    }

    private static final Color DIGI_COLOR = new Color(0xF0, 0xC0, 0x30);
    private static final Color DIGI_COLOR_DIM = new Color(0x80, 0x68, 0x20);
    private static final Color DIGI_COLOR_GLOW = new Color(0xF0, 0xD0, 0x40, 0x60);

    private void drawDigiScope(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(BG_SCOPE);
        g2.fillRoundRect(x, y, w, h, 8, 8);

        // Grid
        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        g2.drawLine(x + 4, y + h / 2, x + w - 4, y + h / 2);

        SIDStateSnapshot snap = snapshotRef.get();

        // Label
        g2.setFont(SMALL_FONT);
        g2.setColor(new Color(0x60, 0x58, 0xA0));
        g2.drawString("OUTPUT", x + 6, y + 12);

        if (snap == null || snap.numSamples == 0) {
            g2.setColor(BORDER_COLOR);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w, h, 8, 8);
            return;
        }

        int drawW = w - 12;
        int drawH = h - 24;
        int drawX = x + 6;
        int drawY = y + 16;
        int displaySamples = Math.min(snap.numSamples, drawW * 2);

        // Always draw mixed output waveform
        long sum = 0;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * snap.numSamples) / displaySamples;
            sum += snap.mixedSamples[idx];
        }
        int dcOffset = (int)(sum / displaySamples);
        int maxAmp = 1;
        for (int i = 0; i < displaySamples; i++) {
            int idx = (i * snap.numSamples) / displaySamples;
            int amp = Math.abs(snap.mixedSamples[idx] - dcOffset);
            if (amp > maxAmp) maxAmp = amp;
        }

        GeneralPath mixPath = new GeneralPath();
        boolean started = false;
        for (int i = 0; i < Math.min(displaySamples, drawW); i++) {
            int idx = (i * snap.numSamples) / drawW;
            if (idx >= snap.numSamples) idx = snap.numSamples - 1;
            float px = drawX + (float)(i * drawW) / Math.min(displaySamples, drawW);
            float val = (float)(snap.mixedSamples[idx] - dcOffset) / maxAmp;
            float py2 = drawY + drawH / 2f - val * drawH * 0.45f;
            py2 = Math.max(drawY, Math.min(drawY + drawH, py2));
            if (!started) { mixPath.moveTo(px, py2); started = true; }
            else { mixPath.lineTo(px, py2); }
        }
        if (started) {
            Color mixColor = new Color(0xA0, 0x98, 0xF0);
            g2.setColor(new Color(0xA0, 0x98, 0xF0, 0x50));
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(mixPath);
            g2.setColor(mixColor);
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(mixPath);
        }

        // Overlay digi waveform in yellow when $D418 volume sampling detected
        if (snap.digiDetected) {
            g2.setFont(SMALL_FONT);
            g2.setColor(DIGI_COLOR);
            String digiLabel = "DIGI \u266B";
            g2.drawString(digiLabel, x + w - g2.getFontMetrics().stringWidth(digiLabel) - 6, y + 12);

            GeneralPath digiPath = new GeneralPath();
            started = false;
            for (int i = 0; i < Math.min(displaySamples, drawW); i++) {
                int idx = (i * snap.numSamples) / drawW;
                if (idx >= snap.numSamples) idx = snap.numSamples - 1;
                float px = drawX + (float)(i * drawW) / Math.min(displaySamples, drawW);
                float val = (snap.digiSamples[idx] - 7.5f) / 7.5f;
                float py2 = drawY + drawH / 2f - val * drawH * 0.45f;
                py2 = Math.max(drawY, Math.min(drawY + drawH, py2));
                if (!started) { digiPath.moveTo(px, py2); started = true; }
                else { digiPath.lineTo(px, py2); }
            }
            if (started) {
                g2.setColor(DIGI_COLOR_GLOW);
                g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(digiPath);
                g2.setColor(DIGI_COLOR);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(digiPath);
            }
        }

        g2.setColor(BORDER_COLOR);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 8, 8);
    }

    private void drawEnvelope(Graphics2D g2, int voiceIndex, int x, int y, int w, int h) {
        g2.setColor(BG_PANEL);
        g2.fillRoundRect(x, y, w, h, 6, 6);

        SIDStateSnapshot snap = snapshotRef.get();
        if (snap == null) {
            g2.setColor(BORDER_COLOR);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w, h, 6, 6);
            return;
        }

        int pad = 4;
        int barX = x + 50;
        int barW = w - 60;
        int barH = h - pad * 2 - 14;
        int barY = y + pad + 14;

        // ADSR label
        g2.setFont(SMALL_FONT);
        g2.setColor(VOICE_COLORS_DIM[voiceIndex]);
        g2.drawString("ENV " + (voiceIndex + 1), x + 6, y + 12);

        // ADSR values
        g2.setColor(TEXT_DIM);
        String adsrStr = String.format("A%X D%X S%X R%X",
                snap.attack[voiceIndex], snap.decay[voiceIndex],
                snap.sustain[voiceIndex], snap.release[voiceIndex]);
        g2.drawString(adsrStr, x + w - g2.getFontMetrics().stringWidth(adsrStr) - 6, y + 12);

        // Envelope level bar
        g2.setColor(BG_SCOPE);
        g2.fillRect(barX, barY, barW, barH);

        int level = snap.envelopeLevel[voiceIndex];
        int fillW = (level * barW) / 255;

        // Gradient fill
        if (fillW > 0) {
            Color c = VOICE_COLORS[voiceIndex];
            GradientPaint gp = new GradientPaint(barX, barY, c,
                    barX + fillW, barY, VOICE_COLORS_DIM[voiceIndex]);
            g2.setPaint(gp);
            g2.fillRect(barX, barY, fillW, barH);
        }

        // Envelope state label
        g2.setFont(SMALL_FONT);
        g2.setColor(TEXT_PRIMARY);
        String stateStr = snap.gate[voiceIndex] ? "GATE" : "----";
        if (snap.envelopeState[voiceIndex] != null) {
            switch (snap.envelopeState[voiceIndex]) {
                case ATTACK: stateStr = "ATK"; break;
                case DECAY_SUSTAIN: stateStr = snap.gate[voiceIndex] ? "D/S" : "REL"; break;
                case RELEASE: stateStr = "REL"; break;
            }
        }
        g2.drawString(stateStr, x + 6, barY + barH / 2 + 4);

        // Level value
        g2.setColor(TEXT_DIM);
        String levelStr = String.format("%3d", level);
        g2.drawString(levelStr, barX + barW + 4, barY + barH / 2 + 4);

        // Border
        g2.setColor(BORDER_COLOR);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 6, 6);
    }

    private void drawPianoRoll(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(BG_PANEL);
        g2.fillRoundRect(x, y, w, h, 8, 8);

        // Piano keyboard spanning ~5 octaves (C1 to C6 = MIDI 24..84)
        int startNote = 24;
        int endNote = 96;
        int numNotes = endNote - startNote;
        int keyboardH = Math.min(h - 20, 40);
        int pianoY = y + h - keyboardH - 6;
        int pianoW = w - 12;
        int pianoX = x + 6;

        // Note display area above piano
        int noteAreaH = h - keyboardH - 16;
        int noteAreaY = y + 8;

        // Draw white keys
        boolean[] isBlack = {false, true, false, true, false, false, true, false, true, false, true, false};
        float keyW = (float) pianoW / numNotes;

        // White key background
        g2.setColor(KEY_BG);
        g2.fillRect(pianoX, pianoY, pianoW, keyboardH);

        // Draw white keys first
        for (int n = 0; n < numNotes; n++) {
            int note = (startNote + n) % 12;
            float kx = pianoX + n * keyW;
            if (!isBlack[note]) {
                g2.setColor(KEY_WHITE);
                g2.fillRect((int) kx, pianoY, Math.max(1, (int) keyW - 1), keyboardH);
            }
        }
        // Then black keys on top
        for (int n = 0; n < numNotes; n++) {
            int note = (startNote + n) % 12;
            float kx = pianoX + n * keyW;
            if (isBlack[note]) {
                g2.setColor(KEY_BLACK);
                g2.fillRect((int) kx, pianoY, Math.max(1, (int) keyW), keyboardH * 2 / 3);
            }
        }

        // Octave markers
        g2.setFont(SMALL_FONT);
        g2.setColor(TEXT_DIM);
        for (int n = 0; n < numNotes; n++) {
            if ((startNote + n) % 12 == 0) {
                int octave = ((startNote + n) / 12) - 1;
                float kx = pianoX + n * keyW;
                g2.drawString("C" + octave, (int) kx + 2, pianoY + keyboardH - 3);
            }
        }

        // Draw active notes
        SIDStateSnapshot snap = snapshotRef.get();
        if (snap != null) {
            for (int v = 0; v < 3; v++) {
                if (snap.envelopeLevel[v] < 2) continue;
                int midi = snap.midiNote[v];
                if (midi < startNote || midi >= endNote) continue;

                int noteIdx = midi - startNote;
                float kx = pianoX + noteIdx * keyW;
                float alpha = snap.envelopeLevel[v] / 255f;

                // Highlight on piano
                Color c = VOICE_COLORS[v];
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        (int)(alpha * 200)));
                g2.fillRect((int) kx, pianoY, Math.max(2, (int) keyW), keyboardH);

                // Note bar above piano
                int barH = (int)(noteAreaH * alpha * 0.8f);
                int barY = noteAreaY + noteAreaH - barH;
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        (int)(alpha * 140)));
                g2.fillRect((int) kx, barY, Math.max(3, (int)(keyW * 1.5f)), barH);

                // Glow effect
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        (int)(alpha * 50)));
                g2.fillRect((int)(kx - keyW), barY - 2,
                        Math.max(5, (int)(keyW * 3.5f)), barH + 4);

                // Note name
                String noteName = SIDStateSnapshot.midiNoteToName(midi);
                g2.setFont(SMALL_FONT);
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        (int)(alpha * 255)));
                g2.drawString(noteName, (int) kx - 2, barY - 3);
            }
        }

        // Border
        g2.setColor(BORDER_COLOR);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 8, 8);

        // Label
        g2.setFont(SMALL_FONT);
        g2.setColor(TEXT_DIM);
        g2.drawString("NOTES", x + 8, y + 14);
    }

    private void drawFilterBar(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(METADATA_BG);
        g2.fillRect(x, y, w, h);
        g2.setColor(BORDER_COLOR);
        g2.drawLine(x, y, x + w, y);

        SIDStateSnapshot snap = snapshotRef.get();
        if (snap == null) {
            g2.setFont(LABEL_FONT);
            g2.setColor(TEXT_DIM);
            g2.drawString("FILTER", x + 12, y + h / 2 + 4);
            return;
        }

        int pad = 10;
        int textY = y + 20;

        // Filter section
        g2.setFont(LABEL_FONT);
        g2.setColor(TEXT_DIM);
        g2.drawString("FILTER", x + pad, textY);

        // Cutoff frequency bar
        int cutoffBarX = x + pad + 60;
        int cutoffBarW = 200;
        int cutoffBarH = 12;
        g2.setColor(BG_SCOPE);
        g2.fillRect(cutoffBarX, textY - 10, cutoffBarW, cutoffBarH);
        int cutoffFill = (snap.filterCutoff * cutoffBarW) / 2047;
        g2.setColor(FILTER_LP_COLOR);
        g2.fillRect(cutoffBarX, textY - 10, cutoffFill, cutoffBarH);

        g2.setFont(SMALL_FONT);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString("FC:" + snap.filterCutoff, cutoffBarX + cutoffBarW + 6, textY);

        // Resonance
        int resX = cutoffBarX + cutoffBarW + 70;
        g2.setColor(TEXT_DIM);
        g2.drawString("Q:" + snap.filterResonance, resX, textY);

        // Filter modes
        int modeX = resX + 50;
        drawFilterModeIndicator(g2, "LP", snap.filterLP, FILTER_LP_COLOR, modeX, textY);
        drawFilterModeIndicator(g2, "BP", snap.filterBP, FILTER_BP_COLOR, modeX + 36, textY);
        drawFilterModeIndicator(g2, "HP", snap.filterHP, FILTER_HP_COLOR, modeX + 72, textY);

        // Voice routing
        int routeX = modeX + 120;
        g2.setColor(TEXT_DIM);
        g2.setFont(SMALL_FONT);
        g2.drawString("Route:", routeX, textY);
        for (int v = 0; v < 3; v++) {
            boolean routed = (snap.filterRouting & (1 << v)) != 0;
            g2.setColor(routed ? VOICE_COLORS[v] : TEXT_DIM);
            g2.fillOval(routeX + 45 + v * 18, textY - 9, 10, 10);
            g2.setColor(BORDER_COLOR);
            g2.drawOval(routeX + 45 + v * 18, textY - 9, 10, 10);
        }

        // Second row: voice frequency info
        int row2Y = textY + 22;
        g2.setFont(SMALL_FONT);
        for (int v = 0; v < 3; v++) {
            int vx = x + pad + v * (w / 3);
            g2.setColor(VOICE_COLORS[v]);
            String noteStr = SIDStateSnapshot.midiNoteToName(snap.midiNote[v]);
            String freqStr = String.format("V%d: %s  %5.0fHz  %s  Env:%3d",
                    v + 1, noteStr, snap.noteFreqHz[v],
                    SIDStateSnapshot.WAVEFORM_NAMES[snap.waveform[v] & 0xF],
                    snap.envelopeLevel[v]);
            g2.drawString(freqStr, vx, row2Y);
        }
    }

    private void drawFilterModeIndicator(Graphics2D g2, String label, boolean active,
                                          Color activeColor, int x, int y) {
        g2.setFont(SMALL_FONT);
        if (active) {
            g2.setColor(activeColor);
            g2.fillRoundRect(x - 2, y - 11, 30, 14, 4, 4);
            g2.setColor(BG_DARK);
            g2.drawString(label, x + 4, y);
        } else {
            g2.setColor(TEXT_DIM);
            g2.drawRoundRect(x - 2, y - 11, 30, 14, 4, 4);
            g2.drawString(label, x + 4, y);
        }
    }
}
