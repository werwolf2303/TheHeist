package heist.engine.ui;

import heist.engine.Assets;
import heist.engine.Gfx;
import heist.engine.Sprite;

public class UiImageView extends UiView {
    public String imageName;

    protected void drawContent(Gfx g) {
        if (imageName == null) return;
        Sprite s = Assets.image(imageName);
        s.draw(g, 0, 0, w, h);
    }
}
