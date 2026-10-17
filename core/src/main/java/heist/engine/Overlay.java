package heist.engine;

/**
 * A layer on top of the game: it is updated every frame, gets every input event before the scene does, and draws over the
 * scene (in 320x480 screen coordinates). A port can use it for tools such as a developer menu. Return true from an input
 * method to consume the event.
 */
public interface Overlay {
    void update(float dt);

    void render(Gfx g);

    boolean key(Key key);

    boolean pointerPressed(float x, float y);

    boolean pointerDragged(float x, float y);

    boolean pointerReleased(float x, float y);
}