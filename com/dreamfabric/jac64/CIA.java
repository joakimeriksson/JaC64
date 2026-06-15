/**
 * This file is a part of JaC64 - a Java C64 Emulator
 * Main Developer: Joakim Eriksson (Dreamfabric.com)
 * Contact: joakime@sics.se
 * Web: http://www.dreamfabric.com/c64
 * ---------------------------------------------------
 */
package com.dreamfabric.jac64;


/**
 * CIA emulation for JaC64. 
 *
 * Created: Sat Jul 30 05:38:32 2005
 *
 * @author Joakim Eriksson
 * @version 1.0
 */
public class CIA {

    public static final boolean TIMER_DEBUG = false; //true;
  public static final boolean WRITE_DEBUG = false; //true;

  public static final int PRA = 0x00;
  public static final int PRB = 0x01;
  public static final int DDRA = 0x02;
  public static final int DDRB = 0x03;
  public static final int TIMALO = 0x04;
  public static final int TIMAHI = 0x05;
  public static final int TIMBLO = 0x06;
  public static final int TIMBHI = 0x07;
  public static final int TODTEN = 0x08;
  public static final int TODSEC = 0x09;
  public static final int TODMIN = 0x0a;
  public static final int TODHRS = 0x0b;
  public static final int SDR = 0x0c;
  public static final int ICR = 0x0d;
  public static final int CRA = 0x0e;
  public static final int CRB = 0x0f;

  // CIA chip model select. The two revisions differ in timer-underflow ->
  // interrupt timing:
  //   8521 ("new" CIA, C64C):  data flag + IR flag + IRQ line all set in the
  //                            SAME cycle as the underflow. (default)
  //   6526 ("old" CIA):        data flag set on underflow, but the IR flag
  //                            (ICR bit 7) and IRQ line lag by ONE cycle.
  // 8521 is the default (matches VICE x64sc and passes irq-ack-vicii /
  // cia-timer-newcias). Set -Djac64.ciaModel=6526 for old-CIA timing
  // (matches the Lorenz CIA timing suite). See CIATimer.update().
  static final boolean CIA_OLD_MODEL =
      "6526".equals(System.getProperty("jac64.ciaModel"));

  CIATimer timerA;
  CIATimer timerB;

  int pra = 0;
  int prb = 0;
  int ddra = 0;
  int ddrb = 0;
  // Track CIA1 PB4 output state for lightpen edge detection. PB4 wires to
  // joystick port 1 pin 6 which connects to VIC /LP (per VICE c64cia1.c:153
  // `vicii_set_light_pen(maincpu_clk, !(m & 0x10))`). HIGH→LOW transition
  // triggers latch. Output is LOW only when DDRB bit 4 = 1 AND PRB bit 4 = 0.
  private boolean lpPrevHigh = true;
  int tod10sec = 0;
  int todsec = 0;
  int todmin = 0;
  int todhour = 0;
  int sdr;
  
  // For the CPU to read (contains status)
  int ciaicrRead;
  int ciaie = 0; // interrupt enable
  
  // B-Div is set if mode (bit 5,6 of CIACRB) = 10
  public static final int TIMER_B_DIV_MASK = 0x60;
  public static final int TIMER_B_DIV_VAL = 0x40;

  public long nextCIAUpdate = 0;

  private MOS6510Core cpu;
  private int offset;

  public int serialFake = 0;
  private ExtChip chips;

  public TimeEvent todEvent = new TimeEvent(0) {
      public void execute(long cycle) {
        time = time + 100000; // Approx a tenth of a second...
        int tmp = (tod10sec & 0x0f) + 1;
        tod10sec = tmp % 10;
        if (tmp > 9) {
          // Maxval == 0x59
          tmp = (todsec & 0x7f) + 1;
          if ((tmp & 0x0f) > 9)
            tmp += 0x06;
          if (tmp > 0x59)
            tmp = 0;
          todsec = tmp;
          // Wrapped seconds...
          // Minutes inc - max 0x59
          if (tmp == 0) {
            tmp = (todmin & 0x7f) + 1;
            if ((tmp & 0x0f) > 9)
              tmp += 0x06;
            if (tmp > 0x59)
              tmp = 0;
            todmin = tmp;

            // Hours, max 0x12
            if (tmp == 0) {
              tmp = (todhour & 0x1f) + 1;
              if ((tmp & 0x0f) > 9)
                tmp += 0x06;

              if (tmp > 0x11)
                tmp = 0;
              // how is hour? 1  - 12  or 0 - 11 ??
              todhour = tmp;
            }
          }
        }

        // TODO: fix alarms and latches !!
        // Since this should continue run, just reschedule...
//        System.out.println("TOD ticked..." + (todhour>>4) + (todhour & 0xf) + ":" + 
//              (todmin>>4) + (todmin&0xf) + ":" + (todsec>>4) + (todsec&0xf));
        cpu.scheduler.addEvent(this);
      }
  };
  
