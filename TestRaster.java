/**
 * Standalone test harness for VIC-II raster effect rendering.
 * Boots the emulator, loads a d64/prg, captures screenshots and
 * FLD traces — no MCP needed.
 *
 * Usage: java -cp build/libs/JaC64.jar TestRaster [url-or-path]
 *        Defaults to the "Let's Scroll It" demo from c64.com
 */
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.net.ssl.*;
import javax.swing.JFrame;

import com.dreamfabric.jac64.*;
import com.dreamfabric.c64utils.*;

public class TestRaster {

    private static final int TRACE_DELAY_MS =
        Integer.getInteger("jac64.traceDelayMs", 0);
    private static final int CAPTURE_FRAMES =
        Integer.getInteger("jac64.captureFrames", 30);
    private static final int DUMP_CODE_FROM =
        Integer.getInteger("jac64.dumpCodeFrom", -1);
    private static final int DUMP_CODE_TO =
        Integer.getInteger("jac64.dumpCodeTo", -1);
    private static final boolean DIRECT_SYS_LAUNCH =
        Boolean.getBoolean("jac64.directSysLaunch");
    private static final boolean WAIT_FOR_EXEC_TRACE =
        Boolean.getBoolean("jac64.waitForExecTrace");
    private static final int EXEC_TRACE_TIMEOUT_SECONDS =
        Integer.getInteger("jac64.execTraceTimeoutSeconds", 120);

    private CPU cpu;
    private C64Screen scr;
    private C64Reader reader;
    private JFrame window;

    private static final class SilentAudioDriver extends AudioDriver {
        private boolean fullSpeed = true;

        public void init(int sampleRate, int bufferSize) {}
        public void write(byte[] buffer) {}
        public long getMicros() { return System.nanoTime() / 1000L; }
        public boolean hasSound() { return false; }
        public int available() { return Integer.MAX_VALUE; }
        public int getMasterVolume() { return 0; }
        public void setMasterVolume(int v) {}
        public void shutdown() {}
        public void setSoundOn(boolean on) {}
        public void setFullSpeed(boolean full) { fullSpeed = full; }
        public boolean fullSpeed() { return fullSpeed; }
    }

    private void initEmulator() {
        SIDMixer.DL_BUFFER_SIZE = 16384;
        Debugger monitor = new Debugger();
        cpu = new CPU(monitor, "", new SELoader());
        scr = new C64Screen(monitor, true);
        cpu.init(scr);

        boolean headless = Boolean.getBoolean("jac64.headless")
            || GraphicsEnvironment.isHeadless();
        C64Canvas canvas = null;
        if (headless) {
            scr.init(cpu, new SilentAudioDriver());
        } else {
            canvas = C64Canvas.setupDesktop(scr, cpu, true);
        }
        scr.setSoundOn(false);

        reader = new C64Reader();
        reader.setCPU(cpu);
        cpu.getDrive().setReader(reader);

        // Fast-load trap (-Djac64.fastLoad=true): patch the KERNAL LOAD at
        // $F49E with the LOAD_FILE pseudo-opcode so a LOAD reads the file
        // directly out of the attached .d64 into RAM (instant) instead of
        // going through the cycle-accurate 1541 + IEC serial transfer
        // (~10 min/test under warp). Bypasses the true drive entirely, so it
        // is opt-in: keep it OFF for 1541-hardware validation, ON for fast
        // test-suite iteration. The patch is re-applied on every cpu.reset()
        // because reset() calls patchROM(list) when a listener is installed.
        if (Boolean.getBoolean("jac64.fastLoad")) {
            final C64Reader fr = reader;
            cpu.patchROM(new PatchListener() {
                public boolean readFile(String name, int addr) {
                    String n = name;
                    int nl = n.indexOf('\n');
                    if (nl >= 0) n = n.substring(0, nl);
                    boolean ok = fr.readFile(n, addr) != null;
                    System.out.println("[fastLoad] LOAD \"" + n + "\" @"
                        + (addr < 0 ? "file" : Integer.toHexString(addr))
                        + " -> " + (ok ? "OK" : "NOT FOUND"));
                    return ok;
                }
            });
            // patchROM installs JSR $F5D2 (SEARCHING/LOADING msg) + RTS at
            // $F49E and sets the listener; the PC-trap at $F4A1 does the
            // actual load (the legacy LOAD_FILE opcode there is dead).
            cpu.fastLoadTrapPc = 0xf4a1;
            System.out.println("[fastLoad] KERNAL LOAD trap installed "
                + "(instant .d64 load; true drive bypassed)");
        }

        scr.setKeyboardEmulation(false);

        javax.swing.JCheckBoxMenuItem warpItem = null;
        if (!headless) {
            window = new JFrame("JaC64 Raster Test");
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setBackground(Color.black);
            window.setLayout(new BorderLayout());
            window.getContentPane().add(canvas, BorderLayout.CENTER);

            // Menu bar with Warp toggle.
            javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
            javax.swing.JMenu speedMenu = new javax.swing.JMenu("Speed");
            final javax.swing.JCheckBoxMenuItem warpMenuItem =
                new javax.swing.JCheckBoxMenuItem("Warp (F12)", false);
            warpItem = warpMenuItem;
            C64Canvas canvasRef = canvas;
            warpMenuItem.addActionListener(e -> {
                scr.setFullSpeed(warpMenuItem.isSelected());
                canvasRef.requestFocusInWindow();
            });
            speedMenu.add(warpMenuItem);
            menuBar.add(speedMenu);
            window.setJMenuBar(menuBar);

            window.pack();
            window.setSize(386 * 2 + 10, 284 * 2 + 70);
            window.setVisible(true);

            canvas.setFocusable(true);
            canvas.requestFocusInWindow();
        }

        if (Boolean.getBoolean("jac64.warp")) {
            scr.setFullSpeed(true);
            if (warpItem != null) {
                warpItem.setSelected(true);
            }
        }

        // Set the deterministic pause-at-cycle BEFORE the CPU thread
        // starts so the pause point is at the first instruction
        // boundary past the target, not "wherever the CPU happened to
        // be when we got around to setting it". Eliminates the JVM
        // startup race that produced cross-run variance.
        // Default 7_000_000 cycles — well past kernal init AND lands
        // on a raster-line alignment where irq-ack-vicii.prg test's
        // adjust_timing converges to the same cycle position the
        // 6569 reference / VICE expects. Empirically: target 6M/6.5M
        // gave wrong pattern (alignment off by a few cycles), 7M/7.5M
        // produce the correct ***-** pattern. Override via
        // -Djac64.injectAtCycle=N if needed.
        if (!"false".equalsIgnoreCase(System.getProperty("jac64.detSysJump", "true"))) {
            long target = Long.getLong("jac64.injectAtCycle", 7_000_000L);
            cpu.pauseAtCycle = target;
        }

        Thread cpuThread = new Thread(() -> cpu.start(), "C64-CPU");
        cpuThread.setDaemon(true);
        cpuThread.start();
    }

