package heist.game;

import java.io.IOException;
import java.util.Map;

import heist.engine.Assets;
import heist.engine.PList;
import heist.engine.Save;

/**
 * The game's progression brain (the original's DisarmController).
 *
 * Every first-time level completion earns its difficulty tier (1..4) in points. Cumulative points
 * promote the "disarm level" (0..12) according to DisarmSettings.plist; some disarm levels are
 * "level ups" that open the next difficulty tier of every puzzle, and several correspond to
 * security components of the safe room (lasers, cameras, electricity, bars, the safe itself).
 */
public final class Disarm {
    public static final int LASERS = 4, CAMERAS = 6, ELECTRICITY = 7, BARS = 9, SAFE_SEQUENCE = 10,
            COMPLETE = 12, PRIZE2 = 13;

    private static Disarm instance;
    private Map<String, Object> settings;
    private int disarmLevel, points;
    private boolean achievedLevelUp;

    public static Disarm get() {
        if (instance == null) instance = new Disarm();
        return instance;
    }

    /** Forgets the cached instance (used by tools that swap the save file). */
    public static void reload() { instance = null; }

    private Disarm() {
        try {
            settings = PList.map(PList.parse(heist.engine.Assets.bytes("DisarmSettings.plist")));
        } catch (IOException e) {
            throw new RuntimeException("cannot read DisarmSettings.plist", e);
        }
        disarmLevel = Save.getInt("disarmLevel", 0);
        points = Save.getInt("difficultyPoints", 0);
    }

    public int disarmLevel() { return disarmLevel; }
    public int difficultyPoints() { return points; }
    public boolean achievedLevelUp() { return achievedLevelUp; }

    private Map<String, Object> level(int n) { return PList.map(settings.get("Disarm " + n)); }

    /** Points a disarm level needs on top of the previous ones (-1 if it has no entry). */
    public int pointsRequiredForDisarm(int n) {
        Object p = level(n).get("points");
        return p == null ? -1 : PList.i(p, -1);
    }

    public int totalPointsRequiredForDisarm(int n) {
        int total = 0;
        for (int i = 1; i <= n; i++) {
            int p = pointsRequiredForDisarm(i);
            if (p != -1) total += p;
        }
        return total;
    }

    public boolean isLevelUp(int n) { return PList.b(level(n).get("levelUp"), false); }

    /** Name of the secure-call audio ("secureCall_2.0"...) played when reaching a disarm level, or null. */
    public String secureCallFor(int n) {
        Object o = level(n).get("secureCall");
        return o == null ? null : String.valueOf(o);
    }

    public int levelUpCount() {
        int count = 0;
        for (int i = 0; i < settings.size(); i++) if (isLevelUp(i) && i <= disarmLevel) count++;
        return count;
    }

    public int nextLevelUpDisarmLevel() {
        for (int i = 0; i < settings.size(); i++) if (isLevelUp(i) && disarmLevel < i) return i;
        return 0;
    }

    public int previousLevelUpDisarmLevel() {
        for (int i = disarmLevel; i >= 0; i--) if (isLevelUp(i) && i <= disarmLevel) return i;
        return 0;
    }

    public int pointsRequiredForNextLevelUp() {
        return totalPointsRequiredForDisarm(nextLevelUpDisarmLevel()) - totalPointsRequiredForDisarm(previousLevelUpDisarmLevel());
    }

    public float percentCompletedForNextDisarmLevel() {
        int base = totalPointsRequiredForDisarm(previousLevelUpDisarmLevel());
        int span = totalPointsRequiredForDisarm(nextLevelUpDisarmLevel()) - base;
        return span > 0 ? (points - base) / (float) span : 1f;
    }

    public float percentCompletedFor100PercentCompletion() {
        return points / (float) totalPointsRequiredForDisarm(COMPLETE);
    }

    public boolean hasUnlockedSafe() { return disarmLevel > 9; }
    public boolean has100PercentCompleted() { return disarmLevel > 11; }

