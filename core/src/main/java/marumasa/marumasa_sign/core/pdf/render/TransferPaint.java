package marumasa.marumasa_sign.core.pdf.render;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * 転送関数 (10.5) をかける Paint。シェーディングやパターンの Paint の色に、赤、緑、青ごとの表をかける。
 */
final class TransferPaint implements Paint {

    private static final ColorModel MODEL = ColorModel.getRGBdefault();

    private final Paint paint;
    private final int[][] tables;

    /**
     * @param tables 赤、緑、青ごとの 256 段階の表 (null の成分は変えない)
     */
    TransferPaint(Paint paint, int[][] tables) {
        this.paint = paint;
        this.tables = tables;
    }

    // 色 (ARGB) に転送関数をかける。tables が null なら変えない
    static int apply(int[][] tables, int argb) {
        if (tables == null) return argb;
        int result = argb & 0xFF000000;
        for (int c = 0; c < 3; c++) {
            final int shift = 16 - 8 * c;
            final int v = (argb >> shift) & 0xFF;
            result |= (tables[c] == null ? v : tables[c][v]) << shift;
        }
        return result;
    }

    @Override
    public int getTransparency() {
        return paint.getTransparency();
    }

    @Override
    public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds, AffineTransform xform,
                                      RenderingHints hints) {
        final PaintContext context = paint.createContext(cm, deviceBounds, userBounds, xform, hints);
        return new PaintContext() {
            @Override
            public void dispose() {
                context.dispose();
            }

            @Override
            public ColorModel getColorModel() {
                return MODEL;
            }

            @Override
            public Raster getRaster(int x, int y, int w, int h) {
                final Raster source = context.getRaster(x, y, w, h);
                final ColorModel model = context.getColorModel();
                final WritableRaster raster = MODEL.createCompatibleWritableRaster(w, h);
                final int[] pixels = new int[w * h];
                final int width = Math.min(w, source.getWidth()), height = Math.min(h, source.getHeight());
                Object data = null;
                for (int j = 0; j < height; j++) {
                    for (int i = 0; i < width; i++) {
                        data = source.getDataElements(source.getMinX() + i, source.getMinY() + j, data);
                        pixels[j * w + i] = apply(tables, model.getRGB(data));
                    }
                }
                raster.setDataElements(0, 0, w, h, pixels);
                return raster;
            }
        };
    }
}
