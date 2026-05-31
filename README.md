# JaC64 - 100% Java C64 Emulation

JaC64 is a pure Java Commodore 64 emulator created by Joakim Eriksson in 2007.
It can be run as a stand-alone desktop application or as an Android app.

**Author:** Joakim Eriksson, [jac64.com](http://www.jac64.com), [dreamfabric.com](http://www.dreamfabric.com)

## What's New

- **Cycle-accurate VIC-II** — the graphics chip was rewritten to follow VICE's
  per-cycle pipeline (the `VicDrawCycle` / `VicSpritePipeline` port). FLI,
  sprite-crunch, border tricks, lightpen and the FLI-bug prefetch now render
  faithfully — **118 of 138 VICE test programs are pixel-perfect**, and the
  6510 CPU is byte-exact with VICE x64sc at the cycle level.
- **MCP server** — drive the emulator from an AI agent / MCP client
  (`JaC64MCP`): peek/poke memory, load files, type text, read the screen,
  take screenshots, inspect CPU state, and more.
- **Headless test harness + VICE comparison tooling** — `TestRaster` with
  deterministic cycle-anchored capture, plus a 3-way REF/JaC/VICE pixel-diff
  pipeline used to drive the accuracy work.
- **Android port** — full C64 emulation on Android with virtual keyboard and joystick
- **Gradle build system** — modern build for both desktop and Android
- **Refactored rendering** — separated platform-independent emulation from UI code

## Acknowledgements

This version incorporates bug fixes and improvements from
[Cat's Eye Technologies' fork](https://github.com/catseye/JaC64), including:
- Applet restart fixes
- More robust joystick handling
- Improved audio error handling
- Makefile cleanup and code refactoring

## Building

### Desktop (Gradle)

The desktop build uses Gradle. To build and run:

```sh
./gradlew build
./gradlew run
```

To build a JAR file:

```sh
./gradlew jar
```

The JAR includes ROM and sound files and can be run standalone with `java -jar build/libs/JaC64.jar`.

You can also still build with the legacy Makefile:

```sh
make
java C64Test
```

### Android

An Android version of JaC64 is available in the `android/` directory.
It reuses the core emulation engine (CPU, VIC-II, SID, CIA, 1541)
while replacing the AWT/Swing UI with Android-native components:

- SurfaceView-based screen rendering
- AudioTrack-based SID audio output
- Virtual on-screen keyboard and joystick overlays
- File picker for loading `.d64`, `.t64`, `.prg`, and `.p00` files

To build the Android app:

```sh
cd android
./gradlew assembleDebug
```

The debug APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

**Note:** C64 ROM files (kernal, basic, chargen, 1541) must be placed in
`android/app/src/main/assets/roms/` before building. These are not
included in the repository due to copyright.

## Running

To run a test application (after building):

```sh
java C64Test
```

Example usage of JaC64 is in the `index_jac64.html` files, showing
simple usage of JaC64 and describing how to use them.

## Accuracy

JaC64's VIC-II and 6510 are validated cycle-by-cycle against
[VICE](https://vice-emu.sourceforge.io/) x64sc, the reference C64 emulator,
using the VICE test-program suite:

- **118 / 138 VICE testprogs render pixel-perfect** vs VICE.
- The **6510 CPU is byte-exact** with VICE at the cycle level.
- Faithful handling of FLI, sprite crunch / multiplexing, open side/top/bottom
  borders, lightpen, `$D018`/bank/`$D016` splits, and the FLI-bug prefetch — so
  demos such as Krestage 3 render correctly.

Most VIC-II behaviours are gated behind `-Djac64.*` flags (default-on) so each
fix can be A/B compared in isolation.

## MCP server

`JaC64MCP` exposes the emulator over the [Model Context Protocol](https://modelcontextprotocol.io)
so an AI agent / MCP client can drive a live C64. Build the JAR (`./gradlew jar`)
and point your MCP client at it:

```json
{
  "mcpServers": {
    "jac64": {
      "type": "stdio",
      "command": "java",
      "args": ["-cp", "build/libs/JaC64.jar", "JaC64MCP"]
    }
  }
}
```

Available tools include: `peek` / `poke`, `load_file`, `type_text`,
`key_press`, `joystick`, `read_screen`, `screenshot`, `cpu_state`, `reset`,
`set_speed`, `set_sid`, `swap_disk`, `list_directory`, and `iec_trace`.
After rebuilding the JAR, reconnect the MCP client so it loads the new build.

## Testing & VICE comparison

`TestRaster` is a headless harness that boots the emulator, loads a
`.d64`/`.prg`, and captures screenshots / traces — no UI needed:

```sh
# warp to a fixed emulated cycle and snapshot (deterministic, repeatable)
java -Djac64.warp=true -Djac64.captureAtCycle=7100000 \
     -cp build/libs/JaC64.jar TestRaster path/to/test.prg

# capture N consecutive frames (one PAL frame apart) to flip through
java -Djac64.warp=true -Djac64.captureAtCycle=168000000 -Djac64.captureBurst=24 \
     -cp build/libs/JaC64.jar TestRaster path/to/demo.prg
```

The `tools/vice-compare/` scripts (`survey_drift.sh`, `three_way_diff.sh`,
`png_cell_diff.py`) run the VICE test suite through both emulators and tabulate
the per-cell differences (REF vs JaC vs VICE) that drive the accuracy work.

## Links

- Website: http://www.jac64.com
- Source code: http://sourceforge.net/projects/jac64/

## Contributors

- **[2002] Jan Blok** - reimplementation of memory model and fixing CPU bugs
- **[2006] Jörg Jahnke** - help with refactoring of CPU class
- **[2006] ByteMaster of Cache64.com** - extensive testing and bug reporting
