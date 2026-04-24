package com.dreamfabric.jac64;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

/**
 * Live debug window showing the 8 VIC-II sprites' current state: pointer,
 * X/Y position, enable / expansion / multicolor flags, color registers,
 * and a visualization of the current sprite data (all 63 bytes).
 *
 * Opens as a separate JFrame alongside the emulator window. Refreshes on
 * a 50 ms timer — cheap, unambiguously live, survives emulator speed
 * changes.
 *
 * Launch via {@link #attach(C64Screen)}; construct once per emulator.
 */
public class SpriteDebugWindow extends JFrame {
    private static final int SPRITE_W = 24;
    private static final int SPRITE_H = 21;
    private static final int PIXEL_SCALE = 3;
    private static final int ROW_HEIGHT = SPRITE_H * PIXEL_SCALE + 16;
    private static final int INFO_WIDTH = 280;

    private final C64Screen screen;
    private final SpritePanel[] panels = new SpritePanel[8];
    private final javax.swing.Timer refreshTimer;

    public static SpriteDebugWindow attach(C64Screen screen) {
        SpriteDebugWindow w = new SpriteDebugWindow(screen);
        w.setVisible(true);
        return w;
    }

    private SpriteDebugWindow(C64Screen screen) {
        super("JaC64 — Sprite debug");
        this.screen = screen;
        setLayout(new GridLayout(8, 1, 0, 2));
        for (int i = 0; i < 8; i++) {
            panels[i] = new SpritePanel(i);
            add(panels[i]);
        }
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(INFO_WIDTH + SPRITE_W * PIXEL_SCALE * 2 + 40, ROW_HEIGHT * 8 + 40);
        setLocationByPlatform(true);

        refreshTimer = new javax.swing.Timer(50, e -> {
            for (SpritePanel p : panels) p.repaint();
        });
        refreshTimer.start();
    }

    /**
     * One row per sprite — left half shows metadata, right half renders
     * the current sprite data using the sprite's colour registers.
     */
    private class SpritePanel extends JPanel {
        private final int idx;
        private final BufferedImage img =
            new BufferedImage(SPRITE_W, SPRITE_H, BufferedImage.TYPE_INT_RGB);

        SpritePanel(int idx) {
            this.idx = idx;
            setPreferredSize(new Dimension(0, ROW_HEIGHT));
            setBackground(Color.BLACK);
            setForeground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            C64Screen.Sprite s = screen.sprites[idx];

            renderSpriteImage(s);

            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(idx % 2 == 0 ? new Color(24, 24, 40) : new Color(12, 12, 24));
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Metadata text
            g2.setColor(Color.LIGHT_GRAY);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            int y = 14;
            g2.drawString(String.format("SPR%d  X=$%03X Y=$%02X",
                idx, s.x & 0x1ff, s.y & 0xff), 6, y); y += 14;
            g2.drawString(String.format("en=%-5s dma=%-5s paint=%s",
                String.valueOf(s.enabled), String.valueOf(s.dma),
                String.valueOf(s.painting)), 6, y); y += 14;
            g2.drawString(String.format("expX=%-5s expY=%-5s mc=%s",
                String.valueOf(s.expandX), String.valueOf(s.expandY),
                String.valueOf(s.multicolor)), 6, y); y += 14;
            g2.drawString(String.format("ptr=$%05X nb=%d",
                s.pointer, s.nextByte), 6, y); y += 14;
            g2.drawString(String.format("color=$%X pri=%s",
                s.color[2] & 0xf, String.valueOf(s.priority)), 6, y); y += 14;

            // Draw sprite preview
            int scale = PIXEL_SCALE;
            int imgX = INFO_WIDTH;
            int imgY = 2;
            g2.drawImage(img, imgX, imgY,
                SPRITE_W * scale, SPRITE_H * scale, null);
            g2.setColor(Color.DARK_GRAY);
            g2.drawRect(imgX - 1, imgY - 1,
                SPRITE_W * scale + 1, SPRITE_H * scale + 1);
        }

        private void renderSpriteImage(C64Screen.Sprite s) {
            int[] mem = screen.memory;
            int base = s.pointer & 0xffff;
            int bg = screen.cbmcolor[screen.bgColor & 0xf] | 0xff000000;
            int fg = screen.cbmcolor[s.color[2] & 0xf] | 0xff000000;
            int mc1 = screen.cbmcolor[screen.bgCol[1] & 0xf] | 0xff000000;
            int mc2 = screen.cbmcolor[screen.bgCol[2] & 0xf] | 0xff000000;

            for (int row = 0; row < SPRITE_H; row++) {
                int off = base + row * 3;
                int b0 = mem[(off) & 0xffff] & 0xff;
                int b1 = mem[(off + 1) & 0xffff] & 0xff;
                int b2 = mem[(off + 2) & 0xffff] & 0xff;
                long data24 = (((long) b0) << 16) | (b1 << 8) | b2;

                if (s.multicolor) {
                    // 12 double-pixels per row in multicolor — duplicate each
                    // to fill the 24-wide display area.
                    for (int pair = 0; pair < 12; pair++) {
                        int shift = 22 - pair * 2;
                        int bits = (int) ((data24 >> shift) & 3);
                        int color;
                        switch (bits) {
                            case 0: color = 0xff000000; break;          // transparent
                            case 1: color = mc1; break;
                            case 2: color = fg; break;
                            default: color = mc2; break;
                        }
                        img.setRGB(pair * 2, row, color);
                        img.setRGB(pair * 2 + 1, row, color);
                    }
                } else {
                    for (int x = 0; x < SPRITE_W; x++) {
                        int bit = 1 << (23 - x);
                        int color = (data24 & bit) != 0 ? fg : 0xff000000;
                        img.setRGB(x, row, color);
                    }
                }
            }
        }
    }
}
