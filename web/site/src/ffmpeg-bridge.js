// Exposed on window (not as ES module exports) so FfmpegBridge's @JSBody calls can reach it.

import { FFmpeg } from "@ffmpeg/ffmpeg";
import { toBlobURL } from "@ffmpeg/util";

let ffmpegInstance = null;

async function ffmpeg() {
  if (ffmpegInstance) return ffmpegInstance;
  const instance = new FFmpeg();
  // worker.js is inside the npm package, not served by Vite directly — blob it
  const workerURL = "https://cdn.jsdelivr.net/npm/@ffmpeg/ffmpeg@0.12.15/dist/esm/worker.js";
  await instance.load({
    coreURL: new URL("ffmpeg-core-esm.js", import.meta.url).href,  // ESM build: worker uses dynamic import(), not importScripts()
    wasmURL: new URL("ffmpeg-core-esm.wasm", import.meta.url).href,
    workerURL: await toBlobURL(workerURL, "text/javascript"),
  });
  ffmpegInstance = instance;
  return instance;
}

/** Converts one audio file's bytes (any ffmpeg-supported input codec) to 16-bit PCM wav bytes. */
async function convertToWav(bytes, sourceExt) {
  const ff = await ffmpeg();
  const inName = "in." + sourceExt;
  const outName = "out.wav";
  await ff.writeFile(inName, bytes);
  await ff.exec(["-i", inName, "-acodec", "pcm_s16le", outName]);
  const data = await ff.readFile(outName);
  await ff.deleteFile(inName);
  await ff.deleteFile(outName);
  return data;
}

/** Returns { frames: Uint8Array[], wav: Uint8Array } at the given fps. */
async function convertVideo(bytes, fps) {
  const ff = await ffmpeg();
  const inName = "in.m4v";
  await ff.writeFile(inName, bytes);

  await ff.exec(["-i", inName, "-vf", `fps=${fps}`, "-q:v", "3", "frame_%04d.jpg"]);
  const frames = [];
  for (let i = 1; ; i++) {
    const name = "frame_" + String(i).padStart(4, "0") + ".jpg";
    try {
      const data = await ff.readFile(name);
      frames.push(data);
      await ff.deleteFile(name);
    } catch (e) {
      break;   // ffmpeg stopped numbering here - no more frames
    }
  }

  await ff.exec(["-i", inName, "-vn", "-acodec", "pcm_s16le", "out.wav"]);
  const wav = await ff.readFile("out.wav");
  await ff.deleteFile(inName);
  await ff.deleteFile("out.wav");

  return { frames, wav };
}

window.heistFfmpegBridge = { convertToWav, convertVideo };
