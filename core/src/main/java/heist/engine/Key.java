package heist.engine;

/** The keys the game reacts to, independent of the platform's own key codes. */
public enum Key {
    /** The back button, or Escape on a keyboard. */
    BACK,
    /** Menu navigation (only keyboards send these). */
    UP, DOWN,
    /** Confirms the highlighted menu entry (Enter or Space). */
    SELECT,
    /** Opens the developer menu on platforms that have one. */
    DEBUG
}
