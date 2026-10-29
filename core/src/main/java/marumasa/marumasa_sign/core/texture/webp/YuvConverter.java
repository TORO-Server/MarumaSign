package marumasa.marumasa_sign.core.texture.webp;

/**
 * VP8 の Y'CbCr (色差は縦横半分) を RGB にする。
 * 色差は、近い 4 つの標本から 9:3:3:1 の重みで補間して画素ごとの値にする (縦横に線形補間するのと同じ)。
 * 丸め方と RGB への変換式 (BT.601、14 ビットの固定小数点) は、参照デコーダ libwebp と同じ結果になるようにしてある。
 */
final class YuvConverter {

    private YuvConverter() {
    }

    /**
     * 面 y (幅 strideY)、u と v (幅 strideUV) から、width x height の ARGB (透明度は 255) を out に書く。
     */
    static void toArgb(byte[] y, int strideY, byte[] u, byte[] v, int strideUV, int width, int height, int[] out) {
        final int chromaWidth = (width + 1) >> 1;
        final int chromaHeight = (height + 1) >> 1;
        final int[] rowU = new int[width];
        final int[] rowV = new int[width];
        for (int row = 0; row < height; row++) {
            // 近い色差の行と、縦にとなりの行 (偶数行は上、奇数行は下。画像の外ならその行自身)
            final int near = row >> 1;
            final int far = (row & 1) != 0 ? Math.min(near + 1, chromaHeight - 1) : Math.max(near - 1, 0);
            interpolateRow(u, near * strideUV, far * strideUV, chromaWidth, width, rowU);
            interpolateRow(v, near * strideUV, far * strideUV, chromaWidth, width, rowV);
            final int from = row * strideY;
            final int to = row * width;
            for (int x = 0; x < width; x++) {
                out[to + x] = toArgb(y[from + x] & 0xFF, rowU[x], rowV[x]);
            }
        }
    }

    /**
     * 色差の 1 行を画素の幅に広げる。near は近い行、far は縦にとなりの行の先頭。
     * 左端と (幅が偶数なら) 右端は縦方向だけの補間 (3:1) にする。
     */
    private static void interpolateRow(byte[] plane, int near, int far, int chromaWidth, int width, int[] out) {
        out[0] = (3 * (plane[near] & 0xFF) + (plane[far] & 0xFF) + 2) >> 2;
        for (int cx = 1; 2 * cx - 1 < width; cx++) {
            if (cx >= chromaWidth) break;
            final int n0 = plane[near + cx - 1] & 0xFF, n1 = plane[near + cx] & 0xFF;
            final int f0 = plane[far + cx - 1] & 0xFF, f1 = plane[far + cx] & 0xFF;
            out[2 * cx - 1] = blend(n0, n1, f0, f1);
            if (2 * cx < width) out[2 * cx] = blend(n1, n0, f1, f0);
        }
        if ((width & 1) == 0 && width > 1) {
            final int last = chromaWidth - 1;
            out[width - 1] = (3 * (plane[near + last] & 0xFF) + (plane[far + last] & 0xFF) + 2) >> 2;
        }
    }

    /**
     * 近い標本 n、横のとなり h、縦のとなり v、斜め d から 9:3:3:1 で補間する。
     * いったん 4 つの平均と横・縦の和から 1/8 の値を作り、近い標本との平均をとる (2 段階で丸める)。
     */
    private static int blend(int n, int h, int v, int d) {
        final int diagonal = (n + h + v + d + 8 + 2 * (h + v)) >> 3;
        return (diagonal + n) >> 1;
    }

    // BT.601 (Y は 16〜235) の Y'CbCr から RGB への変換。係数は 2^14 倍、途中で 8 ビット落とし、最後に 6 ビット落とす
    private static int toArgb(int y, int u, int v) {
        final int luma = (y * 19077) >> 8;
        final int r = clip(luma + ((v * 26149) >> 8) - 14234);
        final int g = clip(luma - ((u * 6419) >> 8) - ((v * 13320) >> 8) + 8708);
        final int b = clip(luma + ((u * 33050) >> 8) - 17685);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int clip(int v) {
        return (v & ~16383) == 0 ? v >> 6 : v < 0 ? 0 : 255;
    }
}
