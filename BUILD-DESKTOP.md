# Building the desktop port

## Requirements

- Java 17+
- Your decrypted copy of *The Heist* v1.1.2 (`.ipa`)
- `ffmpeg` on `PATH` (audio/video conversion on first import)

## Build

```
./gradlew :desktop:jar
```

Output: `desktop/build/libs/theheist.jar`

## Run

**First time** — imports the game files from the IPA into `./assets`:

```
java -jar desktop/build/libs/theheist.jar --import "/path/to/iPhone - The Heist.ipa"
```

**Afterwards:**

```
java -jar desktop/build/libs/theheist.jar
```

Or via Gradle (skips the separate build step):

```
./gradlew :desktop:run --args='--import "/path/to/iPhone - The Heist.ipa"'
```

## Options

| Flag | Default | Description |
|------|---------|-------------|
| `--import <ipa>` | — | Path to the IPA; imports on first run |
| `--assets <dir>` | `./assets` | Use a different game-file folder |
| `--data <dir>` | system default | Keep the save file somewhere else |

`Esc` acts as the back key. Press `D` on the hub screen to open the developer menu.
