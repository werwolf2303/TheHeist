package heist.game;

/** Level completion state, backed by {@link Disarm}. Levels are addressed by their *displayed* number. */
public final class Progress {
    private Progress() {}

    public static boolean isCompleted(PuzzleType t, int level) {
        return Disarm.get().levelIsStoredAsCompleted(t, level);
    }

    /** Stores a first-time or repeat completion; returns the progression result. */
    public static Disarm.Result setCompleted(PuzzleType t, int level) {
        return Disarm.get().saveLevelCompleted(t, level, 0);
    }

    public static Disarm.Result setCompleted(PuzzleType t, int level, double seconds) {
        return Disarm.get().saveLevelCompleted(t, level, seconds);
    }

    public static int completedCount(PuzzleType t) { return Disarm.get().completedCount(t); }

    public static int totalCompleted() {
        int n = 0;
        for (PuzzleType t : PuzzleType.all()) n += completedCount(t);
        return n;
    }

    /** A level is locked until enough level-ups have opened its difficulty tier. */
    public static boolean isUnlocked(PuzzleType t, int level) {
        return Boolean.getBoolean("heist.unlockall") || Disarm.get().levelUpCount() >= t.difficulty(level) - 1;
    }
}
