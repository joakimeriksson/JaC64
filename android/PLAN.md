# JaC64 Android Port - Implementation Plan

## Overview

Port the JaC64 C64 emulator from Java AWT/Swing to Android, reusing the core
emulation engine and replacing only the platform-specific layers.

## Architecture

```
┌─────────────────────────────────────────────────┐
│              Android UI Layer                    │
│  MainActivity, EmulatorSurfaceView,             │
│  VirtualKeyboardView, VirtualJoystickView       │
├─────────────────────────────────────────────────┤
│           Platform Abstraction Layer             │
│  AudioDriverAndroid, AndroidLoader,             │
│  Modified C64Screen (no AWT), Keyboard (no AWT) │
├─────────────────────────────────────────────────┤
│          Core Emulation (unchanged)              │
│  CPU, MOS6510Core, CIA, SID/ReSID, SIDMixer,   │
│  C1541Emu, ExtChip, EventQueue, C64Reader, etc. │
└─────────────────────────────────────────────────┘
```

## Source Strategy

The Gradle build uses `srcDirs` to compile core emulation files directly from
the parent JaC64 directory, avoiding duplication. Only files with AWT
dependencies are replaced with Android versions.

### Files compiled from parent (unchanged)
- `com/dreamfabric/jac64/CPU.java` - 6510 CPU + C64 memory map
- `com/dreamfabric/jac64/MOS6510Core.java` - 6510 base CPU
- `com/dreamfabric/jac64/MOS6510Ops.java` - Opcode definitions
- `com/dreamfabric/jac64/M6510Ops.java` - Instruction implementations
- `com/dreamfabric/jac64/CIA.java` - CIA chips (timers, I/O)
- `com/dreamfabric/jac64/SIDChip.java` - JaC64 SID emulation
- `com/dreamfabric/jac64/SIDVoice6581.java` - SID voice
- `com/dreamfabric/jac64/SIDMixer.java` - Audio mixer + effects
- `com/dreamfabric/jac64/RESIDChip.java` - ReSID wrapper
- `com/dreamfabric/jac64/AudioDriver.java` - Abstract audio interface
- `com/dreamfabric/jac64/ExtChip.java` - Chip base class + interrupts
- `com/dreamfabric/jac64/C64Reader.java` - Disk/tape file reader
- `com/dreamfabric/jac64/C1541*.java` - 1541 disk drive emulation
- `com/dreamfabric/jac64/Loader.java` - Abstract resource loader
- `com/dreamfabric/jac64/VICConstants.java` - Color palettes + timing
- `com/dreamfabric/jac64/EventQueue.java` - Event scheduler
- `com/dreamfabric/jac64/TimeEvent.java` - Timed events
- `com/dreamfabric/jac64/IMonitor.java` - Debug monitor interface
- `com/dreamfabric/jac64/DefaultIMon.java` - Default monitor
- `com/dreamfabric/jac64/Observer.java` - Observer interface
- `com/dreamfabric/jac64/Sprite.java` - If separate file
- `com/dreamfabric/jac64/DirEntry.java` - Directory entry
- `com/dreamfabric/jac64/TFE_CS8900.java` - Network chip
- `com/dreamfabric/jac64/Hex.java` - Hex utilities
- `com/dreamfabric/jac64/PatchListener.java` - ROM patch interface
- `com/dreamfabric/c64utils/*.java` - Debugger, Assembler, etc.
- `resid/*.java` - ReSID SID emulation library

### Files excluded (AWT/Swing dependent)
- `JaC64.java` - Swing desktop application
- `C64Applet.java` - Java Applet
- `C64Test.java` - Swing test application
- `com/dreamfabric/jac64/C64Canvas.java` - Swing JPanel
- `com/dreamfabric/jac64/AudioDriverSE.java` - javax.sound
- `com/dreamfabric/jac64/SELoader.java` - URL-based loader
- `com/dreamfabric/gui/*.java` - Swing widgets

### Files replaced with Android versions
- `com/dreamfabric/jac64/C64Screen.java` - VIC-II + I/O hub
  - Removes: AWT Color, Image, Graphics, MemoryImageSource, AudioClip,
    MouseListener, JPanel references
  - Keeps: All VIC-II emulation logic, scan line rendering, sprite handling,
    I/O register read/write, CIA/SID/keyboard integration
  - Adds: ScreenRefreshListener callback, injectable AudioDriver,
    public pixel buffer access
- `com/dreamfabric/jac64/Keyboard.java` - Keyboard/joystick
  - Removes: java.awt.event.KeyEvent references
  - Keeps: Key matrix mapping, joystick state management
  - Adds: Local VK_* constants, int-based key methods for Android

### New Android files
- `com/dreamfabric/jac64/android/MainActivity.java`
  - Landscape-only Activity
  - Manages emulator lifecycle (start/stop/pause)
  - File picker for .d64/.prg/.t64 loading
  - Settings menu (color set, SID type, joystick port)
