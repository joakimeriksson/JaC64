# JaC64 - 100% Java C64 Emulation

JaC64 is a pure Java Commodore 64 emulator created by Joakim Eriksson in 2007.
It can be run as a stand-alone desktop application or as an Android app.

**Author:** Joakim Eriksson, [jac64.com](http://www.jac64.com), [dreamfabric.com](http://www.dreamfabric.com)

## What's New

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

## Links

- Website: http://www.jac64.com
- Source code: http://sourceforge.net/projects/jac64/

## Contributors

- **[2002] Jan Blok** - reimplementation of memory model and fixing CPU bugs
- **[2006] Jörg Jahnke** - help with refactoring of CPU class
- **[2006] ByteMaster of Cache64.com** - extensive testing and bug reporting
