package com.dreamfabric.jac64.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.PopupMenu;

import com.dreamfabric.jac64.C64Reader;
import com.dreamfabric.jac64.C64Screen;
import com.dreamfabric.jac64.CPU;
import com.dreamfabric.jac64.DefaultIMon;
import com.dreamfabric.jac64.Keyboard;
import com.dreamfabric.jac64.SIDMixer;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Main Activity for JaC64 Android.
 * Manages the emulator lifecycle and UI controls.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_FILE = 1001;

    private CPU cpu;
    private C64Screen screen;
    private C64Reader reader;
    private EmulatorSurfaceView emulatorView;
    private VirtualKeyboardView keyboardView;
    private VirtualJoystickView joystickView;
    private Thread cpuThread;
    private boolean warpEnabled = false;
    private boolean touchPaddleEnabled = false;
    private AudioDriverAndroid audioDriver;

    private boolean keyboardVisible = false;
    private boolean joystickVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive mode
        setupFullscreen();

        setContentView(R.layout.activity_main);

        emulatorView = findViewById(R.id.emulator_view);
        keyboardView = findViewById(R.id.keyboard_view);
        joystickView = findViewById(R.id.joystick_view);

        // Setup toolbar buttons
        ImageButton btnKeyboard = findViewById(R.id.btn_keyboard);
        ImageButton btnJoystick = findViewById(R.id.btn_joystick);
        ImageButton btnOpen = findViewById(R.id.btn_open);
        ImageButton btnMenu = findViewById(R.id.btn_menu);

        btnKeyboard.setOnClickListener(v -> toggleKeyboard());
        btnJoystick.setOnClickListener(v -> toggleJoystick());
        btnOpen.setOnClickListener(v -> openFilePicker());
        btnMenu.setOnClickListener(this::showMenu);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Check for ROMs and initialize emulator
        if (checkRoms()) {
            initEmulator();
        }
    }

    private void setupFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private boolean checkRoms() {
        try {
            String[] roms = {"roms/kernal.c64", "roms/basic.c64", "roms/chargen.c64"};
            for (String rom : roms) {
                InputStream is = getAssets().open(rom);
                is.close();
            }
            return true;
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.roms_missing_title)
                .setMessage(R.string.roms_missing_message)
                .setPositiveButton("OK", null)
                .show();
            return false;
        }
    }

    private void initEmulator() {
        SIDMixer.DL_BUFFER_SIZE = 16384;
        DefaultIMon monitor = new DefaultIMon();

        AndroidLoader loader = new AndroidLoader(this);
        cpu = new CPU(monitor, "", loader);
        screen = new C64Screen(monitor, false);
        audioDriver = new AudioDriverAndroid();

        cpu.init(screen);
        screen.init(cpu, audioDriver);

        // Setup disk reader
        reader = new C64Reader();
        reader.setCPU(cpu);
        cpu.getDrive().setReader(reader);

        // Connect views
        emulatorView.setScreen(screen);
        emulatorView.setCPU(cpu);

        Keyboard keyboard = screen.getKeyboard();
        keyboardView.setKeyboard(keyboard);
        joystickView.setKeyboard(keyboard);

        // Enable extended keyboard emulation for virtual keyboard
        screen.setKeyboardEmulation(true);

        // Start emulation on background thread
        cpuThread = new Thread(() -> {
            cpu.start();
        }, "C64-CPU");
        cpuThread.setDaemon(true);
        cpuThread.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();
        if (cpu != null && cpu.pause) {
            cpu.setPause(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cpu != null) {
            cpu.setPause(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cpu != null) {
            cpu.stop();
        }
        if (audioDriver != null) {
            audioDriver.shutdown();
        }
    }

    // --- Hardware keyboard support ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (screen != null) {
            int c64Key = mapAndroidKey(keyCode);
            if (c64Key != -1) {
                screen.getKeyboard().keyPressed(c64Key, getKeyLocation(event));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (screen != null) {
            int c64Key = mapAndroidKey(keyCode);
            if (c64Key != -1) {
                screen.getKeyboard().keyReleased(c64Key, getKeyLocation(event));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Map Android KeyEvent codes to the VK_* constants used in our Keyboard class.
     */
    private int mapAndroidKey(int androidKey) {
        switch (androidKey) {
            case KeyEvent.KEYCODE_A: return 'A';
            case KeyEvent.KEYCODE_B: return 'B';
            case KeyEvent.KEYCODE_C: return 'C';
            case KeyEvent.KEYCODE_D: return 'D';
            case KeyEvent.KEYCODE_E: return 'E';
            case KeyEvent.KEYCODE_F: return 'F';
            case KeyEvent.KEYCODE_G: return 'G';
            case KeyEvent.KEYCODE_H: return 'H';
            case KeyEvent.KEYCODE_I: return 'I';
            case KeyEvent.KEYCODE_J: return 'J';
            case KeyEvent.KEYCODE_K: return 'K';
            case KeyEvent.KEYCODE_L: return 'L';
            case KeyEvent.KEYCODE_M: return 'M';
            case KeyEvent.KEYCODE_N: return 'N';
            case KeyEvent.KEYCODE_O: return 'O';
            case KeyEvent.KEYCODE_P: return 'P';
            case KeyEvent.KEYCODE_Q: return 'Q';
            case KeyEvent.KEYCODE_R: return 'R';
            case KeyEvent.KEYCODE_S: return 'S';
            case KeyEvent.KEYCODE_T: return 'T';
            case KeyEvent.KEYCODE_U: return 'U';
            case KeyEvent.KEYCODE_V: return 'V';
            case KeyEvent.KEYCODE_W: return 'W';
            case KeyEvent.KEYCODE_X: return 'X';
            case KeyEvent.KEYCODE_Y: return 'Y';
            case KeyEvent.KEYCODE_Z: return 'Z';
            case KeyEvent.KEYCODE_0: return '0';
            case KeyEvent.KEYCODE_1: return '1';
            case KeyEvent.KEYCODE_2: return '2';
            case KeyEvent.KEYCODE_3: return '3';
            case KeyEvent.KEYCODE_4: return '4';
            case KeyEvent.KEYCODE_5: return '5';
            case KeyEvent.KEYCODE_6: return '6';
            case KeyEvent.KEYCODE_7: return '7';
            case KeyEvent.KEYCODE_8: return '8';
            case KeyEvent.KEYCODE_9: return '9';
            case KeyEvent.KEYCODE_SPACE: return ' ';
            case KeyEvent.KEYCODE_ENTER: return Keyboard.VK_ENTER;
            case KeyEvent.KEYCODE_DEL: return Keyboard.VK_BACK_SPACE;
            case KeyEvent.KEYCODE_TAB: return Keyboard.VK_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Keyboard.VK_ESCAPE;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Keyboard.VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return Keyboard.VK_CONTROL;
            case KeyEvent.KEYCODE_DPAD_UP: return Keyboard.VK_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Keyboard.VK_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Keyboard.VK_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Keyboard.VK_RIGHT;
            case KeyEvent.KEYCODE_F1: return Keyboard.VK_F1;
            case KeyEvent.KEYCODE_F3: return Keyboard.VK_F3;
            case KeyEvent.KEYCODE_F5: return Keyboard.VK_F5;
            case KeyEvent.KEYCODE_F7: return Keyboard.VK_F7;
            case KeyEvent.KEYCODE_COMMA: return ',';
            case KeyEvent.KEYCODE_PERIOD: return '.';
            case KeyEvent.KEYCODE_SLASH: return '/';
            case KeyEvent.KEYCODE_SEMICOLON: return ';';
            case KeyEvent.KEYCODE_APOSTROPHE: return '\'';
            case KeyEvent.KEYCODE_MINUS: return '-';
            case KeyEvent.KEYCODE_EQUALS: return '=';
            case KeyEvent.KEYCODE_LEFT_BRACKET: return '[';
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return ']';
            case KeyEvent.KEYCODE_BACKSLASH: return '\\';
            case KeyEvent.KEYCODE_GRAVE: return '`';
            case KeyEvent.KEYCODE_MOVE_HOME: return Keyboard.VK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Keyboard.VK_END;
            case KeyEvent.KEYCODE_INSERT: return Keyboard.VK_INSERT;
            case KeyEvent.KEYCODE_CAPS_LOCK: return Keyboard.VK_CAPS_LOCK;
            default: return -1;
        }
    }

    private int getKeyLocation(KeyEvent event) {
        // Map Android key location to AWT-compatible location values
        if (event.getKeyCode() == KeyEvent.KEYCODE_CTRL_LEFT ||
            event.getKeyCode() == KeyEvent.KEYCODE_SHIFT_LEFT) {
            return Keyboard.KEY_LOCATION_LEFT;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_CTRL_RIGHT ||
            event.getKeyCode() == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            return Keyboard.KEY_LOCATION_RIGHT;
        }
        return 0;
    }

    // --- UI Controls ---

    private void toggleKeyboard() {
        keyboardVisible = !keyboardVisible;
        keyboardView.setVisibility(keyboardVisible ? View.VISIBLE : View.GONE);
        // Hide joystick when keyboard is visible
        if (keyboardVisible) {
            joystickView.setVisibility(View.GONE);
        } else if (joystickVisible) {
            joystickView.setVisibility(View.VISIBLE);
        }
    }

    private void toggleJoystick() {
        joystickVisible = !joystickVisible;
        if (!keyboardVisible) {
            joystickView.setVisibility(joystickVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                loadFile(uri);
            }
        }
    }

    private void loadFile(Uri uri) {
        Log.i("JaC64", "loadFile called with URI: " + uri);
        new Thread(() -> {
            try {
                // Copy URI content to a temp file
                InputStream is = getContentResolver().openInputStream(uri);
                // Query the actual display name from the content resolver
                String path = null;
                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) {
                            path = cursor.getString(nameIndex);
                        }
                    }
                }
                if (path == null) path = "file.prg";
                Log.i("JaC64", "Resolved filename: " + path);

                File tmpFile = new File(getCacheDir(), path);
                FileOutputStream fos = new FileOutputStream(tmpFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                is.close();

                // If zip, extract first C64 file from it
                if (path.toLowerCase().endsWith(".zip")) {
                    tmpFile = extractFromZip(tmpFile);
                    if (tmpFile == null) {
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "No .d64/.t64/.prg/.p00 found in zip", android.widget.Toast.LENGTH_LONG).show());
                        return;
                    }
                    Log.i("JaC64", "Extracted from zip: " + tmpFile.getName());
                }

                String name = tmpFile.getAbsolutePath().toLowerCase();
                Log.i("JaC64", "Temp file: " + tmpFile.getAbsolutePath() + " size: " + tmpFile.length());
                Log.i("JaC64", "Matching extension from: " + name);
                if (name.endsWith(".d64")) {
                    Log.i("JaC64", "Loading as D64 disk image");
                    cpu.reset();
                    Thread.sleep(200);
                    while (!screen.ready()) {
                        Thread.sleep(100);
                    }
                    reader.readDiskFromFile(tmpFile.getAbsolutePath());
                    cpu.enterText("LOAD\"*\",8,1~");
                    Log.i("JaC64", "D64 mounted and LOAD command sent");
                } else if (name.endsWith(".t64")) {
                    Log.i("JaC64", "Loading as T64 tape");
                    cpu.reset();
                    // Wait for reset to be processed (ready goes false)
                    Thread.sleep(200);
                    // Wait for kernal to be ready again
                    while (!screen.ready()) {
                        Thread.sleep(100);
                    }
                    Log.i("JaC64", "Screen ready after reset, loading tape file");
                    reader.readTapeFromFile(tmpFile.getAbsolutePath());
                    reader.readFile("*", -1);
                    Log.i("JaC64", "File loaded, calling runBasic");
                    cpu.runBasic();
                } else if (name.endsWith(".prg") || name.endsWith(".p00")) {
                    Log.i("JaC64", "Loading as PRG");
                    cpu.reset();
                    Thread.sleep(200);
                    while (!screen.ready()) {
                        Thread.sleep(100);
                    }
                    reader.readPGM(tmpFile.getAbsolutePath(), -1);
                    cpu.runBasic();
                    Log.i("JaC64", "PRG loaded and runBasic called");
                } else {
                    Log.w("JaC64", "Unknown file extension: " + name);
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("Failed to load file: " + e.getMessage())
                        .setPositiveButton("OK", null)
                        .show();
                });
            }
        }, "FileLoader").start();
    }

    private File extractFromZip(File zipFile) throws java.io.IOException {
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.FileInputStream(zipFile));
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String entryName = entry.getName().toLowerCase();
            if (entryName.endsWith(".d64") || entryName.endsWith(".t64")
                    || entryName.endsWith(".prg") || entryName.endsWith(".p00")) {
                File out = new File(getCacheDir(), entry.getName());
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                zis.close();
                return out;
            }
        }
        zis.close();
        return null;
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Reset");
        popup.getMenu().add(0, 2, 0, "Hard Reset");
        popup.getMenu().addSubMenu(0, 10, 0, "Color Set")
            .add(10, 11, 0, "Color Set 1 - JaC64 Original");
        // Add color sets directly since submenu in PopupMenu is limited
        popup.getMenu().add(0, 11, 1, "Color Set 1");
        popup.getMenu().add(0, 12, 2, "Color Set 2");
        popup.getMenu().add(0, 13, 3, "Color Set 3");
        popup.getMenu().add(0, 14, 4, "Color Set 4");
        popup.getMenu().add(0, 21, 5, "SID: ReSID 6581");
        popup.getMenu().add(0, 22, 6, "SID: ReSID 8580");
        popup.getMenu().add(0, 23, 7, "SID: JaC64 Original");
        popup.getMenu().add(0, 31, 8, "Joystick Port 1");
        popup.getMenu().add(0, 32, 9, "Joystick Port 2");
        popup.getMenu().add(0, 40, 10, warpEnabled ? "Warp Speed OFF" : "Warp Speed ON");
        popup.getMenu().add(0, 41, 11, touchPaddleEnabled ? "Touch Paddle OFF" : "Touch Paddle ON");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: cpu.reset(); return true;
                case 2: cpu.hardReset(); return true;
                case 11: screen.setColorSet(0); return true;
                case 12: screen.setColorSet(1); return true;
                case 13: screen.setColorSet(2); return true;
                case 14: screen.setColorSet(3); return true;
                case 21: screen.setSID(C64Screen.RESID_6581); return true;
                case 22: screen.setSID(C64Screen.RESID_8580); return true;
                case 23: screen.setSID(C64Screen.JACSID); return true;
                case 31: screen.setStick(true); return true;
                case 32: screen.setStick(false); return true;
                case 40:
                    warpEnabled = !warpEnabled;
                    screen.setFullSpeed(warpEnabled);
                    return true;
                case 41:
                    touchPaddleEnabled = !touchPaddleEnabled;
                    emulatorView.setTouchEnabled(touchPaddleEnabled);
                    // Hide joystick when paddle is active, show when not
                    joystickView.setVisibility(touchPaddleEnabled ? View.GONE : View.VISIBLE);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupFullscreen();
        }
    }
}
