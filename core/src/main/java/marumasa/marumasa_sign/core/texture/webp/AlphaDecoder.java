package marumasa.marumasa_sign.core.texture.webp;

import java.io.IOException;

/**
 * 拡張形式の透明度のチャンク (ALPH、RFC 9649 2.7.1.2) を展開する。
 * 先頭の 1 バイトは、下位から圧縮の方法 2 ビット、予測 (フィルタ) の方法 2 ビット、前処理 2 ビット、予約 2 ビット。
 * 前処理と予約は、仕様どおり読み飛ばす。
 */
public final class AlphaDecoder {

    private AlphaDecoder() {
    }

    /**
     * width x height の透明度を、左上から行ごとに 1 バイトずつ並べて返す。
     */
    public static byte[] decode(byte[] data, int offset, int length, int width, int height) throws IOException {
        if (length < 1) throw new IOException("Empty WebP alpha chunk");
        final int header = data[offset] & 0xFF;
        final int compression = header & 3;
        final int filter = (header >> 2) & 3;
        final int count = width * height;
        final byte[] alpha = new byte[count];
        if (compression == 0) {
            // 圧縮なし: 幅 x 高さ バイトがそのまま並ぶ
            if (length - 1 < count) throw new IOException("Truncated WebP alpha");
            System.arraycopy(data, offset + 1, alpha, 0, count);
        } else if (compression == 1) {
            // 可逆圧縮: 大きさの書かれていない VP8L の画像で、緑の値が透明度
            final int[] argb = LosslessDecoder.decodeHeaderless(data, offset + 1, length - 1, width, height);
            for (int i = 0; i < count; i++) alpha[i] = (byte) (argb[i] >> 8);
        } else {
            throw new IOException("Unknown WebP alpha compression: " + compression);
        }
        if (filter != 0) unfilter(alpha, width, height, filter);
        return alpha;
    }

    /**
     * 予測を元に戻す。1: 左、2: 上、3: 勾配 (左 + 上 - 左上 を 0〜255 に収めたもの)。
     * 左上の画素は 0 から、いちばん上の行は左から、いちばん左の列は上から予測する。
     */
    private static void unfilter(byte[] a, int width, int height, int filter) {
        for (int x = 1; x < width; x++) a[x] = (byte) (a[x] + a[x - 1]);
        for (int y = 1; y < height; y++) {
            final int row = y * width;
            a[row] = (byte) (a[row] + a[row - width]);
            for (int x = 1; x < width; x++) {
                final int i = row + x;
                final int predictor;
                if (filter == 1) {
                    predictor = a[i - 1] & 0xFF;
                } else if (filter == 2) {
                    predictor = a[i - width] & 0xFF;
                } else {
                    final int gradient = (a[i - 1] & 0xFF) + (a[i - width] & 0xFF) - (a[i - width - 1] & 0xFF);
                    predictor = gradient < 0 ? 0 : gradient > 255 ? 255 : gradient;
                }
                a[i] = (byte) (a[i] + predictor);
            }
        }
    }
}
