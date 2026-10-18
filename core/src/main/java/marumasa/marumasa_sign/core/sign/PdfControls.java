package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.ui.Button;
import marumasa.marumasa_sign.core.ui.Theme;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * 看板の PDF に重ねて表示する、ページ送りの操作部分。
 * 照準 (画面中央) が PDF の看板に合っている間だけ、その看板の下側に帯を表示し、
 * 帯の左右のボタンに照準を合わせて使用キー (右クリック) を押すと、ページを戻す・進める。
 * 照準が外れると帯は消える。帯の見た目は MarumaSign の画面と同じテーマで描く。
 * メインスレッド (描画と tick) からのみ使う。
 */
public final class PdfControls {

    public static final int NONE = 0;
    public static final int PREVIOUS = 1;
    public static final int NEXT = 2;
    // 帯のボタン以外の場所 (ページの上)
    public static final int PAGE = 3;

    // 照準が合っているとみなす時間。描画が止まった (看板が見えなくなった) あとは、この時間で外れたことにする
    private static final long HOVER_NANOS = 500_000_000L;
    // ボタンを押したときに、押された見た目にしておく時間
    private static final long PRESS_NANOS = 150_000_000L;
    // ブロックに照準が合っていないとき (遠くの絵を見ているとき) に、操作できる距離 (ブロック)
    private static final double REACH = 6.0;
    // 照準が合っているブロックより、どれだけ奥まで操作できるか (ブロック)。看板の板の当たり判定は絵より手前にある
    private static final double BEHIND_HIT = 1.0;
    // 帯を、絵の表面からどれだけ手前に浮かせるか (ブロック)
    private static final float LIFT = 0.004f;
    // 帯の 1 単位あたりのピクセル数 (テクスチャの細かさ)
    private static final int PIXELS_PER_UNIT = 3;
    // 帯の幅の下限 (単位)。これより狭い絵では、帯を低くして縦横の比を保つ
    private static final int MIN_BAR_UNITS = 120;
    private static final int MAX_TEXTURE_WIDTH = 2048;

    private final Platform platform;

    // 照準が合っている PDF と、その看板の位置
    private PdfSession hovered;
    private int hoveredX, hoveredY, hoveredZ;
    private double hoveredDistance;
    private long hoveredAt;
    private int hoverPart = NONE;
    // 押されたボタンと、押された見た目を終える時刻
    private int pressedPart = NONE;
    private long pressedUntil;

    // 帯のテクスチャと、その内容
    private Object texture;
    private int textureWidth, textureHeight;
    private BufferedImage surface;
    private String paintedKey;

    // 最後に描いた PDF の看板の、絵の中央と各ボタンの中央 (ワールド座標)。動作確認で照準を合わせるのに使う
    private final double[] lastCenter = new double[3];
    private final double[] lastPrevious = new double[3];
    private final double[] lastNext = new double[3];
    private boolean hasLast;

    PdfControls(Platform platform) {
        this.platform = platform;
    }

    // 照準が PDF の看板に合っているかどうか
    public boolean isHovering() {
        return hovered != null && System.nanoTime() - hoveredAt < HOVER_NANOS;
    }

    // 照準が合っている部分 (NONE, PREVIOUS, NEXT, PAGE)
    public int hoverPart() {
        return isHovering() ? hoverPart : NONE;
    }

    /**
     * 使用キー (右クリック) が押されたときに呼ぶ。
     * 照準が PDF の看板に合っていれば、ボタンならページを送り、true を返す (ゲームには渡さない)。
     */
    public boolean click() {
        if (!isHovering()) return false;
        final int part = hoverPart;
        if (part == PREVIOUS || part == NEXT) {
            if (hovered.turn(part == NEXT ? 1 : -1)) {
                pressedPart = part;
                pressedUntil = System.nanoTime() + PRESS_NANOS;
            }
        }
        return true;
    }