- `com/dreamfabric/jac64/android/EmulatorSurfaceView.java`
  - SurfaceView with dedicated render thread
  - Reads pixel buffer from C64Screen.mem[] at ~50Hz
  - Draws via Bitmap.setPixels() -> Canvas.drawBitmap()
  - Handles scaling (fit to screen with aspect ratio)
- `com/dreamfabric/jac64/android/AudioDriverAndroid.java`
  - Extends AudioDriver
  - Uses Android AudioTrack in streaming mode
  - 44kHz, 16-bit mono (matching existing SID output)
- `com/dreamfabric/jac64/android/AndroidLoader.java`
  - Extends Loader
  - Loads ROMs from Android assets folder
  - Uses AssetManager.open() for resource streams
- `com/dreamfabric/jac64/android/VirtualKeyboardView.java`
  - Custom View overlay with C64 keyboard layout
  - Touch-based key press/release
  - Show/hide toggle
  - Maps touch events to Keyboard.keyPressed()/keyReleased()
- `com/dreamfabric/jac64/android/VirtualJoystickView.java`
  - On-screen joystick with directional pad + fire button
  - Touch-based with visual feedback
  - Maps to Keyboard joystick state

## Key Design Decisions

### Display
- Use `SurfaceView` (not TextureView) for lower latency
- Dedicated render thread polls `C64Screen.mem[]` pixel buffer
- `Bitmap.setPixels(mem, 0, 384, 0, 0, 384, 284)` for pixel transfer
- Scale with `Canvas.drawBitmap()` using filter for smooth scaling
- Maintain 384:284 aspect ratio

### Audio
- `AudioTrack` in `MODE_STREAM` with 44kHz, 16-bit, mono
- Buffer size: ~22000 bytes (matching desktop version)
- Write from SIDMixer thread (same as desktop)
- Use `AudioTrack.getPlaybackHeadPosition()` for timing sync

### Input
- Virtual keyboard: 10-row C64 layout drawn as touch regions
- Virtual joystick: D-pad circle + fire button
- Optional hardware keyboard support (USB/Bluetooth)
- Hardware keyboard maps via Android KeyEvent -> C64 key codes

### ROM Loading
- ROMs placed in `assets/roms/` directory
- User must provide: kernal.c64, basic.c64, chargen.c64
- These are copyrighted and not included in the repository

### File Loading
- Android Storage Access Framework for file picking
- Support .d64, .t64, .prg, .p00 formats
- Files loaded via C64Reader (unchanged from desktop)

### Threading Model
```
Main Thread (UI)     - Activity lifecycle, touch input
CPU Thread           - CPU.start() -> emulation loop
Render Thread        - SurfaceView frame drawing at ~50Hz
Audio Thread         - SIDMixer -> AudioTrack writes
1541 Thread          - Disk drive CPU (if EMULATE_1541)
```

## Build Configuration

- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34 (Android 14)
- **Build Tool**: Gradle with Android Gradle Plugin
- **Language**: Java (matching existing codebase)
- **No external dependencies** (pure Android SDK)

## Implementation Phases

### Phase 1: Core (MVP)
1. Set up Android Gradle project with srcDirs configuration
2. Create modified C64Screen.java (remove AWT)
3. Create modified Keyboard.java (remove AWT)
4. Create AudioDriverAndroid
5. Create AndroidLoader
6. Create EmulatorSurfaceView
7. Create MainActivity with basic emulator startup
8. Test: C64 boots to BASIC prompt with display and sound

### Phase 2: Input
1. Create VirtualKeyboardView with full C64 layout
2. Create VirtualJoystickView
3. Add hardware keyboard support
4. Test: Type BASIC commands, play simple games

### Phase 3: File Loading
1. Add file picker (Storage Access Framework)
2. Wire up C64Reader for .d64/.prg loading
3. Add auto-start capability
4. Test: Load and run games from .d64 disk images

### Phase 4: Polish
1. Settings menu (color set, SID type, joystick port)
2. Save/restore emulator state
3. App icon and splash screen
4. Performance optimization if needed
5. Fullscreen immersive mode

## Risk Areas

- **Performance**: The emulation loop is cycle-accurate and runs tight.
  Modern Android devices should handle it easily, but the render thread
  needs to avoid GC pressure (pre-allocate Bitmap, avoid object creation
  in hot path).
- **Audio latency**: Android audio latency varies by device. May need
  tunable buffer size.
- **C64Screen modifications**: The file is ~2000 lines with AWT mixed in.
  Changes must be surgical to preserve emulation accuracy.
- **ROM copyright**: C64 ROMs are copyrighted by Cloanto/whoever owns
  the IP now. Users must supply their own ROM files.