    /** Disarm level at which a security component is cleared (-1 if unknown). */
    public static int disarmLevelForSecurityType(String type) {
        if ("lasers".equals(type)) return LASERS;
        if ("cameras".equals(type)) return CAMERAS;
        if ("electricity".equals(type)) return ELECTRICITY;
        if ("bars".equals(type)) return BARS;
        if ("unlockSafeSequenceHasOccurred".equals(type)) return SAFE_SEQUENCE;
        if ("100PercentCompleted".equals(type)) return COMPLETE;
        if ("collectedPrize2".equals(type)) return PRIZE2;
        return -1;
    }

    // ---- completed levels ------------------------------------------------------------

    private static String key(PuzzleType t, int level) { return t.name + "_LC_" + level; }

    public boolean levelIsStoredAsCompleted(PuzzleType t, int level) { return "1".equals(Save.get(key(t, level))); }

    public int completedCount(PuzzleType t) {
        int n = 0;
        for (int i = 1; i <= t.levels; i++) if (levelIsStoredAsCompleted(t, i)) n++;
        return n;
    }

    /** True when every level of the puzzle type has been completed. */
    public boolean completedSetForGameType(PuzzleType t) { return completedCount(t) == t.levels; }

    /** Result of storing a completed level. */
    public static final class Result {
        public boolean firstCompletion, promoted;
        public int oldDisarmLevel, newDisarmLevel, pointsGained;
    }

    /**
     * Records a finished level. Only the first completion earns points and may promote the disarm
     * level; replays just update the stored time.
     */
    public Result saveLevelCompleted(PuzzleType t, int level, double seconds) {
        Result r = new Result();
        r.oldDisarmLevel = disarmLevel;
        boolean already = levelIsStoredAsCompleted(t, level);
        if (!already) {
            Save.put(key(t, level), "1");
            Save.put(t.name + "_DateCompleted_" + level, System.currentTimeMillis() / 1000.0 - Save.REFERENCE_DATE_OFFSET);   // timeIntervalSinceReferenceDate
        }
        Save.put(t.name + "_TimeElapsed_" + level, seconds);
        if (isNightOwlTime(System.currentTimeMillis())) Save.put(t.name + "_DateCompletedNightOwl_" + level, true);
        r.firstCompletion = !already;
        if (!already) {
            r.pointsGained = t.difficulty(level);
            points += r.pointsGained;
            Save.put("difficultyPoints", points);
            promoteDisarmLevel();
        }
        r.newDisarmLevel = disarmLevel;
        r.promoted = disarmLevel != r.oldDisarmLevel;
        return r;
    }

    /** Moves up disarm levels the points allow; stops after a level-up so its sequence can play. */
    private void promoteDisarmLevel() {
        int next = disarmLevel + 1;
        if (disarmLevel == 0) next = 1;
        if (totalPointsRequiredForDisarm(next) > points) return;
        while (totalPointsRequiredForDisarm(next) <= points) {
            updateDisarmLevel(next);
            achievedLevelUp = isLevelUp(disarmLevel);
            if (achievedLevelUp) break;
            next++;
            if (next > settings.size()) break;
        }
    }

    private void updateDisarmLevel(int n) {
        disarmLevel = n;
        Save.put("disarmLevel", n);
    }

    /** Sets the disarm level directly (the safe-opening sequence moves it to the unlocked state). */
    void setDisarmLevelDirect(int n) { updateDisarmLevel(n); }

    /**
     * Back to the vault's first state: the briefcase on the pedestal, nothing on the shelves, no hint yet (the level
     * chooser does the same with setHasCollectedPrize:0 for every level up to the safe sequence).
     */
    public void resetVaultContents() {
        for (String k : new String[] { "hasCollectedPrize", "hasCollectedPrize2", "hasCollectedPrize3", "hasReceivedHint",
                "didScheduleNotification", "PedestalPosition" }) Save.remove(k);
    }

