package marumasa.marumasa_sign.core.image;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

/**
 * BufferedImage と ARGB (sRGB、乗算済みでないもの) の int 配列との変換。
 * BufferedImage.getRGB と setRGB は 1 ピクセルずつ色の変換を通すので、4096 x 4096 の画像では 0.3 - 0.4 秒かかる。
 * よく使われる形式 (ImageIO が PNG と JPEG を読んだときの形式など) では、データを直接読み替える。
 * それ以外の形式は getRGB に任せるので、どの形式でも getRGB と同じ結果になる。
 */
public final class ArgbPixels {

    private ArgbPixels() {
    }

    // 画像全体のピクセルを、左上から行ごとに並べた ARGB の配列にする
    public static int[] read(BufferedImage image) {
        final int width = image.getWidth(), height = image.getHeight();
        final int[] fast = readDirect(image, width, height);
        return fast != null ? fast : image.getRGB(0, 0, width, height, null, 0, width);
    }

    /**
     * ARGB の配列をそのまま使う画像を作る (配列は写さない)。
     * 作った画像の形式は TYPE_INT_ARGB で、read で速く読める。
     */
    public static BufferedImage wrap(int width, int height, int[] argb) {
        final DirectColorModel model = (DirectColorModel) ColorModel.getRGBdefault();
        final WritableRaster raster = Raster.createPackedRaster(new DataBufferInt(argb, width * height), width, height, width,
                new int[]{model.getRedMask(), model.getGreenMask(), model.getBlueMask(), model.getAlphaMask()}, null);
        return new BufferedImage(model, raster, false, null);
    }

    // 直接読める形式なら読む。読めなければ null
    private static int[] readDirect(BufferedImage image, int width, int height) {
        final WritableRaster raster = image.getRaster();
        // 一部を切り出した画像や、位置のずれた画像は扱わない
        if (raster.getParent() != null || raster.getMinX() != 0 || raster.getMinY() != 0
                || raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0
                || raster.getWidth() != width || raster.getHeight() != height) {
            return null;
        }
        final DataBuffer buffer = raster.getDataBuffer();
        if (buffer.getNumBanks() != 1) return null;
        final int count = width * height;
        switch (image.getType()) {
            case BufferedImage.TYPE_INT_ARGB:
            case BufferedImage.TYPE_INT_RGB: {
                if (!(buffer instanceof DataBufferInt) || !(raster.getSampleModel() instanceof SinglePixelPackedSampleModel)) return null;
                final int stride = ((SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride();
                final int[] data = ((DataBufferInt) buffer).getData();
                final int offset = buffer.getOffset();
                final int[] result = new int[count];
                for (int y = 0; y < height; y++) {
                    System.arraycopy(data, offset + y * stride, result, y * width, width);
                }
                if (image.getType() == BufferedImage.TYPE_INT_RGB) {
                    for (int i = 0; i < count; i++) result[i] |= 0xFF000000;
                }
                return result;
            }
            case BufferedImage.TYPE_3BYTE_BGR:
            case BufferedImage.TYPE_4BYTE_ABGR: {
                if (!(buffer instanceof DataBufferByte) || !(raster.getSampleModel() instanceof ComponentSampleModel)) return null;
                final ComponentSampleModel model = (ComponentSampleModel) raster.getSampleModel();
                final boolean alpha = image.getType() == BufferedImage.TYPE_4BYTE_ABGR;
                final int[] bands = model.getBandOffsets();
                if (bands.length != (alpha ? 4 : 3)) return null;
                final byte[] data = ((DataBufferByte) buffer).getData();
                final int pixelStride = model.getPixelStride(), stride = model.getScanlineStride(), offset = buffer.getOffset();
                // 帯の並びは赤・緑・青 (・不透明度)
                final int r = bands[0], g = bands[1], b = bands[2], a = alpha ? bands[3] : 0;
                final int[] result = new int[count];
                for (int y = 0; y < height; y++) {
                    int p = offset + y * stride;
                    int o = y * width;
                    for (int x = 0; x < width; x++, p += pixelStride) {
                        result[o++] = (alpha ? (data[p + a] & 0xFF) << 24 : 0xFF000000)
                                | (data[p + r] & 0xFF) << 16 | (data[p + g] & 0xFF) << 8 | (data[p + b] & 0xFF);
                    }
                }
                return result;
            }
            case BufferedImage.TYPE_BYTE_INDEXED: {
                // 色の一覧 (パレット) を使う画像。色は一覧から引くだけなので、getRGB と同じになる
                if (!(buffer instanceof DataBufferByte) || !(raster.getSampleModel() instanceof ComponentSampleModel)
                        || !(image.getColorModel() instanceof IndexColorModel)) {
                    return null;
                }
                final ComponentSampleModel model = (ComponentSampleModel) raster.getSampleModel();
                final IndexColorModel colors = (IndexColorModel) image.getColorModel();
                final int[] palette = new int[256];
                colors.getRGBs(palette);
                // 一覧より大きい番号は、getRGB では例外になる。同じにするため、その場合は getRGB に任せる
                final int size = colors.getMapSize();
                final byte[] data = ((DataBufferByte) buffer).getData();
                final int pixelStride = model.getPixelStride(), stride = model.getScanlineStride();
                final int offset = buffer.getOffset() + model.getBandOffsets()[0];
                final int[] result = new int[count];
                for (int y = 0; y < height; y++) {
                    int p = offset + y * stride;
                    int o = y * width;
                    for (int x = 0; x < width; x++, p += pixelStride) {
                        final int index = data[p] & 0xFF;
                        if (index >= size) return null;
                        result[o++] = palette[index];
                    }
                }
                return result;
            }
            default:
                return null;
        }
    }
}
