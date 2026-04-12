package com.dreamfabric.jsidplay;

import com.dreamfabric.jac64.C64Screen;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main application frame for JSIDPlay.
 * Contains transport controls, file browser, chip model selector, and visualization.
 */
public class SIDPlayerFrame extends JFrame {

    private static final Color BG_DARK = new Color(0x16, 0x16, 0x3B);
    private static final Color BG_TOOLBAR = new Color(0x12, 0x12, 0x34);
    private static final Color TEXT_PRIMARY = new Color(0x7B, 0x71, 0xD5);
    private static final Color TEXT_BRIGHT = new Color(0xA0, 0x98, 0xF0);
    private static final Color BUTTON_BG = new Color(0x2A, 0x2A, 0x60);
    private static final Color BUTTON_HOVER = new Color(0x3A, 0x34, 0x80);
    private static final Color ACCENT = new Color(0x50, 0xD0, 0xE0);

    private SIDPlayerEngine engine;
    private SIDVisualizationPanel vizPanel;
    private AtomicReference<SIDStateSnapshot> snapshotRef;
    private javax.swing.Timer repaintTimer;

    // Controls
    private JButton openButton, hvscButton;
    private JButton playButton, pauseButton, stopButton;
    private JButton prevButton, nextButton;
    private JLabel songLabel;
    private JComboBox<String> chipCombo;
    private JLabel statusLabel;
    private File lastDir;

    public SIDPlayerFrame() {
        super("JSIDPlay - SID Music Player");
        snapshotRef = new AtomicReference<>();
        engine = new SIDPlayerEngine();
        engine.init();
        engine.setOnStateChange(this::updateUI);

        initUI();
        initKeyBindings();

        // 50Hz repaint timer
        repaintTimer = new javax.swing.Timer(20, e -> {
            SIDStateSnapshot snap = engine.getLatestSnapshot();
            snapshotRef.set(snap);
            vizPanel.repaint();
        });
        repaintTimer.start();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 700);
        setMinimumSize(new Dimension(700, 500));
        setLocationRelativeTo(null);

