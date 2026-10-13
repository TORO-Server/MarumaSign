package marumasa.marumasa_sign.core.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

public class ListBox extends Widget {

    private static final int ROW_HEIGHT = 16;
    private static final int SCROLL_WIDTH = 15;

    private final List<String> items = new ArrayList<String>();
    private int selected = -1;
    // 一番上に表示している行
    private int top;
    private Runnable onSelect;
    private Runnable onActivate;
    // スクロールバーのつまみをドラッグしているときの、つかんだ位置
    private int dragOffset = -1;

    public ListBox onSelect(Runnable onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    // ダブルクリックや Enter で項目が決定されたとき
    public ListBox onActivate(Runnable onActivate) {
        this.onActivate = onActivate;
        return this;
    }

    public void setItems(List<String> values) {
        items.clear();
        items.addAll(values);
        selected = -1;
        top = 0;
        repaint();
    }

    public int getSelected() {
        return selected;
    }

    @Override
    public boolean focusable() {
        return enabled;
    }

    private int visibleRows() {
        return Math.max(1, (h - 2) / ROW_HEIGHT);
    }

    private int maxTop() {
        return Math.max(0, items.size() - visibleRows());
    }

    private boolean hasScrollBar() {
        return items.size() > visibleRows();
    }

    private void setTop(int value) {
        value = Math.max(0, Math.min(maxTop(), value));
        if (value != top) {
            top = value;
            repaint();
        }
    }

    private void select(int index) {
        index = Math.max(0, Math.min(items.size() - 1, index));
        if (items.isEmpty()) index = -1;
        if (index == selected) return;
        selected = index;
        if (selected >= 0) {
            if (selected < top) top = selected;
            if (selected >= top + visibleRows()) top = selected - visibleRows() + 1;
        }
        repaint();
        if (onSelect != null) onSelect.run();
    }

    private int thumbHeight() {
        return Math.max(16, (h - 2) * visibleRows() / Math.max(1, items.size()));
    }

    private int thumbY() {
        final int range = h - 2 - thumbHeight();
        return y + 1 + (maxTop() == 0 ? 0 : range * top / maxTop());
    }

    @Override
    public void mousePressed(int mx, int my, boolean doubleClick) {
        if (hasScrollBar() && mx >= x + w - SCROLL_WIDTH - 1) {
            final int thumbY = thumbY();
            if (my < thumbY) {
                setTop(top - visibleRows());
            } else if (my >= thumbY + thumbHeight()) {
                setTop(top + visibleRows());
            } else {
                dragOffset = my - thumbY;
            }
            return;
        }
        final int row = top + (my - y - 1) / ROW_HEIGHT;
        if (row < 0 || row >= items.size()) return;
        select(row);
        if (doubleClick && onActivate != null) onActivate.run();
    }

    @Override
    public void mouseDragged(int mx, int my) {
        if (dragOffset < 0) return;
        final int range = h - 2 - thumbHeight();
        if (range <= 0) return;
        setTop(Math.round((float) (my - dragOffset - y - 1) * maxTop() / range));
    }

    @Override
    public void mouseReleased(int mx, int my) {
        dragOffset = -1;
    }

    @Override
    public boolean mouseScrolled(int amount) {
        setTop(top - amount * 3);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        switch (key) {
            case Key.UP:
                select(selected - 1);
                return true;
            case Key.DOWN:
                select(selected + 1);
                return true;
            case Key.PAGE_UP:
                select(selected - visibleRows());
                return true;
            case Key.PAGE_DOWN:
                select(selected + visibleRows());
                return true;
            case Key.HOME:
                select(0);
                return true;
            case Key.END:
                select(items.size() - 1);
                return true;
            case Key.ENTER:
                if (selected < 0 || onActivate == null) return false;
                onActivate.run();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        Theme.paintField(g, x, y, w, h, enabled);

        final boolean scrollBar = hasScrollBar();
        final int rowWidth = w - 2 - (scrollBar ? SCROLL_WIDTH : 0);
        final Shape clip = g.getClip();
        g.clipRect(x + 1, y + 1, rowWidth, h - 2);
        final int rows = visibleRows() + 1;
        for (int i = 0; i < rows; i++) {
            final int index = top + i;
            if (index >= items.size()) break;
            final int rowY = y + 1 + i * ROW_HEIGHT;
            final boolean isSelected = index == selected;
            if (isSelected) {
                g.setColor(focused ? Theme.SELECTION : Theme.GROUP_BORDER);
                g.fillRect(x + 1, rowY, rowWidth, ROW_HEIGHT);
            }
            final Color color = isSelected && focused ? Color.WHITE : Theme.TEXT;
            Theme.drawText(g, Theme.ellipsize(g, items.get(index), rowWidth - 8), x + 5, rowY, ROW_HEIGHT, color);
        }
        g.setClip(clip);

        if (scrollBar) {
            Theme.paintScrollBar(g, x + w - SCROLL_WIDTH - 1, y + 1, SCROLL_WIDTH, h - 2, thumbY(), thumbHeight());
        }
    }
}
