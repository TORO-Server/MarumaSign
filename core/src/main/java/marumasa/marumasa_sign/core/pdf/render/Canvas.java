package marumasa.marumasa_sign.core.pdf.render;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * 描く先の画像。ページそのものと、透明のグループやソフトマスクを描くための一時的な画像の両方に使う。
 * 座標はページの既定の空間 (ポイント) で受け取り、base で画素の座標にする。
 * <p>
 * 普通の描画 (不透明度だけ) は Java2D にそのまま任せる。ソフトマスクや Normal 以外の合成の方法 (Multiply など) があるときは、
 * いったん透明な画像に描いてから、画素ごとに混ぜる。
 */
final class Canvas {

    /**
     * Graphics2D に何かを描くもの。
     */
    interface Painter {
        void paint(Graphics2D g);
    }

    final BufferedImage image;
    final int[] pixels;
    final int width, height;
    // 画素の座標での、この画像の左上の位置 (ページ全体の画像に対して)
    final int originX, originY;
    // ページの既定の空間から、この画像の画素の座標へ
    final AffineTransform base;
    private final Graphics2D graphics;
    private final boolean antialias;
    // ノックアウトのグループ (11.4.8) の中で描いているときの、グループの初めの背景 (knockoutArea の範囲の画素)。null ならノックアウトしない
    int[] knockoutBackdrop;
    Rectangle knockoutArea;

