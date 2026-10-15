package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ウィンドウの中身 1 画面分。部品の配置と、入力の振り分けを行う。
 */
public abstract class Page {

    // ダブルクリックとみなす間隔 (ミリ秒)
    private static final long DOUBLE_CLICK_MILLIS = 400;

    final UiRoot root;
    private final List<Widget> widgets = new ArrayList<Widget>();

    Widget focus;
    private Widget hover;
    private Widget pressed;
    // Enter キーで押されるボタン
    Button defaultButton;
    // Esc キーで押されるボタン
    Button cancelButton;

    private long lastClickTime;
    private Widget lastClickWidget;

    protected Page(UiRoot root) {
        this.root = root;
    }

    public abstract String title();

    // このページが表示されたとき
    public void shown() {
    }

    public void tick() {
    }

    public void filesDropped(List<File> files) {
    }

    protected <T extends Widget> T add(T widget) {
        widget.page = this;
        widgets.add(widget);
        return widget;
    }

    protected void repaint() {
        root.repaint();
    }

    public void setFocus(Widget widget) {
        if (focus == widget) return;
        final Widget old = focus;
        focus = widget;
        if (old != null) old.focusLost();
        root.resetCaret();
        repaint();
    }

    void paint(Graphics2D g) {
        for (Widget widget : widgets) {
            if (widget.visible) widget.paint(g, widget == hover && widget.enabled, widget == focus);
        }
    }

    private Widget widgetAt(int mx, int my) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            final Widget widget = widgets.get(i);
            if (widget.visible && widget.contains(mx, my)) return widget;
        }
        return null;
    }

    void mouseMoved(int mx, int my) {
        if (pressed != null) {
            pressed.mouseDragged(mx, my);
            return;
        }
        final Widget now = widgetAt(mx, my);
        if (now != hover) {
            hover = now;
            repaint();
        }
    }

    void mousePressed(int mx, int my) {
        final Widget target = widgetAt(mx, my);
        hover = target;
        if (target == null || !target.enabled) {
            setFocus(null);
            return;
        }
        final long now = System.currentTimeMillis();
        final boolean doubleClick = target == lastClickWidget && now - lastClickTime < DOUBLE_CLICK_MILLIS;
        lastClickTime = doubleClick ? 0 : now;
        lastClickWidget = target;

        setFocus(target.focusable() ? target : null);
        pressed = target;
        target.mousePressed(mx, my, doubleClick);
    }

    void mouseReleased(int mx, int my) {
        final Widget target = pressed;
        pressed = null;
        if (target != null) target.mouseReleased(mx, my);
        hover = widgetAt(mx, my);
        repaint();
    }

    void mouseScrolled(int mx, int my, int amount) {
        final Widget target = widgetAt(mx, my);
        if (target != null && target.enabled) target.mouseScrolled(amount);
    }

    boolean keyPressed(int key, int modifiers) {
        if (focus != null && focus.enabled && focus.keyPressed(key, modifiers)) return true;
        switch (key) {
            case Key.TAB:
                moveFocus((modifiers & Key.MOD_SHIFT) != 0 ? -1 : 1);
                return true;
            case Key.ENTER:
                if (defaultButton != null) defaultButton.click();
                return true;
            case Key.ESCAPE:
                if (cancelButton != null) cancelButton.click();
                return true;
            default:
                return false;
        }
    }

    void charTyped(char c) {
        if (focus != null && focus.enabled) focus.charTyped(c);
    }

    private void moveFocus(int direction) {
        final int count = widgets.size();
        if (count == 0) return;
        int index = focus == null ? (direction > 0 ? -1 : count) : widgets.indexOf(focus);
        for (int i = 0; i < count; i++) {
            index = (index + direction + count) % count;
            final Widget widget = widgets.get(index);
            if (widget.visible && widget.focusable()) {
                setFocus(widget);
                return;
            }
        }
    }
}
