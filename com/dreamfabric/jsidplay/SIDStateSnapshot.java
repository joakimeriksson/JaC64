package com.dreamfabric.jsidplay;

import resid.EnvelopeGenerator;
import resid.SID;
import resid.Voice;

/**
 * Immutable snapshot of SID state for one frame, used for visualization.
 * Created on the player thread, consumed on the Swing EDT.
 */
public class SIDStateSnapshot {

    public static final double PAL_CLOCK = 985248.0;
    public static final String[] NOTE_NAMES = {
        "C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"
    };
    public static final String[] WAVEFORM_NAMES = {
        "None", "Triangle", "Saw", "Tri+Saw",
        "Pulse", "Tri+Pls", "Saw+Pls", "T+S+P",
        "Noise", "Tri+Noi", "Saw+Noi", "T+S+N",
        "Pls+Noi", "T+P+N", "S+P+N", "All"
    };

    // Per-voice data (3 voices)
    public final int[] frequency = new int[3];
    public final int[] pulseWidth = new int[3];
    public final int[] waveform = new int[3];
    public final int[] envelopeLevel = new int[3];  // 0-255
    public final EnvelopeGenerator.State[] envelopeState = new EnvelopeGenerator.State[3];
    public final int[] attack = new int[3];
    public final int[] decay = new int[3];
    public final int[] sustain = new int[3];
    public final int[] release = new int[3];
    public final boolean[] gate = new boolean[3];

    // Oscilloscope sample buffers
    public final int[][] voiceSamples;
    public final int[] mixedSamples;
    public final int[] digiSamples;  // Volume register digi channel (4-bit)
    public int numSamples;
    public boolean digiDetected;     // True if rapid $D418 volume changes detected

    // Filter state
    public int filterCutoff;
    public int filterResonance;
    public int filterRouting;
    public boolean filterLP, filterBP, filterHP;
    public int masterVolume;

    // Computed note info
    public final double[] noteFreqHz = new double[3];
    public final int[] midiNote = new int[3];

    public long frameNumber;
    public int nmiCount;  // NMI count per frame (for debug)

    public SIDStateSnapshot(int maxSamples) {
        voiceSamples = new int[3][maxSamples];
        mixedSamples = new int[maxSamples];
        digiSamples = new int[maxSamples];
        numSamples = 0;
    }

    /**
     * Populate register-level data from a SID.State snapshot.
     */
    public void populateFromState(SID.State state, double clockFreq) {
        for (int i = 0; i < 3; i++) {
            int base = i * 7;
            frequency[i] = (state.sid_register[base] & 0xFF)
                         | ((state.sid_register[base + 1] & 0xFF) << 8);
            pulseWidth[i] = (state.sid_register[base + 2] & 0xFF)
                          | ((state.sid_register[base + 3] & 0x0F) << 8);
            int ctrl = state.sid_register[base + 4] & 0xFF;
            waveform[i] = (ctrl >> 4) & 0x0F;
            gate[i] = (ctrl & 0x01) != 0;
            int ad = state.sid_register[base + 5] & 0xFF;
            attack[i] = (ad >> 4) & 0x0F;
            decay[i] = ad & 0x0F;
            int sr = state.sid_register[base + 6] & 0xFF;
            sustain[i] = (sr >> 4) & 0x0F;
            release[i] = sr & 0x0F;

            envelopeLevel[i] = state.envelope_counter[i] & 0xFF;
            envelopeState[i] = state.envelope_state[i];

            noteFreqHz[i] = sidFreqToHz(frequency[i], clockFreq);
            midiNote[i] = hzToMidiNote(noteFreqHz[i]);
        }

        // Filter registers
        int fcLo = state.sid_register[0x15] & 0xFF;
        int fcHi = state.sid_register[0x16] & 0xFF;
        filterCutoff = (fcLo & 0x07) | (fcHi << 3);

        int resFilt = state.sid_register[0x17] & 0xFF;
        filterResonance = (resFilt >> 4) & 0x0F;
        filterRouting = resFilt & 0x0F;

        int modeVol = state.sid_register[0x18] & 0xFF;
        int mode = (modeVol >> 4) & 0x07;
        filterLP = (mode & 0x01) != 0;
        filterBP = (mode & 0x02) != 0;
        filterHP = (mode & 0x04) != 0;
        masterVolume = modeVol & 0x0F;
    }

    /**
     * Convert SID frequency register value to Hz.
     * Fout = (Fn * Fclk) / 16777216
     */
    public static double sidFreqToHz(int freqReg, double clockFreq) {
        return (freqReg * clockFreq) / 16777216.0;
    }

    /**
     * Convert Hz to nearest MIDI note number.
     * MIDI note 69 = A4 = 440 Hz
     */
    public static int hzToMidiNote(double hz) {
        if (hz < 1.0) return -1;
        return (int) Math.round(69.0 + 12.0 * Math.log(hz / 440.0) / Math.log(2.0));
    }

    /**
     * Get note name string like "C#4" from MIDI note number.
     */
    public static String midiNoteToName(int midiNote) {
        if (midiNote < 0 || midiNote > 127) return "---";
        int octave = (midiNote / 12) - 1;
        int note = midiNote % 12;
        return NOTE_NAMES[note] + octave;
    }
}
