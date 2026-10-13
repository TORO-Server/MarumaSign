package marumasa.marumasa_sign.core.ui;

import marumasa.marumasa_sign.core.sign.SignSpec;

import java.awt.Graphics2D;

/**
 * 数値の入力欄。右端のボタンとマウスホイールでも値を増減できる。
 */
public class NumberField extends TextField {

    private static final int SPIN_WIDTH = 15;

    private final float min;
    private final float max;
    private final float step;
    private final boolean integer;
    private final float fallback;
    // 押されているボタン (1: 上, -1: 下, 0: なし)
    private int pressedSpin;

    public NumberField(float value, float min, float max, float step, boolean integer) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.integer = integer;
        this.fallback = value;
        allowed(integer ? "0123456789-" : "0123456789-.");
        maxLength(12);
        setValue(value);
    }

    public float getValue() {
        try {
            final float value = Float.parseFloat(getText());
            if (Float.isNaN(value) || Float.isInfinite(value)) return fallback;
            return Math.max(min, Math.min(max, integer ? Math.round(value) : value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setValue(float value) {
        setText(SignSpec.number(Math.max(min, Math.min(max, value))));
    }

    private void spin(int direction) {
        // 誤差がたまらないように小数第 3 位で丸める
        final float next = Math.round((getValue() + step * direction) * 1000f) / 1000f;
        final String before = getText();
        setValue(next);
        if (!before.equals(getText())) changed();
    }

    @Override
    protected int innerWidth() {
        return super.innerWidth() - SPIN_WIDTH;
    }

    private int spinAt(int mx, int my) {
        if (mx < x + w - SPIN_WIDTH - 1) return 0;
        return my < y + h / 2 ? 1 : -1;
    }

    @Override
    public void mousePressed(int mx, int my, boolean doubleClick) {
        pressedSpin = spinAt(mx, my);
        if (pressedSpin != 0) {
            spin(pressedSpin);
        } else {
            super.mousePressed(mx, my, doubleClick);
        }
    }

    @Override
    public void mouseDragged(int mx, int my) {
        if (pressedSpin == 0) super.mouseDragged(mx, my);
    }

    @Override
    public void mouseReleased(int mx, int my) {
        pressedSpin = 0;
        repaint();
    }

    @Override
    public boolean mouseScrolled(int amount) {
        spin(amount > 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (key == Key.UP) {
            spin(1);
            return true;
        }
        if (key == Key.DOWN) {
            spin(-1);
            return true;
        }
        return super.keyPressed(key, modifiers);
    }

    @Override
    public void focusLost() {
        super.focusLost();
        // 範囲外や不正な入力を直す
        final String before = getText();
        setValue(getValue());
        if (!before.equals(getText())) changed();
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        super.paint(g, hover, focused);
        final int bx = x + w - SPIN_WIDTH - 1;
        final int half = (h - 2) / 2;
        Theme.paintSpinButton(g, bx, y + 1, SPIN_WIDTH, half, true, pressedSpin == 1, enabled);
        Theme.paintSpinButton(g, bx, y + 1 + half, SPIN_WIDTH, h - 2 - half, false, pressedSpin == -1, enabled);
    }
}
