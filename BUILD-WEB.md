# Building the web port

## Requirements

- Java 17+
- Node.js 18+

No game files are needed at build time — the player uploads their own IPA in the browser.

## Build

```
./gradlew :web:build
```

This compiles `:core` to WebAssembly via TeaVM, then runs the Vite bundler.

Output: `web/site/dist/`

## Dev server

```
./gradlew :web:build       # compile Java → wasm first
cd web/site
npm run dev                # live-reloading dev server at http://localhost:5173/
```

## How it works

The player visits the page, clicks Play, and uploads their own decrypted `.ipa`. The browser converts the audio and video using `ffmpeg.wasm` (no server involved). Game progress is stored in the browser's Origin Private File System (OPFS).

The port behaves like an iPod touch (portrait, 320×480 letterboxed, touch input). There is no proximity sensor or camera. `ffmpeg` on PATH is not needed for the build.

## Deployment

The GitHub Actions workflow in `.github/workflows/deploy-web.yml` builds and deploys `web/site/dist/` to the `gh-pages` branch automatically on every push to `master` that touches `core/`, `tools/` or `web/`. It can also be triggered manually from the Actions tab.
