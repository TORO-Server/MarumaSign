package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;

public class Button extends Widget {

    public static final int NORMAL = 0;
    public static final int HOVER = 1;
    public static final int PRESSED = 2;
    public static final int DISABLED = 3;

    private final String text;
    private final Runnable action;
    private boolean pressed;
    private boolean inside;

    public Button(String text, Runnable action) {
        this.text = text;
        this.action = action;
    }

    @Override
    public boolean focusable() {
        return enabled;
    }

    public void click() {
        if (enabled && visible) action.run();
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        final int state = !enabled ? DISABLED : pressed && inside ? PRESSED : hover ? HOVER : NORMAL;
        final boolean isDefault = focused || (page != null && page.defaultButton == this && !(page.focus instanceof Button));
        Theme.paintButton(g, x, y, w, h, text, state, isDefault);
    }

    @Override
    public void mousePressed(int mx, int my, boolean doubleClick) {
        pressed = true;
        inside = true;
        repaint();
    }

    @Override
    public void mouseDragged(int mx, int my) {
        final boolean now = contains(mx, my);
        if (now != inside) {
            inside = now;
            repaint();
        }
    }

    @Override
    public void mouseReleased(int mx, int my) {
        final boolean fire = pressed && contains(mx, my);
        pressed = false;
        repaint();
        if (fire) click();
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (key == Key.ENTER || key == Key.SPACE) {
            click();
            return true;
        }
        return false;
    }
}
