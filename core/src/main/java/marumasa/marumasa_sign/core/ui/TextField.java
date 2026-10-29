package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.text.BreakIterator;
import java.util.Locale;

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
        if (value.length() > maxLength) value = value.substring(0, cut(value, maxLength));
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

    // length 文字で切るときの長さ。サロゲートペア (絵文字など) の片方だけを残さない
    private static int cut(CharSequence value, int length) {
        return length > 0 && Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
    }

    // 文字の境目を探すもの。サロゲートペアや、結合文字 (濁点やアクセント記号など) の途中には止まらない
    private BreakIterator characters() {
        final BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text.toString());
        return iterator;
    }

    // index より前の、最も近い文字の境目
    private int previousBoundary(int index) {
        if (index <= 0) return 0;
        final int boundary = characters().preceding(Math.min(index, text.length()));
        return boundary == BreakIterator.DONE ? 0 : boundary;
    }

    // index より後の、最も近い文字の境目
    private int nextBoundary(int index) {
        if (index >= text.length()) return text.length();
        final int boundary = characters().following(Math.max(0, index));
        return boundary == BreakIterator.DONE ? text.length() : boundary;
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
        // とても長い文字列を貼り付けられても、入る分 (と、切る位置を決めるための 1 文字) だけを調べる
        for (int i = 0; i < value.length() && filtered.length() <= maxLength; i++) {
            final char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            if (allowed != null && allowed.indexOf(c) < 0) continue;
            filtered.append(c);
        }
        if (filtered.length() == 0) return;
        final boolean replaced = hasSelection();
        if (replaced) deleteSelection();
        // 前半を入れなかったサロゲートペアの後半も入れない
        if (Character.isLowSurrogate(filtered.charAt(0)) && (caret == 0 || !Character.isHighSurrogate(text.charAt(caret - 1)))) {
            filtered.deleteCharAt(0);
        }
        final int room = maxLength - text.length();
        if (filtered.length() > room) filtered.setLength(Math.max(0, room));
        // 入りきらないサロゲートペアの前半は入れない (1 文字ずつ入力されるときも、後半が入る場所がなければ入れない)
        if (filtered.length() >= room) filtered.setLength(cut(filtered, filtered.length()));
        if (filtered.length() > 0) {
            text.insert(caret, filtered);
            caret += filtered.length();
            anchor = caret;
        } else if (!replaced) {
            return;
        }
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
                moveCaret(previousBoundary(caret), shift);
                return true;
            case Key.RIGHT:
                moveCaret(nextBoundary(caret), shift);
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
                    final int start = previousBoundary(caret);
                    text.delete(start, caret);
                    caret = anchor = start;
                    changed();
                }
                return true;
            case Key.DELETE:
                if (hasSelection()) {
                    deleteSelection();
                    changed();
                } else if (caret < text.length()) {
                    text.delete(caret, nextBoundary(caret));
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
        final BreakIterator iterator = characters();
        for (int i = iterator.first(); i != BreakIterator.DONE; i = iterator.next()) {
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
            // 文字が短くなったら (置き換えや削除)、右に空きを残さず、左に隠れた文字を見せる。
            // そのままでは、キャレットより前の文字がすべて左に隠れたままになることがある
            scroll = Math.max(0, Math.min(scroll, widthTo(text.length()) - inner));
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
