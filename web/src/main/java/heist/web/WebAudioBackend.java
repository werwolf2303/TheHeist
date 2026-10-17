package heist.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.webaudio.AudioBuffer;
import org.teavm.jso.webaudio.AudioBufferSourceNode;
import org.teavm.jso.webaudio.AudioContext;
import org.teavm.jso.webaudio.GainNode;

import heist.platform.AudioBackend;
import heist.platform.PcmChannel;

/**
 * Plays raw PCM through the Web Audio API: one shared AudioContext, one GainNode per channel (for volume -
 * AudioBufferSourceNode itself has no volume control), and (the one real complication) a new
 * AudioBufferSourceNode per actual playback span, because a Web Audio source node is single-use - it cannot
 * be paused and resumed in place the way AVAudioPlayer (the original) or javax.sound (desktop) can.
 */
final class WebAudioBackend implements AudioBackend {
    @JSBody(script = "return new AudioContext();")
    private static native AudioContext newAudioContext();

    private static AudioContext ctx;

    private static AudioContext ctx() {
        if (ctx == null) ctx = newAudioContext();
        return ctx;
    }

    /** AudioContext starts suspended; must be resumed from a real user gesture (the play button). */
    static void resumeOnUserGesture() { ctx().resume(); }

    public PcmChannel open(short[] pcm, int channels, int rate, boolean loop, float volume, boolean startPaused) {
        AudioBuffer buffer = toAudioBuffer(pcm, channels, rate);
        return new WebPcmChannel(buffer, loop, volume, startPaused);
    }

    private static AudioBuffer toAudioBuffer(short[] pcm, int channels, int rate) {
        int frames = pcm.length / channels;
        AudioBuffer buffer = ctx().createBuffer(channels, Math.max(1, frames), rate);
        float[] samples = new float[frames];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) samples[i] = pcm[i * channels + c] / 32768f;
            buffer.copyToChannel(samples, c);
        }
        return buffer;
    }

    /**
     * "Pause" stops the current source node and remembers how far into the buffer playback had reached
     * (AudioContext.currentTime doesn't reset between sounds, so this is always measured as a delta); "resume"
     * starts a fresh node at that remembered offset. A looping channel still loops correctly across a
     * pause/resume cycle because the offset is taken modulo the buffer's duration before each start.
     */
    private static final class WebPcmChannel implements PcmChannel {
        private final AudioBuffer buffer;
        private final boolean loop;
        private final GainNode gain;
        private AudioBufferSourceNode node;
        private double startedAtContextTime;
        private double pausedAtOffset;
        private boolean paused;
        private boolean stopped;
        private boolean endedNaturally;

        WebPcmChannel(AudioBuffer buffer, boolean loop, float volume, boolean startPaused) {
            this.buffer = buffer;
            this.loop = loop;
            this.gain = ctx().createGain();
            gain.connect(ctx().getDestination());
            gain.getGain().setValue(volume);
            if (startPaused) {
                paused = true;
                pausedAtOffset = 0;
            } else {
                startNode(0);
            }
        }

        private void startNode(double offset) {
            node = ctx().createBufferSource();
            node.setBuffer(buffer);
            node.setLoop(loop);
            node.connect(gain);
            if (!loop) {
                node.onEnded(e -> { if (!stopped) endedNaturally = true; });
            }
            double safeOffset = buffer.getDuration() > 0 ? offset % buffer.getDuration() : 0;
            node.start(0, safeOffset);
            startedAtContextTime = ctx().getCurrentTime() - safeOffset;
        }

        public void setVolume(float v) { gain.getGain().setValue(v); }

        public void pause() {
            if (stopped || paused) return;
            paused = true;
            pausedAtOffset = ctx().getCurrentTime() - startedAtContextTime;
            node.stop();
        }

        public void resume() {
            if (stopped || !paused) return;
            paused = false;
            startNode(pausedAtOffset);
        }

        public void stop() {
            if (stopped) return;
            stopped = true;
            if (!paused && node != null) {
                try { node.stop(); } catch (RuntimeException ignored) { }
            }
            gain.disconnect();
        }

        public boolean isFinished() { return stopped || endedNaturally; }
    }
}
