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
import javax.imageio.ImageIO;
import javax.net.ssl.*;

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

    private static final class SilentAudioDriver extends AudioDriver {
        private final long startMicros = System.nanoTime() / 1000L;
        private boolean fullSpeed;
        private int masterVolume;

        @Override
        public void init(int sampleRate, int bufferSize) {
        }

        @Override
        public void write(byte[] buffer) {
        }

        @Override
        public long getMicros() {
            return (System.nanoTime() / 1000L) - startMicros;
        }

        @Override
        public boolean hasSound() {
            return false;
        }

        @Override
        public int available() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int getMasterVolume() {
            return masterVolume;
        }

        @Override
        public void setMasterVolume(int v) {
            masterVolume = v;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void setSoundOn(boolean on) {
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

    private CPU cpu;
    private C64Screen scr;
    private C64Reader reader;

    private void initEmulator() {
        SIDMixer.DL_BUFFER_SIZE = 16384;
        Debugger monitor = new Debugger();
        cpu = new CPU(monitor, "", new SELoader());
        scr = new C64Screen(monitor, true);
        cpu.init(scr);

        // Use a silent driver so the harness can run headless on machines
        // without a working Java Sound output line.
        AudioDriver audioDriver = new SilentAudioDriver();
        scr.init(cpu, audioDriver);
        audioDriver.setMasterVolume(0); // silent but still running

        reader = new C64Reader();
        reader.setCPU(cpu);
        cpu.getDrive().setReader(reader);

        scr.setKeyboardEmulation(false);

        new Thread(() -> cpu.start(), "C64-CPU").start();
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
        int h = 284;  // SC_HEIGHT
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
                filename = parts[1].trim().replaceAll("^\"|\"$", "").split(";")[0].trim();
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
        cpu.enterText("LOAD\"*\",8,1~");
    }

    public void run(String source) throws Exception {
        System.out.println("=== JaC64 Raster Test ===");
        initEmulator();
        waitReady();

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
                    if (screen.contains("READY.") && !screen.contains("LOADING")) {
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
            waitReady();
            reader.readPGM(path, -1);
            cpu.runBasic();
            System.out.println("PRG loaded and RUN");
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

        // Capture screenshots every second for 30 seconds
        System.out.println("Capturing screenshots...");
        for (int i = 0; i < CAPTURE_FRAMES; i++) {
            Thread.sleep(1000);
            screenshot("/tmp/jac64_test_frame_" + String.format("%03d", i) + ".png");
            System.out.println("  Frame " + i + " captured (t=" + i + "s)");
        }

        System.out.println("=== Test complete ===");
        System.out.println("Screenshots in /tmp/jac64_test_frame_*.png");
        System.out.println("FLD trace in /tmp/jac64_fld_trace.log");
        System.exit(0);
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
        if (sc == 0x20) return ' ';
        if (sc >= 0x01 && sc <= 0x1a) return (char)('A' + sc - 1);
        if (sc >= 0x30 && sc <= 0x39) return (char)('0' + sc - 0x30);
        if (sc == 0x2e) return '.';
        if (sc == 0x2c) return ',';
        if (sc == 0x2a) return '*';
        if (sc == 0x22) return '"';
        return ' ';
    }

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] :
            "https://www.c64.com/demos/download.php?id=1982";
        new TestRaster().run(source);
    }
}