    /**
     * PDF の看板を描くたびに呼ぶ。照準が合っているかを調べ、合っていれば帯の四角形を返す (なければ null)。
     *
     * @param mesh SignImage.mesh の頂点列 (ブロックの角を原点とした座標)
     */
    SignImage.Batch overlay(PdfSession session, float[] mesh, int blockX, int blockY, int blockZ) {
        // 絵の表の 4 頂点のうち 3 つ。p0 は右下、p1 は右上、p3 は左下 (表から見て)
        final int s = SignImage.STRIDE;
        final double p0x = mesh[0] + blockX, p0y = mesh[1] + blockY, p0z = mesh[2] + blockZ;
        final double ux = mesh[3 * s] + blockX - p0x, uy = mesh[3 * s + 1] + blockY - p0y, uz = mesh[3 * s + 2] + blockZ - p0z;
        final double vx = mesh[s] + blockX - p0x, vy = mesh[s + 1] + blockY - p0y, vz = mesh[s + 2] + blockZ - p0z;
        // 表の向き: 表の頂点から、同じ位置の裏の頂点 (最後の頂点) を引いたもの
        double nx = mesh[0] - mesh[7 * s], ny = mesh[1] - mesh[7 * s + 1], nz = mesh[2] - mesh[7 * s + 2];
        final double nLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        final double uu = ux * ux + uy * uy + uz * uz, vv = vx * vx + vy * vy + vz * vz;
        if (nLength <= 0 || uu <= 0 || vv <= 0) return null;
        nx /= nLength;
        ny /= nLength;
        nz /= nLength;

        final double width = Math.sqrt(uu), height = Math.sqrt(vv);
        // 帯の高さ (ブロック) と、帯の幅に対する比
        double bar = Math.max(0.12, Math.min(0.4, height * 0.16));
        bar = Math.min(bar, height * 0.35);
        bar = Math.min(bar, width * Theme.PAGE_BAR_HEIGHT / MIN_BAR_UNITS);
        final double barFraction = bar / height;
        final int barUnits = (int) Math.round(width / bar * Theme.PAGE_BAR_HEIGHT);
        final double buttonFraction = (Theme.PAGE_BUTTON_MARGIN + 1 + Theme.PAGE_BUTTON_WIDTH / 2.0) / barUnits;

        // 動作確認用に、絵の中央と各ボタンの中央を覚えておく (左が s = 1)
        point(lastCenter, p0x, p0y, p0z, ux, uy, uz, vx, vy, vz, 0.5, 0.5);
        point(lastPrevious, p0x, p0y, p0z, ux, uy, uz, vx, vy, vz, 1 - buttonFraction, barFraction / 2);
        point(lastNext, p0x, p0y, p0z, ux, uy, uz, vx, vy, vz, buttonFraction, barFraction / 2);
        hasLast = true;

        final boolean same = hovered != null && hoveredX == blockX && hoveredY == blockY && hoveredZ == blockZ;
        final int part = hitTest(p0x, p0y, p0z, ux, uy, uz, vx, vy, vz, nx, ny, nz, uu, vv, barFraction, barUnits);
        if (part == NONE) {
            if (same) hovered = null;
            return null;
        }
        // 照準の先に PDF の看板が重なっていれば、手前のものを選ぶ
        if (!same && isHovering() && hitDistance >= hoveredDistance) return null;
        hovered = session;
        hoveredX = blockX;
        hoveredY = blockY;
        hoveredZ = blockZ;
        hoveredDistance = hitDistance;
        hoverPart = part;

        final Object barTexture = barTexture(session, barUnits);
        // 帯を描き直すのに時間がかかることがあるので、描いたあとの時刻にする
        hoveredAt = System.nanoTime();
        if (barTexture == null) return null;
        return barBatch(barTexture, mesh, nx, ny, nz, barFraction);
    }

    // 最後の hitTest で求めた、照準から絵までの距離
    private double hitDistance;

    // 照準が絵のどこに合っているか。合っていなければ NONE
    private int hitTest(double p0x, double p0y, double p0z, double ux, double uy, double uz, double vx, double vy, double vz,
                        double nx, double ny, double nz, double uu, double vv, double barFraction, int barUnits) {
        final double[] ray = platform.crosshair();
        if (ray == null) return NONE;
        final double ox = ray[0], oy = ray[1], oz = ray[2], dx = ray[3], dy = ray[4], dz = ray[5];
        // 裏から見ているとき (と、面に沿って見ているとき) は操作できない
        final double facing = dx * nx + dy * ny + dz * nz;
        if (facing >= -1e-6) return NONE;
        final double t = ((p0x - ox) * nx + (p0y - oy) * ny + (p0z - oz) * nz) / facing;
        if (t < 0) return NONE;
        // 壁越しに操作できないよう、照準が合っているブロックより奥は除く
        final double limit = ray[6] >= 0 ? ray[6] + BEHIND_HIT : REACH;
        if (t > Math.min(limit, REACH)) return NONE;
        final double hx = ox + dx * t - p0x, hy = oy + dy * t - p0y, hz = oz + dz * t - p0z;
        final double s = (hx * ux + hy * uy + hz * uz) / uu;
        final double r = (hx * vx + hy * vy + hz * vz) / vv;
        if (s < 0 || s > 1 || r < 0 || r > 1) return NONE;
        hitDistance = t;
        if (r > barFraction) return PAGE;
        // 帯の中での位置 (単位)。左端が 0
        final double barX = (1 - s) * barUnits;
        final double barY = (1 - r / barFraction) * Theme.PAGE_BAR_HEIGHT;
        if (barY < Theme.PAGE_BUTTON_MARGIN || barY > Theme.PAGE_BAR_HEIGHT - Theme.PAGE_BUTTON_MARGIN) return PAGE;
        final int left = Theme.PAGE_BUTTON_MARGIN + 1;
        if (barX >= left && barX <= left + Theme.PAGE_BUTTON_WIDTH) return PREVIOUS;
        if (barX >= barUnits - left - Theme.PAGE_BUTTON_WIDTH && barX <= barUnits - left) return NEXT;
        return PAGE;
    }

    private static void point(double[] out, double p0x, double p0y, double p0z, double ux, double uy, double uz,
                              double vx, double vy, double vz, double s, double r) {
        out[0] = p0x + ux * s + vx * r;
        out[1] = p0y + uy * s + vy * r;
        out[2] = p0z + uz * s + vz * r;
    }

