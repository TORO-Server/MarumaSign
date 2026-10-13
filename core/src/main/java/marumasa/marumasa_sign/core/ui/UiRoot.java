package marumasa.marumasa_sign.core.ui;

import marumasa.marumasa_sign.core.Lang;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.List;

/**
 * MarumaSign のウィンドウ。
 * 画面全体を 1 枚の画像として描画し、各バージョンの実装はそれをテクスチャとして表示するだけにする。
 * 座標の入力はすべてフレームバッファのピクセル単位で受け取る。
 */
public final class UiRoot {

    // ウィンドウの大きさ (拡大前)
    public static final int WIDTH = 548;
    public static final int HEIGHT = 352;

    private static final int MAX_SCALE = 4;
    // キャレットの点滅間隔 (tick)
    private static final int CARET_TICKS = 10;

    final Platform platform;

    private MainPage mainPage;
    private Page page;
    private boolean open;

    private int scale = 1;
    private int originX, originY;
    private BufferedImage surface;
    private int[] pixels;
    private boolean dirty = true;

    private Object texture;
    private int textureWidth, textureHeight;

    private int closeState = Button.NORMAL;
    private boolean closePressed;
    private int caretTicks;

    public UiRoot(Platform platform) {
        this.platform = platform;
    }

    public boolean isOpen() {
        return open;
    }

    public MainPage mainPage() {
        return mainPage;
    }

    // 画面が開かれたとき
    public void opened() {
        Lang.select(platform.languageCode());
        if (mainPage == null) mainPage = new MainPage(this);
        open = true;
        showPage(mainPage);
    }

    // 画面が閉じられたとき
    public void closed() {
        open = false;
        if (texture != null) {
            platform.releaseTexture(texture);
            texture = null;
        }
        surface = null;
        pixels = null;
    }

    void showPage(Page next) {
        page = next;
        closeState = Button.NORMAL;
        next.shown();
        repaint();
    }

    void showMainPage() {
        showPage(mainPage);
    }

    void close() {
        platform.closeUi();
    }

    void repaint() {
        dirty = true;
    }

    void resetCaret() {
        caretTicks = 0;
    }

    boolean caretVisible() {
        return caretTicks < CARET_TICKS;
    }

    // ---------- 表示 ----------

    // フレームバッファの大きさに合わせて、拡大率と位置を決める
    public void layout(int framebufferWidth, int framebufferHeight) {
        final int fit = Math.min(framebufferWidth / WIDTH, framebufferHeight / HEIGHT);
        final int newScale = Math.max(1, Math.min(MAX_SCALE, fit));
        if (newScale != scale || surface == null) {
            scale = newScale;
            surface = new BufferedImage(WIDTH * scale, HEIGHT * scale, BufferedImage.TYPE_INT_ARGB);
            pixels = ((DataBufferInt) surface.getRaster().getDataBuffer()).getData();
            dirty = true;
        }
        originX = Math.max(0, (framebufferWidth - WIDTH * scale) / 2);
        originY = Math.max(0, (framebufferHeight - HEIGHT * scale) / 2);
    }

    // 表示位置と大きさ (フレームバッファのピクセル単位)
    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int pixelWidth() {
        return WIDTH * scale;
    }

    public int pixelHeight() {
        return HEIGHT * scale;
    }

    /**
     * 現在の画面内容のテクスチャを返す。毎フレーム呼ぶこと。
     * 内容が変わっていれば描き直してテクスチャを更新する。
     */
    public Object texture() {
        if (surface == null || page == null) return null;
        final int width = pixelWidth();
        final int height = pixelHeight();
        if (texture != null && (textureWidth != width || textureHeight != height)) {
            platform.releaseTexture(texture);
            texture = null;
        }
        if (dirty || texture == null) {
            paint();
            dirty = false;
            if (texture == null) {
                texture = platform.createTexture(width, height, pixels);
                textureWidth = width;
                textureHeight = height;
            } else {
                platform.updateTexture(texture, width, height, pixels);
            }
        }
        return texture;
    }

    private void paint() {
        final Graphics2D g = surface.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, surface.getWidth(), surface.getHeight());
            g.setComposite(AlphaComposite.SrcOver);

            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);

            Theme.paintWindow(g, WIDTH, HEIGHT, page.title(), closeState);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            page.paint(g);
        } catch (RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to paint the window", e);
        } finally {
            g.dispose();
        }
    }

    // ---------- 入力 ----------

    private int toLocalX(double px) {
        return (int) Math.floor((px - originX) / scale);
    }

    private int toLocalY(double py) {
        return (int) Math.floor((py - originY) / scale);
    }

    private boolean onClose(int mx, int my) {
        final int cx = Theme.closeX(WIDTH);
        return mx >= cx && mx < cx + Theme.CLOSE_SIZE && my >= Theme.CLOSE_Y && my < Theme.CLOSE_Y + Theme.CLOSE_SIZE;
    }

    private void setCloseState(int state) {
        if (closeState != state) {
            closeState = state;
            repaint();
        }
    }

    public void mouseMoved(double px, double py) {
        if (page == null) return;
        final int mx = toLocalX(px), my = toLocalY(py);
        final boolean over = onClose(mx, my);
        setCloseState(closePressed ? (over ? Button.PRESSED : Button.NORMAL) : over ? Button.HOVER : Button.NORMAL);
        if (!closePressed) page.mouseMoved(mx, my);
    }

    public void mousePressed(double px, double py) {
        if (page == null) return;
        final int mx = toLocalX(px), my = toLocalY(py);
        if (onClose(mx, my)) {
            closePressed = true;
            setCloseState(Button.PRESSED);
            return;
        }
        page.mousePressed(mx, my);
    }

    public void mouseReleased(double px, double py) {
        if (page == null) return;
        final int mx = toLocalX(px), my = toLocalY(py);
        if (closePressed) {
            closePressed = false;
            setCloseState(Button.NORMAL);
            if (onClose(mx, my)) close();
            return;
        }
        page.mouseReleased(mx, my);
    }

    // amount: 上方向が正
    public void mouseScrolled(double px, double py, double amount) {
        if (page == null || amount == 0) return;
        page.mouseScrolled(toLocalX(px), toLocalY(py), amount > 0 ? 1 : -1);
    }

    // key, modifiers は Key クラスの値
    public boolean keyPressed(int key, int modifiers) {
        if (page == null) return false;
        resetCaret();
        repaint();
        return page.keyPressed(key, modifiers);
    }

    public void charTyped(char c) {
        if (page == null) return;
        resetCaret();
        page.charTyped(c);
    }

    public void filesDropped(List<File> files) {
        if (page == null || files == null || files.isEmpty()) return;
        showMainPage();
        mainPage.filesDropped(files);
    }

    public void tick() {
        if (!open || page == null) return;
        caretTicks = (caretTicks + 1) % (CARET_TICKS * 2);
        if (page.focus instanceof TextField && (caretTicks == 0 || caretTicks == CARET_TICKS)) repaint();
        page.tick();
    }
}
