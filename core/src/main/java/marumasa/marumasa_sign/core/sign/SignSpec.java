package marumasa.marumasa_sign.core.sign;

import java.util.Locale;

/**
 * 看板に書かれる文字列の内容。
 * フォーマット: URL|X|Y|Z|横幅|高さ|Z軸回転|Y軸回転|X軸回転
 */
public final class SignSpec {

    public static final int PARAMETER_COUNT = 9;

    public final String url;
    // 位置
    public final float x, y, z;
    // 大きさ
    public final float scaleX, scaleY;
    // 回転
    public final float roll, yaw, pitch;

    public SignSpec(String url, float x, float y, float z, float scaleX, float scaleY, float roll, float yaw, float pitch) {
        this.url = url;
        this.x = x;
        this.y = y;
        this.z = z;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.roll = roll;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public SignSpec withUrl(String url) {
        return new SignSpec(url, x, y, z, scaleX, scaleY, roll, yaw, pitch);
    }

    // 看板の文字列が MarumaSign の形式になりうるかどうかを軽く判定する
    public static boolean looksLikeSign(String text) {
        if (text == null || text.length() < 2 * PARAMETER_COUNT - 1) return false;
        int count = 0;
        for (int i = 0, len = text.length(); i < len; i++) {
            if (text.charAt(i) == '|' && ++count >= PARAMETER_COUNT - 1) return true;
        }
        return false;
    }

    public static SignSpec parse(String text) {
        if (!looksLikeSign(text)) return null;
        final String[] p = text.split("\\|");
        if (p.length < PARAMETER_COUNT) return null;
        try {
            return new SignSpec(
                    p[0],
                    Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]),
                    Float.parseFloat(p[6]), Float.parseFloat(p[7]), Float.parseFloat(p[8])
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isSign(String text) {
        return parse(text) != null;
    }

    public String toText() {
        return url.replace("|", "")
                + '|' + number(x) + '|' + number(y) + '|' + number(z)
                + '|' + number(scaleX) + '|' + number(scaleY)
                + '|' + number(roll) + '|' + number(yaw) + '|' + number(pitch);
    }

    // 看板に収まりやすいように、できるだけ短い表記にする
    public static String number(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return "0";
        if (value == (long) value) return Long.toString((long) value);
        String s = String.format(Locale.ROOT, "%.3f", value);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        s = s.substring(0, end);
        if (s.startsWith("0.")) return s.substring(1);
        if (s.startsWith("-0.")) return "-" + s.substring(2);
        return s.isEmpty() || s.equals("-") ? "0" : s;
    }

    // 文字列を看板の 4 行に均等に分ける。入りきらない場合は null
    public static String[] splitLines(String text, int maxLineLength) {
        if (text == null) text = "";
        final int len = text.length();
        if (len > maxLineLength * 4) return null;

        final String[] result = new String[4];
        final int base = len / 4;
        final int remainder = len % 4;
        int start = 0;
        for (int i = 0; i < 4; i++) {
            final int size = base + (i < remainder ? 1 : 0);
            result[i] = text.substring(start, start + size);
            start += size;
        }
        return result;
    }
}