  /**
   * Creates a new <code>CIA</code> instance.
   *
   */
  public CIA(MOS6510Core cpu, int offset, ExtChip chips) {
    this.cpu = cpu;
    this.offset = offset;
    this.chips = chips;
    timerA = new CIATimer("TimerA", 1, true, null);
    timerB = new CIATimer("TimerB", 2, false, timerA);
    timerA.otherTimer = timerB;
    todEvent.time = cpu.cycles + 10000;
    cpu.scheduler.addEvent(todEvent);
  }

  /**
   * Detect CIA-1 PB4 HIGH→LOW transition and trigger VIC lightpen latch.
   * Only CIA-1 (offset 0x10c00) is wired to VIC /LP. PB4 output is LOW iff
   * DDRB bit 4 = 1 AND PRB bit 4 = 0. Open-drain otherwise (= HIGH).
   * Used by fldscroll's rastersync_lp routine which writes $DC01=$00 to
   * trigger the LP latch and reads $D013 to get the precise CPU cycle.
   *
   * Per VICE c64cia1.c:153 — m = val & pb & read_joyport_dig(JOYPORT_1);
   * vicii_set_light_pen(maincpu_clk, !(m & 0x10)). So bit 4 is the LP line.
   * Initial implementation used bit 3 (0x08) which incidentally triggered
   * on tests like spritefetchbug/test-136-2a where bit 3 toggles but
   * bit 4 doesn't (+322 spurious cells). Bit 4 (0x10) is correct.
   */
  private void checkLightPenTrigger() {
    if (offset != 0x10c00) return; // only CIA1
    // Flag jac64.lightpen default true. Set false to disable the LP
    // hardware (revert to legacy "all $D013/$D014 reads return 0").
    if (!Boolean.parseBoolean(System.getProperty("jac64.lightpen", "true"))) return;
    boolean nowHigh = !((ddrb & 0x10) != 0 && (prb & 0x10) == 0);
    if (lpPrevHigh && !nowHigh && chips instanceof C64Screen) {
      ((C64Screen) chips).triggerLightPen();
    }
    lpPrevHigh = nowHigh;
  }

  public void reset() {
    ciaicrRead = 0;
    ciaie = 0;

    timerA.reset();
    timerB.reset();
    tod10sec = 0;
    todsec = 0;
    todmin = 0;
    todhour = 0;

    updateInterrupts();
  }

  public String ciaID() {
    return offset == 0x10c00 ? "CIA 1" : "CIA 2";
  }

