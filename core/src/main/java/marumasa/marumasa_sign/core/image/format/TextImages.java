package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C のソースの形をした画像: XBM (白黒) と XPM (色の一覧つき)。
 */
final class TextImages {

    private static final int MAX_TEXT = 64 * 1024 * 1024;

    private TextImages() {
    }

    static boolean looksLikeXpm(byte[] h, int n) {
        return FormatUtil.startsWith(h, n, "/* XPM */");
    }

    // ---------- XBM ----------

    static DecodedImage decodeXbm(byte[] d) throws IOException {
        final String text = text(d);
        int width = -1, height = -1;
        final Matcher defines = Pattern.compile("#define\\s+\\S*?_?(width|height)\\s+(\\d+)").matcher(text);
        while (defines.find()) {
            if (defines.group(1).equals("width")) width = Integer.parseInt(defines.group(2));
            else height = Integer.parseInt(defines.group(2));
        }
        FormatUtil.checkSize(width, height);
        final int open = text.indexOf('{');
        final int close = text.lastIndexOf('}');
        if (open < 0 || close < open) throw new IOException("Invalid XBM");
        final int rowBytes = (width + 7) / 8;
        final int[] bytes = new int[rowBytes * height];
        int count = 0;
        final Matcher numbers = Pattern.compile("0[xX][0-9a-fA-F]+|\\d+").matcher(text.substring(open + 1, close));
        while (numbers.find() && count < bytes.length) {
            final String token = numbers.group();
            bytes[count++] = token.length() > 1 && (token.charAt(1) == 'x' || token.charAt(1) == 'X')
                    ? Integer.parseInt(token.substring(2), 16) : Integer.parseInt(token);
        }
        if (count < bytes.length) throw new IOException("Truncated XBM");
        final int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 下位のビットが左。1 が黒
                final boolean set = ((bytes[y * rowBytes + (x >> 3)] >> (x & 7)) & 1) != 0;
                result[y * width + x] = set ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return FormatUtil.still(width, height, result);
    }

    // ---------- XPM (バージョン 3) ----------

    static DecodedImage decodeXpm(byte[] d) throws IOException {
        final String text = text(d);
        // 引用符で囲まれた文字列を順に取り出す (コメントは飛ばす)
        final java.util.List<String> strings = new java.util.ArrayList<String>();
        int i = text.indexOf('{');
        if (i < 0) throw new IOException("Invalid XPM");
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '"') {
                final int end = text.indexOf('"', i + 1);
                if (end < 0) throw new IOException("Invalid XPM");
                strings.add(text.substring(i + 1, end));
                i = end + 1;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                final int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else {
                i++;
            }
        }
        if (strings.isEmpty()) throw new IOException("Invalid XPM");
        final String[] header = strings.get(0).trim().split("\\s+");
        if (header.length < 4) throw new IOException("Invalid XPM header");
        final int width, height, colors, cpp;
        try {
            width = Integer.parseInt(header[0]);
            height = Integer.parseInt(header[1]);
            colors = Integer.parseInt(header[2]);
            cpp = Integer.parseInt(header[3]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid XPM header", e);
        }
        FormatUtil.checkSize(width, height);
        if (cpp < 1 || cpp > 8 || colors < 1 || colors > 1 << 20 || strings.size() < 1 + colors + height) throw new IOException("Invalid XPM");

        final Map<String, Integer> table = new HashMap<String, Integer>();
        for (int k = 0; k < colors; k++) {
            final String line = strings.get(1 + k);
            if (line.length() < cpp) throw new IOException("Invalid XPM color");
            table.put(line.substring(0, cpp), color(line.substring(cpp)));
        }
        final int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            final String row = strings.get(1 + colors + y);
            if (row.length() < width * cpp) throw new IOException("Truncated XPM");
            for (int x = 0; x < width; x++) {
                final Integer value = table.get(row.substring(x * cpp, x * cpp + cpp));
                result[y * width + x] = value == null ? 0 : value;
            }
        }
        return FormatUtil.still(width, height, result);
    }

    // 「c red m black」のような指定から、色を選ぶ (c、g4、g、m の順)
    private static int color(String spec) {
        final String[] words = spec.trim().split("\\s+");
        String chosen = null;
        for (String key : new String[]{"c", "g4", "g", "m"}) {
            for (int i = 0; i + 1 < words.length && chosen == null; i++) {
                if (words[i].equals(key)) {
                    // 色の名前に空白が含まれるときは、次の種類の指定まで続ける
                    final StringBuilder name = new StringBuilder(words[i + 1]);
                    for (int j = i + 2; j < words.length && !isKey(words[j]); j++) name.append(' ').append(words[j]);
                    chosen = name.toString();
                }
            }
            if (chosen != null) break;
        }
        if (chosen == null) return 0;
        return parseColor(chosen);
    }

    private static boolean isKey(String word) {
        return word.equals("c") || word.equals("m") || word.equals("g") || word.equals("g4") || word.equals("s");
    }

    private static int parseColor(String name) {
        final String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("none")) return 0;
        if (lower.startsWith("#")) {
            final String hex = lower.substring(1);
            try {
                if (hex.length() % 3 == 0 && hex.length() >= 3 && hex.length() <= 12) {
                    final int digits = hex.length() / 3;
                    final long max = (1L << (4 * digits)) - 1;
                    return FormatUtil.argb(255,
                            FormatUtil.to8(Long.parseLong(hex.substring(0, digits), 16), max),
                            FormatUtil.to8(Long.parseLong(hex.substring(digits, digits * 2), 16), max),
                            FormatUtil.to8(Long.parseLong(hex.substring(digits * 2), 16), max));
                }
            } catch (NumberFormatException e) {
                return 0xFF000000;
            }
            return 0xFF000000;
        }
        final Matcher gray = Pattern.compile("gr[ae]y(\\d{1,3})").matcher(lower);
        if (gray.matches()) {
            final int v = FormatUtil.to8(Math.min(100, Integer.parseInt(gray.group(1))), 100);
            return FormatUtil.argb(255, v, v, v);
        }
        final Integer known = NamedColors.get(lower.replace(" ", ""));
        return known != null ? known : 0xFF000000;
    }

    private static String text(byte[] d) throws IOException {
        if (d.length > MAX_TEXT) throw new IOException("Text image is too large");
        return new String(d, StandardCharsets.ISO_8859_1);
    }
}
