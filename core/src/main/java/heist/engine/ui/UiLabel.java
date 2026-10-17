package heist.engine.ui;

import heist.engine.Fnt;
import heist.engine.Gfx;

public class UiLabel extends UiView {
    public String text = "";
    public Fnt font = Fnt.sans(true, 17);
    public int textColor = 0xFFFFFFFF;
    public int alignment;  // 0 left, 1 center, 2 right (UITextAlignment)

    protected void drawContent(Gfx g) {
        if (text == null || text.isEmpty()) return;
        g.setFont(font);
        g.setColor(textColor);
        float sw = g.stringWidth(text);
        float tx = alignment == 1 ? (w - sw) / 2f : alignment == 2 ? w - sw : 0;
        float ty = (h - g.fontHeight()) / 2f + g.fontAscent();
        g.drawString(text, tx, ty);
    }
}