  public int performRead(int address, long cycles) {
    address -= offset;
    switch(address) {
    case DDRA:
      return ddra;
    case DDRB:
      return ddrb;
    case PRA:
      return (pra | ~ddra) & 0xff;
    case PRB:
      int data = (prb | ~ddrb) & 0xff;
      if ((timerA.cr & 0x02) > 0) {
        data &= 0xbf;
        if ((timerA.cr & 0x04) > 0) {
          data |= timerA.flipflop ? 0x40 : 0;
        } else {
          data |= timerA.underflow ? 0x40 : 0;          
        }
      }
      if ((timerB.cr & 0x02) > 0) {
        data &= 0x7f;
        if ((timerB.cr & 0x04) > 0) {
          data |= timerB.flipflop ? 0x80 : 0;
        } else {
          data |= timerB.underflow ? 0x80 : 0;          
        }
      }
      return data;
    case TIMALO:
//      System.out.println(ciaID() + " getTimerA LO, timer = " + timerA.getTimer(cycles));
      return timerA.getTimer(cycles) & 0xff;
    case TIMAHI:
      return timerA.getTimer(cycles) >> 8;
    case TIMBLO:
//      System.out.println(ciaID() + " getTimerB LO, timer = " + timerB.getTimer(cycles));
      return timerB.getTimer(cycles) & 0xff;
    case TIMBHI:
      return timerB.getTimer(cycles) >> 8;
    case TODTEN:
      return tod10sec;
    case TODSEC:
      return todsec;
    case TODMIN:
      return todmin;
    case TODHRS:
      return todhour;
    case SDR:
      return sdr;
    case CRA:
      return timerA.cr;
    case CRB:
      return timerB.cr;
    case ICR:
      // Clear interrupt register (flags)!
      if (TIMER_DEBUG && offset == 0x10d00) println("clear Interrupt register, was: " +
          Hex.hex2(ciaicrRead));
      int val = ciaicrRead;
      ciaicrRead = 0;

      // Latch off the IRQ/NMI immediately!!!
      updateInterrupts();

      return val;
      default:
        return 0xff;
    }
  }

  public void performWrite(int address, int data, long cycles) {
    address -= offset;

    if (WRITE_DEBUG) println(ciaID() + " Write to :" +
        Integer.toString(address, 16) + " = " +
        Integer.toString(data, 16));

    switch (address) {
    //monitor.println("Set Keyboard:" + data);
    case DDRA:
      ddra = data;
      break;
    case DDRB:
      ddrb = data;
      checkLightPenTrigger();
      break;
    case PRA:
      pra = data;
      break;
    case PRB:
      prb = data;
      checkLightPenTrigger();
      break;
    case TIMALO:
      // Update latch value
      timerA.latch = (timerA.latch & 0xff00) | data;
      break;
    case TIMAHI:
      timerA.latch = (timerA.latch & 0xff) | (data << 8);
      if (timerA.state == CIATimer.STOP) {
        timerA.timer = timerA.latch; 
      }
      if (TIMER_DEBUG && offset == 0x10d00) println(ciaID() + ": Timer A latch: " +
          timerA.latch + ": " + cycles);
      break;
    case TIMBLO:
      timerB.latch = (timerB.latch & 0xff00) | data;
      break;
    case TIMBHI:
      timerB.latch = (timerB.latch & 0xff) | (data << 8);
      if (timerB.state == CIATimer.STOP) {
        timerB.timer = timerB.latch; 
      }

      if (TIMER_DEBUG && offset == 0x10d00) println(ciaID() + ": Timer B latch: " +
          timerB.latch + ": " + cycles);
      break;
    case TODTEN:
      tod10sec = data;
      break;
    case TODSEC:
      todsec = data;
      break;
    case TODMIN:
      todmin = data;
      break;
    case TODHRS:
      todhour = data;
      break;
    case ICR:      // dc0d - CIAICR - CIA Interrupt Control Register
      boolean val = (data & 0x80) != 0;
      if (val) {
        // Set the 1 bits if val = 1
        ciaie |= data & 0x7f;
      } else {
        // Clear the 1 bits
        ciaie &= ~data;
      }
      // Trigger interrupts if needed...
      updateInterrupts();
      break;
    case CRA:
      timerA.writeCR(cycles, data);
      timerA.countCycles = (data & 0x20) == 0;
      break;

    case CRB:
      timerB.writeCR(cycles, data);
      timerB.countCycles = (data & 0x60) == 0;
      timerB.countUnderflow = (data & 0x60) == 0x40;
      break;
    }
  }

