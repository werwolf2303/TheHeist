package heist.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import heist.platform.Host;
import heist.platform.PcmChannel;

/**
 * Fire-and-forget sound effects and the game's music, as raw PCM played through the platform's
 * {@link heist.platform.AudioBackend}. The sound files are decoded, mixed and faded here; the backend only plays channels.
 */
public final class Audio {
    /** A playing sound. */
    public interface Handle {
        void stop();
    }

    private static final class Pcm {
        final short[] data;
        final int channels, rate;
        Pcm(short[] data, int channels, int rate) { this.data = data; this.channels = channels; this.rate = rate; }
    }

    private static final Map<String, Pcm> cache = new HashMap<String, Pcm>();
    private static final Map<String, Pcm> loopCache = new HashMap<String, Pcm>();
    private static Player music;
    private static String musicName;
    private static String suspendedMusic;
    private static boolean sfxOn = true, musicOn = true, broken, suspended;

    private Audio() {}

    public static boolean isSfxOn() { return sfxOn; }
    public static boolean isMusicOn() { return musicOn; }

    public static void setSfxOn(boolean on) { sfxOn = on; }

    private static final List<Player> active = new ArrayList<Player>();


    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }

    private static int le32(byte[] b, int o) { return le16(b, o) | (le16(b, o + 2) << 16); }

    /** Length of a wav in seconds (0 if unknown). */
    public static float lengthSeconds(String name) {
        try {
            if (!Assets.exists(name + ".wav")) return 0f;
            Pcm p = load(name);
            return p == null ? 0f : p.data.length / (float) (p.rate * p.channels);
        } catch (IOException e) {
            return 0f;
        }
    }

    /** Reads a 16 bit PCM wav into memory. */
    private static Pcm read(String name) throws IOException {
        byte[] all = Assets.bytes(name + ".wav");
        int pos = 12, channels = 2, rate = 44100, bits = 16;
        while (pos + 8 <= all.length) {
            int size = le32(all, pos + 4);
            String id = new String(all, pos, 4, "US-ASCII");
            int body = pos + 8;
            if (id.equals("fmt ")) {
                channels = le16(all, body + 2);
                rate = le32(all, body + 4);
                bits = le16(all, body + 14);
            } else if (id.equals("data")) {
                if (bits != 16) throw new IOException("only 16 bit sound is supported");
                int n = size > 0 && body + size <= all.length ? size : all.length - body;
                short[] pcm = new short[n / 2];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) le16(all, body + 2 * i);
                return new Pcm(pcm, channels, rate);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException("no sound data in " + name);
    }

    private static Pcm load(String name) throws IOException {
        Pcm p = cache.get(name);
        if (p == null) {
            if (!Assets.exists(name + ".wav")) return null;
            p = read(name);
            cache.put(name, p);
        }
        return p;
    }

    /** A sound on a backend channel, with the state the music rules need (volume, paused, stopped). */
    private static final class Player implements Handle {
        final PcmChannel channel;
        volatile boolean stopRequested, paused;
        volatile float volume;

        Player(Pcm pcm, boolean loop, float volume, boolean startPaused) {
            this.volume = volume;
            this.paused = startPaused;
            this.channel = Host.get().audio().open(pcm.data, pcm.channels, pcm.rate, loop, volume, startPaused);
        }

        void setVolume(float v) {
            volume = v;
            channel.setVolume(v);
        }

        public void stop() {
            stopRequested = true;
            channel.stop();
        }

        /** Pauses without losing position. */
        void pause() {
            paused = true;
            channel.pause();
        }

        void resume() {
            paused = false;
            channel.resume();
        }

        /** True until the sound has ended or was stopped. */
        boolean alive() { return !stopRequested && !channel.isFinished(); }

        boolean isPlaying() { return alive() && !paused; }
    }

    /** Stops every playing sound effect (not the music track). */
    public static void stopAllEffects() {
        synchronized (active) {
            for (Player p : new ArrayList<Player>(active)) p.stop();
        }
    }

    private static Player launch(Pcm pcm, boolean loop) {
        Player p = new Player(pcm, loop, 1f, false);
        synchronized (active) {
            for (int i = active.size() - 1; i >= 0; i--) if (!active.get(i).alive()) active.remove(i);
            active.add(p);
        }
        return p;
    }

    /** Plays a sound by base name, e.g. "lock_clink" (the import converts everything to .wav). */
    public static void play(String name) {
        if (!sfxOn || broken || suspended) return;
        try {
            Pcm pcm = load(name);
            if (pcm != null) launch(pcm, false);
        } catch (Throwable t) {
            broken = true;
            Host.get().error("[audio] disabled: " + t);
        }
    }

    /** Starts a looping sound effect and returns a handle for {@link #stopLoop} (null if muted/unavailable). */
    public static Handle playLoop(String name) {
        if (!sfxOn || broken || suspended) return null;
        try {
            Pcm pcm = load(name);
            return pcm == null ? null : launch(pcm, true);
        } catch (Throwable t) {
            broken = true;
            Host.get().error("[audio] disabled: " + t);
            return null;
        }
    }

    public static void stopLoop(Handle h) {
        if (h != null) h.stop();
    }

    // safe room player persists (paused/resumed); game player is replaced per level; fades step 0.1 every 0.2 s

    /** The safe room track. */
    public static final String MAIN = "safe_room2";

    private static Player mainPlayer, gamePlayer;
    private static String gameTrack;

    private static final class Slot {
        final Player player;
        float acc;
        Slot(Player player) { this.player = player; }
    }

    private static final Object fadeLock = new Object();
    private static Slot outSlot, inSlot;

    /** Cancels both fade timers. */
    private static void cancelFades() {
        synchronized (fadeLock) { outSlot = null; inSlot = null; }
    }

    private static void beginFadeOut(Player p) {
        if (p == null) return;
        synchronized (fadeLock) {
            if (outSlot == null) outSlot = new Slot(p);
        }
    }

    private static void beginFadeIn(Player p) {
        if (p == null) return;
        synchronized (fadeLock) {
            if (inSlot == null) inSlot = new Slot(p);
        }
        p.setVolume(0f);
    }

    /** Ticks the music fade timers. Called from {@link Game#update} once per frame. */
    public static void update(float dt) {
        synchronized (fadeLock) {
            if (outSlot != null) {
                outSlot.acc += dt;
                while (outSlot != null && outSlot.acc >= 0.2f) {
                    outSlot.acc -= 0.2f;
                    Player p = outSlot.player;
                    if (p.volume > 0f) {
                        p.setVolume(Math.max(0f, p.volume - 0.1f));
                    } else {
                        outSlot = null;
                        p.setVolume(0f);
                        p.pause();
                    }
                }
            }
            if (inSlot != null) {
                inSlot.acc += dt;
                while (inSlot != null && inSlot.acc >= 0.2f) {
                    inSlot.acc -= 0.2f;
                    Player p = inSlot.player;
                    if (p.volume < 1f) p.setVolume(Math.min(1f, p.volume + 0.1f));
                    else inSlot = null;
                }
            }
        }
    }

    private static boolean musicAllowed() { return musicOn && !broken && !suspended; }

    /** A looping music player that has not started playing yet (or has, when {@code paused} is false). */
    private static Player newMusicPlayer(String name, float volume, boolean paused) {
        try {
            Pcm pcm = loopCache.get(name);
            if (pcm == null) {
                if (!Assets.exists(name + ".wav")) return null;
                pcm = smoothLoop(read(name));
                loopCache.put(name, pcm);
            }
            return new Player(pcm, true, volume, paused);
        } catch (Throwable t) {
            broken = true;
            Host.get().error("[audio] disabled: " + t);
            return null;
        }
    }

    /** Fades the safe room track in; no-op if it is already playing. */
    public static void startSafeRoomMusic() {
        if (!musicAllowed()) return;
        if (mainPlayer == null) mainPlayer = newMusicPlayer(MAIN, 0f, true);
        if (mainPlayer == null || mainPlayer.isPlaying()) return;
        cancelFades();
        beginFadeIn(mainPlayer);
        mainPlayer.resume();
    }

    /** Starts a level track; safe room track fades out while the new one fades in. */
    public static void startGameMusic(String track) {
        if (!musicAllowed()) return;
        if (gamePlayer != null) gamePlayer.stop();
        gamePlayer = newMusicPlayer(track, 0f, false);
        gameTrack = gamePlayer == null ? null : track;
        cancelFades();
        beginFadeOut(mainPlayer);
        beginFadeIn(gamePlayer);
    }

    /** Stops the level track immediately, no fade. */
    public static void stopGameMusic() {
        if (gamePlayer != null) {
            gamePlayer.stop();
            gamePlayer = null;
            gameTrack = null;
        }
    }

    /** Plays a track by name; the safe room track reuses the shared player, others get their own. */
    public static void playMusic(String name) {
        if (!musicAllowed()) return;
        if (name.equals(MAIN)) {
            if (mainPlayer == null) mainPlayer = newMusicPlayer(MAIN, 1f, false);
            else if (!mainPlayer.isPlaying()) { mainPlayer.setVolume(1f); mainPlayer.resume(); }
            return;
        }
        if (name.equals(musicName) && music != null && music.alive()) return;
        stopOtherMusic();
        Player p = newMusicPlayer(name, 1f, false);
        if (p != null) { music = p; musicName = name; }
    }

    public static void playMusicFadeIn(String name) {
        if (name.equals(MAIN)) { startSafeRoomMusic(); return; }
        if (!musicAllowed()) return;
        if (name.equals(musicName) && music != null && music.alive()) return;
        stopOtherMusic();
        Player p = newMusicPlayer(name, 0f, false);
        if (p == null) return;
        music = p;
        musicName = name;
        cancelFades();
        beginFadeIn(p);
    }

    public static void fadeOutMusic() {
        cancelFades();
        if (mainPlayer != null && mainPlayer.isPlaying()) { beginFadeOut(mainPlayer); return; }
        if (music != null) {
            beginFadeOut(music);
            music = null;
            musicName = null;
        }
    }

    /** Stops ring/call music; safe room and game tracks are unaffected. */
    public static void stopRingMusic() { stopOtherMusic(); }

    private static void stopOtherMusic() {
        if (music != null) {
            music.stop();
            music = null;
            musicName = null;
        }
    }

    /** Stops every music track at once. */
    public static void stopMusic() {
        cancelFades();
        stopOtherMusic();
        if (mainPlayer != null) { mainPlayer.stop(); mainPlayer = null; }
        stopGameMusic();
    }

    public static void setMusicOn(boolean on) {
        musicOn = on;
        if (!on) stopMusic();
    }


    /** The app left the screen: everything goes quiet, the music is remembered. */
    public static void suspend() {
        if (gamePlayer != null && gamePlayer.isPlaying()) suspendedMusic = "game:" + gameTrack;
        else if (mainPlayer != null && mainPlayer.isPlaying()) suspendedMusic = MAIN;
        else suspendedMusic = musicName;
        suspended = true;
        stopMusic();
        stopAllEffects();
    }

    /** The app is back: the music that was playing starts again. */
    public static void resume() {
        suspended = false;
        String m = suspendedMusic;
        suspendedMusic = null;
        if (m == null) return;
        if (m.startsWith("game:")) startGameMusic(m.substring(5));
        else if (m.equals(MAIN)) startSafeRoomMusic();
        else playMusic(m);
    }

    /**
     * Makes a track loop cleanly. The AAC conversion leaves about 85 ms of decoder silence at the start of some tracks and the
     * end can stop mid-waveform; both make the loop point audible. The silence is trimmed and, when the join is rough, the last
     * 50 ms are blended into the head so the wrap is seamless.
     */
    private static Pcm smoothLoop(Pcm in) {
        short[] pcm = in.data;
        int ch = in.channels;
        int frames = pcm.length / ch;
        // leading digital silence
        int start = 0;
        while (start < frames) {
            boolean quiet = true;
            for (int c = 0; c < ch; c++) if (Math.abs(pcm[start * ch + c]) > 8) { quiet = false; break; }
            if (!quiet) break;
            start++;
        }
        int len = frames - start;
        int xf = (int) (in.rate * 0.05);
        if (len < xf * 4) return in;
        // how rough is the wrap compared with the track's usual sample-to-sample change?
        double step = 0;
        int probe = Math.min(len - 1, 4000), mid = start + len / 2;
        for (int i = 0; i < probe; i++) step += Math.abs(pcm[(mid + i + 1) * ch] - pcm[(mid + i) * ch]);
        step = Math.max(1.0, step / probe);
        double jump = Math.abs(pcm[(frames - 1) * ch] - pcm[start * ch]);
        boolean rough = jump > 2.5 * step;
        if (start == 0 && !rough) return in;
        int outFrames = rough ? len - xf : len;
        short[] out = new short[outFrames * ch];
        int from = rough ? start + xf : start;   // the head that gets blended in is dropped from the front
        for (int i = 0; i < outFrames; i++) for (int c = 0; c < ch; c++) out[i * ch + c] = pcm[(from + i) * ch + c];
        if (rough) {
            // the last xf frames: old tail fades out while the dropped head fades in
            for (int i = 0; i < xf; i++) {
                double t = (i + 1) / (double) xf;
                for (int c = 0; c < ch; c++) {
                    double tail = pcm[(frames - xf + i) * ch + c];
                    double head = pcm[(start + i) * ch + c];
                    double v = tail * Math.cos(t * Math.PI / 2) + head * Math.sin(t * Math.PI / 2);
                    out[(outFrames - xf + i) * ch + c] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                }
            }
        }
        return new Pcm(out, ch, in.rate);
    }
}
