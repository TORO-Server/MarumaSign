package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.image.jpeg.JpegReader;

import java.io.IOException;

/**
 * JPEG。Java だけで書いた JpegReader で読み (ImageIO の libjpeg には渡さない)、写真の向き (Exif) を直す。
 * CMYK / YCCK (印刷用) も読む。色の情報 (ICC プロファイル) は使わない。
 */
final class JpegDecoder {

    private JpegDecoder() {
    }

    static DecodedImage decode(byte[] d) throws IOException {
        int orientation = 1;
        // マーカーを順に見て、Exif の向きを探す。画像データ (SOS) まで
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
            }
            p += 2 + length;
        }

        final int[] size = JpegReader.size(d);
        if (size == null) throw new IOException("Not a JPEG image");
        FormatUtil.checkSize(size[0], size[1]);
        final JpegReader.Image jpeg;
        try {
            jpeg = JpegReader.decode(d, 1, (long) ImageLoader.MAX_STATIC_SIZE * ImageLoader.MAX_STATIC_SIZE, -1);
        } catch (RuntimeException e) {
            throw new IOException("Not an image", e);
        }
        return Orientation.apply(orientation, jpeg.width, jpeg.height, JpegReader.toArgb(jpeg));
    }
}
