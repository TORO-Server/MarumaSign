package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;
import java.awt.Shape;

public class TextField extends Widget {

    private static final int PADDING = 4;

    protected final StringBuilder text = new StringBuilder();
    private int caret;
    // 選択範囲のもう一方の端。caret と同じなら選択なし
    private int anchor;
    private int scroll;
    private int maxLength = 2048;
    // 入力できる文字。null ならすべて
    private String allowed;
    private Runnable onChange;
    private Runnable onEnter;

    public TextField() {
    }

    public TextField allowed(String allowed) {
        this.allowed = allowed;
        return this;
    }

    public TextField maxLength(int maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public TextField onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    public TextField onEnter(Runnable onEnter) {
        this.onEnter = onEnter;
        return this;
    }

    public String getText() {
        return text.toString();
    }

    public void setText(String value) {
        if (value == null) value = "";
        if (value.length() > maxLength) value = value.substring(0, maxLength);
        if (value.contentEquals(text)) return;
        text.setLength(0);
        text.append(value);
        caret = anchor = text.length();
        scroll = 0;
        repaint();
    }

    @Override
    public boolean focusable() {
        return enabled;
    }

    // 文字を描ける領域の幅
    protected int innerWidth() {
        return w - PADDING * 2;
    }

    private int widthTo(int index) {
        // 描画と同じフォント (文字列全体で選んだもの) で測る
        return Theme.textWidth(text.substring(0, index), Theme.font(text.toString()));
    }

    protected void changed() {
        repaint();
        if (onChange != null) onChange.run();
    }

    private boolean hasSelection() {
        return caret != anchor;
    }

    private void deleteSelection() {
        final int start = Math.min(caret, anchor);
        final int end = Math.max(caret, anchor);
        text.delete(start, end);
        caret = anchor = start;
    }

    private String selection() {
        return text.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    protected void insert(String value) {
        final StringBuilder filtered = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            if (allowed != null && allowed.indexOf(c) < 0) continue;
            filtered.append(c);
        }
        if (filtered.length() == 0) return;
        if (hasSelection()) deleteSelection();
        final int room = maxLength - text.length();
        if (room <= 0) return;
        if (filtered.length() > room) filtered.setLength(room);
        text.insert(caret, filtered);
        caret += filtered.length();
        anchor = caret;
        changed();
    }

    private void moveCaret(int position, boolean select) {
        caret = Math.max(0, Math.min(text.length(), position));
        if (!select) anchor = caret;
        repaint();
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
        repaint();
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        final boolean shift = (modifiers & Key.MOD_SHIFT) != 0;
        final boolean ctrl = (modifiers & Key.MOD_CTRL) != 0;
        switch (key) {
            case Key.LEFT:
                moveCaret(caret - 1, shift);
                return true;
            case Key.RIGHT:
                moveCaret(caret + 1, shift);
                return true;
            case Key.HOME:
                moveCaret(0, shift);
                return true;
            case Key.END:
                moveCaret(text.length(), shift);
                return true;
            case Key.BACKSPACE:
                if (hasSelection()) {
                    deleteSelection();
                    changed();
                } else if (caret > 0) {
                    text.deleteCharAt(--caret);
                    anchor = caret;
                    changed();
                }
                return true;
            case Key.DELETE:
                if (hasSelection()) {
                    deleteSelection();
                    changed();
                } else if (caret < text.length()) {
                    text.deleteCharAt(caret);
                    changed();
                }
                return true;
            case Key.ENTER:
                if (onEnter == null) return false;
                onEnter.run();
                return true;
            case Key.A:
                if (!ctrl) return false;
                selectAll();
                return true;
            case Key.C:
            case Key.X:
                if (!ctrl) return false;
                if (hasSelection() && page != null) {
                    page.root.platform.setClipboard(selection());
                    if (key == Key.X) {
                        deleteSelection();
                        changed();
                    }
                }
                return true;
            case Key.V:
                if (!ctrl) return false;
                if (page != null) {
                    final String clip = page.root.platform.getClipboard();
                    if (clip != null) insert(clip.trim());
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean charTyped(char c) {
        insert(String.valueOf(c));
        return true;
    }

    // クリックした位置に最も近い文字の境目を返す
    private int indexAt(int mx) {
        final int target = mx - x - PADDING + scroll;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i <= text.length(); i++) {
            final int distance = Math.abs(widthTo(i) - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    @Override
    public void mousePressed(int mx, int my, boolean doubleClick) {
        if (doubleClick) {
            selectAll();
        } else {
            moveCaret(indexAt(mx), false);
        }
    }

    @Override
    public void mouseDragged(int mx, int my) {
        moveCaret(indexAt(mx), true);
    }

    @Override
    public void focusLost() {
        anchor = caret;
        scroll = 0;
        repaint();
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        Theme.paintField(g, x, y, w, h, enabled);

        final int inner = innerWidth();
        final int caretX = widthTo(caret);
        // キャレットが見える位置までスクロールする
        if (focused) {
            if (caretX - scroll > inner) scroll = caretX - inner;
            if (caretX - scroll < 0) scroll = caretX;
        }

        final Shape clip = g.getClip();
        g.clipRect(x + PADDING - 1, y + 1, inner + 2, h - 2);
        final int textX = x + PADDING - scroll;
        if (focused && hasSelection()) {
            final int start = widthTo(Math.min(caret, anchor));
            final int end = widthTo(Math.max(caret, anchor));
            g.setColor(Theme.SELECTION);
            g.fillRect(textX + start, y + 3, end - start, h - 6);
        }
        Theme.drawText(g, text.toString(), textX, y, h, enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (focused && hasSelection()) {
            // 選択範囲の文字は白で重ねて描く
            final int start = widthTo(Math.min(caret, anchor));
            final int end = widthTo(Math.max(caret, anchor));
            final Shape inside = g.getClip();
            g.clipRect(textX + start, y, end - start, h);
            Theme.drawText(g, text.toString(), textX, y, h, java.awt.Color.WHITE);
            g.setClip(inside);
        }
        if (focused && page != null && page.root.caretVisible()) {
            g.setColor(Theme.TEXT);
            g.drawLine(textX + caretX, y + 3, textX + caretX, y + h - 4);
        }
        g.setClip(clip);
    }
}
