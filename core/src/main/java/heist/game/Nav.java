package heist.game;

import heist.engine.Game;
import heist.engine.Scene;

/** Scene navigation shared by every screen. */
public final class Nav {
    private Nav() {}

    private static Game game;
    private static HubScene hub;

    public static void init(Game g) { game = g; }

    public static Game game() { return game; }

    public static void show(Scene s) { if (game != null) game.setScene(s); }

    public static HubScene hub() { return hub; }

    public static void setHub(HubScene h) { hub = h; }

    /** Builds the scene for a puzzle level (also used by the headless tools). */
    public static PuzzleScene create(PuzzleType t, int level) {
        if (t.name.equals("Sokoban")) return new SokobanScene(t, level);
        if (t.name.equals("Fifteen")) return new FifteenScene(t, level);
        if (t.name.equals("Gridlock")) return new GridlockScene(t, level);
        if (t.name.equals("Sudoku")) return new SudokuScene(t, level);
        throw new IllegalArgumentException("unknown puzzle type " + t.name);
    }

    /** Builds the scene for a *displayed* level (which plays a remapped data level). */
    public static PuzzleScene createForDisplayed(PuzzleType t, int displayed) {
        PuzzleScene s = create(t, t.dataLevel(displayed));
        s.setDisplayLevel(displayed);
        return s;
    }

    /** Returns to the safe room. */
    public static void backToMenu() { if (hub != null) show(hub); }
}