    private void waitReady() {
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        int timeout = 50;
        while (!scr.ready() && timeout-- > 0) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private void waitFrames(int frames) {
        // ~20ms per frame at 50Hz
        try { Thread.sleep(frames * 20); } catch (InterruptedException e) {}
    }

    private void screenshot(String filename) throws Exception {
        int[] pixels = scr.getPixelBuffer();
        int w = 384;  // SC_WIDTH
        // Derive h from the pixel buffer so geometry changes in C64Screen.SC_HEIGHT
        // don't require updating TestRaster in lockstep.
        int h = pixels.length / w;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        File f = new File(filename);
        ImageIO.write(img, "png", f);
        System.out.println("Screenshot saved: " + f.getAbsolutePath());
    }

    private void dumpCodeIfRequested() throws Exception {
        if (DUMP_CODE_FROM < 0 || DUMP_CODE_TO < DUMP_CODE_FROM) {
            return;
        }

        int[] memory = cpu.getMemory();
        File outFile = new File("/tmp/jac64_code_dump.txt");
        try (PrintWriter out = new PrintWriter(new FileWriter(outFile))) {
            int pc = DUMP_CODE_FROM & 0xffff;
            int end = DUMP_CODE_TO & 0xffff;
            while (pc <= end) {
                int opcode = memory[pc] & 0xff;
                int encoded = MOS6510Ops.INSTRUCTION_SET[opcode];
                int adrMode = (encoded & MOS6510Ops.ADDRESSING_MASK)
                    >> MOS6510Ops.ADDRESSING_SHIFT;
                int len = MOS6510Ops.ADR_LEN[adrMode];
                if (pc + len - 1 > end) {
                    len = end - pc + 1;
                }

                StringBuilder bytes = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    if (i > 0) bytes.append(' ');
                    bytes.append(Hex.hex2(memory[(pc + i) & 0xffff] & 0xff));
                }

                out.printf("%04X: %-9s %s%n",
                    pc,
                    bytes.toString(),
                    MOS6510Ops.toString(opcode));
                pc += Math.max(len, 1);
            }
        }
        System.out.println("Code dump saved: " + outFile.getAbsolutePath());
    }

