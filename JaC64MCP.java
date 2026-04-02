/**
 * MCP (Model Context Protocol) server for JaC64 emulator.
 * Allows AI tools to control the C64 emulator via stdio JSON-RPC.
 *
 * Usage: java -cp build/libs/JaC64.jar JaC64MCP
 */
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.google.gson.*;

import com.dreamfabric.jac64.*;
import com.dreamfabric.c64utils.*;

public class JaC64MCP {

    private CPU cpu;
    private C64Screen scr;
    private C64Reader reader;
    private Keyboard keyboard;
    private JFrame window;
    private final PrintStream mcpOut;

    public JaC64MCP(PrintStream mcpOut) {
        this.mcpOut = mcpOut;
    }

    private void initEmulator() {
        SIDMixer.DL_BUFFER_SIZE = 16384;
        Debugger monitor = new Debugger();

        cpu = new CPU(monitor, "", new SELoader());
        scr = new C64Screen(monitor, true);
        cpu.init(scr);

        C64Canvas canvas = C64Canvas.setupDesktop(scr, cpu, true);

        reader = new C64Reader();
        reader.setCPU(cpu);
        cpu.getDrive().setReader(reader);

        keyboard = scr.getKeyboard();
        scr.setKeyboardEmulation(false);

        window = new JFrame("JaC64 MCP");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setBackground(Color.black);
        window.setLayout(new BorderLayout());
        window.getContentPane().add(canvas, BorderLayout.CENTER);

        // Menu bar
        JMenuBar menuBar = new JMenuBar();
        window.setJMenuBar(menuBar);
        ActionListener menuHandler = e -> handleMenu(e.getActionCommand());

        // File menu
        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);
        for (String item : new String[]{"Open File/Disk...", "Reset", "Warp Speed"}) {
            JMenuItem mi = new JMenuItem(item);
            mi.addActionListener(menuHandler);
            fileMenu.add(mi);
        }

        // Settings menu
        JMenu settingsMenu = new JMenu("Settings");
        menuBar.add(settingsMenu);

