package com.dreamfabric.jac64.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.dreamfabric.jac64.AudioDriver;

/**
 * Android AudioTrack-based audio driver for SID emulation output.
 */
public class AudioDriverAndroid extends AudioDriver {

    private AudioTrack audioTrack;
    private int vol = 100;
    private boolean soundOn = true;
    private boolean fullSpeed = false;
    private long startTimeNanos;
    private int totalBytesWritten;
    private int sampleRate;

    @Override
    public void init(int sampleRate, int bufferSize) {
        this.sampleRate = sampleRate;
        int minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        // Use at least the requested buffer size or the minimum
        int actualBufSize = Math.max(bufferSize, minBuf);

        try {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualBufSize,
                    AudioTrack.MODE_STREAM);

            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play();
                startTimeNanos = System.nanoTime();
                totalBytesWritten = 0;
                System.out.println("AudioDriverAndroid: initialized, buffer=" + actualBufSize);
            } else {
                System.out.println("AudioDriverAndroid: failed to initialize");
                audioTrack.release();
                audioTrack = null;
            }
        } catch (Exception e) {
            System.out.println("AudioDriverAndroid: error initializing");
            e.printStackTrace();
            audioTrack = null;
        }
    }

    @Override
    public void write(byte[] buffer) {
        if (audioTrack == null) return;

        if (!soundOn) {
            // Mute: write silence
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = 0;
            }
        }

        if (fullSpeed) {
            // In full speed mode, skip if buffer is full
            int headPos = audioTrack.getPlaybackHeadPosition();
            int bytesPlayed = headPos * 2; // 16-bit = 2 bytes per sample
            int buffered = totalBytesWritten - bytesPlayed;
            if (buffered > buffer.length * 4) {
                return;
            }
        }

        int written = audioTrack.write(buffer, 0, buffer.length);
        if (written > 0) {
            totalBytesWritten += written;
        }
    }

    @Override
    public long getMicros() {
        if (audioTrack == null) return 0;
        return (System.nanoTime() - startTimeNanos) / 1000;
    }

    @Override
    public boolean hasSound() {
        // Return false so C64Screen's sleep throttle provides frame
        // pacing. Android AudioTrack buffers are too large to provide
        // the tight backpressure that desktop Java Sound gives.
        return false;
    }

    @Override
    public int available() {
        if (audioTrack == null) return 0;
        int headPos = audioTrack.getPlaybackHeadPosition();
        int bytesPlayed = headPos * 2; // 16-bit = 2 bytes per sample
        int buffered = totalBytesWritten - bytesPlayed;
        int bufferSize = audioTrack.getBufferSizeInFrames() * 2;
        return Math.max(0, bufferSize - buffered);
    }

    @Override
    public int getMasterVolume() {
        return vol;
    }

    @Override
    public void setMasterVolume(int v) {
        vol = v;
        if (audioTrack != null) {
            float volume = v / 100.0f;
            audioTrack.setStereoVolume(volume, volume);
        }
    }

    @Override
    public void shutdown() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                // ignore
            }
            audioTrack = null;
        }
    }

    @Override
    public void setSoundOn(boolean on) {
        soundOn = on;
    }

    @Override
    public void setFullSpeed(boolean full) {
        fullSpeed = full;
    }

    @Override
    public boolean fullSpeed() {
        return fullSpeed;
    }
}
