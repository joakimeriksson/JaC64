package com.dreamfabric.jsidplay;

import javax.swing.*;
import java.io.File;

/**
 * JSIDPlay - A SID music player with real-time visualization.
 * Uses the JaC64 ReSID engine for cycle-accurate SID emulation.
 *
 * Usage: java com.dreamfabric.jsidplay.JSIDPlay [file.sid]
 *
 * Keyboard shortcuts:
 *   Space       - Play / Pause
 *   Left/Right  - Previous / Next song
 *   Escape      - Stop
 *   Cmd/Ctrl+O  - Open file
 */
public class JSIDPlay {

    public static void main(String[] args) {
        // Use system look and feel for file dialogs, but override colors
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            // ignore
        }

        SwingUtilities.invokeLater(() -> {
            SIDPlayerFrame frame = new SIDPlayerFrame();
            frame.setVisible(true);

            // Load file from command line if provided
            if (args.length > 0) {
                File file = new File(args[0]);
                if (file.exists()) {
                    frame.loadFile(file);
                } else {
                    System.err.println("File not found: " + args[0]);
                }
            }
        });
    }
}