    Canvas(int width, int height, int originX, int originY, AffineTransform pageToDevice, boolean opaqueWhite, boolean antialias) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.originX = originX;
        this.originY = originY;
        this.antialias = antialias;
        image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (opaqueWhite) java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        base = AffineTransform.getTranslateInstance(-originX, -originY);
        base.concatenate(pageToDevice);
        graphics = image.createGraphics();
        hints(graphics, antialias);
        graphics.setTransform(base);
    }

    static void hints(Graphics2D g, boolean antialias) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    void dispose() {
        graphics.dispose();
    }

    /**
     * 描く。
     *
     * @param pageBounds 描くものの範囲 (ページの空間)。ソフトマスクなどで一時的な画像を作る大きさに使う
     * @param clip       クリップ (ページの空間、null なら制限なし)
     */
    void paint(Painter painter, Rectangle2D pageBounds, double alpha, SoftMask mask, String blend, Area clip) {
        // ノックアウトのグループの中では、不透明度 0 のものも、形の中を初めの背景に戻す
        if (alpha <= 0 && knockoutBackdrop == null) return;
        final boolean normal = blend == null || blend.equals("Normal") || blend.equals("Compatible");
        // 不透明で普通の重ね方なら、ノックアウトしても結果は同じ (形の割合で、前のものを置き換える)
        if (mask == null && normal && (knockoutBackdrop == null || alpha >= 1)) {
            graphics.setTransform(base);
            graphics.setClip(clip);
            graphics.setComposite(alpha >= 1 ? AlphaComposite.SrcOver : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
            try {
                painter.paint(graphics);
            } finally {
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setClip(null);
            }
            return;
        }
        // 一時的な画像に描いてから混ぜる
        Rectangle area = deviceBounds(pageBounds, clip);
        if (area == null) return;
        final BufferedImage layer = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = layer.createGraphics();
        try {
            hints(g, antialias);
            final AffineTransform transform = AffineTransform.getTranslateInstance(-area.x, -area.y);
            transform.concatenate(base);
            g.setTransform(transform);
            g.setClip(clip);
            painter.paint(g);
        } finally {
            g.dispose();
        }
        final int[] source = ((DataBufferInt) layer.getRaster().getDataBuffer()).getData();
        composite(source, area, alpha, mask, normal ? null : blend);
    }

    /**
     * ページの空間の範囲を、この画像の画素の範囲にする (クリップと画像の大きさで切る)。空なら null。
     */
    Rectangle deviceBounds(Rectangle2D pageBounds, Area clip) {
        Rectangle area = new Rectangle(0, 0, width, height);
        if (pageBounds != null) {
            // なめらかにする処理のはみ出しの分を、少し広げる
            final Rectangle r = base.createTransformedShape(pageBounds).getBounds();
            r.grow(2, 2);
            area = area.intersection(r);
        }
        if (clip != null) {
            final Rectangle r = base.createTransformedShape(clip).getBounds();
            r.grow(1, 1);
            area = area.intersection(r);
        }
        return area.isEmpty() ? null : area;
    }

    /**
     * 画素の並び source (area の大きさ、ARGB) を、不透明度、ソフトマスク、合成の方法を使って混ぜる。
     */
    void composite(int[] source, Rectangle area, double alpha, SoftMask mask, String blend) {
        final int alpha256 = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        final Blend mode = blend == null ? null : Blend.of(blend);
        for (int y = 0; y < area.height; y++) {
            final int dy = area.y + y;
            if (dy < 0 || dy >= height) continue;
            for (int x = 0; x < area.width; x++) {
                final int dx = area.x + x;
                if (dx < 0 || dx >= width) continue;
                final int s = source[y * area.width + x];
                final int shape = s >>> 24;
                if (shape == 0) continue;
                final int index = dy * width + dx;
                if (knockoutBackdrop != null && knockoutArea.contains(dx, dy)) {
                    // ノックアウト: 不透明度だけで初めの背景に重ね、形の割合で前のものと置き換える
                    int q = alpha256;
                    if (mask != null) q = q * mask.value(dx + originX, dy + originY) / 255;
                    final int backdrop = knockoutBackdrop[(dy - knockoutArea.y) * knockoutArea.width + dx - knockoutArea.x];
                    final int result = q == 0 ? backdrop : mode == null ? over(s, q, backdrop) : mode.apply(s, q, backdrop);
                    pixels[index] = shape == 255 ? result : mix(pixels[index], result, shape);
                    continue;
                }
                int sa = shape * alpha256 / 255;
                if (mask != null) sa = sa * mask.value(dx + originX, dy + originY) / 255;
                if (sa == 0) continue;
                pixels[index] = mode == null ? over(s, sa, pixels[index]) : mode.apply(s, sa, pixels[index]);
            }
        }
    }

    // 普通の重ね方 (色は乗算していない ARGB)
    static int over(int source, int sourceAlpha, int backdrop) {
        final int ba = backdrop >>> 24;
        if (sourceAlpha == 255 || ba == 0) return (sourceAlpha << 24) | (source & 0xFFFFFF);
        final int ra = sourceAlpha + ba * (255 - sourceAlpha) / 255;
        if (ra == 0) return 0;
        int result = ra << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final int cs = (source >> shift) & 0xFF, cb = (backdrop >> shift) & 0xFF;
            final int c = (cs * sourceAlpha + cb * ba * (255 - sourceAlpha) / 255) / ra;
            result |= Math.min(255, c) << shift;
        }
        return result;
    }

    // from から to へ、t (0 から 255) の割合で寄せた色 (色は乗算していない ARGB。乗算した値で混ぜる)
    static int mix(int from, int to, int t) {
        final int fa = from >>> 24, ta = to >>> 24;
        final int wf = fa * (255 - t), wt = ta * t;
        final int a = (wf + wt + 127) / 255;
        if (a == 0 || wf + wt == 0) return 0;
        int result = a << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final int c = (((from >> shift) & 0xFF) * wf + ((to >> shift) & 0xFF) * wt + (wf + wt) / 2) / (wf + wt);
            result |= Math.min(255, c) << shift;
        }
        return result;
    }

    /**
     * 今の画素のうち、範囲 area の写し (ノックアウトのグループの初めの背景に使う)。
     */
    int[] copy(Rectangle area) {
        final int[] copy = new int[area.width * area.height];
        for (int y = 0; y < area.height; y++) System.arraycopy(pixels, (area.y + y) * width + area.x, copy, y * area.width, area.width);
        return copy;
    }

    /**
     * 別の画像 (透明のグループ) を、この画像に重ねる。
     */
    void compositeLayer(Canvas layer, double alpha, SoftMask mask, String blend) {
        final Rectangle area = new Rectangle(layer.originX - originX, layer.originY - originY, layer.width, layer.height);
        final boolean normal = blend == null || blend.equals("Normal") || blend.equals("Compatible");
        composite(layer.pixels, area, alpha, mask, normal ? null : blend);
    }

    /**
     * 形の、この画像の画素の範囲 (はみ出しを 2 画素分広げたもの)。
     */
    Rectangle bounds(Shape pageShape) {
        final Rectangle r = base.createTransformedShape(pageShape).getBounds();
        r.grow(2, 2);
        return r.intersection(new Rectangle(0, 0, width, height));
    }
}
