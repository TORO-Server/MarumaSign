package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.image.jpeg.JpegReader;
import marumasa.marumasa_sign.core.pdf.color.ColorSpaces;
import marumasa.marumasa_sign.core.pdf.color.DeviceSpaces;
import marumasa.marumasa_sign.core.pdf.color.PdfColorSpace;
import marumasa.marumasa_sign.core.pdf.color.SpecialSpaces;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;
import marumasa.marumasa_sign.core.pdf.filter.CcittFax;
import marumasa.marumasa_sign.core.pdf.filter.Filters;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 画像の XObject とインライン画像を、ARGB の画像にする (8.9)。
 * 大きな画像は、描く大きさに合わせて間引いて読む (速く、メモリも少ない)。
 * 幅と高さ、展開したデータの大きさには上限を設ける。
 */
final class ImageDecoder {

    // 幅と高さの上限
    private static final int MAX_SIDE = 1 << 16;
    // 作る画像の画素の数の上限
    private static final long MAX_OUTPUT_PIXELS = 32L * 1024 * 1024;
    // JPEG を読むときの画素の数の上限
    private static final long MAX_JPEG_PIXELS = 64L * 1024 * 1024;

    /**
     * 戻した画像。
     */
    static final class Result {
        final BufferedImage image;
        // 元の画像の大きさ
        final int sourceWidth, sourceHeight;
        // ステンシルマスク (ImageMask) なら true。そのときの画像は、塗る所の不透明度だけを持つ
        final boolean stencil;
        final boolean interpolate;

        Result(BufferedImage image, int sourceWidth, int sourceHeight, boolean stencil, boolean interpolate) {
            this.image = image;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.stencil = stencil;
            this.interpolate = interpolate;
        }
    }

    // 画素の値を並べたもの (間引いたあと)
    private static final class Samples {
        int width, height;
        int components;
        int bits;
        // 1 行のバイト数
        int rowBytes;
        byte[] data;
        // 間引きの幅 (元の画素の何個おきか)
        int stepX = 1, stepY = 1;
        int sourceWidth, sourceHeight;

        int sample(int x, int y, int c) {
            final int sx = Math.min(sourceWidth - 1, x * stepX), sy = Math.min(sourceHeight - 1, y * stepY);
            final long bit = ((long) sx * components + c) * bits;
            final long offset = (long) sy * rowBytes;
            if (bits == 8) {
                final long p = offset + bit / 8;
                return p < data.length ? data[(int) p] & 0xFF : 0;
            }
            if (bits == 16) {
                final long p = offset + bit / 8;
                return p + 1 < data.length ? ((data[(int) p] & 0xFF) << 8) | (data[(int) p + 1] & 0xFF) : 0;
            }
            final long p = offset + bit / 8;
            if (p >= data.length) return 0;
            final int shift = 8 - bits - (int) (bit % 8);
            return ((data[(int) p] & 0xFF) >> shift) & ((1 << bits) - 1);
        }
    }

    private ImageDecoder() {
    }

    /**
     * @param targetWidth  描く大きさ (画素) の目安。間引きに使う
     * @param targetHeight 同上
     */
    static Result decode(PdfStream stream, PdfDict resources, int targetWidth, int targetHeight) throws IOException {
        return decode(stream, resources, targetWidth, targetHeight, true);
    }

    /**
     * @param withMasks /Mask と /SMask を使うか (マスクの画像を読むときは使わない。マスクが自分自身を指すファイルで止まらないように)
     */
    private static Result decode(PdfStream stream, PdfDict resources, int targetWidth, int targetHeight, boolean withMasks)
            throws IOException {
        final PdfDict dict = stream.dict;
        final int width = integer(dict.get("Width", "W"));
        final int height = integer(dict.get("Height", "H"));
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) throw new IOException("Bad image size");
        final boolean imageMask = Boolean.TRUE.equals(dict.get("ImageMask", "IM"));
        final boolean interpolate = Boolean.TRUE.equals(dict.get("Interpolate", "I"));
        int bits = imageMask ? 1 : integer(dict.get("BitsPerComponent", "BPC"));
        PdfColorSpace space = null;
        if (!imageMask) {
            final Object cs = dict.get("ColorSpace", "CS");
            if (cs != null) space = colorSpace(cs, resources);
        }
        // 間引き: 描く大きさの 2 倍までは残す
        final int stepX = Math.max(1, width / Math.max(1, 2 * targetWidth));
        final int stepY = Math.max(1, height / Math.max(1, 2 * targetHeight));

