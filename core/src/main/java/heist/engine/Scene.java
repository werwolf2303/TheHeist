package heist.engine;

/** One screen of the game. All coordinates are in logical 320x480 points. */
public abstract class Scene {
    protected Game game;

    public void attach(Game g) { this.game = g; }

    public void onEnter() {}
    public void onExit() {}
    public void update(float dt) {}
    public abstract void render(Gfx g);

    public void pointerPressed(float x, float y) {}
    public void pointerDragged(float x, float y) {}
    public void pointerReleased(float x, float y) {}
    public void keyPressed(Key key) {}
}
