package heist.desktop;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;

import heist.platform.AudioBackend;
import heist.platform.PcmChannel;

/** Sound through javax.sound: every channel is a Clip holding the whole sound in memory. */
final class ClipAudio implements AudioBackend {
    public PcmChannel open(short[] pcm, int channels, int rate, boolean loop, float volume, boolean startPaused) {
        try {
            return new ClipChannel(pcm, channels, rate, loop, volume, startPaused);
        } catch (Exception e) {
            throw new RuntimeException("cannot open a sound channel: " + e, e);
        }
    }

    private static final class ClipChannel implements PcmChannel {
        private final Clip clip;
        private final boolean loop;
        private volatile boolean paused, stopped, ended;

        ClipChannel(short[] pcm, int channels, int rate, boolean loop, float volume, boolean startPaused) throws Exception {
            this.loop = loop;
            byte[] bytes = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                bytes[2 * i] = (byte) pcm[i];
                bytes[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false);
            clip = AudioSystem.getClip();
            clip.open(format, bytes, 0, bytes.length);
            setVolume(volume);
            clip.addLineListener(new LineListener() {
                public void update(LineEvent e) {
                    // unsolicited stop (not a pause) = sound reached its end naturally
                    if (e.getType() == LineEvent.Type.STOP && !paused && !stopped) {
                        ended = true;
                        clip.close();
                    }
                }
            });
            paused = startPaused;
            if (!startPaused) play();
        }

        private void play() {
            if (loop) clip.loop(Clip.LOOP_CONTINUOUSLY);
            else clip.start();
        }

        public void setVolume(float v) {
            if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float db = v <= 0.001f ? gain.getMinimum() : (float) (20.0 * Math.log10(v));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        }

        public void pause() {
            if (stopped || ended) return;
            paused = true;
            clip.stop();
        }

        public void resume() {
            if (stopped || ended || !paused) return;
            paused = false;
            play();
        }

        public void stop() {
            if (stopped) return;
            stopped = true;
            clip.stop();
            clip.close();
        }

        public boolean isFinished() { return stopped || ended; }
    }
}
