package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

/**
 * 写真の向き (Exif の Orientation。1 から 8) に合わせて、ピクセルを回したり反転したりする。
 */
final class Orientation {

    private Orientation() {
    }

    // 1 以外なら、向きを直した画像を返す
    static DecodedImage apply(int orientation, int width, int height, int[] argb) {
        if (orientation < 2 || orientation > 8) return FormatUtil.still(width, height, argb);
        final boolean swap = orientation >= 5;
        final int newWidth = swap ? height : width;
        final int newHeight = swap ? width : height;
        final int[] result = new int[argb.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int tx, ty;
                switch (orientation) {
                    case 2: tx = width - 1 - x; ty = y; break;
                    case 3: tx = width - 1 - x; ty = height - 1 - y; break;
                    case 4: tx = x; ty = height - 1 - y; break;
                    case 5: tx = y; ty = x; break;
                    case 6: tx = height - 1 - y; ty = x; break;
                    case 7: tx = height - 1 - y; ty = width - 1 - x; break;
                    default: tx = y; ty = width - 1 - x; break;
                }
                result[ty * newWidth + tx] = argb[y * width + x];
            }
        }
        return FormatUtil.still(newWidth, newHeight, result);
    }

    /**
     * Exif (または TIFF) の見出しから向きを読む。見つからなければ 1。
     *
     * @param start TIFF の見出し ("II" か "MM") の位置
     */
    static int fromTiff(byte[] d, int start, int end) {
        try {
            if (start + 8 > end) return 1;
            final boolean little = d[start] == 'I' && d[start + 1] == 'I';
            if (!little && !(d[start] == 'M' && d[start + 1] == 'M')) return 1;
            final long ifd = u32(d, start + 4, little);
            if (ifd < 8 || start + ifd + 2 > end) return 1;
            final int count = u16(d, (int) (start + ifd), little);
            for (int i = 0; i < count; i++) {
                final int entry = (int) (start + ifd) + 2 + i * 12;
                if (entry + 12 > end) return 1;
                if (u16(d, entry, little) == 0x0112) {
                    final int value = u16(d, entry + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
        } catch (RuntimeException e) {
            // 壊れた Exif は無視して、向きはそのままにする
        }
        return 1;
    }

    private static int u16(byte[] d, int p, boolean little) {
        return little ? (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 : (d[p] & 0xFF) << 8 | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p, boolean little) {
        return little ? FormatUtil.u32le(d, p) : FormatUtil.u32be(d, p);
    }
}
