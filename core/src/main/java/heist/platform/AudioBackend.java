package heist.platform;

/** Plays raw PCM. Mixing, fading and music logic stay in the game. */
public interface AudioBackend {
    /** pcm: interleaved 16-bit samples; volume: 0..1; starts playing immediately unless startPaused. */
    PcmChannel open(short[] pcm, int channels, int rate, boolean loop, float volume, boolean startPaused);
}
