package marumasa.marumasa_sign.core.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Path2D;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 画面の見た目 (クラシックなデスクトップ風) をまとめたクラス。
 */
public final class Theme {

    public static final int TITLE_HEIGHT = 28;
    public static final int BORDER = 3;

    public static final Color FACE = new Color(0xECE9D8);
    public static final Color TEXT = Color.BLACK;
    public static final Color TEXT_DISABLED = new Color(0xACA899);
    public static final Color FIELD_BORDER = new Color(0x7F9DB9);
    public static final Color FIELD_DISABLED = new Color(0xF5F4EA);
    public static final Color SELECTION = new Color(0x316AC5);
    public static final Color GROUP_BORDER = new Color(0xD0D0BF);
    public static final Color GROUP_TITLE = new Color(0x0046D5);
    public static final Color ERROR_TEXT = new Color(0xC00000);

    private static final Color FRAME = new Color(0x0831D9);
    private static final Color BUTTON_BORDER = new Color(0x003C74);
    private static final Color BUTTON_BORDER_DISABLED = new Color(0xC9C7BA);
    private static final Color BUTTON_HOVER = new Color(0xE5971F);
    private static final Color BUTTON_DEFAULT = new Color(0x89ADE4);
    private static final Color SCROLL_TRACK = new Color(0xF3F1EC);
    private static final Color SCROLL_THUMB = new Color(0xC3D5FD);
    private static final Color SCROLL_BORDER = new Color(0x98B1E4);

    private static final float[] TITLE_STOPS = {0f, 0.08f, 0.40f, 0.88f, 0.95f, 1f};
    private static final Color[] TITLE_COLORS = {
            new Color(0x0997FF), new Color(0x0053EE), new Color(0x0050EE),
            new Color(0x0066FF), new Color(0x005BFF), new Color(0x003DD7)
    };

    private static final Font FONT = new Font("Tahoma", Font.PLAIN, 11);
    private static final Font FONT_FALLBACK = new Font(Font.DIALOG, Font.PLAIN, 11);
    private static final Font TITLE_FONT = new Font("Trebuchet MS", Font.BOLD, 13);
    private static final Font TITLE_FONT_FALLBACK = new Font(Font.DIALOG, Font.BOLD, 13);

    private Theme() {
    }

    // 代替フォントでも表示できない文字 (タミル文字など) のために、インストールされているフォントから探して選んだフォント。
    // キーは「文字の種類,スタイル,大きさ」。見つからなかった種類は代替フォントのままにする
    private static final Map<String, Font> FONT_BY_SCRIPT = new HashMap<String, Font>();
    // 先に試すフォント。多くの文字を含み、どの OS にもどれかがあることが多い
    private static final String[] PREFERRED_FAMILIES = {
            "Segoe UI", "Nirmala UI", "Segoe UI Historic", "Segoe UI Symbol", "Gadugi", "Ebrima",
            "Noto Sans", "Arial Unicode MS", "Lucida Grande", "DejaVu Sans"
    };
    private static String[] installedFamilies;

    // 文字列をすべて表示できるフォントを選ぶ (日本語などは代替フォントになる)。
    // 1 つのフォントでは表示できない文字列 (上下逆さの英語の一部の文字など) は、最初に表示できなかった文字に合わせる
    public static Font font(String text) {
        if (text == null || FONT.canDisplayUpTo(text) < 0) return FONT;
        final int missing = FONT_FALLBACK.canDisplayUpTo(text);
        return missing < 0 ? FONT_FALLBACK : scriptFont(text.codePointAt(missing), FONT_FALLBACK);
    }

    private static Font titleFont(String text) {
        if (TITLE_FONT.canDisplayUpTo(text) < 0) return TITLE_FONT;
        final int missing = TITLE_FONT_FALLBACK.canDisplayUpTo(text);
        return missing < 0 ? TITLE_FONT_FALLBACK : scriptFont(text.codePointAt(missing), TITLE_FONT_FALLBACK);
    }

    // 1 つのフォントで表示できるかどうか。できなければ、文字ごとにフォントを変えて描く
    private static boolean singleFont(String text) {
        return FONT.canDisplayUpTo(text) < 0 || FONT_FALLBACK.canDisplayUpTo(text) < 0;
    }

