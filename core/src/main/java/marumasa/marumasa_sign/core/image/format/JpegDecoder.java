package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * JPEG。ImageIO で読み、写真の向き (Exif) を直す。
 * ImageIO が読めない CMYK / YCCK (印刷用) や、色の情報 (ICC プロファイル) が壊れている JPEG は、生のデータから自分で色を作る。
 */
final class JpegDecoder {

    private JpegDecoder() {
    }

    static DecodedImage decode(byte[] d) throws IOException {
        int orientation = 1;
        int adobe = -1;
        int components = 0;
        // マーカーを順に見て、Exif の向きと Adobe の色の変換の指定を探す。画像データ (SOS) まで
        int p = 2;
        while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
            final int marker = d[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0xFF) {
                p++;
                continue;
            }
            if (marker >= 0xD0 && marker <= 0xD8 || marker == 0x01) {
                p += 2;
                continue;
            }
            final int length = FormatUtil.u16be(d, p + 2);
            if (length < 2) break;
            if (marker == 0xE1 && length >= 14 && FormatUtil.matchesAt(d, d.length, p + 4, "Exif\0\0")) {
                orientation = Orientation.fromTiff(d, p + 10, Math.min(d.length, p + 2 + length));
            } else if (marker == 0xEE && length >= 14 && FormatUtil.matchesAt(d, d.length, p + 4, "Adobe")) {
                adobe = d[p + 15] & 0xFF;
            } else if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && length >= 8) {
                components = d[p + 9] & 0xFF;
            }
            p += 2 + length;
        }

        // 4 つの成分 (CMYK) は、ImageIO によって結果が違う (読めない、色の変換が違う) ので、いつも自分で色を作る
        if (components == 4) {
            try {
                return raw(d, orientation, adobe);
            } catch (IOException | RuntimeException e) {
                // 読めなければ、ImageIO に任せる
            }
        }

        BufferedImage image = null;
        IOException failure = null;
        try {
            image = ImageBytes.read(d);
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("Not an image", e);
        }
        if (image != null) {
            FormatUtil.checkSize(image.getWidth(), image.getHeight());
            return Orientation.apply(orientation, image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
        }
        try {
            return raw(d, orientation, adobe);
        } catch (IOException e) {
            throw failure != null ? failure : e;
        } catch (RuntimeException e) {
            throw failure != null ? failure : new IOException("Not an image", e);
        }
    }

    // 色の変換をせずに生のデータを読んで、自分で ARGB にする
    private static DecodedImage raw(byte[] d, int orientation, int adobe) throws IOException {
        final Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) throw new IOException("No JPEG reader");
        final ImageReader reader = readers.next();
        try {
            reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(d)), false, true);
            FormatUtil.checkSize(reader.getWidth(0), reader.getHeight(0));
            final Raster raster = reader.readRaster(0, null);
            final int w = raster.getWidth(), h = raster.getHeight();
            final int bands = raster.getNumBands();
            final int[] pixels = new int[w * h];
            final int[] row = new int[w * bands];
            for (int y = 0; y < h; y++) {
                raster.getPixels(raster.getMinX(), raster.getMinY() + y, w, 1, row);
                for (int x = 0; x < w; x++) {
                    final int o = x * bands;
                    final int color;
                    if (bands == 1) {
                        color = FormatUtil.argb(255, row[o], row[o], row[o]);
                    } else if (bands == 3) {
                        // Adobe の指定が 0 なら RGB のまま、そうでなければ YCbCr
                        color = adobe == 0 ? FormatUtil.argb(255, row[o], row[o + 1], row[o + 2]) : ycc(row[o], row[o + 1], row[o + 2]);
                    } else if (bands == 4) {
                        if (adobe == 2) {
                            // YCCK。YCC を RGB にしたものが、そのままインクの量になる (Adobe の値は反転している)
                            final int rgb = ycc(row[o], row[o + 1], row[o + 2]);
                            color = FormatUtil.cmyk((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255 - row[o + 3]);
                        } else if (adobe == 0) {
                            color = FormatUtil.cmyk(255 - row[o], 255 - row[o + 1], 255 - row[o + 2], 255 - row[o + 3]);
                        } else {
                            color = FormatUtil.cmyk(row[o], row[o + 1], row[o + 2], row[o + 3]);
                        }
                    } else {
                        throw new IOException("Unsupported JPEG components: " + bands);
                    }
                    pixels[y * w + x] = color;
                }
            }
            return Orientation.apply(orientation, w, h, pixels);
        } finally {
            reader.dispose();
        }
    }

    private static int ycc(int y, int cb, int cr) {
        final double c1 = cb - 128, c2 = cr - 128;
        return FormatUtil.argb(255,
                FormatUtil.clamp8((int) Math.round(y + 1.402 * c2)),
                FormatUtil.clamp8((int) Math.round(y - 0.344136 * c1 - 0.714136 * c2)),
                FormatUtil.clamp8((int) Math.round(y + 1.772 * c1)));
    }
}