        JMenu sidMenu = new JMenu("SID Emulation");
        settingsMenu.add(sidMenu);
        ButtonGroup sidGroup = new ButtonGroup();
        for (String[] sid : new String[][]{{"reSID 6581", "1"}, {"reSID 8580", "2"}, {"JaC SID", "3"}}) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(sid[0]);
            mi.setActionCommand("SID:" + sid[1]);
            mi.addActionListener(menuHandler);
            if (sid[1].equals("1")) mi.setSelected(true);
            sidGroup.add(mi);
            sidMenu.add(mi);
        }

        JMenu joyMenu = new JMenu("Joystick Port");
        settingsMenu.add(joyMenu);
        ButtonGroup joyGroup = new ButtonGroup();
        for (String[] joy : new String[][]{{"Port 1", "1"}, {"Port 2", "2"}}) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(joy[0]);
            mi.setActionCommand("JOY:" + joy[1]);
            mi.addActionListener(menuHandler);
            if (joy[1].equals("1")) mi.setSelected(true);
            joyGroup.add(mi);
            joyMenu.add(mi);
        }

        JMenu colorMenu = new JMenu("Color Set");
        settingsMenu.add(colorMenu);
        ButtonGroup colorGroup = new ButtonGroup();
        for (int i = 1; i <= 4; i++) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem("Color Set " + i);
            mi.setActionCommand("COLOR:" + (i - 1));
            mi.addActionListener(menuHandler);
            if (i == 1) mi.setSelected(true);
            colorGroup.add(mi);
            colorMenu.add(mi);
        }

        JCheckBoxMenuItem muteItem = new JCheckBoxMenuItem("Mute");
        muteItem.setSelected(true);
        muteItem.addActionListener(e2 -> scr.setSoundOn(!muteItem.isSelected()));
        settingsMenu.add(muteItem);
        scr.setSoundOn(false); // Start muted

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (window.isFocused()) {
                if (e.getID() == KeyEvent.KEY_PRESSED) canvas.keyPressed(e);
                else if (e.getID() == KeyEvent.KEY_RELEASED) canvas.keyReleased(e);
                else if (e.getID() == KeyEvent.KEY_TYPED) canvas.keyTyped(e);
                return true;
            }
            return false;
        });

        window.pack();
        window.setSize(386 * 2 + 10, 284 * 2 + 70);
        window.setVisible(true);

        canvas.setFocusable(true);
        canvas.requestFocusInWindow();

        // Start CPU on its own thread
        Thread cpuThread = new Thread(() -> cpu.start(), "C64-CPU");
        cpuThread.setDaemon(true);
        cpuThread.start();

        // Wait for KERNAL to be ready
        while (!scr.ready()) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private void handleMenu(String cmd) {
        switch (cmd) {
            case "Open File/Disk...":
                FileDialog fd = new FileDialog(window, "Open File/Disk", FileDialog.LOAD);
                fd.setFilenameFilter((dir, name) -> {
                    String n = name.toLowerCase();
                    return n.endsWith(".d64") || n.endsWith(".t64") || n.endsWith(".prg") || n.endsWith(".p00") || n.endsWith(".zip");
                });
                fd.setVisible(true);
                if (fd.getFile() != null) {
                    String path = fd.getDirectory() + fd.getFile();
                    String lower = path.toLowerCase();
                    try {
                        if (lower.endsWith(".zip")) {
                            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                                new java.io.FileInputStream(path));
                            java.util.zip.ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                String n = entry.getName().toLowerCase();
                                if (n.endsWith(".d64") || n.endsWith(".t64") || n.endsWith(".prg") || n.endsWith(".p00")) {
                                    File tmp = File.createTempFile("jac64_", "_" + entry.getName());
                                    tmp.deleteOnExit();
                                    FileOutputStream fos = new FileOutputStream(tmp);
                                    byte[] buf = new byte[8192];
                                    int len;
                                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                                    fos.close();
                                    path = tmp.getAbsolutePath();
                                    lower = path.toLowerCase();
                                    break;
                                }
                            }
                            zis.close();
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                    if (lower.endsWith(".d64")) {
                        reader.readDiskFromFile(path);
                        cpu.reset();
                        waitReady();
                        reader.readDiskFromFile(path);
                        cpu.enterText("LOAD\"*\",8,1~");
                    } else if (lower.endsWith(".t64")) {
                        cpu.reset();
                        waitReady();
                        reader.readTapeFromFile(path);
                        reader.readFile("*", -1);
                        cpu.runBasic();
                    } else if (lower.endsWith(".prg") || lower.endsWith(".p00")) {
                        cpu.reset();
                        waitReady();
                        reader.readPGM(path, -1);
                        cpu.runBasic();
                    }
                }
                break;
            case "Reset":
                scr.setFullSpeed(false);
                cpu.reset();
                break;
            case "Warp Speed":
                boolean warp = !scr.getAudioDriver().fullSpeed();
                scr.setFullSpeed(warp);
                window.setTitle("JaC64 MCP" + (warp ? " [WARP]" : ""));
                break;
        }
        if (cmd.startsWith("SID:")) {
            int sid = Integer.parseInt(cmd.substring(4));
            scr.setSID(sid);
        } else if (cmd.startsWith("JOY:")) {
            scr.setStick(cmd.equals("JOY:1"));
        } else if (cmd.startsWith("COLOR:")) {
            scr.setColorSet(Integer.parseInt(cmd.substring(6)));
        }
    }

    private void runMCP() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String method = request.has("method") ? request.get("method").getAsString() : "";
                JsonElement id = request.get("id");

                // Notifications (no id) don't get a response
                if (id == null) continue;

                JsonObject response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                response.add("id", id);

                switch (method) {
                    case "initialize":
                        response.add("result", handleInitialize());
                        break;
                    case "tools/list":
                        response.add("result", handleToolsList());
                        break;
                    case "tools/call":
                        response.add("result", handleToolCall(request.getAsJsonObject("params")));
                        break;
                    default:
                        JsonObject error = new JsonObject();
                        error.addProperty("code", -32601);
                        error.addProperty("message", "Method not found: " + method);
                        response.add("error", error);
                }

                mcpOut.println(new Gson().toJson(response));
                mcpOut.flush();
            }
        } catch (IOException e) {
            System.err.println("MCP IO error: " + e.getMessage());
        }
    }

    // --- MCP Protocol Handlers ---

    private JsonObject handleInitialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");

        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        result.add("capabilities", caps);

        JsonObject info = new JsonObject();
        info.addProperty("name", "jac64");
        info.addProperty("version", "1.0");
        result.add("serverInfo", info);

        return result;
    }

    private JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        tools.add(toolDef("load_file", "Load a .d64, .t64, .prg, or .p00 file into the emulator",
            prop("path", "string", "Absolute path to the file"),
            prop("mount_only", "boolean", "If true, only mount the disk without resetting or typing LOAD (for disk swaps)")));

        tools.add(toolDef("type_text", "Type text into the C64 keyboard buffer. Use \\n for ENTER key.",
            prop("text", "string", "Text to type")));

        tools.add(toolDef("read_screen", "Read the C64 screen as 25 lines of 40 characters",
            null));

        tools.add(toolDef("screenshot", "Take a screenshot of the C64 screen (returns PNG image)",
            null));

        tools.add(toolDef("joystick", "Set joystick state for a duration then release",
            prop("up", "boolean", "Up direction"),
            prop("down", "boolean", "Down direction"),
            prop("left", "boolean", "Left direction"),
            prop("right", "boolean", "Right direction"),
            prop("fire", "boolean", "Fire button"),
            prop("duration_ms", "integer", "Duration in ms (default 100)")));

        tools.add(toolDef("peek", "Read bytes from C64 memory",
            prop("address", "integer", "Memory address (0-65535)"),
            prop("length", "integer", "Number of bytes to read (default 1, max 256)")));

        tools.add(toolDef("poke", "Write a byte to C64 memory",
            prop("address", "integer", "Memory address (0-65535)"),
            prop("value", "integer", "Byte value (0-255)")));

        tools.add(toolDef("reset", "Reset the C64",
            prop("hard", "boolean", "Hard reset if true (default: soft reset)")));

        tools.add(toolDef("list_directory", "List files on the currently loaded disk",
            null));

        tools.add(toolDef("set_speed", "Set emulator speed. Use full_speed=true for warp mode (e.g. during loading), false for normal speed.",
            prop("full_speed", "boolean", "true for maximum speed, false for normal 50Hz PAL speed")));

        tools.add(toolDef("key_press", "Press a special C64 key. Named keys: RUN_STOP, RETURN, F1-F8, SPACE, DELETE, HOME, CTRL, COMMODORE, SHIFT, CRSR_UP, CRSR_DOWN, CRSR_LEFT, CRSR_RIGHT, RESTORE",
            prop("key", "string", "Key name (e.g. RUN_STOP, F1, SPACE)"),
            prop("duration_ms", "integer", "Duration in ms (default 100)")));

        tools.add(toolDef("set_sid", "Switch SID chip emulation: resid_6581 (original, default), resid_8580 (newer revision), jacsid (lightweight)",
            prop("type", "string", "SID type: resid_6581, resid_8580, or jacsid")));

        tools.add(toolDef("cpu_state", "Read CPU registers (PC, SP, A, X, Y) and 1541 drive CPU state",
            null));

        tools.add(toolDef("iec_trace", "Enable/disable IEC serial bus tracing to stderr",
            prop("enabled", "boolean", "true to start tracing, false to stop")));

        result.add("tools", tools);
        return result;
    }

    private JsonObject handleToolCall(JsonObject params) {
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            switch (name) {
                case "load_file": return toolLoadFile(args);
                case "type_text": return toolTypeText(args);
                case "read_screen": return toolReadScreen();
                case "screenshot": return toolScreenshot();
                case "joystick": return toolJoystick(args);
                case "peek": return toolPeek(args);
                case "poke": return toolPoke(args);
                case "reset": return toolReset(args);
                case "list_directory": return toolListDirectory();
                case "set_speed": return toolSetSpeed(args);
                case "key_press": return toolKeyPress(args);
                case "set_sid": return toolSetSid(args);
                case "cpu_state": return toolCpuState();
                case "iec_trace": return toolIecTrace(args);
                default: return toolError("Unknown tool: " + name);
            }
        } catch (Exception e) {
            return toolError(e.getMessage());
        }
    }

    // --- Tool Implementations ---

    private JsonObject toolLoadFile(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        String lower = path.toLowerCase();

        // Extract from zip if needed
        if (lower.endsWith(".zip")) {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.FileInputStream(path));
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName().toLowerCase();
                if (n.endsWith(".d64") || n.endsWith(".t64") || n.endsWith(".prg") || n.endsWith(".p00")) {
                    File tmp = File.createTempFile("jac64_", "_" + entry.getName());
                    tmp.deleteOnExit();
                    FileOutputStream fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.close();
                    zis.close();
                    path = tmp.getAbsolutePath();
                    lower = path.toLowerCase();
                    break;
                }
            }
            if (lower.endsWith(".zip")) {
                zis.close();
                return toolError("No .d64/.t64/.prg/.p00 found in zip");
            }
        }

        boolean mountOnly = args.has("mount_only") && args.get("mount_only").getAsBoolean();

        if (lower.endsWith(".d64")) {
            reader.readDiskFromFile(path);
            if (mountOnly) {
                return toolResult("Disk mounted (swap only, no reset).");
            }
            // Wait for screen ready, then type LOAD command
            cpu.reset();
            waitReady();
            reader.readDiskFromFile(path);
            cpu.enterText("LOAD\"*\",8,1~");
            return toolResult("Disk mounted and LOAD\"*\",8,1 typed. Drive LED will show loading activity.");
        } else if (lower.endsWith(".t64")) {
            cpu.reset();
            waitReady();
            reader.readTapeFromFile(path);
            reader.readFile("*", -1);
            cpu.runBasic();
            return toolResult("T64 loaded and RUN executed.");
        } else if (lower.endsWith(".prg") || lower.endsWith(".p00")) {
            cpu.reset();
            waitReady();
            reader.readPGM(path, -1);
            cpu.runBasic();
            return toolResult("PRG loaded and RUN executed.");
        }

        return toolError("Unsupported file type: " + path);
    }

    private JsonObject toolTypeText(JsonObject args) {
        String text = args.get("text").getAsString();
        // Convert \n (both literal newlines and escaped \\n) to ~ (which enterText converts to C64 RETURN)
        text = text.replace("\\n", "~");
        text = text.replace("\n", "~");
        cpu.enterText(text);
        return toolResult("Typed: " + text.replace("~", "\\n"));
    }

    private JsonObject toolIecTrace(JsonObject args) {
        boolean enabled = args.has("enabled") && args.get("enabled").getAsBoolean();
        if (enabled) {
            scr.iecTraceCount = 0;
            scr.iecLogPos = 0;
            for (int i = 0; i < C64Screen.IEC_LOG_SIZE; i++) scr.iecLog[i] = null;
            scr.iecTrace = true;
            return toolResult("IEC trace started");
        } else {
            scr.iecTrace = false;
            // Dump last N events from ring buffer
            StringBuilder sb = new StringBuilder();
            sb.append("IEC trace: " + scr.iecTraceCount + " total events. Last entries:\n");
            for (int i = 0; i < C64Screen.IEC_LOG_SIZE; i++) {
                int idx = (scr.iecLogPos + i) % C64Screen.IEC_LOG_SIZE;
                if (scr.iecLog[idx] != null) {
                    sb.append(scr.iecLog[idx]).append('\n');
                }
            }
            return toolResult(sb.toString());
        }
    }

    private JsonObject toolCpuState() {
        C1541Emu drive = cpu.getDrive();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("C64  PC=$%04X SP=$%02X A=$%02X X=$%02X Y=$%02X\n",
            cpu.getPC(), cpu.getSP(), cpu.getAcc(), cpu.getX(), cpu.getY()));
        sb.append(String.format("1541 PC=$%04X SP=$%02X A=$%02X X=$%02X Y=$%02X\n",
            drive.getPC(), drive.getSP(), drive.getAcc(), drive.getX(), drive.getY()));
        // Show a few bytes at C64 PC for context
        int pc = cpu.getPC() & 0xffff;
        int[] mem = cpu.getMemory();
        sb.append(String.format("C64 @PC: %02X %02X %02X %02X %02X\n",
            mem[pc % mem.length]&0xff, mem[(pc+1) % mem.length]&0xff,
            mem[(pc+2) % mem.length]&0xff, mem[(pc+3) % mem.length]&0xff,
            mem[(pc+4) % mem.length]&0xff));
        // 1541 IEC/VIA state
        C1541Chips chips = drive.chips;
        int dpc = drive.getPC();
        int[] dmem = drive.getMemory();
        sb.append(String.format("1541 @PC: %02X %02X %02X %02X %02X\n",
            dmem[dpc]&0xff, dmem[(dpc+1)&0xffff]&0xff, dmem[(dpc+2)&0xffff]&0xff,
            dmem[(dpc+3)&0xffff]&0xff, dmem[(dpc+4)&0xffff]&0xff));
        sb.append(String.format("IEC c64=$%02X drv=$%02X via1PB=$%02X via1CB=$%02X track=%d sector=%d\n",
            scr.iecLines & 0xff, chips.iecLines & 0xff, chips.via1PB & 0xff, chips.via1CB & 0xff,
            chips.currentTrack, chips.currentSector));
        sb.append(String.format("via2PB=$%02X via1IE=$%02X via1IF=$%02X motor=%b IRQLow=%b I=%b\n",
            chips.via2PB & 0xff,
            chips.via1IEnable & 0xff, chips.via1IFlag & 0xff,
            chips.motorOn, drive.getIRQLow(),
            (drive.getStatus() & 0x04) != 0));
        // Dump 1541 code: initial handshake area and IEC transfer
        for (int base : new int[]{0x0370, 0x0380, 0x0390, 0x0670, 0x0680, 0x0690}) {
            sb.append(String.format("$%04X:", base));
            for (int i = 0; i < 16; i++)
                sb.append(String.format(" %02X", dmem[(base + i) & 0xFFFF] & 0xFF));
            sb.append('\n');
        }
        return toolResult(sb.toString());
    }

    private JsonObject toolReadScreen() {
        int[] mem = cpu.getMemory();
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 25; row++) {
            for (int col = 0; col < 40; col++) {
                int code = mem[0x0400 + row * 40 + col] & 0xff;
                sb.append(screenCodeToChar(code));
            }
            sb.append('\n');
        }
        return toolResult(sb.toString());
    }

    private JsonObject toolScreenshot() {
        int[] pixels = scr.getPixelBuffer();
        int w = 384, h = 284;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject imgContent = new JsonObject();
            imgContent.addProperty("type", "image");
            imgContent.addProperty("data", base64);
            imgContent.addProperty("mimeType", "image/png");
            content.add(imgContent);
            result.add("content", content);
            return result;
        } catch (IOException e) {
            return toolError("Screenshot failed: " + e.getMessage());
        }
    }

    private JsonObject toolJoystick(JsonObject args) {
        int joy = 0xff;
        if (args.has("up") && args.get("up").getAsBoolean()) joy &= ~Keyboard.STICK_UP;
        if (args.has("down") && args.get("down").getAsBoolean()) joy &= ~Keyboard.STICK_DOWN;
        if (args.has("left") && args.get("left").getAsBoolean()) joy &= ~Keyboard.STICK_LEFT;
        if (args.has("right") && args.get("right").getAsBoolean()) joy &= ~Keyboard.STICK_RIGHT;
        if (args.has("fire") && args.get("fire").getAsBoolean()) joy &= ~Keyboard.STICK_FIRE;

        int duration = args.has("duration_ms") ? args.get("duration_ms").getAsInt() : 100;

        keyboard.setJoystickState(joy);
        try { Thread.sleep(duration); } catch (InterruptedException e) {}
        keyboard.setJoystickState(0xff); // release

        return toolResult("Joystick action for " + duration + "ms");
    }

    private int peekByte(int address) {
        // Route I/O space ($D000-$DFFF) through chip emulation
        if (address >= 0xD000 && address <= 0xDFFF) {
            return scr.performRead(address, cpu.cycles) & 0xff;
        }
        return cpu.getMemory()[address] & 0xff;
    }

    private JsonObject toolPeek(JsonObject args) {
        int address = args.get("address").getAsInt();
        int length = args.has("length") ? args.get("length").getAsInt() : 1;
        length = Math.min(length, 256);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("$%04X:", address));
        for (int i = 0; i < length; i++) {
            if (i > 0 && i % 16 == 0) sb.append(String.format("\n$%04X:", address + i));
            sb.append(String.format(" %02X", peekByte((address + i) & 0xffff)));
        }
        return toolResult(sb.toString());
    }

    private JsonObject toolPoke(JsonObject args) {
        int address = args.get("address").getAsInt();
        int value = args.get("value").getAsInt();
        cpu.poke(address, value & 0xff);
        return toolResult(String.format("Poked $%04X = $%02X", address, value & 0xff));
    }

    private JsonObject toolReset(JsonObject args) {
        scr.setFullSpeed(false); // ensure normal speed on reset
        cpu.reset();
        waitReady();
        return toolResult("Reset complete");
    }

    private JsonObject toolListDirectory() {
        ArrayList dirNames = reader.getDirNames();
        if (dirNames == null || dirNames.isEmpty()) {
            return toolResult("No disk loaded or empty directory.");
        }
        StringBuilder sb = new StringBuilder();
        for (Object obj : dirNames) {
            DirEntry entry = (DirEntry) obj;
            sb.append(String.format("%-16s %4d %s\n", entry.name.trim(), entry.size, entry.getTypeString()));
        }
        return toolResult(sb.toString());
    }

    private JsonObject toolSetSpeed(JsonObject args) {
        boolean fullSpeed = args.has("full_speed") && args.get("full_speed").getAsBoolean();
        scr.setFullSpeed(fullSpeed);
        return toolResult(fullSpeed ? "Warp mode enabled" : "Normal speed restored");
    }

    private JsonObject toolSetSid(JsonObject args) {
        String type = args.get("type").getAsString().toLowerCase();
        switch (type) {
            case "resid_6581": scr.setSID(C64Screen.RESID_6581); return toolResult("SID set to reSID 6581");
            case "resid_8580": scr.setSID(C64Screen.RESID_8580); return toolResult("SID set to reSID 8580");
            case "jacsid": scr.setSID(C64Screen.JACSID); return toolResult("SID set to JaC SID");
            default: return toolError("Unknown SID type: " + type + ". Use resid_6581, resid_8580, or jacsid");
        }
    }

    private JsonObject toolKeyPress(JsonObject args) {
        String keyName = args.get("key").getAsString().toUpperCase();
        int duration = args.has("duration_ms") ? args.get("duration_ms").getAsInt() : 100;

        int keyCode;
        switch (keyName) {
            case "RUN_STOP": keyCode = Keyboard.VK_ESCAPE; break;
            case "RETURN": keyCode = Keyboard.VK_ENTER; break;
            case "SPACE": keyCode = 32; break;
            case "DELETE": keyCode = Keyboard.VK_BACK_SPACE; break;
            case "HOME": keyCode = Keyboard.VK_HOME; break;
            case "F1": keyCode = Keyboard.VK_F1; break;
            case "F3": keyCode = Keyboard.VK_F3; break;
            case "F5": keyCode = Keyboard.VK_F5; break;
            case "F7": keyCode = Keyboard.VK_F7; break;
            case "SHIFT": keyCode = Keyboard.VK_SHIFT; break;
            case "CTRL": keyCode = Keyboard.VK_CONTROL; break;
            case "COMMODORE": keyCode = Keyboard.VK_TAB; break;
            case "CRSR_UP": case "UP": keyCode = Keyboard.VK_UP; break;
            case "CRSR_DOWN": case "DOWN": keyCode = Keyboard.VK_DOWN; break;
            case "CRSR_LEFT": case "LEFT": keyCode = Keyboard.VK_LEFT; break;
            case "CRSR_RIGHT": case "RIGHT": keyCode = Keyboard.VK_RIGHT; break;
            default: return toolError("Unknown key: " + keyName);
        }

        keyboard.keyPressed(keyCode, 0);
        try { Thread.sleep(duration); } catch (InterruptedException e) {}
        keyboard.keyReleased(keyCode, 0);

        return toolResult("Pressed " + keyName + " for " + duration + "ms");
    }

    // --- Helpers ---

    private void waitReady() {
        try { Thread.sleep(200); } catch (InterruptedException e) {}
        int timeout = 50; // 5 seconds max
        while (!scr.ready() && timeout-- > 0) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private static char screenCodeToChar(int code) {
        code = code & 0x7f; // strip reverse bit
        if (code == 0) return '@';
        if (code >= 1 && code <= 26) return (char)('A' + code - 1);
        if (code == 27) return '[';
        if (code == 28) return '\\';
        if (code == 29) return ']';
        if (code == 30) return '^';
        if (code == 31) return '<';
        if (code == 32) return ' ';
        if (code >= 33 && code <= 63) return (char)(code);
        return ' '; // graphics chars
    }

    private JsonObject toolResult(String text) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        content.add(item);
        result.add("content", content);
        return result;
    }

    private JsonObject toolError(String message) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", "Error: " + message);
        content.add(item);
        result.add("content", content);
        result.addProperty("isError", true);
        return result;
    }

    private JsonObject toolDef(String name, String description, String[]... properties) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (properties != null) {
            for (String[] p : properties) {
                if (p != null) {
                    JsonObject prop = new JsonObject();
                    prop.addProperty("type", p[1]);
                    prop.addProperty("description", p[2]);
                    props.add(p[0], prop);
                }
            }
        }
        schema.add("properties", props);
        tool.add("inputSchema", schema);

        return tool;
    }

    private String[] prop(String name, String type, String description) {
        return new String[]{name, type, description};
    }

    // --- Main ---

    public static void main(String[] args) {
        // Save original stdout for MCP protocol
        PrintStream mcpOut = System.out;
        // Redirect System.out to stderr so emulator logging doesn't interfere
        System.setOut(System.err);

        JaC64MCP mcp = new JaC64MCP(mcpOut);
        mcp.initEmulator();
        System.err.println("JaC64 MCP server ready");
        mcp.runMCP();
    }
}