        final Filters.Step imageFilter = Filters.imageStep(dict);
        final byte[] raw = stream.decodeExceptImage(PdfDocument.MAX_DECODED);
        final Samples samples = new Samples();
        samples.sourceWidth = width;
        samples.sourceHeight = height;
        if (imageFilter != null && imageFilter.name.equals(Filters.DCT)) {
            int scale = 1;
            while (scale < 8 && width / (scale * 2) >= targetWidth && height / (scale * 2) >= targetHeight) scale *= 2;
            final int transform = imageFilter.parameters == null ? -1 : imageFilter.parameters.integer("ColorTransform", -1);
            final JpegReader.Image jpeg = JpegReader.decode(raw, scale, MAX_JPEG_PIXELS, transform);
            samples.sourceWidth = jpeg.width;
            samples.sourceHeight = jpeg.height;
            samples.components = jpeg.components;
            samples.bits = 8;
            samples.rowBytes = jpeg.width * jpeg.components;
            samples.data = jpeg.samples;
            bits = 8;
            if (space == null || space.components() != jpeg.components) {
                space = jpeg.components == 1 ? DeviceSpaces.GRAY : jpeg.components == 3 ? DeviceSpaces.RGB : DeviceSpaces.CMYK;
            }
            // JPEG を縮小して読んだ分だけ、間引きを減らす
            samples.stepX = Math.max(1, jpeg.width / Math.max(1, 2 * targetWidth));
            samples.stepY = Math.max(1, jpeg.height / Math.max(1, 2 * targetHeight));
        } else {
            byte[] data = raw;
            if (imageFilter != null && imageFilter.name.equals(Filters.CCITT)) {
                data = CcittFax.decode(raw, imageFilter.parameters, width, height, PdfDocument.MAX_DECODED);
                bits = 1;
                if (space == null && !imageMask) space = DeviceSpaces.GRAY;
            } else if (imageFilter != null) {
                // JPXDecode と JBIG2Decode には対応していない
                throw new IOException("Unsupported image filter " + imageFilter.name);
            }
            if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) throw new IOException("Bad BitsPerComponent " + bits);
            if (space == null && !imageMask) throw new IOException("Image without a color space");
            samples.components = imageMask ? 1 : space.components();
            samples.bits = bits;
            final long rowBits = (long) width * samples.components * bits;
            samples.rowBytes = (int) Math.min(Integer.MAX_VALUE, (rowBits + 7) / 8);
            samples.data = data;
            samples.stepX = stepX;
            samples.stepY = stepY;
        }
        samples.width = (samples.sourceWidth + samples.stepX - 1) / samples.stepX;
        samples.height = (samples.sourceHeight + samples.stepY - 1) / samples.stepY;
        if ((long) samples.width * samples.height > MAX_OUTPUT_PIXELS) throw new IOException("Image is too large");

        final double[] decode = decodeArray(dict.get("Decode", "D"), imageMask ? null : space, bits, samples.components);
        final BufferedImage image = new BufferedImage(samples.width, samples.height, BufferedImage.TYPE_INT_ARGB);
        final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (imageMask) {
            // 0 の画素を塗る (Decode が [1 0] なら 1 の画素)
            final boolean inverted = decode[0] > decode[1];
            for (int y = 0; y < samples.height; y++) {
                for (int x = 0; x < samples.width; x++) {
                    final boolean paint = (samples.sample(x, y, 0) == 0) != inverted;
                    pixels[y * samples.width + x] = paint ? 0xFF000000 : 0;
                }
            }
            return new Result(image, width, height, true, interpolate);
        }
        convert(samples, space, decode, pixels);
        if (withMasks) applyMasks(dict, resources, samples, pixels);
        return new Result(image, width, height, false, interpolate);
    }

    private static int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static PdfColorSpace colorSpace(Object value, PdfDict resources) throws IOException {
        // インライン画像の略した名前 (/G /RGB /CMYK /I) にも対応する
        return ColorSpaces.create(value, resources);
    }

    private static double[] decodeArray(Object value, PdfColorSpace space, int bits, int components) {
        final double[] defaults = space == null ? new double[]{0, 1} : space.defaultDecode(bits);
        if (value instanceof PdfArray) {
            final double[] given = ((PdfArray) value).numbers();
            if (given != null && given.length >= defaults.length) return java.util.Arrays.copyOf(given, defaults.length);
        }
        return defaults;
    }

    // 画素の値を色にする
    private static void convert(Samples s, PdfColorSpace space, double[] decode, int[] pixels) {
        final int n = s.components;
        final double max = (1 << s.bits) - 1;
        final boolean defaultDecode = isDefault(decode, space, s.bits);
        // よく使う形は、色空間の計算を省く
        if (defaultDecode && s.bits == 8 && space == DeviceSpaces.RGB) {
            for (int y = 0; y < s.height; y++) {
                for (int x = 0; x < s.width; x++) {
                    pixels[y * s.width + x] = 0xFF000000 | (s.sample(x, y, 0) << 16) | (s.sample(x, y, 1) << 8) | s.sample(x, y, 2);
                }
            }
            return;
        }
        if (defaultDecode && s.bits == 8 && space == DeviceSpaces.GRAY) {
            for (int y = 0; y < s.height; y++) {
                for (int x = 0; x < s.width; x++) {
                    final int v = s.sample(x, y, 0);
                    pixels[y * s.width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
            return;
        }
        // 1 成分 (Indexed、灰色、特色) は、値ごとの色を先に求める
        if (n == 1 && s.bits <= 8) {
            final int[] table = new int[1 << s.bits];
            final float[] value = new float[1];
            for (int v = 0; v < table.length; v++) {
                value[0] = (float) (decode[0] + v * (decode[1] - decode[0]) / max);
                table[v] = 0xFF000000 | space.toRgb(value);
            }
            for (int y = 0; y < s.height; y++) {
                for (int x = 0; x < s.width; x++) pixels[y * s.width + x] = table[s.sample(x, y, 0)];
            }
            return;
        }
        // そのほか: 同じ値の色を覚えながら計算する
        final Map<Long, Integer> cache = new HashMap<>();
        final float[] value = new float[n];
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                long key = 0;
                for (int c = 0; c < n; c++) {
                    final int v = s.sample(x, y, c);
                    key = (key << 16) | v;
                    value[c] = (float) (decode[2 * c] + v * (decode[2 * c + 1] - decode[2 * c]) / max);
                }
                Integer color = n <= 4 ? cache.get(key) : null;
                if (color == null) {
                    color = 0xFF000000 | space.toRgb(value);
                    if (n <= 4 && cache.size() < 1 << 18) cache.put(key, color);
                }
                pixels[y * s.width + x] = color;
            }
        }
    }

    private static boolean isDefault(double[] decode, PdfColorSpace space, int bits) {
        return java.util.Arrays.equals(decode, space.defaultDecode(bits));
    }

    // /Mask (色の範囲か、ステンシルマスクの画像) と /SMask (不透明度の画像)
    private static void applyMasks(PdfDict dict, PdfDict resources, Samples s, int[] pixels) {
        final Object mask = dict.get("Mask");
        if (mask instanceof PdfArray) {
            final double[] ranges = ((PdfArray) mask).numbers();
            if (ranges != null && ranges.length >= 2 * s.components) {
                for (int y = 0; y < s.height; y++) {
                    for (int x = 0; x < s.width; x++) {
                        boolean inside = true;
                        for (int c = 0; c < s.components && inside; c++) {
                            final int v = s.sample(x, y, c);
                            inside = v >= ranges[2 * c] && v <= ranges[2 * c + 1];
                        }
                        if (inside) pixels[y * s.width + x] = 0;
                    }
                }
            }
        } else if (mask instanceof PdfStream) {
            final Result stencil = decodeMask((PdfStream) mask, resources, s.width, s.height);
            if (stencil != null) {
                // ステンシル: 塗る所 (0 の画素) だけを残す
                multiplyAlpha(pixels, s.width, s.height, stencil.image, false);
            }
        }
        final PdfStream softMask = dict.stream("SMask");
        if (softMask != null) {
            final Result alpha = decodeMask(softMask, resources, s.width, s.height);
            if (alpha != null) {
                final PdfArray matte = softMask.dict.array("Matte");
                if (matte != null) unmatte(pixels, s.width, s.height, alpha.image, matte, dict, resources);
                multiplyAlpha(pixels, s.width, s.height, alpha.image, true);
            }
        }
    }

    private static Result decodeMask(PdfStream mask, PdfDict resources, int targetWidth, int targetHeight) {
        try {
            return decode(mask, resources, targetWidth, targetHeight, false);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // マスクの画像 (大きさは違ってよい) で、不透明度をかける。gray なら灰色の明るさ、そうでなければステンシルの不透明度
    private static void multiplyAlpha(int[] pixels, int width, int height, BufferedImage mask, boolean gray) {
        final int mw = mask.getWidth(), mh = mask.getHeight();
        final int[] m = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            final int my = Math.min(mh - 1, (int) ((y + 0.5) * mh / height));
            for (int x = 0; x < width; x++) {
                final int mx = Math.min(mw - 1, (int) ((x + 0.5) * mw / width));
                final int value = m[my * mw + mx];
                final int a = gray ? value & 0xFF : value >>> 24;
                final int p = pixels[y * width + x];
                final int alpha = (p >>> 24) * a / 255;
                pixels[y * width + x] = (alpha << 24) | (p & 0xFFFFFF);
            }
        }
    }

    // /Matte: 色に背景の色が前もって混ぜてあるので、それを取り除く
    private static void unmatte(int[] pixels, int width, int height, BufferedImage mask, PdfArray matte, PdfDict dict,
                                PdfDict resources) {
        final double[] values = matte.numbers();
        final Object cs = dict.get("ColorSpace", "CS");
        if (values == null || cs == null) return;
        int matteRgb;
        try {
            final PdfColorSpace space = ColorSpaces.create(cs, resources);
            if (space instanceof SpecialSpaces.Indexed || values.length < space.components()) return;
            final float[] f = new float[space.components()];
            for (int i = 0; i < f.length; i++) f[i] = (float) values[i];
            matteRgb = space.toRgb(f);
        } catch (IOException | RuntimeException e) {
            return;
        }
        final int mw = mask.getWidth(), mh = mask.getHeight();
        final int[] m = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            final int my = Math.min(mh - 1, (int) ((y + 0.5) * mh / height));
            for (int x = 0; x < width; x++) {
                final int mx = Math.min(mw - 1, (int) ((x + 0.5) * mw / width));
                final int a = m[my * mw + mx] & 0xFF;
                if (a == 0) continue;
                final int p = pixels[y * width + x];
                int result = p & 0xFF000000;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    final int c = (p >> shift) & 0xFF, mc = (matteRgb >> shift) & 0xFF;
                    final int v = mc + (c - mc) * 255 / a;
                    result |= Math.max(0, Math.min(255, v)) << shift;
                }
                pixels[y * width + x] = result;
            }
        }
    }

    static boolean isName(Object value, String name) {
        return value instanceof PdfName && ((PdfName) value).name.equals(name);
    }
}
