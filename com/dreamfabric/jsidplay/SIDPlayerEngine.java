package com.dreamfabric.jsidplay;

import com.dreamfabric.jac64.*;
import resid.SID;
import resid.Voice;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SID playback engine. Uses the full C64Screen chip emulation (VIC-II, CIA, SID)
 * so both PSID and RSID tunes work correctly.
 */
public class SIDPlayerEngine {

    public static final int PAL_CLOCK = 985248;
    public static final int PAL_CYCLES_PER_FRAME = 19656; // 312 lines * 63 cycles
    public static final int NTSC_CYCLES_PER_FRAME = 17095;
    public static final int SAMPLE_RATE = 44000;
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE / 50;

    // Trampoline and idle loop addresses - all in RAM so they work
    // regardless of ROM banking configuration
    private static final int TRAMPOLINE_ADDR = 0x0300;
    private static final int IDLE_LOOP_ADDR = 0x0340; // CLI; NOP; JMP $0340

    // Components
    private CPU cpu;
    private C64Screen screen;
    private AudioDriverSE audioDriver;
    private PSIDFile psid;

    // Playback state
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile int currentSong = 1;
    private volatile boolean songChangeRequested;
    private volatile boolean c64Booted;
    private Thread playerThread;
    private long frameCount;

    // Visualization data exchange
    private final AtomicReference<SIDStateSnapshot> latestSnapshot = new AtomicReference<>();

    // Listener for state changes
    private Runnable onStateChange;

    public SIDPlayerEngine() {
    }

    public void setOnStateChange(Runnable listener) {
        this.onStateChange = listener;
    }

    /**
     * Initialize the engine using the full C64Screen chip emulation.
     * This gives us real CIA timers, VIC-II, and proper IRQ handling,
     * so both PSID and RSID tunes work correctly.
     */
    public void init() {
        audioDriver = new AudioDriverSE();

        DefaultIMon monitor = new DefaultIMon();
        SELoader loader = new SELoader();
        cpu = new CPU(monitor, null, loader);
        screen = new C64Screen(monitor, false);
        cpu.init(screen);
        screen.init(cpu, audioDriver);

        // Boot C64 immediately so playback starts without delay
        bootC64();
        c64Booted = true;
    }

    /**
     * Run the Kernal RESET routine to fully initialize the C64 hardware.
     * This sets up CIA timers, VIC-II, Kernal vectors ($0314-$0333),
     * memory map, and everything RSID tunes depend on.
     */
    private void bootC64() {
        System.out.println("Booting C64 Kernal...");

        // Trigger a proper CPU reset -- reads RESET vector ($FCE2) from ROM
        cpu.reset();
        cpu.running = true;

        // Run until the BASIC warm-start loop or a timeout
        // The BASIC idle loop sits around $A7AE (after READY prompt)
        long maxCycles = cpu.getCycles() + 3000000; // ~3 seconds of C64 time
        while (cpu.getCycles() < maxCycles) {
            cpu.emulateOp();
            // Detect when we've reached the BASIC main loop:
            // $A7AE is the BASIC input loop (after printing READY.)
            int pc = cpu.getPC();
            if (pc == 0xA7AE || pc == 0xA7B1 || pc == 0xA480) {
                break;
            }
        }

        System.out.println("C64 booted, cycles=" + cpu.getCycles()
                + " pc=$" + Integer.toHexString(cpu.getPC()));
    }

    private SID getSID() {
        RESIDChip rc = screen.getSidChip();
        return rc != null ? rc.getSID() : null;
    }

    /**
     * Load a PSID file and prepare for playback.
     */
    public void loadSID(PSIDFile psid) {
        stop();
        this.psid = psid;
        this.currentSong = psid.startSong;
        notifyStateChange();
    }

    /**
     * Load a .sid file from disk.
     */
    public void loadFile(File file) throws Exception {
        PSIDFile p = PSIDFile.load(file);
        System.out.println("Loaded: " + p);
        loadSID(p);
    }

    /**
     * Start or resume playback.
     */
    public synchronized void play() {
        if (psid == null) return;

        if (paused) {
            paused = false;
            notify();
            notifyStateChange();
            return;
        }

        if (playing) return;

        playing = true;
        paused = false;
        frameCount = 0;

        playerThread = new Thread(this::playerLoop, "SIDPlayer");
        playerThread.setDaemon(true);
        playerThread.start();
        notifyStateChange();
    }

    /**
     * Pause playback.
     */
    public synchronized void pause() {
        if (playing && !paused) {
            paused = true;
            notifyStateChange();
        }
    }

