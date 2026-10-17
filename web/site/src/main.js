// Set to true to enable the in-game developer menu (press D while on the hub screen).
window.HEIST_DEBUG = false;

if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register(import.meta.env.BASE_URL + "sw.js");
}

import "./ffmpeg-bridge.js";
import "../teavm-dist/web.wasm-runtime.js";

const playStep      = document.getElementById("play-step");
const playButton    = document.getElementById("play-button");
const uploadStep    = document.getElementById("upload-step");
const fileInput     = document.getElementById("file-input");
const chosenName    = document.getElementById("chosen-name");
const progressBar   = document.getElementById("progress-bar");
const progressFill  = document.getElementById("progress-fill");
const progressText  = document.getElementById("progress-text");
const errorText     = document.getElementById("error-text");
const screen        = document.getElementById("screen");
const canvasHolder  = document.getElementById("game-canvas-holder");

document.getElementById("file-input-trigger").addEventListener("click", () => fileInput.click());

function showProgress(phase, fraction) {
  progressBar.style.display = "flex";
  progressText.textContent = phase;
  if (fraction > 0) progressFill.style.width = Math.round(fraction * 100) + "%";
}

function showError(message) {
  errorText.style.display = "block";
  errorText.textContent = message;
  progressBar.style.display = "none";
  uploadStep.style.display = "flex";
  fileInput.disabled = false;
}

function revealGame() {
  screen.style.display = "none";
  canvasHolder.style.display = "flex";
  const canvas = document.querySelector("body > canvas");
  if (canvas) canvasHolder.appendChild(canvas);
}

async function boot() {
  const teavm = await TeaVM.wasmGC.load(import.meta.env.BASE_URL + "teavm-dist/web.wasm");
  const exports = teavm.exports;
  exports.main([]);

  function onPlay() {
    exports.onPlayButtonClicked();
    playStep.style.display = "none";
    progressText.textContent = "Checking for a saved game...";
    progressBar.style.display = "flex";

    exports.checkExistingGame(
      () => {
        progressBar.style.display = "none";
        progressText.textContent = "";
        revealGame();
        exports.setDebugEnabled(window.HEIST_DEBUG);
        exports.startGame();
      },
      () => {
        progressBar.style.display = "none";
        progressText.textContent = "";
        uploadStep.style.display = "flex";
      }
    );
  }

  playButton.addEventListener("click", onPlay);

  // Skip the play button when running as an installed PWA.
  if (window.matchMedia("(display-mode: standalone)").matches ||
      window.matchMedia("(display-mode: fullscreen)").matches ||
      window.navigator.standalone === true) {
    document.addEventListener("click", function autoPlay() {
      document.removeEventListener("click", autoPlay);
      onPlay();
    }, { once: true });
  }

  fileInput.addEventListener("change", async () => {
    const file = fileInput.files[0];
    if (!file) return;

    fileInput.disabled = true;
    uploadStep.style.display = "none";
    errorText.style.display = "none";

    const buffer = await file.arrayBuffer();
    const bytes = new Uint8Array(buffer);

    exports.startImport(
      bytes,
      (phase, fraction) => showProgress(phase, fraction),
      () => {
        revealGame();
        exports.setDebugEnabled(window.HEIST_DEBUG);
      },
      (message) => showError(message)
    );
  });
}

boot().catch((e) => {
  showError("Could not start the game: " + e);
  console.error(e);
});
