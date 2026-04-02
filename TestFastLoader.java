/**
 * Test for fast loader compatibility.
 * Reads C64 screen memory to verify LOAD succeeds before testing RUN.
 */
import com.dreamfabric.jac64.*;
import com.dreamfabric.c64utils.*;
import java.lang.reflect.Field;

public class TestFastLoader {

    static int readPC(MOS6510Core cpu) {
        try {
            Field pcField = MOS6510Core.class.getDeclaredField("pc");
            pcField.setAccessible(true);
            return pcField.getInt(cpu);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to read PC", e);
        }
    }

    static String readScreen(CPU cpu) {
        int[] mem = cpu.getMemory();
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 25; row++) {
            for (int col = 0; col < 40; col++) {
                int code = mem[0x0400 + row * 40 + col] & 0xff;
                // C64 screen codes to ASCII
                if (code == 0x20) sb.append(' ');
                else if (code >= 0x01 && code <= 0x1a) sb.append((char)('A' + code - 1));
                else if (code == 0x00) sb.append('@');
                else if (code == 0x2e) sb.append('.');
                else if (code == 0x3f) sb.append('?');
                else if (code >= 0x30 && code <= 0x39) sb.append((char)('0' + code - 0x30));
                else if (code == 0x2a) sb.append('*');
                else if (code == 0x2c) sb.append(',');
                else if (code == 0x22) sb.append('"');
                else sb.append(' ');
            }
            // Trim trailing spaces
            String line = sb.substring(sb.length() - 40);
            sb.setLength(sb.length() - 40);
            sb.append(line.stripTrailing());
            sb.append('\n');
        }
        return sb.toString();
    }

    static String dumpBytes(int[] mem, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", mem[(start + i) & 0xffff] & 0xff));
        }
        return sb.toString();
    }

    static boolean matchesBytes(int[] mem, int start, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if ((mem[(start + i) & 0xffff] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeAloftLateStage(CPU cpu) {
        int[] mem = cpu.getMemory();
        return readScreen(cpu).startsWith("@@@@@BBBBBBBBBBBB")
            && matchesBytes(mem, 0x20b4,
                0x38, 0xec, 0x12, 0xd0, 0xd0, 0xfb, 0x20, 0x00,
                0x80, 0x88, 0x88, 0x10, 0x02, 0xa0, 0x26, 0x38);
    }

    static boolean screenContains(CPU cpu, String text) {
        return readScreen(cpu).contains(text);
    }

    public static void main(String[] args) throws Exception {
        String diskPath = args.length > 0 ? args[0] :
            "/Users/joakimeriksson/Downloads/Aloft/Aloft-Side1.d64";

        System.out.println("=== Fast Loader Test ===");
        System.out.println("Disk: " + diskPath);

        // Create emulator
        SIDMixer.DL_BUFFER_SIZE = 16384;
        Debugger monitor = new Debugger();
        CPU cpu = new CPU(monitor, "", new SELoader());
        C64Screen scr = new C64Screen(monitor, true);
        cpu.init(scr);
        AudioDriverSE audio = new AudioDriverSE();
        scr.init(cpu, audio);
        audio.setSoundOn(false);

        C64Reader reader = new C64Reader();
        reader.setCPU(cpu);
        cpu.getDrive().setReader(reader);

        // Start CPU
        Thread cpuThread = new Thread(() -> cpu.start(), "C64-CPU");
        cpuThread.setDaemon(true);
        cpuThread.start();

        // Wait for KERNAL ready
        System.out.print("Booting...");
        for (int i = 0; i < 100 && !scr.ready(); i++) Thread.sleep(100);
        System.out.println(" OK");

        // Mount disk and type LOAD
        reader.readDiskFromFile(diskPath);
        cpu.enterText("LOAD\"*\",8,1~");

        // Wait for LOAD — check screen for READY. or ERROR
        System.out.print("Loading");
        boolean loadOK = false;
        boolean loadError = false;
        for (int i = 0; i < 1200; i++) { // up to 120 seconds
            Thread.sleep(100);
            if (i % 10 == 0) System.out.print(".");

            String screen = readScreen(cpu);
            // Need TWO "READY." — one from boot, one after LOAD completes
            int readyCount = 0;
            int idx = 0;
            while ((idx = screen.indexOf("READY.", idx)) >= 0) { readyCount++; idx += 6; }
            if (readyCount >= 2 && screen.contains("LOADING")) {
                loadOK = true;
                break;
            }
            if (screen.contains("ERROR")) {
                loadError = true;
                System.out.println("\nLOAD FAILED! Screen:");
                System.out.println(screen.substring(0, Math.min(screen.length(), 400)));
                System.exit(3);
            }
        }
        System.out.println(loadOK ? " OK" : " TIMEOUT");
        String screenStr = readScreen(cpu).replace('\n', '|');
        System.out.println("Screen: " + screenStr.substring(0, Math.min(screenStr.length(), 200)));

        // Type RUN
        Thread.sleep(500);
        cpu.enterText("RUN~");
        System.out.println("RUN typed. Waiting for fast loader...");

        // Monitor: check if fast loader completes (drive back in ROM, motor off)
        C1541Chips chips = cpu.getDrive().chips;
        boolean lateStatePrinted = false;

        for (int i = 0; i < 600; i++) { // 60 seconds
            Thread.sleep(100);

            int c64pc = readPC(cpu);
            int drvpc = readPC(cpu.getDrive());

            if (!lateStatePrinted && drvpc == 0x06c8) {
                lateStatePrinted = true;
                System.out.printf("LATE STATE at drvpc=06C8: c64pc=%04X trk=%d motor=%b%n",
                    c64pc, chips.currentTrack, chips.motorOn);
                System.out.printf("C64 bytes @%04X: %s%n", c64pc - 4,
                    dumpBytes(cpu.getMemory(), c64pc - 4, 12));
                System.out.printf("DRV bytes @%04X: %s%n", drvpc - 4,
                    dumpBytes(cpu.getDrive().getMemory(), drvpc - 4, 12));
            }

            if (!chips.motorOn && i > 20) {
                System.out.printf("SUCCESS! Fast loader completed in %.1fs%n", i * 0.1);
                System.exit(0);
            }

            if (looksLikeAloftLateStage(cpu)) {
                System.out.printf("SUCCESS! Aloft reached VICE-matched late stage in %.1fs%n",
                    i * 0.1);
                System.exit(0);
            }

            if (i % 50 == 0) {
                System.out.printf("  [%ds] trk=%d motor=%b c64pc=%04X drvpc=%04X%n",
                    i/10, chips.currentTrack, chips.motorOn,
                    c64pc, drvpc);
            }
        }

        System.out.printf("TIMEOUT — fast loader did not complete (c64pc=%04X drvpc=%04X trk=%d motor=%b)%n",
            readPC(cpu), readPC(cpu.getDrive()), chips.currentTrack, chips.motorOn);
        System.out.printf("C64 bytes @%04X: %s%n", readPC(cpu) - 4,
            dumpBytes(cpu.getMemory(), readPC(cpu) - 4, 12));
        System.out.printf("DRV bytes @%04X: %s%n", readPC(cpu.getDrive()) - 4,
            dumpBytes(cpu.getDrive().getMemory(), readPC(cpu.getDrive()) - 4, 12));
        System.out.printf("6510 port: $00=%02X $01=%02X ioON=%b charROM=%b basic=%b kernal=%b%n",
            cpu.getMemory()[0] & 0xff, cpu.getMemory()[1] & 0xff,
            cpu.ioON, cpu.charROM, cpu.basicROM, cpu.kernalROM);
        System.out.printf("RAM  @D020: %s%n", dumpBytes(cpu.getMemory(), 0xd020, 16));
        System.out.printf("I/O  @D020: %s%n", dumpBytes(cpu.getMemory(), CPU.IO_OFFSET + 0xd020, 16));
        System.out.printf("RAM  @D060: %s%n", dumpBytes(cpu.getMemory(), 0xd060, 16));
        System.out.printf("I/O  @D060: %s%n", dumpBytes(cpu.getMemory(), CPU.IO_OFFSET + 0xd060, 16));
        System.out.println("Screen after timeout:");
        System.out.println(readScreen(cpu).substring(0, Math.min(readScreen(cpu).length(), 400)));
        System.out.println("TIMEOUT — fast loader did not complete");
        System.exit(2);
    }
}
