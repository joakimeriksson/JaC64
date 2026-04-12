package com.dreamfabric.jsidplay;

import com.dreamfabric.jac64.*;
import resid.SID;

/**
 * Minimal ExtChip implementation for SID playback.
 * Only routes SID register I/O ($D400-$D41F) to RESIDChip.
 * All other I/O (VIC, CIA, etc.) is ignored.
 */
public class SIDPlayerChips extends ExtChip {

    private final RESIDChip residChip;

    public SIDPlayerChips(MOS6510Core cpu, AudioDriver audioDriver) {
        init(cpu);
        residChip = new RESIDChip(cpu, audioDriver);
    }

    public RESIDChip getRESIDChip() {
        return residChip;
    }

    public SID getSID() {
        return residChip.getSID();
    }

    @Override
    public int performRead(int address, long cycles) {
        // CPU passes raw C64 addresses ($D000-$DFFF).
        // RESIDChip expects address + IO_OFFSET (matching C64Screen convention).
        if (address >= 0xD400 && address <= 0xD41F) {
            return residChip.performRead(address + CPU.IO_OFFSET, cycles);
        }
        // CIA1 registers
        if (address >= 0xDC00 && address <= 0xDC0F) {
            int reg = address - 0xDC00;
            switch (reg) {
                case 0x04: return ciaTimerALo;
                case 0x05: return ciaTimerAHi;
                case 0x0D:
                    int val = ciaICR;
                    ciaICR = 0; // Reading clears flags
                    return val;
                case 0x0E: return ciaCRA;
            }
            return 0;
        }
        return 0;
    }

    // Minimal CIA1 Timer A emulation for RSID tunes
    private int ciaTimerALo = 0xFF;
    private int ciaTimerAHi = 0xFF;
    private int ciaICR = 0;       // Interrupt control register
    private int ciaICRMask = 0;   // Interrupt mask
    private int ciaCRA = 0;       // Control register A
    private boolean ciaTimerRunning = false;

    public int getCIATimerA() {
        return ciaTimerALo | (ciaTimerAHi << 8);
    }

    public boolean isCIATimerRunning() {
        return ciaTimerRunning;
    }

    @Override
    public void performWrite(int address, int data, long cycles) {
        // CPU passes raw C64 addresses ($D000-$DFFF).
        // RESIDChip expects address + IO_OFFSET.
        if (address >= 0xD400 && address <= 0xD41F) {
            residChip.performWrite(address + CPU.IO_OFFSET, data, cycles);
        }
        // CIA1 registers ($DC00-$DC0F)
        else if (address >= 0xDC00 && address <= 0xDC0F) {
            int reg = address - 0xDC00;
            switch (reg) {
                case 0x04: ciaTimerALo = data & 0xFF; break;  // Timer A low
                case 0x05: ciaTimerAHi = data & 0xFF; break;  // Timer A high
                case 0x0D: // ICR - interrupt control
                    if ((data & 0x80) != 0) {
                        ciaICRMask |= (data & 0x1F);  // Set bits
                    } else {
                        ciaICRMask &= ~(data & 0x1F); // Clear bits
                    }
                    break;
                case 0x0E: // CRA - control register A
                    ciaCRA = data & 0xFF;
                    ciaTimerRunning = (data & 0x01) != 0;
                    break;
            }
        }
        // Silently ignore writes to VIC, CIA2, etc.
    }

    @Override
    public void clock(long cycles) {
        // No-op: no VIC raster, no CIA timers
    }

    @Override
    public void reset() {
        residChip.reset();
    }

    @Override
    public void stop() {
        residChip.stop();
    }

    public void setChipModel(int model) {
        residChip.setChipVersion(model);
    }
}