  private void updateInterrupts() {
    C64Screen screen = (chips instanceof C64Screen) ? (C64Screen) chips : null;
    boolean trace = screen != null && screen.fldTrace && offset == 0x10c00;
    if ((ciaie & ciaicrRead & 0x1f) != 0) {
      ciaicrRead |= 0x80;
      if (trace) {
        screen.fldLog("CIA1-IRQ-SET icr=$" + Integer.toHexString(ciaicrRead) +
            " ie=$" + Integer.toHexString(ciaie) +
            " clk=" + cpu.cycles +
            " vbeam=" + screen.vbeam +
            " ta=" + timerA.getTimer(cpu.cycles) +
            " tb=" + timerB.getTimer(cpu.cycles));
      }
      // Trigger the IRQ/NMI immediately!!!
      if (offset == 0x10c00) {
//    	  cpu.log("CIA 1  *** TRIGGERING CIA TIMER!!!: " +
//    			  ciaie + " " + chips.getIRQFlags() + " " + cpu.getIRQLow());
    	  chips.setIRQ(ExtChip.CIA_TIMER_IRQ);
      } else {
        chips.setNMI(ExtChip.CIA_TIMER_NMI);
      }
    } else {
      if (trace) {
        screen.fldLog("CIA1-IRQ-CLR icr=$" + Integer.toHexString(ciaicrRead) +
            " ie=$" + Integer.toHexString(ciaie) +
            " clk=" + cpu.cycles +
            " vbeam=" + screen.vbeam +
            " ta=" + timerA.getTimer(cpu.cycles) +
            " tb=" + timerB.getTimer(cpu.cycles));
      }
      if (offset == 0x10c00) {
//	System.out.println("*** CLEARING CIA TIMER!!!");
        chips.clearIRQ(ExtChip.CIA_TIMER_IRQ);
      } else {
        chips.clearNMI(ExtChip.CIA_TIMER_NMI);
      }
    }
  }

  private void println(String s) {
    System.out.println(ciaID() + ": " + s);
  }

  public void printStatus() {
    System.out.println("--------------------------");
    println(" status");
    System.out.println("Timer A state: " + timerA.state);
    System.out.println("Timer A next trigger: " + timerA.nextZero);
    System.out.println("CIA CRA: " + Hex.hex2(timerA.cr) + " => " +
        (((timerA.cr & 0x08) == 0) ?
            "cont" : "one-shot"));
    //    System.out.println("Timer A Interrupt: " + timerATrigg);
    System.out.println("Timer A Latch: " + timerA.latch);
    System.out.println("Timer B state: " + timerB.state);
    System.out.println("Timer B next trigger: " + timerB.nextZero);
    System.out.println("CIA CRB: " + Hex.hex2(timerA.cr) + " => " +
        (((timerB.cr & 0x08) == 0) ?
            "cont" : "one-shot"));
    //    System.out.println("Timer B Interrupt: " + timerBTrigg);
    System.out.println("Timer B Latch: " + timerB.latch);
    System.out.println("--------------------------");
  }


  // A timer class for handling the Timer state machines
  // The CIATimer is inspired by the CIA Timer State Machine in the
  // Frodo emulator
  private class CIATimer {

    // The states of the timer
    private static final int STOP = 0;
    private static final int WAIT = 1;
    private static final int LOAD_STOP = 2;
    private static final int LOAD_COUNT = 3;
    private static final int LOAD_WAIT_COUNT = 5;
    private static final int COUNT = 6;
    private static final int COUNT_STOP = 7;

    // If timer is connected...
    CIATimer otherTimer;

    int state = STOP;
    // The latch for this timer
    int latch = 0;
    int timer = 0;

    // When is an update needed for this timer?
    long nextUpdate = 0;
    long nextZero = 0;
    long lastLatch = 0;

    boolean interruptNext = false;
    // 6526 (old CIA) only: set when the data flag was raised this cycle and
    // the IR flag / IRQ line must be raised one cycle later.
    boolean irqRaisePending = false;
    boolean underflow = false;
    boolean flipflop = false;

    boolean countCycles = false;
    boolean countUnderflow = false;

    TimeEvent updateEvent = new TimeEvent(0) {
      public void execute(long cycles) {
        doUpdate(cycles);
        if (state != STOP) {
//	    System.out.println(ciaID() + " Adding Timer update event at " + cycles +
//			       " with time: " + nextUpdate + " state: " + state);
          cpu.scheduler.addEvent(this, nextUpdate);
        }
      }
    };
    
    int writeCR = -1;
    int cr = 0;
    String id;
    int iflag;
    boolean updateOther;

    CIATimer(String id, int flag, boolean uo, CIATimer other) {
      this.id = id;
      this.otherTimer = other;
      this.iflag = flag;
      updateOther = uo;
    }