    /**
     * Stop playback.
     */
    public synchronized void stop() {
        playing = false;
        paused = false;
        notify();
        if (playerThread != null) {
            try {
                playerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playerThread = null;
        }
        latestSnapshot.set(null);
        notifyStateChange();
    }

    /**
     * Select a song (1-based). If playing, signals the player loop
     * to re-init without rebooting the C64.
     */
    public synchronized void selectSong(int songNumber) {
        if (psid == null) return;
        if (songNumber < 1) songNumber = psid.songs;
        if (songNumber > psid.songs) songNumber = 1;
        currentSong = songNumber;
        if (playing) {
            songChangeRequested = true;
            // Wake up if paused
            paused = false;
            notify();
        }
        notifyStateChange();
    }

    public void nextSong() { selectSong(currentSong + 1); }
    public void prevSong() { selectSong(currentSong - 1); }

    public void setChipModel(int model) {
        if (screen != null) {
            screen.setSID(model);
        }
    }

    // --- Getters ---

    public PSIDFile getPSID() { return psid; }
    public int getCurrentSong() { return currentSong; }
    public boolean isPlaying() { return playing; }
    public boolean isPaused() { return paused; }
    public SIDStateSnapshot getLatestSnapshot() { return latestSnapshot.get(); }

    // --- Internal ---

    private void notifyStateChange() {
        if (onStateChange != null) {
            onStateChange.run();
        }
    }

    /**
     * Load SID tune data into C64 memory and set up playback trampoline.
     * Called after bootC64() so the Kernal vectors and hardware are already initialized.
     */
    private void setupMemory() {
        int[] mem = cpu.getMemory();

        // Load SID binary data into RAM
        int loadAddr = psid.getActualLoadAddress();
        int dataStart = psid.getDataStartOffset();
        int dataLen = psid.data.length - dataStart;

        System.out.println("Loading SID data at $" + Integer.toHexString(loadAddr)
                + ", " + dataLen + " bytes");

        for (int i = 0; i < dataLen && (loadAddr + i) < 0x10000; i++) {
            mem[loadAddr + i] = psid.data[dataStart + i] & 0xFF;
        }

        // Install idle loop in RAM at $0340: CLI; NOP; JMP $0340
        // In RAM so it works regardless of ROM banking
        mem[IDLE_LOOP_ADDR] = 0x58;     // CLI - enable interrupts
        mem[IDLE_LOOP_ADDR + 1] = 0xEA; // NOP
        mem[IDLE_LOOP_ADDR + 2] = 0x4C; // JMP $0340
        mem[IDLE_LOOP_ADDR + 3] = IDLE_LOOP_ADDR & 0xFF;
        mem[IDLE_LOOP_ADDR + 4] = (IDLE_LOOP_ADDR >> 8) & 0xFF;

        // Set up play trampoline at $0300 for tunes with explicit play address:
        //   $0300: JSR playAddress
        //   $0303: JMP $FFF0 (idle loop)
        int playAddr = psid.playAddress;
        if (playAddr == 0) {
            // IRQ-driven tune: trampoline just idles; CIA IRQs drive playback.
            playAddr = IDLE_LOOP_ADDR;
        }
        mem[TRAMPOLINE_ADDR] = 0x20; // JSR
        mem[TRAMPOLINE_ADDR + 1] = playAddr & 0xFF;
        mem[TRAMPOLINE_ADDR + 2] = (playAddr >> 8) & 0xFF;
        mem[TRAMPOLINE_ADDR + 3] = 0x4C; // JMP
        mem[TRAMPOLINE_ADDR + 4] = IDLE_LOOP_ADDR & 0xFF;
        mem[TRAMPOLINE_ADDR + 5] = (IDLE_LOOP_ADDR >> 8) & 0xFF;
    }

    /**
     * Call the tune's init routine.
     */
    private void initSong(int songNumber) {
        // Reset SID - clear all registers
        SID sid = getSID();
        if (sid != null) {
            for (int i = 0; i < 0x19; i++) {
                sid.write(i, 0);
            }
        }

        // Reset CIAs so old timer IRQs/NMIs don't interfere
        CIA[] cias = screen.getCIAs();
        if (cias != null) {
            for (CIA cia : cias) {
                if (cia != null) cia.reset();
            }
        }

        // Clear pending interrupts
        screen.resetInterrupts();

        // Restore Kernal IRQ/NMI vectors (init may overwrite them)
        int[] mem = cpu.getMemory();
        mem[0x0314] = 0x31; mem[0x0315] = 0xEA; // IRQ -> $EA31
        mem[0x0316] = 0x66; mem[0x0317] = 0xFE; // BRK -> $FE66
        mem[0x0318] = 0x47; mem[0x0319] = 0xFE; // NMI -> $FE47

        // Set up CPU state for init call
        cpu.setAcc(songNumber - 1); // Song number in accumulator (0-based)
        cpu.setX(0);
        cpu.setY(0);
        cpu.setSP(0xFF);

        // Push return address for the init routine's RTS
        // RTS pops address and adds 1, so push (IDLE_LOOP_ADDR - 1)
        int retAddr = IDLE_LOOP_ADDR - 1;
        mem[0x1FF] = (retAddr >> 8) & 0xFF;
        mem[0x1FE] = retAddr & 0xFF;
        cpu.setSP(0xFD);

        // Set PC to init address and run
        cpu.setPC(psid.initAddress);
        cpu.running = true;

        if (psid.playAddress != 0) {
            // PSID: init should return quickly. Wait for RTS to idle loop.
            long maxCycles = cpu.getCycles() + 2000000;
            while (cpu.getCycles() < maxCycles && cpu.getPC() != IDLE_LOOP_ADDR) {
                cpu.emulateOp();
            }
            if (cpu.getPC() != IDLE_LOOP_ADDR) {
                System.out.println("Warning: init stuck at pc=$"
                        + Integer.toHexString(cpu.getPC()));
                cpu.setPC(IDLE_LOOP_ADDR);
            }
        }
        // For RSID (playAddress==0): don't wait at all.
        // The init IS the player -- it will keep running inside the play loop.
        // This avoids the multi-second delay before visualization starts.

        System.out.println("Init complete, cycles=" + cpu.getCycles());
    }

    /**
     * Main playback loop - runs on the player thread.
     */
    private void playerLoop() {
        try {
            setupMemory();
            initSong(currentSong);

            int cyclesPerFrame = PAL_CYCLES_PER_FRAME;
            if (psid.isCIATiming(currentSong)) {
                cyclesPerFrame = NTSC_CYCLES_PER_FRAME;
            }

            SID sid = getSID();
            int clocksPerSample = PAL_CLOCK / SAMPLE_RATE;
            boolean irqDriven = (psid.playAddress == 0);

            while (playing) {
                // Handle song change without rebooting
                if (songChangeRequested) {
                    songChangeRequested = false;
                    setupMemory();
                    initSong(currentSong);
                    irqDriven = (psid.playAddress == 0);
                    sid = getSID();
                    cyclesPerFrame = PAL_CYCLES_PER_FRAME;
                    if (psid.isCIATiming(currentSong)) {
                        cyclesPerFrame = NTSC_CYCLES_PER_FRAME;
                    }
                    frameCount = 0;
                }

                // Handle pause
                if (paused) {
                    synchronized (this) {
                        while (paused && playing && !songChangeRequested) {
                            try {
                                wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    if (!playing) break;
                    continue; // re-check songChangeRequested
                }

                // Create snapshot for this frame
                SIDStateSnapshot snap = new SIDStateSnapshot(SAMPLES_PER_FRAME + 100);
                snap.frameNumber = frameCount++;

                if (irqDriven) {
                    // IRQ-driven tune: the CIA timer fires IRQs automatically.
                    // CPU idles at $FFF0 (CLI; NOP; JMP $FFF0) and the Kernal
                    // IRQ chain dispatches through $0314/$0315 to the play routine.
                } else {
                    // Direct play: JSR to play routine via trampoline
                    cpu.setPC(TRAMPOLINE_ADDR);
                    cpu.setSP(0xFF);
                }
                cpu.running = true;

                // Run CPU for one frame's worth of cycles
                long frameStart = cpu.getCycles();
                long frameEnd = frameStart + cyclesPerFrame;
                long nextVizSample = frameStart + clocksPerSample;
                int sampleIdx = 0;

                int nmiCount = 0;
                boolean lastNMI = false;

                while (cpu.getCycles() < frameEnd && playing) {
                    cpu.emulateOp();

                    // Count NMI edges
                    boolean nmiNow = cpu.NMILow;
                    if (nmiNow && !lastNMI) nmiCount++;
                    lastNMI = nmiNow;

                    // Capture visualization samples at audio rate
                    if (sid != null && cpu.getCycles() >= nextVizSample
                            && sampleIdx < snap.voiceSamples[0].length) {
                        Voice v0 = sid.getVoice(0);
                        Voice v1 = sid.getVoice(1);
                        Voice v2 = sid.getVoice(2);
                        int s0 = v0.output();
                        int s1 = v1.output();
                        int s2 = v2.output();
                        snap.voiceSamples[0][sampleIdx] = s0;
                        snap.voiceSamples[1][sampleIdx] = s1;
                        snap.voiceSamples[2][sampleIdx] = s2;
                        // Final output: filtered, volume-scaled, everything
                        snap.mixedSamples[sampleIdx] = sid.output();
                        // Capture volume register for digi detection
                        snap.digiSamples[sampleIdx] = sid.filter.getVol();
                        sampleIdx++;
                        nextVizSample += clocksPerSample;
                    }
                }

                snap.numSamples = sampleIdx;

                snap.nmiCount = nmiCount;

                // Detect digi playback: count volume changes in this frame
                if (snap.numSamples > 10) {
                    int volChanges = 0;
                    for (int i = 1; i < snap.numSamples; i++) {
                        if (snap.digiSamples[i] != snap.digiSamples[i - 1]) {
                            volChanges++;
                        }
                    }
                    // If volume changes >20 times per frame, it's digi playback
                    snap.digiDetected = (volChanges > 20);
                }

                // Capture register state
                if (sid != null) {
                    SID.State state = sid.read_state();
                    snap.populateFromState(state, PAL_CLOCK);
                }

                // Publish snapshot for UI
                latestSnapshot.set(snap);
            }
        } catch (Exception e) {
            System.err.println("SID player error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            playing = false;
            notifyStateChange();
        }
    }
}
