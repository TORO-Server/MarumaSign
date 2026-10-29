package marumasa.marumasa_sign.core.pdf.render;

/**
 * ソフトマスク (11.6.5.2)。画素ごとの不透明度 (0 から 255)。
 * 座標はページ全体の画像の画素の座標。マスクを描いた範囲の外は、背景の値 (outside) を使う。
 */
final class SoftMask {

    private final int originX, originY, width, height;
    private final byte[] values;
    private final int outside;

    SoftMask(int originX, int originY, int width, int height, byte[] values, int outside) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.values = values;
        this.outside = outside;
    }

    int value(int x, int y) {
        final int lx = x - originX, ly = y - originY;
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return outside;
        return values[ly * width + lx] & 0xFF;
    }
}