    // 帯の四角形。絵の下側に、少し手前に浮かせて置く
    private SignImage.Batch barBatch(Object barTexture, float[] mesh, double nx, double ny, double nz, double barFraction) {
        final int s = SignImage.STRIDE;
        final float liftX = (float) (nx * LIFT), liftY = (float) (ny * LIFT), liftZ = (float) (nz * LIFT);
        final float f = (float) barFraction;
        // 絵の右下 (p0)、左下 (p3)、右上 (p1) から、帯の 4 隅を求める
        final float[] p0 = {mesh[0], mesh[1], mesh[2]};
        final float[] u = {mesh[3 * s] - p0[0], mesh[3 * s + 1] - p0[1], mesh[3 * s + 2] - p0[2]};
        final float[] v = {mesh[s] - p0[0], mesh[s + 1] - p0[1], mesh[s + 2] - p0[2]};
        // 絵と同じ並び順 (右下, 右上, 左上, 左下) で、テクスチャ座標も合わせる
        final float[][] corners = {{0, 0, 1, 1}, {0, f, 1, 0}, {1, f, 0, 0}, {1, 0, 0, 1}};
        final SignImage.Batch batch = new SignImage.Batch();
        batch.texture = barTexture;
        batch.vertexCount = 4;
        batch.blend = true;
        batch.vertices = new float[4 * SignImage.Batch.STRIDE];
        for (int i = 0; i < 4; i++) {
            final float cs = corners[i][0], cr = corners[i][1];
            final int o = i * SignImage.Batch.STRIDE;
            batch.vertices[o] = p0[0] + u[0] * cs + v[0] * cr + liftX;
            batch.vertices[o + 1] = p0[1] + u[1] * cs + v[1] * cr + liftY;
            batch.vertices[o + 2] = p0[2] + u[2] * cs + v[2] * cr + liftZ;
            batch.vertices[o + 3] = corners[i][2];
            batch.vertices[o + 4] = corners[i][3];
        }
        return batch;
    }

    // 帯のテクスチャ。内容が変わったときだけ描き直す
    private Object barTexture(PdfSession session, int barUnits) {
        int scale = PIXELS_PER_UNIT;
        while (scale > 1 && barUnits * scale > MAX_TEXTURE_WIDTH) scale--;
        final int width = Math.min(MAX_TEXTURE_WIDTH, barUnits * scale);
        final int height = Theme.PAGE_BAR_HEIGHT * scale;

        final long now = System.nanoTime();
        final int pressed = now < pressedUntil ? pressedPart : NONE;
        final int previousState = session.page() <= 0 ? Button.DISABLED
                : pressed == PREVIOUS ? Button.PRESSED : hoverPart == PREVIOUS ? Button.HOVER : Button.NORMAL;
        final int nextState = session.page() >= session.pageCount() - 1 ? Button.DISABLED
                : pressed == NEXT ? Button.PRESSED : hoverPart == NEXT ? Button.HOVER : Button.NORMAL;
        final String text = (session.page() + 1) + " / " + session.pageCount();
        final String key = width + "," + height + "," + previousState + "," + nextState + "," + text;
        if (texture != null && key.equals(paintedKey)) return texture;

        try {
            if (surface == null || surface.getWidth() != width || surface.getHeight() != height) {
                surface = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            }
            final Graphics2D g = surface.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, width, height);
                g.setComposite(AlphaComposite.SrcOver);
                g.scale(width / (double) barUnits, scale);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Theme.paintPageBar(g, barUnits, text, previousState, nextState);
            } finally {
                g.dispose();
            }
            final int[] pixels = ((DataBufferInt) surface.getRaster().getDataBuffer()).getData();
            if (texture != null && textureWidth == width && textureHeight == height) {
                platform.updateTexture(texture, width, height, pixels);
            } else {
                MarumaSign.releaseTexture(platform, texture);
                texture = platform.createTexture(width, height, pixels);
                textureWidth = width;
                textureHeight = height;
            }
            paintedKey = key;
            return texture;
        } catch (RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to paint the page controls", e);
            return null;
        }
    }

    // ---------- 動作確認用 ----------

    /**
     * 最後に描いた PDF の看板の、指定した部分の中央 (ワールド座標)。まだ描いていなければ null。
     *
     * @param part PREVIOUS, NEXT, PAGE (絵の中央)
     */
    public double[] lastTarget(int part) {
        if (!hasLast) return null;
        return (part == PREVIOUS ? lastPrevious : part == NEXT ? lastNext : lastCenter).clone();
    }

    // 照準が合っている PDF の、表示したいページ (0 から始まる) と総ページ数、描いている途中か。合っていなければ null
    public int[] hoveredPage() {
        if (!isHovering()) return null;
        return new int[]{hovered.page(), hovered.pageCount(), hovered.isLoading() ? 1 : 0};
    }

    void close() {
        hovered = null;
        hasLast = false;
        if (texture != null) {
            MarumaSign.releaseTexture(platform, texture);
            texture = null;
            paintedKey = null;
        }
    }
}
