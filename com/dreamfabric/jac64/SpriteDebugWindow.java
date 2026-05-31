package com.dreamfabric.jac64;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

/**
 * Live debug window showing the 8 VIC-II sprites, with every unique
 * multiplex instance captured during the previous frame on the same
 * row. Each row shows the "current live" state plus up to N historical
 * position/data snapshots (one per unique X+pointer+nextByte tuple
 * seen at any scan-line start during the frame). Makes the 9-sprite
 * trick and per-line pointer swaps visible at a glance.
 *
 * Launch with `-Djac64.spriteDebug=true` in JaC64's main class; the
 * GUI auto-attaches it.
 */
public class SpriteDebugWindow extends JFrame {
    private static final int SPRITE_W = 24;
    private static final int SPRITE_H = 21;
    private static final int PIXEL_SCALE = 2;
    private static final int TILE_W = SPRITE_W * PIXEL_SCALE + 4;
    private static final int TILE_H = SPRITE_H * PIXEL_SCALE + 14;
    private static final int INFO_WIDTH = 200;
    private static final int ROW_HEIGHT = TILE_H + 6;

    private final C64Screen screen;
    private final SpriteRow[] rows = new SpriteRow[8];
    private final javax.swing.Timer refreshTimer;

    public static SpriteDebugWindow attach(C64Screen screen) {
        SpriteDebugWindow w = new SpriteDebugWindow(screen);
        w.setVisible(true);
        return w;
    }

    private SpriteDebugWindow(C64Screen screen) {
        super("JaC64 — Sprite debug");
        this.screen = screen;
        setLayout(new GridLayout(8, 1, 0, 1));
        for (int i = 0; i < 8; i++) {
            rows[i] = new SpriteRow(i);
            add(rows[i]);
        }
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        // Width: info panel + N tiles, where N = SPRITE_FRAME_MAX
        int w = INFO_WIDTH + TILE_W * C64Screen.SPRITE_FRAME_MAX + 20;
        setSize(w, ROW_HEIGHT * 8 + 40);
        setLocationByPlatform(true);

        refreshTimer = new javax.swing.Timer(50, e -> {
            for (SpriteRow p : rows) p.repaint();
        });
        refreshTimer.start();
    }

    private class SpriteRow extends JPanel {
        private final int idx;
        private final BufferedImage live =
            new BufferedImage(SPRITE_W, SPRITE_H, BufferedImage.TYPE_INT_RGB);
        private final BufferedImage[] hist =
            new BufferedImage[C64Screen.SPRITE_FRAME_MAX];

        SpriteRow(int idx) {
            this.idx = idx;
            setPreferredSize(new Dimension(0, ROW_HEIGHT));
            setBackground(Color.BLACK);
            for (int i = 0; i < hist.length; i++) {
                hist[i] = new BufferedImage(SPRITE_W, SPRITE_H,
                    BufferedImage.TYPE_INT_RGB);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            C64Screen.Sprite s = screen.sprites[idx];

            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(idx % 2 == 0 ? new Color(24, 24, 40) : new Color(12, 12, 24));
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Metadata text
            g2.setColor(Color.LIGHT_GRAY);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            int y = 12;
            g2.drawString(String.format("SPR%d  X=$%03X Y=$%02X",
                idx, s.x & 0x1ff, s.y & 0xff), 4, y); y += 12;
            g2.drawString(String.format("en=%d dma=%d paint=%d",
                s.enabled ? 1 : 0, s.dma ? 1 : 0, s.painting ? 1 : 0), 4, y); y += 12;
            g2.drawString(String.format("expX=%d expY=%d mc=%d",
                s.expandX ? 1 : 0, s.expandY ? 1 : 0,
                s.multicolor ? 1 : 0), 4, y); y += 12;
            g2.drawString(String.format("ptr=$%05X col=$%X",
                s.pointer, s.color[2] & 0xf), 4, y); y += 12;
            int count = screen.spriteFrameCountLast[idx];
            g2.drawString(String.format("frame: %d instances", count), 4, y);

            // Live sprite (left-most tile)
            renderSpriteImage(live, s.pointer & 0xffff,
                s.multicolor, s.color[2] & 0xf);
            drawTile(g2, INFO_WIDTH, live, "live", s.x & 0x1ff, s.y & 0xff);

            // Historical frame instances — already deduped by
            // (x, pointer, nextByte) in C64Screen's capture.
            for (int i = 0; i < count && i < C64Screen.SPRITE_FRAME_MAX; i++) {
                renderSpriteImage(hist[i],
                    screen.spriteFramePtrLast[idx][i] & 0xffff,
                    s.multicolor, s.color[2] & 0xf);
                int xTile = INFO_WIDTH + (i + 1) * TILE_W;
                drawTile(g2, xTile, hist[i],
                    String.format("nb=%d", screen.spriteFrameNbLast[idx][i]),
                    screen.spriteFrameXLast[idx][i] & 0x1ff,
                    screen.spriteFrameYLast[idx][i] & 0xff);
            }
        }

        private void drawTile(Graphics2D g2, int x, BufferedImage img,
                              String label, int sx, int sy) {
            int imgW = SPRITE_W * PIXEL_SCALE;
            int imgH = SPRITE_H * PIXEL_SCALE;
            g2.drawImage(img, x, 2, imgW, imgH, null);
            g2.setColor(new Color(60, 60, 60));
            g2.drawRect(x - 1, 1, imgW + 1, imgH + 1);
            g2.setColor(Color.GRAY);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            g2.drawString(label, x, imgH + 12);
            g2.drawString(String.format("$%03X,$%02X", sx, sy),
                x, imgH + 22);
        }

        private void renderSpriteImage(BufferedImage img, int base,
                                        boolean mc, int colorReg) {
            int[] mem = screen.memory;
            int fg = screen.cbmcolor[colorReg] | 0xff000000;
            int mc1 = screen.cbmcolor[screen.bgCol[1] & 0xf] | 0xff000000;
            int mc2 = screen.cbmcolor[screen.bgCol[2] & 0xf] | 0xff000000;
            int bg = 0xff000000; // transparent = black

            for (int row = 0; row < SPRITE_H; row++) {
                int off = base + row * 3;
                int b0 = mem[(off) & 0xffff] & 0xff;
                int b1 = mem[(off + 1) & 0xffff] & 0xff;
                int b2 = mem[(off + 2) & 0xffff] & 0xff;
                long data24 = (((long) b0) << 16) | (b1 << 8) | b2;

                if (mc) {
                    for (int pair = 0; pair < 12; pair++) {
                        int shift = 22 - pair * 2;
                        int bits = (int) ((data24 >> shift) & 3);
                        int color;
                        switch (bits) {
                            case 0: color = bg; break;
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
                        int color = (data24 & bit) != 0 ? fg : bg;
                        img.setRGB(x, row, color);
                    }
                }
            }
        }
    }
}
