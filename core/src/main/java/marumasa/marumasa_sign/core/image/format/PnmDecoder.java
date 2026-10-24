package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;
import java.util.Locale;

/**
 * Netpbm の PBM・PGM・PPM (P1 から P6)、PAM (P7)、PFM (Pf と PF)。
 */
final class PnmDecoder {

    private final byte[] d;
    private int p;

    private PnmDecoder(byte[] d) {
        this.d = d;
    }

    // 先頭が PNM の署名らしいかどうか
    static boolean looksLikePnm(byte[] h, int n) {
        if (n < 3 || h[0] != 'P') return false;
        final int c = h[1];
        final boolean known = (c >= '1' && c <= '7') || c == 'f' || c == 'F';
        return known && (h[2] == '\n' || h[2] == '\r' || h[2] == ' ' || h[2] == '\t');
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (!looksLikePnm(d, d.length)) throw new IOException("Not a PNM image");
        final PnmDecoder in = new PnmDecoder(d);
        in.p = 2;
        final char kind = (char) d[1];
        if (kind == 'f' || kind == 'F') return in.pfm(kind == 'F');
        if (kind == '7') return in.pam();
        final int type = kind - '0';

        final int width = in.number();
        final int height = in.number();
        FormatUtil.checkSize(width, height);
        final boolean bitmap = type == 1 || type == 4;
        final int maxval = bitmap ? 1 : in.number();
        if (maxval < 1 || maxval > 65535) throw new IOException("Invalid PNM maxval");
        final boolean color = type == 3 || type == 6;
        final boolean ascii = type <= 3;
        final int[] result = new int[width * height];
        if (!ascii) in.p++; // ヘッダーの最後の空白 1 文字

        if (type == 4) {
            final int rowBytes = (width + 7) / 8;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int bit = (d[in.p + y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                    result[y * width + x] = bit != 0 ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return FormatUtil.still(width, height, result);
        }
        final int channels = color ? 3 : 1;
        final boolean wide = maxval > 255;
        final int[] c = new int[3];
        for (int i = 0; i < result.length; i++) {
            for (int k = 0; k < channels; k++) {
                final int v;
                if (ascii) {
                    v = type == 1 ? in.bit() : in.number();
                } else if (wide) {
                    v = FormatUtil.u16be(d, in.p);
                    in.p += 2;
                } else {
                    v = d[in.p++] & 0xFF;
                }
                c[k] = type == 1 ? (v != 0 ? 0 : 255) : FormatUtil.to8(Math.min(v, maxval), maxval);
            }
            result[i] = color ? FormatUtil.argb(255, c[0], c[1], c[2]) : FormatUtil.argb(255, c[0], c[0], c[0]);
        }
        return FormatUtil.still(width, height, result);
    }

    private DecodedImage pam() throws IOException {
        int width = 0, height = 0, depth = 0, maxval = 255;
        String tuple = "";
        while (true) {
            final String line = line();
            if (line == null) throw new IOException("Invalid PAM header");
            final String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (text.equals("ENDHDR")) break;
            final int space = text.indexOf(' ');
            final String key = space < 0 ? text : text.substring(0, space);
            final String value = space < 0 ? "" : text.substring(space + 1).trim();
            switch (key) {
                case "WIDTH": width = Integer.parseInt(value); break;
                case "HEIGHT": height = Integer.parseInt(value); break;
                case "DEPTH": depth = Integer.parseInt(value); break;
                case "MAXVAL": maxval = Integer.parseInt(value); break;
                case "TUPLTYPE": tuple = value.toUpperCase(Locale.ROOT); break;
                default: break;
            }
        }
        FormatUtil.checkSize(width, height);
        if (depth < 1 || depth > 4 || maxval < 1 || maxval > 65535) throw new IOException("Unsupported PAM");
        final int bytes = maxval > 255 ? 2 : 1;
        final int[] result = new int[width * height];
        final boolean alpha = tuple.endsWith("_ALPHA");
        final int[] c = new int[4];
        for (int i = 0; i < result.length; i++) {
            for (int k = 0; k < depth; k++) {
                final int v = bytes == 2 ? FormatUtil.u16be(d, p) : d[p] & 0xFF;
                p += bytes;
                c[k] = FormatUtil.to8(Math.min(v, maxval), maxval);
            }
            if (tuple.startsWith("BLACKANDWHITE") && maxval == 1) {
                final int v = c[0] == 0 ? 0 : 255;
                result[i] = FormatUtil.argb(alpha ? c[1] : 255, v, v, v);
            } else if (depth >= 3) {
                result[i] = FormatUtil.argb(depth == 4 ? c[3] : 255, c[0], c[1], c[2]);
            } else {
                result[i] = FormatUtil.argb(depth == 2 ? c[1] : 255, c[0], c[0], c[0]);
            }
        }
        return FormatUtil.still(width, height, result);
    }

    // Portable FloatMap。大きさのあとの数が負なら下位のバイトが先
    private DecodedImage pfm(boolean color) throws IOException {
        final int width = number();
        final int height = number();
        FormatUtil.checkSize(width, height);
        final double scale = Double.parseDouble(token());
        p++; // 空白 1 文字
        final boolean little = scale < 0;
        final int channels = color ? 3 : 1;
        if ((long) width * height * channels * 4 > d.length - p) throw new IOException("Truncated PFM");
        final int[] result = new int[width * height];
        final int[] c = new int[3];
        for (int y = 0; y < height; y++) {
            // 行は下から上へ並ぶ
            final int row = (height - 1 - y) * width;
            for (int x = 0; x < width; x++) {
                for (int k = 0; k < channels; k++) {
                    final int bits = little ? FormatUtil.s32le(d, p) : FormatUtil.s32be(d, p);
                    p += 4;
                    // 多くのツールと同じく、値をそのまま 0 から 1 の明るさとして扱う
                    final float f = Float.intBitsToFloat(bits);
                    c[k] = Float.isNaN(f) ? 0 : FormatUtil.clamp8(Math.round(Math.max(-1f, Math.min(2f, f)) * 255));
                }
                result[row + x] = color ? FormatUtil.argb(255, c[0], c[1], c[2]) : FormatUtil.argb(255, c[0], c[0], c[0]);
            }
        }
        return FormatUtil.still(width, height, result);
    }

    private String line() {
        if (p >= d.length) return null;
        final StringBuilder text = new StringBuilder();
        while (p < d.length && d[p] != '\n') text.append((char) (d[p++] & 0xFF));
        p++;
        return text.toString();
    }

    private void skipSpace() {
        while (p < d.length) {
            final int c = d[p];
            if (c == '#') {
                while (p < d.length && d[p] != '\n') p++;
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                p++;
            } else {
                break;
            }
        }
    }

    private String token() throws IOException {
        skipSpace();
        final int start = p;
        while (p < d.length && d[p] > ' ' && d[p] != '#') p++;
        if (p == start) throw new IOException("Truncated PNM");
        return new String(d, start, p - start, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private int number() throws IOException {
        try {
            return Integer.parseInt(token());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid PNM number", e);
        }
    }

    // P1 の数字は、区切りがなくても 1 文字ずつ
    private int bit() throws IOException {
        skipSpace();
        if (p >= d.length) throw new IOException("Truncated PNM");
        return d[p++] == '1' ? 1 : 0;
    }
}
