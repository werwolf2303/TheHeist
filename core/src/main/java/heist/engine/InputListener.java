package heist.engine;

/**
 * Where a platform delivers input. Pointer positions are in view pixels (the engine maps them to the 320x480 screen);
 * the calls may come from any thread and are applied on the game thread at the next update.
 */
public interface InputListener {
    void touchDown(float viewX, float viewY);

    void touchMove(float viewX, float viewY);

    void touchUp(float viewX, float viewY);

    void key(Key key);
}