    // 文字ごとに、表示できるフォントを割り当てた文字列
    private static AttributedString runs(String text) {
        final AttributedString result = new AttributedString(text);
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            final int next = i + Character.charCount(codePoint);
            final Font font = FONT.canDisplay(codePoint) ? FONT
                    : FONT_FALLBACK.canDisplay(codePoint) ? FONT_FALLBACK : scriptFont(codePoint, FONT_FALLBACK);
            result.addAttribute(TextAttribute.FONT, font, i, next);
            i = next;
        }
        return result;
    }

    // 代替フォントでも表示できない文字の種類のために、その種類の文字を表示できるフォントを探す
    private static synchronized Font scriptFont(int codePoint, Font dialog) {
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        final String key = script + "," + dialog.getStyle() + "," + dialog.getSize();
        if (FONT_BY_SCRIPT.containsKey(key)) {
            final Font found = FONT_BY_SCRIPT.get(key);
            return found != null ? found : dialog;
        }
        final Font found = findFont(codePoint, dialog);
        FONT_BY_SCRIPT.put(key, found);
        return found != null ? found : dialog;
    }

    private static Font findFont(int codePoint, Font dialog) {
        if (installedFamilies == null) {
            try {
                installedFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            } catch (RuntimeException e) {
                installedFamilies = new String[0];
            }
        }
        final List<String> candidates = new ArrayList<String>(Arrays.asList(PREFERRED_FAMILIES));
        candidates.retainAll(Arrays.asList(installedFamilies));
        candidates.addAll(Arrays.asList(installedFamilies));
        for (String family : candidates) {
            final Font font = new Font(family, dialog.getStyle(), dialog.getSize());
            if (font.canDisplay(codePoint)) return font;
        }
        return null;
    }

    public static int textWidth(Graphics2D g, String text) {
        if (text == null || text.isEmpty() || singleFont(text)) return g.getFontMetrics(font(text)).stringWidth(text);
        return (int) Math.ceil(new TextLayout(runs(text).getIterator(), g.getFontRenderContext()).getAdvance());
    }

    // 描画先がない場面 (クリック位置の計算など) で文字列の幅を測るためのもの
    private static final Graphics2D SCRATCH =
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();

    public static int textWidth(String text) {
        return textWidth(SCRATCH, text);
    }

    public static int textWidth(String text, Font font) {
        return SCRATCH.getFontMetrics(font).stringWidth(text);
    }

    // 文字列を縦方向の中央に描く
    public static void drawText(Graphics2D g, String text, int x, int y, int height, Color color) {
        if (text == null || text.isEmpty()) return;
        g.setColor(color);
        if (!singleFont(text)) {
            // 行の高さは代替フォントに合わせる
            final FontMetrics metrics = g.getFontMetrics(FONT_FALLBACK);
            g.setFont(FONT_FALLBACK);
            g.drawString(runs(text).getIterator(), x, y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
            return;
        }
        final Font font = font(text);
        final FontMetrics metrics = g.getFontMetrics(font);
        g.setFont(font);
        g.drawString(text, x, y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
    }

    public static void drawTextCentered(Graphics2D g, String text, int x, int y, int width, int height, Color color) {
        drawText(g, text, x + (width - textWidth(g, text)) / 2, y, height, color);
    }

    // 幅に収まらない文字列を末尾の "..." で省略する
    public static String ellipsize(Graphics2D g, String text, int width) {
        if (text == null || textWidth(g, text) <= width) return text;
        final String dots = "...";
        int end = text.length();
        while (end > 0 && textWidth(g, text.substring(0, end) + dots) > width) end--;
        // サロゲートペア (絵文字など) の前半だけを残さない
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + dots;
    }

    // 上の角だけが丸い形
    private static Shape topRounded(float x, float y, float w, float h, float r) {
        final Path2D.Float path = new Path2D.Float();
        path.moveTo(x, y + h);
        path.lineTo(x, y + r);
        path.quadTo(x, y, x + r, y);
        path.lineTo(x + w - r, y);
        path.quadTo(x + w, y, x + w, y + r);
        path.lineTo(x + w, y + h);
        path.closePath();
        return path;
    }

    // ウィンドウの枠とタイトルバーを描く
    public static void paintWindow(Graphics2D g, int width, int height, String title, int closeState) {
        g.setColor(FRAME);
        g.fill(topRounded(0, 0, width, height, 8));

        g.setPaint(new LinearGradientPaint(0, 0, 0, TITLE_HEIGHT, TITLE_STOPS, TITLE_COLORS));
        g.fill(topRounded(0, 0, width, TITLE_HEIGHT, 8));

        g.setColor(FACE);
        g.fillRect(BORDER, TITLE_HEIGHT, width - BORDER * 2, height - TITLE_HEIGHT - BORDER);

        final Font font = titleFont(title);
        final FontMetrics metrics = g.getFontMetrics(font);
        final int baseline = (TITLE_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setFont(font);
        g.setColor(new Color(0x0A1883));
        g.drawString(title, 9, baseline + 1);
        g.setColor(Color.WHITE);
        g.drawString(title, 8, baseline);

        paintCloseButton(g, closeX(width), CLOSE_Y, closeState);
    }

    public static final int CLOSE_SIZE = 21;
    public static final int CLOSE_Y = 4;

    public static int closeX(int windowWidth) {
        return windowWidth - CLOSE_SIZE - 6;
    }

    private static void paintCloseButton(Graphics2D g, int x, int y, int state) {
        final Color top = state == Button.PRESSED ? new Color(0xB5320F) : state == Button.HOVER ? new Color(0xF1836A) : new Color(0xE7755A);
        final Color bottom = state == Button.PRESSED ? new Color(0x8E2A0E) : state == Button.HOVER ? new Color(0xDB4A26) : new Color(0xC3391B);
        g.setPaint(new GradientPaint(0, y, top, 0, y + CLOSE_SIZE, bottom));
        g.fillRoundRect(x, y, CLOSE_SIZE, CLOSE_SIZE, 6, 6);
        g.setColor(Color.WHITE);
        g.drawRoundRect(x, y, CLOSE_SIZE - 1, CLOSE_SIZE - 1, 6, 6);

        g.setStroke(new BasicStroke(2f));
        g.drawLine(x + 6, y + 6, x + CLOSE_SIZE - 7, y + CLOSE_SIZE - 7);
        g.drawLine(x + CLOSE_SIZE - 7, y + 6, x + 6, y + CLOSE_SIZE - 7);
        g.setStroke(new BasicStroke(1f));
    }

    public static void paintButton(Graphics2D g, int x, int y, int w, int h, String text, int state, boolean isDefault) {
        final boolean disabled = state == Button.DISABLED;
        if (state == Button.PRESSED) {
            g.setPaint(new GradientPaint(0, y, new Color(0xD6D0C5), 0, y + h, new Color(0xECEBE5)));
        } else {
            g.setPaint(new GradientPaint(0, y, Color.WHITE, 0, y + h, disabled ? new Color(0xF5F4EA) : new Color(0xD6D0C5)));
        }
        g.fillRoundRect(x, y, w, h, 6, 6);

        if (!disabled && state == Button.HOVER) {
            g.setColor(BUTTON_HOVER);
            g.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 4, 4);
        } else if (!disabled && isDefault) {
            g.setColor(BUTTON_DEFAULT);
            g.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 4, 4);
        }
        g.setColor(disabled ? BUTTON_BORDER_DISABLED : BUTTON_BORDER);
        g.drawRoundRect(x, y, w - 1, h - 1, 6, 6);

        if (text != null) {
            final int offset = state == Button.PRESSED ? 1 : 0;
            drawTextCentered(g, text, x + offset, y + offset, w, h, disabled ? TEXT_DISABLED : TEXT);
        }
    }

    // 数値欄の右端にある、値を増減する小さなボタン
    public static void paintSpinButton(Graphics2D g, int x, int y, int w, int h, boolean up, boolean pressed, boolean enabled) {
        g.setPaint(pressed
                ? new GradientPaint(0, y, new Color(0xB9C9EF), 0, y + h, new Color(0xDCE5FB))
                : new GradientPaint(0, y, new Color(0xE6EEFC), 0, y + h, new Color(0xC1D2F8)));
        g.fillRect(x, y, w, h);
        g.setColor(enabled ? SCROLL_BORDER : BUTTON_BORDER_DISABLED);
        g.drawRect(x, y, w - 1, h - 1);

        // 上向き・下向きの小さな三角形
        g.setColor(enabled ? new Color(0x4D6185) : TEXT_DISABLED);
        final int centerX = x + w / 2;
        final int centerY = y + h / 2;
        for (int i = 0; i < 3; i++) {
            final int row = up ? centerY - 1 + i : centerY + 1 - i;
            g.drawLine(centerX - i, row, centerX + i, row);
        }
    }

    public static void paintField(Graphics2D g, int x, int y, int w, int h, boolean enabled) {
        g.setColor(enabled ? Color.WHITE : FIELD_DISABLED);
        g.fillRect(x, y, w, h);
        g.setColor(FIELD_BORDER);
        g.drawRect(x, y, w - 1, h - 1);
    }

    public static void paintGroup(Graphics2D g, int x, int y, int w, int h, String title) {
        g.setColor(GROUP_BORDER);
        g.drawRoundRect(x, y + 6, w - 1, h - 7, 6, 6);
        if (title != null && !title.isEmpty()) {
            final int textWidth = textWidth(g, title);
            g.setColor(FACE);
            g.fillRect(x + 7, y, textWidth + 6, 13);
            drawText(g, title, x + 10, y, 13, GROUP_TITLE);
        }
    }

    // 看板の PDF に重ねる、ページ送りの帯の高さと、ボタンの大きさ
    public static final int PAGE_BAR_HEIGHT = 28;
    public static final int PAGE_BUTTON_WIDTH = 40;
    public static final int PAGE_BUTTON_MARGIN = 3;

    /**
     * 看板の PDF に重ねる、ページ送りの帯を描く。左右にページを戻す・進めるボタン、中央に「現在のページ / 総ページ数」。
     *
     * @param previousState 戻すボタンの状態 (Button.NORMAL など)
     * @param nextState     進めるボタンの状態
     */
    public static void paintPageBar(Graphics2D g, int width, String text, int previousState, int nextState) {
        final int h = PAGE_BAR_HEIGHT;
        // ウィンドウと同じ色の枠を付けた、面の色の帯
        g.setColor(FRAME);
        g.fillRoundRect(0, 0, width, h, 8, 8);
        g.setColor(FACE);
        g.fillRoundRect(2, 2, width - 4, h - 4, 6, 6);

        final int buttonHeight = h - PAGE_BUTTON_MARGIN * 2 - 2;
        final int y = PAGE_BUTTON_MARGIN + 1;
        final int previousX = PAGE_BUTTON_MARGIN + 1;
        final int nextX = width - PAGE_BUTTON_MARGIN - 1 - PAGE_BUTTON_WIDTH;
        paintButton(g, previousX, y, PAGE_BUTTON_WIDTH, buttonHeight, null, previousState, false);
        paintButton(g, nextX, y, PAGE_BUTTON_WIDTH, buttonHeight, null, nextState, false);
        paintArrow(g, previousX, y, PAGE_BUTTON_WIDTH, buttonHeight, false, previousState);
        paintArrow(g, nextX, y, PAGE_BUTTON_WIDTH, buttonHeight, true, nextState);

        final int textX = previousX + PAGE_BUTTON_WIDTH;
        drawTextCentered(g, text, textX, 0, nextX - textX, h, TEXT);
    }

    // ページ送りのボタンの三角形。right なら右向き
    private static void paintArrow(Graphics2D g, int x, int y, int w, int h, boolean right, int state) {
        final int offset = state == Button.PRESSED ? 1 : 0;
        final float cx = x + w / 2f + offset, cy = y + h / 2f + offset;
        final float size = h * 0.28f;
        final Path2D.Float path = new Path2D.Float();
        if (right) {
            path.moveTo(cx - size * 0.6f, cy - size);
            path.lineTo(cx + size * 0.8f, cy);
            path.lineTo(cx - size * 0.6f, cy + size);
        } else {
            path.moveTo(cx + size * 0.6f, cy - size);
            path.lineTo(cx - size * 0.8f, cy);
            path.lineTo(cx + size * 0.6f, cy + size);
        }
        path.closePath();
        g.setColor(state == Button.DISABLED ? TEXT_DISABLED : new Color(0x1C3D82));
        g.fill(path);
    }

    public static void paintScrollBar(Graphics2D g, int x, int y, int w, int h, int thumbY, int thumbHeight) {
        g.setColor(SCROLL_TRACK);
        g.fillRect(x, y, w, h);
        g.setColor(GROUP_BORDER);
        g.drawLine(x, y, x, y + h - 1);
        if (thumbHeight > 0) {
            g.setColor(SCROLL_THUMB);
            g.fillRoundRect(x + 2, thumbY, w - 3, thumbHeight, 4, 4);
            g.setColor(SCROLL_BORDER);
            g.drawRoundRect(x + 2, thumbY, w - 4, thumbHeight - 1, 4, 4);
        }
    }
}