    private void reset() {
      latch = 0xffff;
      timer = 0xffff;
      countUnderflow = false;
      flipflop = false;
      state = STOP;
      nextZero = 0;
      nextUpdate = 0;
      writeCR = -1;
      interruptNext = false;
      irqRaisePending = false;
      cpu.scheduler.removeEvent(updateEvent);
    }

    private int getTimer(long cycles) {
      if (state != COUNT) return timer;
      // Cascade timer (count other-timer underflows, not Phi2): its live
      // value is maintained in `timer` by the COUNT/countUnderflow branch
      // — decremented only when the other timer underflows. The Phi2
      // formula below (nextZero - cycles) would make it free-run on
      // wall-clock cycles AND corrupt `timer` (line `timer = t`), since
      // getTimer() runs at the top of every update(). Return `timer` as-is.
      // (CPUTIMING uses CIA2 Timer B cascade as a 32-bit cycle counter;
      // the Phi2 formula made it read garbage — e.g. 589833 vs 7.)
      if (countUnderflow) return timer;
      int t = (int) (nextZero - cycles);
      if (t < 0) {
        // Unexpected underflow...
        t = 0;
      }
      timer = t;
      return t;
    }

    private void loadTimer(long cycles) {
      timer = latch;
      nextZero = cycles + latch;

      if (TIMER_DEBUG && offset == 0x10d00) {
        System.out.println(ciaID() + ": " + id + " - timer loaded at "
            + cycles + " with: " + latch + " diff " +
            (cycles - lastLatch));
        lastLatch = cycles;
      }
    }

    private void triggerInterrupt(long cycles) {
      if (TIMER_DEBUG && offset == 0x10d00)
        System.out.println(ciaID() + ": " + id + " - trigger interrupt at: "
            + cycles + " nextZero: " + nextZero +
            " nextUpdate: " + nextUpdate);
      interruptNext = true;
      underflow = true;
      flipflop = !flipflop;
      // One shot?
      if ((cr & 8) != 0) {
        cr &= 0xfe;
        writeCR &= 0xfe;
        state = LOAD_STOP;
      } else {
        state = LOAD_COUNT;
      } // TODO: fix update of tb.
//      if (updateOther) {
//        otherTimer.update(cycles);
//      }
    }

    void writeCR(long cycles, int data) {
      writeCR = data;
      if (nextUpdate > cycles + 1 || !updateEvent.scheduled) {
        nextUpdate = cycles + 1;
        cpu.scheduler.addEvent(updateEvent, nextUpdate);
      }
    }


    public void doUpdate(long cycles) {
      if (nextUpdate == 0) {
        nextUpdate = cycles;
        nextZero = cycles;
      }
      if (cycles == nextUpdate) {
        // If the call is on "spot" then just do it!
        update(cycles);
      } else {
        // As long as cycles is larger than next update, just continue
        // call it!
        while (cycles >= nextUpdate) {
          System.out.println(ciaID() + ": " + id +
              " ** update at: " + cycles + " expected: " + nextUpdate + " state: " + state);
          update(nextUpdate);
        }
      }
    }

