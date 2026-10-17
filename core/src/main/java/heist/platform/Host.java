package heist.platform;

/** Holds the one {@link Platform} the game runs on. */
public final class Host {
    private static volatile Platform platform;

    private Host() {}

    /** Registers the platform; call it first, before anything loads or draws. */
    public static void set(Platform p) { platform = p; }

    public static Platform get() {
        Platform p = platform;
        if (p == null) throw new IllegalStateException("no platform registered: call Host.set(...) at startup");
        return p;
    }
}
