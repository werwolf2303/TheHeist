# Building and using the dev tools

The tools live in `desktop` and depend only on `:core`. Build the desktop JAR first:

```
./gradlew :desktop:jar
```

Then run any tool with:

```
java -cp desktop/build/libs/theheist.jar <class> [args...]
```

---

## Screenshot

Renders a puzzle level or a hub state to a PNG headlessly.

```
heist.desktop.tools.Screenshot <assetsDir> <PuzzleName> <level> <out.png> [scale]
heist.desktop.tools.Screenshot <assetsDir> Hub <script> <out.png> [scale]
```

**Puzzle names:** `Sokoban`, `Fifteen`, `Sudoku`, `Gridlock`

**Script** (comma-separated steps): `wait:<s>`, `shot:<file>`, `tap:<x>:<y>`, `press:<x>:<y>`, `move:<x>:<y>`, `release:<x>:<y>`, `key:<KEY>`, `open:<0-3>`, `prefs`, `points:<n>`

Default scale is 2.

---

## LevelVerifier

Solves every puzzle level (BFS for Sokoban, replay for the rest) and checks that each one registers as complete.

```
heist.desktop.tools.LevelVerifier <assetsDir>
```

---

## NibDump

Prints every object in a compiled `.nib` file (NIBArchive format) for inspection.

```
heist.desktop.tools.NibDump <file.nib | folder>
```

Accepts a single file or a folder; if a folder is given, all files in it are dumped.

---

## NibTree

Prints the decoded view hierarchy, outlets and actions of a `.nib`.

```
heist.desktop.tools.NibTree <file.nib | folder>
```

Same file/folder semantics as NibDump.

---

## IPA Importer (`:tools`)

Extracts game resources from the IPA: decrushes PNGs, converts audio to WAV via `ffmpeg`, and extracts the Sokoban level table from the Mach-O binary.

```
./gradlew :tools:classes
java -cp tools/build/classes/java/main heist.tools.IpaImporter "/path/to/iPhone - The Heist.ipa" <outputDir>
```

Exit codes: `0` success · `1` fatal error · `3` partial failure (some files failed).
