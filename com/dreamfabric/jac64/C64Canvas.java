/**
 * This file is a part of JaC64 - a Java C64 Emulator
 * Main Developer: Joakim Eriksson (Dreamfabric.com)
 * Contact: joakime@sics.se
 * Web: http://www.dreamfabric.com/c64
 * ---------------------------------------------------
 *
 * Desktop AWT/Swing rendering component. Handles all desktop-specific
 * rendering, keyboard bridging, and mouse input.
 */

package com.dreamfabric.jac64;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import javax.swing.JPanel;

/**
 * The actual AWT component that shows the C64 Screen.
 * Implements ScreenRefreshListener to receive frame-ready signals
 * from the VIC-II emulation and trigger repaints.
 *
 * @author Joakim Eriksson
 * @version 2.0
 */
public class C64Canvas extends JPanel implements KeyListener, FocusListener,
    MouseListener, MouseMotionListener, C64Screen.ScreenRefreshListener {

  private static final long serialVersionUID = 5124260828376559537L;

  boolean integerScale = true;
  C64Screen scr;
  Keyboard keyboard;
  boolean autoScale;
  int w;
  int h;

  // Rendering
  private Image screenImage;
  private MemoryImageSource mis;
  private int[] renderBuffer;

  public C64Canvas(C64Screen screen, boolean dob, Keyboard keyboard) {
    super();
    autoScale = dob;
    scr = screen;
    this.keyboard = keyboard;
    setFont(new Font("Monospaced", Font.PLAIN, 11));
    setFocusTraversalKeysEnabled(false);
    addFocusListener(this);
    addKeyListener(this);
    addMouseListener(this);
    addMouseMotionListener(this);

    // Create a render buffer and MemoryImageSource for efficient rendering
    renderBuffer = new int[C64Screen.IMG_TOTWIDTH * C64Screen.IMG_TOTHEIGHT];
    mis = new MemoryImageSource(C64Screen.IMG_TOTWIDTH, C64Screen.IMG_TOTHEIGHT,
        renderBuffer, 0, C64Screen.IMG_TOTWIDTH);
    mis.setAnimated(true);
    mis.setFullBufferUpdates(true);
    screenImage = createImage(mis);
  }

  /**
   * Convenience method to set up a complete desktop screen.
   * Creates AudioDriverSE, initializes the screen, creates the canvas,
   * and wires everything together.
   */
  public static C64Canvas setupDesktop(C64Screen screen, CPU cpu, boolean autoScale) {
    AudioDriverSE audioDriver = new AudioDriverSE();
    screen.init(cpu, audioDriver);
    C64Canvas canvas = new C64Canvas(screen, autoScale, screen.getKeyboard());
    screen.setScreenRefreshListener(canvas);
    return canvas;
  }

  public void setAutoscale(boolean val) {
    autoScale = val;
  }

  public void setIntegerScaling(boolean yes) {
    integerScale = yes;
  }

  // -------------------------------------------------------------------
  // ScreenRefreshListener - called from VIC-II emulation thread
  // -------------------------------------------------------------------

  @Override
  public void onFrameReady() {
    // Copy pixels from the front buffer to our render buffer
    int[] pixels = scr.getPixelBuffer();
    if (pixels != null && renderBuffer != null) {
      System.arraycopy(pixels, 0, renderBuffer, 0,
          Math.min(pixels.length, renderBuffer.length));
      mis.newPixels();
    }
    repaint();
  }

  // -------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------

  public void update(Graphics g) {
    // No clearing of paint area...
    paint(g);
  }

  public void paint(Graphics g) {
    if (g == null) return;

    if (screenImage == null) {
      // Image not yet created (can happen before component is displayable)
      screenImage = createImage(mis);
      if (screenImage == null) return;
    }

    int dw = scr.displayWidth;
    int dh = scr.displayHeight;
    int ox = scr.offsetX;
    int oy = scr.offsetY;

    if (autoScale) {
      if (w != getWidth() || h != getHeight()) {
        w = getWidth();
        h = getHeight();
        double fac = (1.0 * w) / C64Screen.IMG_TOTWIDTH;
        if (fac > (1.0 * h) / C64Screen.IMG_TOTHEIGHT) {
          fac = (1.0 * h) / C64Screen.IMG_TOTHEIGHT;
        }
        if (integerScale && fac > 1.0) fac = (int) fac;
        scr.setDisplayFactor(fac);
        scr.setDisplayOffset((int) (w - fac * C64Screen.IMG_TOTWIDTH) / 2,
            (int) (h - fac * C64Screen.IMG_TOTHEIGHT) / 2);
        dw = scr.displayWidth;
        dh = scr.displayHeight;
        ox = scr.offsetX;
        oy = scr.offsetY;
      }
    }

    // Draw black borders
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, ox, dh + oy * 2);
    g.fillRect(ox + dw, 0, ox, dh + oy * 2);
    g.fillRect(0, 0, dw + ox * 2, oy);
    g.fillRect(0, dh + oy, dw + ox * 2, oy);

    // Draw the C64 screen
    g.drawImage(screenImage, ox, oy, dw, dh, null);
  }

  // -------------------------------------------------------------------
  // Keyboard
  // -------------------------------------------------------------------

  public void keyPressed(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == 0) {
      key = (int) Character.toLowerCase(event.getKeyChar());
    }
    keyboard.keyPressed(key, event.getKeyLocation(), event.getModifiersEx());
  }

  public void keyReleased(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == 0) {
      key = (int) Character.toLowerCase(event.getKeyChar());
    }
    keyboard.keyReleased(key, event.getKeyLocation());
  }

  public void keyTyped(KeyEvent event) {
    char chr = event.getKeyChar();
    if (chr == 'w') {
      if ((event.getModifiers() & KeyEvent.ALT_MASK) != 0) {
        scr.getAudioDriver().setFullSpeed(!scr.getAudioDriver().fullSpeed());
      }
    }
  }

  // -------------------------------------------------------------------
  // Focus listener
  // -------------------------------------------------------------------

  public void focusGained(FocusEvent evt) {
    keyboard.reset();
  }

  public void focusLost(FocusEvent evt) {
    keyboard.reset();
  }

  public boolean isFocusable() {
    return true;
  }

  // -------------------------------------------------------------------
  // Mouse listener - for paddle/pointer emulation
  // -------------------------------------------------------------------

  public void mouseDragged(MouseEvent e) {
    scr.setPointerPosition(e.getX(), e.getY());
  }

  public void mouseMoved(MouseEvent e) {
    scr.setPointerPosition(e.getX(), e.getY());
  }

  public void mouseClicked(MouseEvent e) {
  }

  public void mouseEntered(MouseEvent e) {
  }

  public void mouseExited(MouseEvent e) {
  }

  public void mousePressed(MouseEvent e) {
    if (e.getButton() == MouseEvent.BUTTON1) {
      scr.button1 = true;
    } else {
      scr.button2 = true;
    }
  }

  public void mouseReleased(MouseEvent e) {
    if (e.getButton() == MouseEvent.BUTTON1) {
      scr.button1 = false;
    } else {
      scr.button2 = false;
    }
  }
}
