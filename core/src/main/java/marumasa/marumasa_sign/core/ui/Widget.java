package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;

/**
 * 画面の部品。座標はウィンドウの左上を原点とした、拡大前の値。
 */
public abstract class Widget {

    public int x, y, w, h;
    public boolean visible = true;
    public boolean enabled = true;

    Page page;

    public Widget bounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    public boolean contains(int mx, int my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    // Tab キーでフォーカスを受け取るかどうか
    public boolean focusable() {
        return false;
    }

    public abstract void paint(Graphics2D g, boolean hover, boolean focused);

    public void mousePressed(int mx, int my, boolean doubleClick) {
    }

    public void mouseDragged(int mx, int my) {
    }

    public void mouseReleased(int mx, int my) {
    }

    public boolean mouseScrolled(int amount) {
        return false;
    }

    public boolean keyPressed(int key, int modifiers) {
        return false;
    }

    public boolean charTyped(char c) {
        return false;
    }

    public void focusLost() {
    }

    protected void repaint() {
        if (page != null) page.repaint();
    }
}
