<div align="center">
  <img src="web/site/public/icon-192.png" width="120" alt="The Heist icon" />
  <h1>The Heist</h1>
  <p>A fan-made multi-platform port of Tap Tap Tap's <em>The Heist</em> (iPhone, 2011).</p>
</div>

---

> **You need your own copy of the game.**
> This repository contains no game files. A decrypted `.ipa` of *The Heist* v1.1.2 is required to build and play.

## Ports

| Port | Platform | Status |
|------|----------|--------|
| Desktop | Windows / macOS / Linux (Java 2D) | ✅ Playable |
| Android | Android 2.3+ (API 9+) | ✅ Playable |
| Web | Any modern browser (WebAssembly) | ✅ Playable — [try it](https://werwolf2303.github.io/TheHeist/) |

## How it works

The entire game and engine live in `:core` — pure Java 8, no platform code. Each port is a thin module that wires in drawing, audio, storage and input through a small set of interfaces. Changing `:core` updates every port at once.

See [docs.md](docs.md) for the full architecture and interface reference.

## Building

- [BUILD-DESKTOP.md](BUILD-DESKTOP.md) — desktop JAR (Windows / macOS / Linux)
- [BUILD-ANDROID.md](BUILD-ANDROID.md) — debug APK
- [BUILD-WEB.md](BUILD-WEB.md) — WebAssembly bundle (Vite + TeaVM)
- [BUILD-TOOLS.md](BUILD-TOOLS.md) — command-line dev tools

All ports require **Java 17+** and **Gradle** (via the wrapper). The code itself targets Java 8.
