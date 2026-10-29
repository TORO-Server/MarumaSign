package marumasa.marumasa_sign.core.pdf.filter;

import marumasa.marumasa_sign.core.pdf.cos.PdfDict;

import java.io.IOException;

/**
 * FlateDecode と LZWDecode の /Predictor (TIFF の差分と、PNG の行ごとのフィルタ) を戻す。
 */
final class Predictor {

    private Predictor() {
    }

    static byte[] apply(byte[] data, PdfDict parameters, int limit) throws IOException {
        if (parameters == null) return data;
        final int predictor = parameters.integer("Predictor", 1);
        if (predictor <= 1) return data;
        final int colors = parameters.integer("Colors", 1);
        final int bits = parameters.integer("BitsPerComponent", 8);
        final int columns = parameters.integer("Columns", 1);
        if (colors < 1 || colors > 32 || (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) || columns < 1) {
            throw new IOException("Bad predictor parameters");
        }
        final long rowBits = (long) colors * bits * columns;
        if (rowBits > (long) limit * 8) throw new IOException("Predictor row is too large");
        final int rowBytes = (int) ((rowBits + 7) / 8);
        // 1 画素のバイト数 (端数は切り上げる。RGB の 4 ビットなら 12 ビットで 2 バイト。pdf.js と poppler と同じ)
        final int pixelBytes = (colors * bits + 7) / 8;
        if (predictor == 2) return tiff(data, colors, bits, columns, rowBytes);
        return png(data, rowBytes, pixelBytes, limit);
    }

    private static byte[] tiff(byte[] data, int colors, int bits, int columns, int rowBytes) {
        final byte[] out = data.clone();
        final int rows = out.length / rowBytes;
        for (int row = 0; row < rows; row++) {
            final int base = row * rowBytes;
            if (bits == 8) {
                for (int i = colors; i < rowBytes; i++) out[base + i] = (byte) (out[base + i] + out[base + i - colors]);
            } else if (bits == 16) {
                for (int i = 2 * colors; i + 1 < rowBytes; i += 2) {
                    final int value = (((out[base + i] & 0xFF) << 8) | (out[base + i + 1] & 0xFF))
                            + (((out[base + i - 2 * colors] & 0xFF) << 8) | (out[base + i + 1 - 2 * colors] & 0xFF));
                    out[base + i] = (byte) (value >> 8);
                    out[base + i + 1] = (byte) value;
                }
            } else {
                // 1, 2, 4 ビット: 成分ごとに前の画素の値を足す
                final int mask = (1 << bits) - 1;
                final int[] previous = new int[colors];
                for (int x = 0; x < columns; x++) {
                    for (int c = 0; c < colors; c++) {
                        final int bit = (x * colors + c) * bits;
                        final int index = base + bit / 8;
                        final int shift = 8 - bits - bit % 8;
                        final int value = (((out[index] & 0xFF) >> shift) + previous[c]) & mask;
                        previous[c] = value;
                        out[index] = (byte) ((out[index] & ~(mask << shift)) | (value << shift));
                    }
                }
            }
        }
        return out;
    }

    private static byte[] png(byte[] data, int rowBytes, int pixelBytes, int limit) throws IOException {
        // 各行の先頭に、フィルタの種類の 1 バイトがある
        final int rows = (data.length + rowBytes) / (rowBytes + 1);
        if ((long) rows * rowBytes > limit) throw new IOException("Decoded data is too large");
        final byte[] out = new byte[rows * rowBytes];
        final byte[] previous = new byte[rowBytes];
        final byte[] current = new byte[rowBytes];
        int in = 0;
        for (int row = 0; row < rows; row++) {
            if (in >= data.length) break;
            final int type = data[in++] & 0xFF;
            final int available = Math.min(rowBytes, data.length - in);
            System.arraycopy(data, in, current, 0, available);
            // 最後の行が欠けていれば、残りは 0
            for (int i = available; i < rowBytes; i++) current[i] = 0;
            in += available;
            switch (type) {
                case 1: // Sub
                    for (int i = pixelBytes; i < rowBytes; i++) current[i] += current[i - pixelBytes];
                    break;
                case 2: // Up
                    for (int i = 0; i < rowBytes; i++) current[i] += previous[i];
                    break;
                case 3: // Average
                    for (int i = 0; i < rowBytes; i++) {
                        final int left = i >= pixelBytes ? current[i - pixelBytes] & 0xFF : 0;
                        current[i] += (byte) ((left + (previous[i] & 0xFF)) >> 1);
                    }
                    break;
                case 4: // Paeth
                    for (int i = 0; i < rowBytes; i++) {
                        final int a = i >= pixelBytes ? current[i - pixelBytes] & 0xFF : 0;
                        final int b = previous[i] & 0xFF;
                        final int c = i >= pixelBytes ? previous[i - pixelBytes] & 0xFF : 0;
                        final int p = a + b - c;
                        final int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                        current[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                    }
                    break;
                default:
                    // 0 (None) と、知らない種類はそのまま
                    break;
            }
            System.arraycopy(current, 0, out, row * rowBytes, rowBytes);
            System.arraycopy(current, 0, previous, 0, rowBytes);
        }
        return out;
    }
}
