package marumasa.marumasa_sign.core.pdf.render;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * タイルのパターン (8.7.3) で塗る Paint。
 * 1 つのタイルの絵 (幅は XStep と BBox の幅の小さいほう、高さも同じ) を前もって描いておき (隣のタイルからはみ出す分も含める)、
 * 画素ごとに周期で折り返して色を取る。周期が BBox より大きければ、タイルの間は塗らない。
 */
final class TilingPaint implements Paint {

    private static final ColorModel MODEL = new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);

    // タイルの絵。左上が (x0, y0 + height) にあたる
    private final int[] cell;
    private final int cellWidth, cellHeight;
    private final double x0, y0, xStep, yStep, width, height;
    // パターンの空間からページの既定の空間へ
    private final AffineTransform toPage;

    TilingPaint(BufferedImage cell, double x0, double y0, double xStep, double yStep, double width, double height,
                AffineTransform toPage) {
        this.cell = ((DataBufferInt) cell.getRaster().getDataBuffer()).getData();
        this.cellWidth = cell.getWidth();
        this.cellHeight = cell.getHeight();
        this.x0 = x0;
        this.y0 = y0;
        this.xStep = xStep;
        this.yStep = yStep;
        this.width = width;
        this.height = height;
        this.toPage = toPage;
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
        final AffineTransform deviceToPattern = inverse;
        return new PaintContext() {
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
                if (deviceToPattern != null) {
                    final double[] point = new double[2];
                    for (int j = 0; j < h; j++) {
                        for (int i = 0; i < w; i++) {
                            point[0] = x + i + 0.5;
                            point[1] = y + j + 0.5;
                            deviceToPattern.transform(point, 0, point, 0, 1);
                            double u = (point[0] - x0) / xStep;
                            double v = (point[1] - y0) / yStep;
                            if (Double.isNaN(u) || Double.isNaN(v) || Double.isInfinite(u) || Double.isInfinite(v)) continue;
                            // タイルの中の位置 (パターンの空間の単位)
                            final double lu = (u - Math.floor(u)) * xStep;
                            final double lv = (v - Math.floor(v)) * yStep;
                            if (lu >= width || lv >= height) continue;
                            final int cx = Math.min(cellWidth - 1, (int) (lu / width * cellWidth));
                            final int cy = Math.min(cellHeight - 1, (int) ((1 - lv / height) * cellHeight));
                            out[j * w + i] = cell[cy * cellWidth + cx];
                        }
                    }
                }
                raster.setDataElements(0, 0, w, h, out);
                return raster;
            }
        };
    }
}