        // Dark window background
        getContentPane().setBackground(BG_DARK);
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 0));

        // --- Toolbar ---
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbar.setBackground(BG_TOOLBAR);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                new Color(0x3A, 0x34, 0x80)));

        openButton = makeButton("\u2395 Open", "Open a local .sid file");
        hvscButton = makeButton("\u266B HVSC", "Browse High Voltage SID Collection");
        playButton = makeButton("\u25B6 Play", "Start playback");
        pauseButton = makeButton("\u23F8 Pause", "Pause playback");
        stopButton = makeButton("\u25A0 Stop", "Stop playback");
        prevButton = makeButton("\u25C0 Prev", "Previous song");
        nextButton = makeButton("Next \u25B6", "Next song");

        songLabel = new JLabel("  --/--  ");
        songLabel.setForeground(TEXT_BRIGHT);
        songLabel.setFont(new Font("Monospaced", Font.BOLD, 13));

        chipCombo = new JComboBox<>(new String[]{"SID 6581", "SID 8580"});
        chipCombo.setBackground(BUTTON_BG);
        chipCombo.setForeground(TEXT_PRIMARY);
        chipCombo.setFont(new Font("Monospaced", Font.PLAIN, 11));
        chipCombo.addActionListener(e -> {
            int model = chipCombo.getSelectedIndex() == 0
                    ? C64Screen.RESID_6581 : C64Screen.RESID_8580;
            engine.setChipModel(model);
        });

        toolbar.add(openButton);
        toolbar.add(hvscButton);
        toolbar.add(makeSeparator());
        toolbar.add(prevButton);
        toolbar.add(playButton);
        toolbar.add(pauseButton);
        toolbar.add(stopButton);
        toolbar.add(nextButton);
        toolbar.add(makeSeparator());
        toolbar.add(songLabel);
        toolbar.add(makeSeparator());
        toolbar.add(chipCombo);

        add(toolbar, BorderLayout.NORTH);

        // --- Visualization ---
        vizPanel = new SIDVisualizationPanel(snapshotRef);
        add(vizPanel, BorderLayout.CENTER);

        // --- Status bar ---
        statusLabel = new JLabel("  Ready - press Open to load a .sid file");
        statusLabel.setForeground(TEXT_PRIMARY);
        statusLabel.setBackground(BG_TOOLBAR);
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x3A, 0x34, 0x80)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        add(statusLabel, BorderLayout.SOUTH);

        // Button actions
        openButton.addActionListener(e -> openFile());
        hvscButton.addActionListener(e -> browseHVSC());
        playButton.addActionListener(e -> engine.play());
        pauseButton.addActionListener(e -> engine.pause());
        stopButton.addActionListener(e -> engine.stop());
        prevButton.addActionListener(e -> { engine.prevSong(); engine.play(); });
        nextButton.addActionListener(e -> { engine.nextSong(); engine.play(); });
    }

    private void initKeyBindings() {
        // Global keyboard shortcuts
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_SPACE:
                    if (engine.isPlaying() && !engine.isPaused()) {
                        engine.pause();
                    } else {
                        engine.play();
                    }
                    return true;
                case KeyEvent.VK_LEFT:
                    engine.prevSong();
                    engine.play();
                    return true;
                case KeyEvent.VK_RIGHT:
                    engine.nextSong();
                    engine.play();
                    return true;
                case KeyEvent.VK_ESCAPE:
                    engine.stop();
                    return true;
                case KeyEvent.VK_O:
                    if (e.isMetaDown() || e.isControlDown()) {
                        openFile();
                        return true;
                    }
                    break;
            }
            return false;
        });
    }

    private void openFile() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".sid")
                        || f.getName().toLowerCase().endsWith(".psid");
            }
            public String getDescription() {
                return "SID Music Files (*.sid, *.psid)";
            }
        });

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            lastDir = file.getParentFile();
            loadFile(file);
        }
    }

    private void browseHVSC() {
        HVSCBrowser browser = new HVSCBrowser(this);
        browser.setVisible(true); // blocks until closed (modal)

        File downloaded = browser.getDownloadedFile();
        if (downloaded != null) {
            loadFile(downloaded);
        }
    }

    public void loadFile(File file) {
        try {
            engine.loadFile(file);
            vizPanel.setPSIDInfo(engine.getPSID());
            statusLabel.setText("  " + engine.getPSID().toString());
            updateSongLabel();
            engine.play();
        } catch (Exception ex) {
            statusLabel.setText("  Error: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Failed to load SID file:\n" + ex.getMessage(),
                    "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateUI() {
        SwingUtilities.invokeLater(() -> {
            updateSongLabel();
            vizPanel.setCurrentSong(engine.getCurrentSong());
            PSIDFile p = engine.getPSID();
            if (p != null) {
                String state = engine.isPlaying()
                        ? (engine.isPaused() ? "Paused" : "Playing")
                        : "Stopped";
                statusLabel.setText("  " + state + " - " + p.title + " by " + p.author);
            }
        });
    }

    private void updateSongLabel() {
        PSIDFile p = engine.getPSID();
        if (p != null) {
            songLabel.setText("  " + engine.getCurrentSong() + "/" + p.songs + "  ");
        } else {
            songLabel.setText("  --/--  ");
        }
    }

    private JButton makeButton(String text, String tooltip) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.setBackground(BUTTON_BG);
        btn.setForeground(TEXT_PRIMARY);
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setFont(new Font("Monospaced", Font.PLAIN, 12));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x3A, 0x34, 0x80), 1),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(BUTTON_HOVER); }
            public void mouseExited(MouseEvent e) { btn.setBackground(BUTTON_BG); }
        });
        return btn;
    }

    private Component makeSeparator() {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 24));
        sep.setForeground(new Color(0x3A, 0x34, 0x80));
        return sep;
    }
}
