# The Heist: one game, many ports

The whole game and engine live once, in `core`. Every platform is a thin module that supplies drawing, sound, storage,
files and input. To change the game you change `core`; every port gets the change.

    Game/
      core/      the game and the 2D engine: pure Java 8, no platform code (no AWT, no Android)
      tools/     build-time tool that unpacks YOUR copy of the game (the IPA) into the game files
      desktop/   desktop port: Java2D drawing, javax.sound, a window
      android/   Android port: Canvas drawing, AudioTrack sound, the APK's assets
      web/       browser port: Canvas2D drawing, Web Audio, OPFS storage, TeaVM WasmGC compile target

No game files are part of this repository. You supply your own copy of the iPhone game
(`iPhone - The Heist.ipa`, version 1.1.2) when you build. `ffmpeg` must be on the PATH (sound and video conversion).

## What a port provides

`core` talks to the platform only through these interfaces; a port implements them and registers its `Platform` with
`Host.set(...)` before the first scene is made.

| Area    | Interface                                   | What the port does                                              |
|---------|---------------------------------------------|-----------------------------------------------------------------|
| Drawing | `heist.engine.Gfx`, `Image`, `Fnt`           | paints through its own canvas / Graphics2D; owns pictures, fonts |
| Audio   | `heist.platform.AudioBackend`, `PcmChannel`  | plays 16 bit PCM; mixing, fades and music rules stay in `core`  |
| Input   | `heist.engine.InputListener`, `Key`          | calls `touchDown/Move/Up(viewX, viewY)` and `key(Key.BACK)`      |
| Files   | `heist.platform.AssetSource`                 | opens the game files by name                                    |
| Storage | `heist.platform.Storage`                     | keeps the player's progress (the original's NSUserDefaults)     |
| Rest    | `heist.platform.Platform`                    | creates/decodes pictures, loads fonts, logs                     |

A port's loop is: `game.update(dt)`, then `game.render(gfx, viewWidth, viewHeight)` (the engine letterboxes the 320x480
screen into the view).

## Build

    ./gradlew :desktop:jar                                                    # desktop/build/libs/theheist.jar
    ./gradlew :android:assembleDebug -PheistIpa="/path/to/iPhone - The Heist.ipa"
    ./gradlew :web:build                                                      # web/site/dist/
    ./gradlew build -PheistIpa="/path/to/iPhone - The Heist.ipa"              # everything in one go

The IPA path can also be given as `heistIpa=...` in `local.properties` or as the `HEIST_IPA` environment variable.
`local.properties` also holds `sdk.dir=...` for the Android SDK (platform 35). Gradle needs Java 17+; the code itself is Java 8.

### Desktop

    java -jar desktop/build/libs/theheist.jar --import "/path/to/iPhone - The Heist.ipa"    # first time: unpacks to ./assets
    java -jar desktop/build/libs/theheist.jar                                               # afterwards

`--assets dir` uses another game-file folder, `--data dir` keeps the save somewhere else. Escape is the back key.
Or `./gradlew :desktop:run --args='--import "/path/to/iPhone - The Heist.ipa"'`.

### Android

    adb install -r android/build/outputs/apk/debug/android-debug.apk

The build unpacks the IPA with `:tools` and packages the result as APK assets, so the APK contains the game's files; keep it
to yourself. The app keeps the original identity (id `com.taptaptap.TheHeist`, name "The Heist", the game's own icon).
It behaves like an iPod touch: portrait, the 320x480 screen letterboxed, one finger.

### Web

    ./gradlew :web:build          # compiles Java → WasmGC via TeaVM, then runs Vite; output goes to web/site/dist/
    cd web/site && npm run dev    # dev server with hot reload at http://localhost:5173/

The web port runs entirely in the browser. No game files are bundled — the player uploads their own IPA on first visit;
ffmpeg.wasm converts the audio/video. Progress is kept in the browser's Origin Private File System (OPFS).
The port behaves like an iPod touch (no proximity sensor, no camera). `ffmpeg` on PATH is not needed for the web build.

## Developer tools

The desktop, Android, and web ports all expose a developer menu. On desktop press `D`; on Android it is available
in debug builds, opened with a 3-finger tap; on web it is enabled by calling `setDebugEnabled(true)` from the page
JavaScript before `startGame()`.

### Desktop tools

They live in `desktop` and use `core` only through small public hooks, so no other port carries them.

- **Developer menu** (arrow keys + Enter or the mouse, `Esc` closes): replay the big animations, set the safe room to a
  scene state, trigger any call, level up or down, reset the puzzles. A replay runs on a rebuilt room and puts the save back
  afterwards (`Esc` aborts and restores at once). It is an `heist.engine.Overlay` set with `game.setOverlay(...)`.
- **Layer window** (menu entry "Layer window"): lists every layer of the current frame by group and asset, switches layers off
  and shows them pulled apart in 3D. It works by wrapping the drawing context (`TraceGfx`, a `Gfx` decorator); the game
  names its layers through the no-op hooks `Gfx.group(...)` and `Gfx.beginLayer(...)`.
- **Command line tools** (`heist.desktop.tools`, run with `java -cp desktop/build/libs/theheist.jar <class> <assetsDir> ...`):
  `Screenshot` (renders a puzzle level or the safe room to a PNG), `LevelVerifier` (solves and replays every level),
  `NibDump` and `NibTree` (inspect the original's UI files).

## Adding a port

1. Add a module (`include ':myport'` in `settings.gradle`) with `implementation project(':core')`.
2. Implement `Platform` (and `Gfx`, `Image`, `Fnt`, `AudioBackend`, `AssetSource`, `Storage`).
3. Register it with `Host.set(...)`, create `Game`, `Nav.init(game)`, `HubScene`, `Nav.setHub(hub)`, `game.setScene(hub)`,
   forward input to the game, and run the update/render loop.

The desktop module (`desktop/src/main/java/heist/desktop`) is the smallest complete example.

## Online features

Game Center, social sharing, the review button and the web prize claim are intentionally inert, as in the earlier ports.