    private boolean waitForExecTraceIfRequested() throws Exception {
        if (!WAIT_FOR_EXEC_TRACE) {
            return false;
        }

        String tracePath = System.getProperty("jac64.execTraceFile");
        if (tracePath == null || tracePath.isEmpty()) {
            return false;
        }

        File traceFile = new File(tracePath);
        System.out.println("Waiting for exec trace: " + traceFile.getAbsolutePath());
        for (int i = 0; i < EXEC_TRACE_TIMEOUT_SECONDS; i++) {
            Thread.sleep(1000);
            if (traceFile.isFile() && traceFile.length() > 0) {
                System.out.println("Exec trace captured at " + i + "s");
                return true;
            }
            if (i > 0 && (i % 10) == 0) {
                System.out.println("  Still waiting for exec trace... (" + i + "s)");
            }
        }

        System.out.println("Exec trace not captured within timeout");
        System.out.println("Current PC=$" + Integer.toHexString(cpu.getPC() & 0xffff));
        System.out.println(readScreen());
        return false;
    }

    private int detectBasicSysAddress() {
        int[] memory = cpu.getMemory();
        int basicStart = 0x0801;
        int nextLine = (memory[basicStart] & 0xff) | ((memory[basicStart + 1] & 0xff) << 8);
        if (nextLine <= basicStart + 4) {
            System.out.println("No BASIC stub at $0801, nextLine=$" + Integer.toHexString(nextLine));
            return -1;
        }

        for (int pos = basicStart + 4; pos < nextLine; pos++) {
            if ((memory[pos] & 0xff) != 0x9e) {
                continue;
            }

            StringBuilder digits = new StringBuilder();
            for (int i = pos + 1; i < nextLine; i++) {
                int ch = memory[i] & 0xff;
                if (ch == 0) {
                    break;
                }
                if (ch >= '0' && ch <= '9') {
                    digits.append((char) ch);
                    continue;
                }
                if (ch == ' ') {
                    continue;
                }
                break;
            }
            if (digits.length() == 0) {
                System.out.println("SYS token found but no digits at $"
                    + Integer.toHexString(pos));
                return -1;
            }
            try {
                return Integer.parseInt(digits.toString());
            } catch (NumberFormatException e) {
                System.out.println("Failed to parse SYS digits: " + digits);
                return -1;
            }
        }
        System.out.println(String.format(
            "No SYS token at $0801 bytes: %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
            memory[basicStart] & 0xff, memory[basicStart + 1] & 0xff,
            memory[basicStart + 2] & 0xff, memory[basicStart + 3] & 0xff,
            memory[basicStart + 4] & 0xff, memory[basicStart + 5] & 0xff,
            memory[basicStart + 6] & 0xff, memory[basicStart + 7] & 0xff,
            memory[basicStart + 8] & 0xff, memory[basicStart + 9] & 0xff,
            memory[basicStart + 10] & 0xff, memory[basicStart + 11] & 0xff));
        return -1;
    }