    // maybe only update when "needed" and when registers read???
    // NEED TO CHECK IF WE ARE CALLED WHEN EXPECTED!!!
    // No - since BA Low will cause no updates of IO units..??
    public void update(long cycles) {
      // set a default
      underflow = false;
      nextUpdate = cycles + 1;
      // Update timer...
      getTimer(cycles);

      // 6526 (old CIA) only: the data flag was raised on last cycle's
      // underflow; raise the IR flag (ICR bit 7) + IRQ line NOW, one cycle
      // later. (8521 raises both in the same cycle — see the end of this
      // method.) This is the OLD-CIA 1-cycle interrupt delay the Lorenz CIA
      // suite expects; the 8521 default keeps irq-ack-vicii passing.
      if (CIA_OLD_MODEL && irqRaisePending) {
        irqRaisePending = false;
        updateInterrupts();
      }

      // Timer state machine!
      // NOTE: the `if (interruptNext)` firing block runs AFTER the state
      // machine (end of this method). For NEW CIA (8521, default) that makes
      // underflow → IRQ source set happen IN THE SAME CYCLE (VICE x64sc
      // ciacore.c:402-417 CIA_IRQ_RAISE0). For OLD CIA (6526) the IR flag /
      // IRQ line is deferred one cycle by the irqRaisePending block above.
      switch (state) {
      case STOP:
        // Nothing...
        break;
      case WAIT:
        // VICE pipeline: at this cycle the load completes (cnt=latch), and
        // COUNT3 is still off so no decrement. Set nextZero now so the
        // first cycle in COUNT (next update) reads `latch`.
        loadTimer(cycles);
        state = COUNT;
        break;
      case LOAD_STOP:
        loadTimer(cycles);
        // Stop timer!
        state = STOP;
        break;
      case LOAD_COUNT:
        loadTimer(cycles);
        state = COUNT;
        break;
      case LOAD_WAIT_COUNT:
        if (nextZero == cycles + 1) {
          triggerInterrupt(cycles);
        }
        // Cache latch into timer so getTimer() during the WAIT hold cycle
        // returns the loaded value. nextZero is set when WAIT→COUNT runs,
        // matching VICE's pipeline (cnt=latch on LOAD, hold cycle, then
        // COUNT3 raised → decrement starts).
        timer = latch;
        state = WAIT;
        break;
      case COUNT_STOP:
        if (!countUnderflow) {
          // Cache the remaining-count value when stopping. Was `cycles -
          // nextZero` which is negative until underflow → got clamped to 0,
          // making bascan tests 3/4/6/7 read 0 instead of the live value.
          timer = (int) (nextZero - cycles);
          if (timer < 0) timer = 0;
        }
        state = STOP;
        break;
      case COUNT:
        // perform count - this assumes that we are counting
        // cycles!!!
        if (countUnderflow) {
          if (otherTimer.underflow)
            timer--;
          if (timer <= 0) {
            triggerInterrupt(cycles);
          }
        } else {
          // TODO: check this!!!
          if (cycles >= nextZero && state != STOP) {
            state = LOAD_COUNT;
            triggerInterrupt(cycles);
          } else {
            // We got here too early... what now?
            nextUpdate = nextZero;
          }
        }
        break;
      }

      // Delayed write of CR - is that only for timers?
      if (writeCR != -1) {
        delayedWrite(cycles);
        writeCR = -1;
      }
      // triggerInterrupt set interruptNext above (this cycle's underflow).
      // The DATA flag (ICR bit 0/1) is raised this cycle in BOTH models.
      if (interruptNext) {
        ciaicrRead |= iflag;
        interruptNext = false;
        if (CIA_OLD_MODEL) {
          // 6526: defer the IR flag / IRQ line to next cycle's update().
          irqRaisePending = true;
        } else {
          // 8521: IR flag + IRQ line raised in the SAME cycle.
          updateInterrupts();
        }
      }
    }

    void delayedWrite(long cycles) {
      nextUpdate = cycles + 1;
      switch(state) {
      case STOP:
      case LOAD_STOP:
        if ((writeCR & 1) > 0) {
          // Start timer
          if ((writeCR & 0x10) > 0) {
            // force load
            state = LOAD_WAIT_COUNT;
          } else {
            // Just count!
            loadTimer(cycles);
            state = WAIT;
          }
        } else {
          if ((writeCR & 0x10) > 0) {
            state = LOAD_STOP;
          }
        }
        break;
      case COUNT:
        if ((writeCR & 1) > 0) {
          if ((writeCR & 0x10) > 0) {
            // force load
            state = LOAD_WAIT_COUNT;
          } // Otherwise just continue counting!
        } else {
          if ((writeCR & 0x10) > 0) {
            state = LOAD_STOP;
          } else {
            state = COUNT_STOP;
          }
        }
        break;
      case LOAD_COUNT:
      case WAIT:
        if ((writeCR & 1) > 0) {
          // Start timer
          if ((writeCR & 8) > 0) {
            // one-shot
            writeCR = writeCR & 0xfe;
            state = STOP;
          } else if ((writeCR & 0x10) > 0) {
            state = LOAD_WAIT_COUNT;
          } // Otherwise just continue counting!
        } else {
          state = COUNT_STOP;
        }
        break;
      }
      cr = writeCR & 0xef;
    }
  }
}
