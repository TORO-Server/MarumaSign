package marumasa.marumasa_sign.core.pdf.render;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * シェーディングで塗る Java2D の Paint。
 * toPage はシェーディングの空間からページの既定の空間への変換。Graphics2D の変換 (ページから画素) と合わせて、画素ごとの色を求める。
 */
final class ShadingPaint implements Paint {

    private static final ColorModel MODEL = new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
    // メッシュを描く画像の画素の数の上限
    private static final long MAX_MESH_PIXELS = 16L * 1024 * 1024;

    private final Shading shading;
    private final AffineTransform toPage;
    // シェーディングの外を塗る色 (パターンのときの Background)。なければ 0
    private final int background;

    /**
     * @param pattern シェーディングのパターンとして塗るか (Background を使う。sh では使わない)
     */
    ShadingPaint(Shading shading, AffineTransform toPage, boolean pattern) {
        this.shading = shading;
        this.toPage = toPage;
        this.background = pattern ? shading.background : 0;
    }

    // シェーディングの空間の点 (x, y) の、BBox と Background を考えた色
    private int finish(int color, double x, double y) {
        final double[] b = shading.bbox;
        // BBox は、塗るときの一時的なクリップ (8.7.4.5.1)
        if (b != null && (x < b[0] || x > b[2] || y < b[1] || y > b[3])) return 0;
        return color >>> 24 == 0 ? background : color;
    }

    @Override
    public int getTransparency() {
        return Transparency.TRANSLUCENT;
    }

    @Override
    public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds, AffineTransform xform,
                                      RenderingHints hints) {
        final AffineTransform toDevice = new AffineTransform(xform);
        toDevice.concatenate(toPage);
        AffineTransform inverse;
        try {
            inverse = toDevice.createInverse();
        } catch (NoninvertibleTransformException e) {
            inverse = null;
        }
        if (shading.type >= 4) return new MeshContext(toDevice, inverse, deviceBounds);
        return new PointContext(inverse);
    }

    // Type 1 から 3: 画素の中心の点の色
    private final class PointContext implements PaintContext {
        private final AffineTransform inverse;

        PointContext(AffineTransform inverse) {
            this.inverse = inverse;
        }

        @Override
        public void dispose() {
        }

        @Override
        public ColorModel getColorModel() {
            return MODEL;
        }

        @Override
        public Raster getRaster(int x, int y, int w, int h) {
            final WritableRaster raster = MODEL.createCompatibleWritableRaster(w, h);
            final int[] pixels = new int[w * h];
            if (inverse != null) {
                final double[] point = new double[2];
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        point[0] = x + i + 0.5;
                        point[1] = y + j + 0.5;
                        inverse.transform(point, 0, point, 0, 1);
                        pixels[j * w + i] = finish(shading.colorAt(point[0], point[1]), point[0], point[1]);
                    }
                }
            }
            raster.setDataElements(0, 0, w, h, pixels);
            return raster;
        }
    }

    // Type 4 から 7: 三角形を画素に描いておき、そこから色を取る
    private final class MeshContext implements PaintContext {
        private final Rectangle area;
        private final int[] pixels;
        // 画素からシェーディングの空間へ (BBox と Background を使わないときと、戻せないときは null)
        private final AffineTransform inverse;

        MeshContext(AffineTransform toDevice, AffineTransform inverse, Rectangle bounds) {
            this.inverse = shading.bbox != null || background != 0 ? inverse : null;
            Rectangle region = bounds == null ? new Rectangle(0, 0, 1, 1) : new Rectangle(bounds);
            if ((long) region.width * region.height > MAX_MESH_PIXELS) region = new Rectangle(region.x, region.y, 1, 1);
            area = region;
            pixels = new int[Math.max(1, area.width * area.height)];
            final double[] p = new double[6];
            for (int t = 0; t < shading.triangles.size(); t++) {
                toDevice.transform(shading.triangles.get(t), 0, p, 0, 3);
                rasterize(p, shading.triangleColors.get(t));
            }
        }

        // 三角形の中の画素を、頂点の値の線形補間で塗る (関数があれば t を補間してから色にする)
        private void rasterize(double[] p, float[][] colors) {
            final double x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3], x2 = p[4], y2 = p[5];
            final double area2 = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (area2 == 0 || Double.isNaN(area2) || Double.isInfinite(area2)) return;
            final int minX = Math.max(area.x, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            final int maxX = Math.min(area.x + area.width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            final int minY = Math.max(area.y, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            final int maxY = Math.min(area.y + area.height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            if (minX > maxX || minY > maxY) return;
            final int n = colors[0].length;
            final float[] value = new float[n];
            final boolean function = shading.hasFunction();
            final int[] cornerColors = function ? null : new int[]{
                    shading.colorForComponents(colors[0]), shading.colorForComponents(colors[1]), shading.colorForComponents(colors[2])};
            for (int y = minY; y <= maxY; y++) {
                final double py = y + 0.5;
                for (int x = minX; x <= maxX; x++) {
                    final double px = x + 0.5;
                    // 重心座標。辺の上の点は、隣の三角形と隙間ができないよう少し甘く含める
                    final double w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area2;
                    final double w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area2;
                    final double w2 = 1 - w0 - w1;
                    if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                    final int color;
                    if (function) {
                        value[0] = (float) (w0 * colors[0][0] + w1 * colors[1][0] + w2 * colors[2][0]);
                        color = shading.colorForT(value[0]);
                    } else {
                        // 色の成分を補間すると色空間ごとの計算が重いので、角の RGB を補間する
                        color = mix(cornerColors, w0, w1, w2);
                    }
                    pixels[(y - area.y) * area.width + (x - area.x)] = color;
                }
            }
        }

        private int mix(int[] c, double w0, double w1, double w2) {
            int result = 0xFF000000;
            for (int shift = 16; shift >= 0; shift -= 8) {
                final double v = ((c[0] >> shift) & 0xFF) * w0 + ((c[1] >> shift) & 0xFF) * w1 + ((c[2] >> shift) & 0xFF) * w2;
                result |= Math.max(0, Math.min(255, (int) Math.round(v))) << shift;
            }
            return result;
        }

        @Override
        public void dispose() {
        }

        @Override
        public ColorModel getColorModel() {
            return MODEL;
        }

        @Override
        public Raster getRaster(int x, int y, int w, int h) {
            final WritableRaster raster = MODEL.createCompatibleWritableRaster(w, h);
            final int[] out = new int[w * h];
            final double[] point = new double[2];
            for (int j = 0; j < h; j++) {
                final int sy = y + j - area.y;
                for (int i = 0; i < w; i++) {
                    final int sx = x + i - area.x;
                    final int color = sx >= 0 && sx < area.width && sy >= 0 && sy < area.height ? pixels[sy * area.width + sx] : 0;
                    if (inverse == null) {
                        out[j * w + i] = color;
                    } else {
                        point[0] = x + i + 0.5;
                        point[1] = y + j + 0.5;
                        inverse.transform(point, 0, point, 0, 1);
                        out[j * w + i] = finish(color, point[0], point[1]);
                    }
                }
            }
            raster.setDataElements(0, 0, w, h, out);
            return raster;
        }
    }
}
