package heist.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import heist.platform.AudioBackend;
import heist.platform.PcmChannel;

/** Sound through AudioTrack: short sounds in a static track (which plays however short it is), long ones streamed. */
final class AndroidAudio implements AudioBackend {
    public PcmChannel open(short[] pcm, int channels, int rate, boolean loop, float volume, boolean startPaused) {
        return new TrackChannel(pcm, channels, rate, loop, volume, startPaused);
    }

    private static final class TrackChannel implements PcmChannel, Runnable {
        /** Sounds up to this many bytes are loaded into a static track. */
        private static final int STATIC_LIMIT = 2_000_000;

        private final short[] data;
        private final int channels, rate;
        private final boolean loop, isStatic;
        private final AudioTrack track;
        private volatile boolean stopRequested, running = true, paused;

        TrackChannel(short[] data, int channels, int rate, boolean loop, float volume, boolean startPaused) {
            this.data = data;
            this.channels = channels;
            this.rate = rate;
            this.loop = loop;
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int bytes = data.length * 2;
            isStatic = bytes <= STATIC_LIMIT;
            int buffer;
            if (isStatic) {
                buffer = Math.max(bytes, 4);
            } else {
                int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                buffer = Math.max(min, 8192) * 2;
            }
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask,
                    AudioFormat.ENCODING_PCM_16BIT, buffer,
                    isStatic ? AudioTrack.MODE_STATIC : AudioTrack.MODE_STREAM);
            if (isStatic) {
                track.write(data, 0, data.length);
                if (loop) track.setLoopPoints(0, data.length / channels, -1);
            }
            setTrackVolume(track, volume);
            paused = startPaused;
            if (!startPaused) track.play();
            Thread t = new Thread(this, "audio");
            t.setDaemon(true);
            t.start();
        }

        public void setVolume(float v) {
            try { setTrackVolume(track, v); } catch (IllegalStateException ignored) { }
        }

        @SuppressWarnings("deprecation")
        private static void setTrackVolume(AudioTrack t, float v) {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                t.setVolume(v);
            } else {
                t.setStereoVolume(v, v);
            }
        }

        public void pause() {
            paused = true;
            try { track.pause(); } catch (RuntimeException ignored) { }
        }

        public void resume() {
            paused = false;
            try { track.play(); } catch (RuntimeException ignored) { }
        }

        public void stop() { stopRequested = true; }

        public boolean isFinished() { return stopRequested || !running; }

        public void run() {
            try {
                if (isStatic) runStatic(); else runStream();
            } catch (RuntimeException e) {
                // track went away; nothing more to play
            } finally {
                running = false;
                try { track.pause(); track.flush(); } catch (RuntimeException ignored) { }
                track.release();
            }
        }

        /** Static mode plays by itself; just wait for its end (or stop) before releasing. */
        private void runStatic() {
            long frames = data.length / channels;
            long deadline = System.nanoTime() + (long) ((frames / (double) rate + 1.0) * 1e9);
            while (!stopRequested) {
                if (!loop && (track.getPlaybackHeadPosition() >= frames || System.nanoTime() > deadline)) break;
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
        }

        private void runStream() {
            int pos = 0, chunk = 2048 * channels;
            long written = 0;
            while (!stopRequested) {
                if (paused) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                if (pos >= data.length) {
                    if (!loop) break;
                    pos = 0;
                }
                int n = Math.min(chunk, data.length - pos);
                int w = android.os.Build.VERSION.SDK_INT >= 21
                        ? track.write(data, pos, n, AudioTrack.WRITE_NON_BLOCKING)
                        : track.write(data, pos, n);
                if (w < 0) break;
                if (w == 0) {
                    try { Thread.sleep(4); } catch (InterruptedException e) { break; }
                    continue;
                }
                pos += w;
                written += w;
            }
            if (!stopRequested && !loop) {
                // let what is buffered finish playing
                long frames = written / channels;
                long deadline = System.nanoTime() + (long) ((frames / (double) rate + 1.0) * 1e9);
                while (!stopRequested && track.getPlaybackHeadPosition() < frames && System.nanoTime() < deadline) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }
            }
        }
    }
}