    private void waitForBasicIdle(int timeoutSeconds) throws Exception {
        for (int i = 0; i < timeoutSeconds * 10; i++) {
            int pc = cpu.getPC() & 0xffff;
            if ((pc >= 0xfda3 && pc <= 0xfdc0) || findScreenText("READY.") >= 0) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private int findScreenText(String text) {
        int[] memory = cpu.getMemory();
        int screenBase = 0x0400;
        int len = text.length();
        for (int row = 0; row < 25; row++) {
            for (int col = 0; col <= 40 - len; col++) {
                boolean match = true;
                for (int i = 0; i < len; i++) {
                    int ch = memory[screenBase + row * 40 + col + i] & 0xff;
                    if (petsciiToAscii(ch) != text.charAt(i)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return row * 40 + col;
                }
            }
        }
        return -1;
    }

    private void waitForScreenText(String text, int timeoutSeconds) throws Exception {
        for (int i = 0; i < timeoutSeconds * 10; i++) {
            if (findScreenText(text) >= 0) {
                return;
            }
            Thread.sleep(100);
        }
        System.out.println(text + " not reached within timeout");
        System.out.println("Current PC=$" + Integer.toHexString(cpu.getPC() & 0xffff));
        System.out.println(readScreen());
    }

    private String downloadToTemp(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection) {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String t) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String t) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
            }}, null);
            ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            ((HttpsURLConnection) conn).setHostnameVerifier((h, s) -> true);
        }
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "JaC64/1.0");
        if (conn.getResponseCode() != 200)
            throw new IOException("HTTP " + conn.getResponseCode());

        String filename = null;
        String disposition = conn.getHeaderField("Content-Disposition");
        if (disposition != null && disposition.toLowerCase().contains("filename=")) {
            String[] parts = disposition.split("(?i)filename=");
            if (parts.length > 1)
                filename = parts[1].split(";")[0].trim().replaceAll("^\"|\"$", "").trim();
        }
        if (filename == null || filename.isEmpty()) {
            String path = url.getPath();
            int slash = path.lastIndexOf('/');
            filename = slash >= 0 ? path.substring(slash + 1) : path;
        }
        filename = URLDecoder.decode(filename, "UTF-8");
        if (filename.isEmpty()) filename = "download.prg";

        File tmp = File.createTempFile("jac64_test_", "_" + filename);
        tmp.deleteOnExit();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
        conn.disconnect();

        // Extract from zip if needed
        if (tmp.getName().toLowerCase().endsWith(".zip") ||
            filename.toLowerCase().endsWith(".zip")) {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(tmp));
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName().toLowerCase();
                if (n.endsWith(".d64") || n.endsWith(".t64") || n.endsWith(".prg") || n.endsWith(".p00")) {
                    File extracted = File.createTempFile("jac64_test_", "_" + entry.getName());
                    extracted.deleteOnExit();
                    try (FileOutputStream fos = new FileOutputStream(extracted)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    zis.close();
                    System.out.println("Extracted: " + entry.getName());
                    return extracted.getAbsolutePath();
                }
            }
            zis.close();
        }
        return tmp.getAbsolutePath();
    }

    private void loadD64(String path) {
        reader.readDiskFromFile(path);
        cpu.reset();
        waitReady();
        reader.readDiskFromFile(path);
        String name = System.getProperty("jac64.loadName", "*");
        cpu.enterText("LOAD\"" + name + "\",8,1~");
    }

    public void run(String source) throws Exception {
        System.out.println("=== JaC64 Raster Test ===");
        initEmulator();
        waitReady();
        waitForScreenText("READY.", 30);

        // Download/resolve source
        String path;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            System.out.println("Downloading: " + source);
            path = downloadToTemp(source);
        } else {
            path = source;
        }
        System.out.println("Loading: " + path);

        // Determine file type and load
        String lower = path.toLowerCase();
        if (lower.endsWith(".d64")) {
            loadD64(path);
            System.out.println("Waiting for disk load...");
            for (int i = 0; i < 120; i++) {
                Thread.sleep(1000);
                if (scr.ready()) {
                    String screen = readScreen();
                    if (findScreenText("READY.") >= 0 && !screen.contains("LOADING")) {
                        System.out.println("Load complete at " + i + "s");
                        break;
                    }
                }
            }
            screenshot("/tmp/jac64_test_before_run.png");
            System.out.println("Typing RUN...");
            cpu.enterText("RUN~");

            // Wait for demo to start (detect non-blue border)
            System.out.println("Waiting for demo to start...");
            for (int i = 0; i < 180; i++) {
                Thread.sleep(1000);
                int borderCol = cpu.getMemory()[0xd020 + 0x10000] & 0x0f;
                int bgCol = cpu.getMemory()[0xd021 + 0x10000] & 0x0f;
                if (i > 5 && (borderCol != 6 || bgCol != 6)) {
                    System.out.println("Demo started at " + i + "s");
                    Thread.sleep(3000);
                    break;
                }
                if (i % 10 == 0)
                    System.out.println("  Still waiting... (" + i + "s)");
            }
        } else {
            // PRG file - load directly and run
            reader.readPGM(path, -1);

            // Deterministic injection (jac64.detSysJump=true, default ON
            // for cycle-sensitive test ROMs): pause CPU at a fixed
            // emulated cycle count, set CPU state for SYS jump
            // synchronously, then resume. Eliminates the Thread.sleep
            // polling races that caused cross-run output variance on
            // tests like irq-ack-vicii.prg.
            boolean detSysJump =
                !"false".equalsIgnoreCase(System.getProperty("jac64.detSysJump", "true"));
            if (detSysJump) {
                int sysAddress = detectBasicSysAddress();
                if (sysAddress >= 0) {
                    long target = Long.getLong("jac64.injectAtCycle", 7_000_000L);
                    System.out.println("Det. SYS jump: pause-at-cycle " + target);
                    // pauseAtCycle already set in initEmulator BEFORE cpu.start,
                    // so by the time we get here CPU should already be paused.
                    // Re-set just in case it was cleared by something else.
                    if (!cpu.pause) {
                        cpu.pauseAtCycle = target;
                    }
                    // Wait for the deterministic pause to land (CPU
                    // exits its loop at the first instruction boundary
                    // past target — same cycle every run).
                    for (int i = 0; i < 600; i++) {
                        if (cpu.pause && cpu.cycles >= target) break;
                        Thread.sleep(50);
                    }
                    long landed = cpu.cycles;
                    System.out.println("Paused at cycle " + landed
                        + " (target " + target + "), jumping to SYS $"
                        + Integer.toHexString(sysAddress));
                    // Phase H2: match VICE -autostartprgmode 1 by zeroing
                    // color RAM ($D800-$DBFF) before SYS jump. JaC64
                    // BASIC ROM init populates color RAM with $05/$0E;
                    // VICE skips BASIC init so its color RAM is 0 at
                    // test start. Without this, tests inheriting color
                    // RAM state (ss-pri, rmwtest, others) diverge.
                    // Set via -Djac64.zeroColorRam=false to skip.
                    if (!"false".equalsIgnoreCase(
                            System.getProperty("jac64.zeroColorRam", "true"))) {
                        int[] mem = cpu.getMemory();
                        // Color RAM lives at IO_OFFSET+$D800. IO_OFFSET
                        // = 0x10000 - 0xd000 = 0x3000 → color RAM base
                        // at 0x10800.
                        for (int i = 0; i < 0x400; i++) {
                            mem[0x10800 + i] = 0;
                        }
                        System.out.println("Zeroed color RAM (Phase H2)");
                    }
                    // Phase K iter#3: zero screen RAM ($0400-$07FF). OPT-IN.
                    // BASIC fills screen RAM with $20 (space char); colorsplit
                    // and similar tests inherit this state and render solid
                    // stripes instead of the VICE-reference dotted pattern.
                    // Empirically: zeroing -> colorsplit cell-diff 2676 -> 1428
                    // (47% drop). May regress tests that depend on screen RAM
                    // contents — left opt-in via -Djac64.zeroScreenRam=true.
                    if ("true".equalsIgnoreCase(
                            System.getProperty("jac64.zeroScreenRam", "false"))) {
                        int[] mem = cpu.getMemory();
                        for (int i = 0; i < 0x400; i++) {
                            mem[0x0400 + i] = 0;
                        }
                        System.out.println("Zeroed screen RAM (Phase K iter#3 opt-in)");
                    }
                    // Phase K iter#5: load a VICE-captured screen+color RAM
                    // dump into JaC64's RAM at SYS-entry. Replicates VICE's
                    // post-BASIC state (banner + "READY." + light-blue
                    // color RAM) so tests that depend on it (colorsplit)
                    // render with the same memory pattern as VICE.
                    // Format: 0x400 screen RAM + 0x400 color RAM (4 bits).
                    // Generate via VICE patch: JAC64_SCREEN_DUMP_FILE=... x64sc.
                    String dumpPath = System.getProperty("jac64.screenDumpFile");
                    if (dumpPath != null) {
                        try {
                            byte[] buf = java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(dumpPath));
                            if (buf.length >= 0x800) {
                                int[] mem = cpu.getMemory();
                                for (int i = 0; i < 0x400; i++) {
                                    mem[0x0400 + i] = buf[i] & 0xff;
                                }
                                for (int i = 0; i < 0x400; i++) {
                                    mem[0x10800 + i] = buf[0x400 + i] & 0xff;
                                }
                                System.out.println("Loaded screen+color RAM dump from " + dumpPath);
                            } else {
                                System.err.println("WARN: dump file too short: " + buf.length);
                            }
                        } catch (Exception e) {
                            System.err.println("WARN: failed to load dump: " + e);
                        }
                    }
                    {
                        int[] mem = cpu.getMemory();
                        String dump = System.getProperty("jac64.dumpMem");
                        if (dump != null) {
                            String[] parts = dump.split(",");
                            int addr = Integer.decode(parts[0]);
                            int len = parts.length > 1 ? Integer.parseInt(parts[1]) : 16;
                            StringBuilder sb = new StringBuilder();
                            sb.append("DUMP @ SYS-jump $").append(Integer.toHexString(addr)).append(":");
                            for (int i = 0; i < len; i++) {
                                sb.append(" ").append(String.format("%02x", mem[(addr + i) & 0xFFFF]));
                            }
                            System.out.println(sb.toString());
                        }
                    }
                    cpu.jumpToSubroutine(sysAddress);
                    cpu.setPause(false);
                } else {
                    // No SYS detected — fall back to typing RUN with
                    // cycle-precise pause (stays deterministic).
                    long target = Long.getLong("jac64.injectAtCycle", 2_000_000L);
                    cpu.pauseAtCycle = target;
                    for (int i = 0; i < 600; i++) {
                        if (cpu.pause && cpu.cycles >= target) break;
                        Thread.sleep(50);
                    }
                    cpu.enterText("RUN~");
                    cpu.setPause(false);
                }
            } else if (Boolean.getBoolean("jac64.sysJump")) {
                int sysAddress = detectBasicSysAddress();
                if (sysAddress >= 0) {
                    waitForBasicIdle(10);
                    cpu.jumpToSubroutine(sysAddress);
                    System.out.println("PRG loaded and jumped to SYS " + sysAddress);
                } else {
                    waitForBasicIdle(10);
                    cpu.enterText("RUN~");
                    System.out.println("PRG loaded and RUN");
                }
            } else {
                waitForBasicIdle(10);
                cpu.enterText("RUN~");
                System.out.println("PRG loaded and RUN");
            }
            // Set captureAtCycle pause BEFORE the long sleep, so warp-mode
            // CPU doesn't overshoot past the target during the wait window.
            {
                long _capEarly = Long.getLong("jac64.captureAtCycle", -1L);
                if (_capEarly > 0) cpu.pauseAtCycle = _capEarly;
            }
            Thread.sleep(3000);
        }

        if (TRACE_DELAY_MS > 0) {
            System.out.println("Waiting " + TRACE_DELAY_MS + "ms before FLD trace...");
            Thread.sleep(TRACE_DELAY_MS);
        }

        dumpCodeIfRequested();

        // Start FLD trace
        System.out.println("Starting FLD trace...");
        scr.startFldTrace();

        if (waitForExecTraceIfRequested()) {
            System.out.println("=== Test complete ===");
            System.out.println("Exec trace in " + System.getProperty("jac64.execTraceFile"));
            System.exit(0);
        }

        // Deterministic cycle-anchored capture: pause at a fixed emulated
        // cycle (same cycle every run) and snapshot the front buffer, then
        // exit. Matches VICE's `-limitcycles N -exitscreenshot` determinism
        // so JaC/VICE can be compared at the same demo phase — needed for
        // transient effects (e.g. Krestage 3's 9th sprite) that wall-clock
        // capture can't reliably land on.
        long captureAtCycle = Long.getLong("jac64.captureAtCycle", -1L);
        if (captureAtCycle > 0) {
            System.out.println("Deterministic capture: pause-at-cycle " + captureAtCycle);
            // Burst mode: -Djac64.captureBurst=N captures N consecutive frames
            // (one PAL frame = 19656 cycles apart, tunable via
            // jac64.captureBurstStride), each deterministically paused, written
            // as <captureFile-without-ext>_000.png.. . Lets transient per-frame
            // artifacts (e.g. FLI left-edge flicker) be flipped through / diffed.
            int burst = Integer.getInteger("jac64.captureBurst", 1);
            long stride = Long.getLong("jac64.captureBurstStride", 19656L);
            String capPath = System.getProperty("jac64.captureFile",
                "/tmp/jac64_capture.png");
            if (burst > 1) {
                String base = capPath.replaceFirst("\\.png$", "");
                for (int frame = 0; frame < burst; frame++) {
                    long target = captureAtCycle + frame * stride;
                    cpu.pauseAtCycle = target;
                    cpu.setPause(false);
                    for (int i = 0; i < 4000; i++) {
                        if (cpu.pause && cpu.cycles >= target) break;
                        Thread.sleep(5);
                    }
                    String fp = String.format("%s_%03d.png", base, frame);
                    screenshot(fp);
                    System.out.println("Burst frame " + frame + " at clk="
                        + cpu.cycles + " -> " + fp);
                }
                System.out.flush();
                System.exit(0);
            }
            cpu.pauseAtCycle = captureAtCycle;
            for (int i = 0; i < 2000; i++) {
                if (cpu.pause && cpu.cycles >= captureAtCycle) break;
                Thread.sleep(20);
            }
            screenshot(capPath);
            System.out.println("Captured at clk=" + cpu.cycles + " -> " + capPath);
            String dumpRangeAtCapture = System.getProperty("jac64.dumpMemRange");
            if (dumpRangeAtCapture != null) {
                String[] parts = dumpRangeAtCapture.split(":");
                int start = Integer.decode(parts[0]);
                int end = Integer.decode(parts[1]);
                String binPath = parts.length >= 3 ? parts[2] : "/tmp/jac64_memdump.bin";
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(binPath)) {
                    int[] mem = cpu.getMemory();
                    byte[] buf = new byte[end - start];
                    for (int i = 0; i < buf.length; i++) buf[i] = (byte)(mem[start + i] & 0xff);
                    fos.write(buf);
                    System.out.println("Memory dump $" + Integer.toHexString(start)
                        + "-$" + Integer.toHexString(end) + " in " + binPath);
                } catch (Exception e) {
                    System.err.println("Memory dump failed: " + e);
                }
            }
            System.out.flush();
            System.exit(0);
        }

        // Capture screenshots every second for 30 seconds
        System.out.println("Capturing screenshots...");
        boolean dumpScreen = Boolean.getBoolean("jac64.dumpScreen");
        for (int i = 0; i < CAPTURE_FRAMES; i++) {
            Thread.sleep(1000);
            screenshot("/tmp/jac64_test_frame_" + String.format("%03d", i) + ".png");
            int d020 = readIo(0xd020) & 0x0f;
            int d7ff = cpu.getMemory()[0xd7ff] & 0xff;
            System.out.println("  Frame " + i + " captured (t=" + i + "s)"
                + " clk=" + cpu.cycles
                + " $D020=$" + Integer.toHexString(d020)
                + " $D7FF=$" + Integer.toHexString(d7ff));
            if (dumpScreen) {
                int rows = Integer.getInteger("jac64.dumpRows", 6);
                dumpScreenRows(0, rows);
            }
        }

        // Test-ROM pass/fail signal: many VICE testprogs write a result
        // code to $D020 (border) or $D7FF (color RAM last byte):
        //   $D020 $05 = pass (green), $D020 $02 = fail (red)
        //   $D7FF $00 = pass, $D7FF $FF = fail (irq-ack-vicii)
        // $D020 is a VIC-II I/O register — must be read via the chip API,
        // not direct memory[0xd020] which addresses ROM space.
        int d020 = readIo(0xd020) & 0x0f;
        int d7ff = cpu.getMemory()[0xd7ff] & 0xff;
        String passFail;
        // Border-color pixel sample is the truth. $D7FF agrees with it
        // when both reach end_of_tests, but $D020-via-pixel handles
        // tests that hang in IRQ loops without ever reaching the final
        // stx $d7ff.
        if (d020 == 5) passFail = "PASS (border=green)";
        else if (d020 == 2) passFail = "FAIL (border=red)";
        else if (d7ff == 0x00) passFail = "PASS? (D7FF=0, border=$"
            + Integer.toHexString(d020) + ")";
        else if (d7ff == 0xff) passFail = "FAIL? (D7FF=ff, border=$"
            + Integer.toHexString(d020) + ")";
        else passFail = "? (no signal, border=$" + Integer.toHexString(d020) + ")";
        System.out.println("=== Test complete: $D020=$" + Integer.toHexString(d020)
            + " $D7FF=$" + Integer.toHexString(d7ff) + " → " + passFail + " ===");
        System.out.println("Screenshots in /tmp/jac64_test_frame_*.png");
        System.out.println("FLD trace in /tmp/jac64_fld_trace.log");
        if (Boolean.getBoolean("jac64.dumpScreen")) {
            String dumpPath = System.getProperty("jac64.dumpScreenFile",
                "/tmp/jac64_screen_dump.txt");
            dumpScreenRam(dumpPath);
            System.out.println("Screen RAM dump in " + dumpPath);
        }
        // jac64.dumpMemRange=START:END:PATH writes a raw binary copy of CPU
        // RAM bytes [START..END) to PATH. Used to byte-compare bascan's
        // BUFFER ($4000-$4900) against the test-bundled dump-c64.bin.
        String dumpRange = System.getProperty("jac64.dumpMemRange");
        if (dumpRange != null) {
            String[] parts = dumpRange.split(":");
            int start = Integer.decode(parts[0]);
            int end = Integer.decode(parts[1]);
            String binPath = parts.length >= 3 ? parts[2] : "/tmp/jac64_memdump.bin";
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(binPath)) {
                int[] mem = cpu.getMemory();
                byte[] buf = new byte[end - start];
                for (int i = 0; i < buf.length; i++) buf[i] = (byte)(mem[start + i] & 0xff);
                fos.write(buf);
                System.out.println("Memory dump $" + Integer.toHexString(start)
                    + "-$" + Integer.toHexString(end) + " in " + binPath);
            } catch (Exception e) {
                System.err.println("Memory dump failed: " + e);
            }
        }
        System.exit(0);
    }

    /* Raw screen-RAM dump: hex + ASCII (preserving inverse-video bit so
       irq-ack-vicii's $81/$84 result codes are distinguishable from
       regular characters).  Used by the cycle-accuracy debugging loop
       to compare cell-by-cell against a VICE screen dump. */
    private void dumpScreenRam(String path) {
        int base = Integer.getInteger("jac64.dumpScreenBase", 0x0400);
        try (java.io.PrintWriter pw = new java.io.PrintWriter(path)) {
            int[] mem = cpu.getMemory();
            pw.printf("# screen base $%04x%n", base);
            for (int row = 0; row < 25; row++) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int col = 0; col < 40; col++) {
                    int b = mem[base + row * 40 + col] & 0xff;
                    hex.append(String.format("%02x ", b));
                    /* preserve inverse-video bit: '~' prefix on inverse,
                       printable ASCII for normal codes, '.' for non-print. */
                    int low = b & 0x7f;
                    char c;
                    if (low == 0x20) c = ' ';
                    else if (low >= 0x01 && low <= 0x1a) c = (char)('A' + low - 1);
                    else if (low >= 0x30 && low <= 0x39) c = (char)('0' + low - 0x30);
                    else if (low == 0x2e) c = '.';
                    else if (low == 0x2a) c = '*';
                    else if (low == 0x2d) c = '-';
                    else c = '.';
                    ascii.append((b & 0x80) != 0 ? Character.toLowerCase(c) : c);
                }
                pw.printf("row %02d: %s | %s%n", row, hex.toString(), ascii.toString());
            }
        } catch (Exception e) {
            System.err.println("Screen dump failed: " + e);
        }
    }

    private String readScreen() {
        StringBuilder sb = new StringBuilder();
        int screenBase = 0x0400;
        for (int row = 0; row < 25; row++) {
            for (int col = 0; col < 40; col++) {
                int ch = cpu.getMemory()[screenBase + row * 40 + col] & 0xff;
                sb.append(petsciiToAscii(ch));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private char petsciiToAscii(int sc) {
        // Mask off inverse-video bit so we still decode characters in
        // the high half of the screen-code range.
        sc = sc & 0x7f;
        if (sc == 0x20) return ' ';
        if (sc >= 0x01 && sc <= 0x1a) return (char)('A' + sc - 1);
        if (sc >= 0x30 && sc <= 0x39) return (char)('0' + sc - 0x30);
        if (sc == 0x2e) return '.';
        if (sc == 0x2c) return ',';
        if (sc == 0x2a) return '*';
        if (sc == 0x22) return '"';
        if (sc == 0x2d) return '-';
        if (sc == 0x3f) return '?';
        if (sc == 0x21) return '!';
        if (sc == 0x28) return '(';
        if (sc == 0x29) return ')';
        if (sc == 0x2b) return '+';
        if (sc == 0x2f) return '/';
        if (sc == 0x3a) return ':';
        if (sc == 0x3b) return ';';
        if (sc == 0x3c) return '<';
        if (sc == 0x3d) return '=';
        if (sc == 0x3e) return '>';
        if (sc == 0x40) return '@';
        return '.';
    }

    private int readIo(int address) {
        // Sample the top-left border pixel from the rendered framebuffer
        // and reverse-look up its CBM color index. The actual rendered
        // border is the truth — register-field reflection sometimes
        // shows stale state vs the screenshot.
        try {
            Object chips = cpu.getChips();
            java.lang.reflect.Method m =
                chips.getClass().getMethod("getPixelBuffer");
            int[] buf = (int[]) m.invoke(chips);
            // Top-left pixel is in the border area.
            int rgb = buf[0] & 0xFFFFFF;
            int idx = cbmRgbToIndex(rgb);
            // For debugging: stash raw color separately if needed.
            if (Boolean.getBoolean("jac64.tracePixel")) {
                System.out.println("  border pixel rgb=$"
                    + Integer.toHexString(rgb) + " idx=$" + Integer.toHexString(idx));
            }
            return idx;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Reverse mapping from rendered ARGB to the 16-color JaC64 palette.
     * Reads the live palette from C64Screen.cbmcolor so it tracks the
     * jac64.colorSet system property (default now: set 1, VICE 8565-aligned).
     */
    private int cbmRgbToIndex(int rgb) {
        int[] pal;
        try {
            java.lang.reflect.Field f = scr.getClass().getField("cbmcolor");
            pal = (int[]) f.get(scr);
        } catch (Exception e) {
            // Fall back to set 1 (VICE 8565) literal.
            pal = new int[] {
                0x000000, 0xffffff, 0x68372b, 0x70a4b2,
                0x6f3d86, 0x588d43, 0x352879, 0xb8c76f,
                0x6f4f25, 0x433900, 0x9a6759, 0x444444,
                0x6c6c6c, 0x9ad284, 0x6c5eb5, 0x959595,
            };
        }
        int best = -1;
        int bestD = Integer.MAX_VALUE;
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        for (int i = 0; i < 16; i++) {
            int pr = (pal[i] >> 16) & 0xff;
            int pg = (pal[i] >> 8) & 0xff;
            int pb = pal[i] & 0xff;
            int d = (pr - r) * (pr - r) + (pg - g) * (pg - g) + (pb - b) * (pb - b);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void dumpScreenRows(int firstRow, int lastRow) {
        int screenBase = 0x0400;
        System.out.println("--- screen rows " + firstRow + ".." + lastRow + " ---");
        for (int row = firstRow; row <= lastRow; row++) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%2d: |", row));
            for (int col = 0; col < 40; col++) {
                int ch = cpu.getMemory()[screenBase + row * 40 + col] & 0xff;
                sb.append(petsciiToAscii(ch));
            }
            sb.append('|');
            System.out.println(sb.toString());
        }
    }

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] :
            "https://www.c64.com/demos/download.php?id=1982";
        new TestRaster().run(source);
    }
}
