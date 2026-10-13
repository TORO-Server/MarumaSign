package marumasa.marumasa_sign.core.ui;

import java.awt.Color;
import java.awt.Graphics2D;

public class Label extends Widget {

    private String text;
    private Color color = Theme.TEXT;

    public Label(String text) {
        this.text = text;
    }

    public void setText(String text, Color color) {
        if (text == null) text = "";
        if (text.equals(this.text) && color.equals(this.color)) return;
        this.text = text;
        this.color = color;
        repaint();
    }

    public void setText(String text) {
        setText(text, Theme.TEXT);
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        Theme.drawText(g, Theme.ellipsize(g, text, w), x, y, h, enabled ? color : Theme.TEXT_DISABLED);
    }
}
