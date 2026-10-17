package heist.game;

import heist.engine.Save;

/**
 * The delayed pedestal-hint call, as the original arranges it: once the prize video call has been seen (the local
 * "Backstabbed" flag) and the hint has not been given yet, quitting the game records the exit time. When the game is
 * started again at least five minutes later, the hint call rings a moment after launch.
 */
public final class HintCall {
    private HintCall() {}

    static final String BACKSTABBED = "LocalGCAchievement_Backstabbed";
    private static final double WAIT_SECONDS = 300.0;

    /** handleApplicationWillResignActiveOrTerminate: remembers when the player left. */
    public static void onExit() {
        if (!Save.getBool(BACKSTABBED, false)) return;
        if (Save.getBool("hasReceivedHint", false)) return;
        if (Save.getBool("didScheduleNotification", false)) return;
        Save.put("exitTime", System.currentTimeMillis() / 1000.0);
        Save.put("didScheduleNotification", true);
    }

    /**
     * checkForElapsedTime: true if the hint call is due now. When the player came back too early, the pending hint is
     * cancelled (and only the next exit schedules it again).
     */
    static boolean dueOnLaunch() {
        if (!Save.getBool("didScheduleNotification", false)) return false;
        double exit = Save.getDouble("exitTime", 0);
        double now = System.currentTimeMillis() / 1000.0;
        if (exit <= 0 || now - exit < WAIT_SECONDS) {
            Save.put("didScheduleNotification", false);
            return false;
        }
        return true;
    }

    /**
     * application:didFinishLaunching: version bookkeeping (a save that already holds a prize from an older version
     * gets the hint call right away), plus the launch counter that feeds the "Regular" achievement.
     * Returns true if the hint call has to ring after launch.
     */
    static boolean launchBookkeeping() {
        boolean hint = false;
        if (Save.getInt("versionNumber", 0) < 2) {
            boolean collected = Save.getBool("hasCollectedPrize", false);
            boolean complete = Save.getInt("disarmLevel", 0) > Disarm.COMPLETE;
            if (collected) Save.put("eetsClaimedPriorToUpdate", true);
            if (complete) Save.put("100PercentClaimedPriorToUpdate", true);
            Save.put("versionNumber", 2);
            hint = (collected || complete) && !Save.getBool("hasReceivedHint", false);
        }
        int launches = Save.getInt("appLaunchCount", 0) + 1;
        if (launches < 40) Save.put("appLaunchCount", launches);   // counting stops where the achievement is awarded
        return hint;
    }

    /** triggerHintCall: the hint counts as received from the moment it is triggered. */
    static void markTriggered() {
        Save.put("hasReceivedHint", true);
        Save.put("didScheduleNotification", false);
    }
}