    public void clearLevelUpFlag() { achievedLevelUp = false; }

    /** After a level-up sequence: promote through any further disarm levels the points already cover. */
    public void matchDisarmLevelToDifficultyPoints() {
        int next = disarmLevel + 1;
        while (totalPointsRequiredForDisarm(next) <= points) {
            if (pointsRequiredForDisarm(next) < 1) return;
            updateDisarmLevel(next);
            next++;
        }
    }

    private static boolean isNightOwlTime(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 0 && h < 5;
    }

    // ---- flags kept by the safe room ------------------------------------------------------

    /**
     * The two launch flags of the original: animationSecuritySequenceCompleted (the power-up has played) and
     * displaySecureCallIntroductionCompleted (the intro call was heard). Both set means a normal launch.
     */
    private static final int[] LADDER = { 1, Disarm.LASERS, Disarm.CAMERAS, Disarm.ELECTRICITY, Disarm.BARS, Disarm.COMPLETE };

    /** Developer tool: jumps to a disarm level with exactly the points that level needs. */
    public void debugSetLevel(int n) {
        updateDisarmLevel(n);
        points = totalPointsRequiredForDisarm(n);
        Save.put("difficultyPoints", points);
        achievedLevelUp = false;
        if (n <= SAFE_SEQUENCE) resetVaultContents();
    }

    /** Developer tool: earns just enough points to fill the bar, as finishing a puzzle would. Null if nothing is left to earn. */
    public Result debugFillBar() {
        int next = nextLevelUpDisarmLevel();
        if (next <= disarmLevel) return null;
        Result r = new Result();
        r.oldDisarmLevel = disarmLevel;
        r.firstCompletion = true;
        int target = totalPointsRequiredForDisarm(next);
        r.pointsGained = Math.max(0, target - points);
        points = Math.max(points, target);
        Save.put("difficultyPoints", points);
        promoteDisarmLevel();
        r.newDisarmLevel = disarmLevel;
        r.promoted = disarmLevel != r.oldDisarmLevel;
        return r;
    }

    /** Developer tool: forgets every finished puzzle (and its time and snapshot) and goes back to the starting level. */
    public void debugResetPuzzles() {
        Save.removeContaining("_LC_", "_DateCompleted_", "_TimeElapsed_", "_DateCompletedNightOwl_");
        LevelStrip.clearThumbnails();
        debugSetLevel(1);
        markIntroSeen(true);
    }

    /** Developer tool: drops to the previous security stage. False if already at the start. */
    public boolean debugLevelDown() {
        int target = -1;
        for (int v : LADDER) if (v < disarmLevel) target = v;
        if (target < 0) return false;
        debugSetLevel(target);
        return true;
    }

    /** Developer tool: the disarm level just below the given security stage's level-up (the state right before it plays). */
    public static int debugLevelBefore(int stageLevel) {
        int prev = 1;
        for (int v : LADDER) if (v < stageLevel) prev = v;
        return prev;
    }

    public static void markIntroSeen(boolean seen) {
        if (seen) {
            Save.put("animationSecuritySequenceCompleted", true);
            Save.put("displaySecureCallIntroductionCompleted", true);
        } else {
            Save.remove("animationSecuritySequenceCompleted");
            Save.remove("displaySecureCallIntroductionCompleted");
        }
    }

    public boolean getFlag(String k) { return Save.getBool(k, false); }
    public void setFlag(String k, boolean v) { Save.put(k, v); }

    public boolean hasCollectedPrize() { return getFlag("hasCollectedPrize"); }
    public boolean hasCollectedSecondPrize() { return getFlag("hasCollectedPrize2"); }
    public boolean hasMovedPedestal() { return getFlag("hasMovedPedestal"); }
    public boolean hasReceivedHint() { return getFlag("hasReceivedHint"); }

}
