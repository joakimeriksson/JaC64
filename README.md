# JaC64 - 100% Java C64 Emulation

JaC64 is a pure Java Commodore 64 emulator that can be run in any modern Java
enabled web-browser. It can also be run as a stand-alone C64 emulator.

**Originator and main developer:** Joakim Eriksson, [jac64.com](http://www.jac64.com), [dreamfabric.com](http://www.dreamfabric.com)

## Fork History

This is Cat's Eye Technologies' fork of the original JaC64 distribution.

This fork was made from what was the tip revision of the JaC64 sources
on Sourceforge, [revision 140](http://sourceforge.net/p/jac64/code/HEAD/tree/).

Several bug fixes and minor enhancements have been applied. The
full details can be found in the [git log](https://github.com/catseye/JaC64/commits/master).

Some highlights are:
- **Restartable!** There were problems before with applet start/stop
  (e.g. reloading a web page that has a JaC64 applet on it)
- More robust handling of joystick (does not initially "stick" in the
  top-left direction at the start of some games)
- More robust handling of broken audio support
- Easier building (cleaned up Makefile, build warnings)
- Refactored some code

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
