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

    // 数値の上限 (画面で入力できる範囲と同じ)。看板は誰でも書けるので、とても大きな画像で空を覆うようなことはさせない
    public static final float MAX_VALUE = 1000f;

    public static SignSpec parse(String text) {
        if (!looksLikeSign(text)) return null;
        final String[] p = text.split("\\|");
        if (p.length < PARAMETER_COUNT) return null;
        final float[] values = new float[PARAMETER_COUNT - 1];
        for (int i = 0; i < values.length; i++) {
            final float value;
            try {
                value = Float.parseFloat(p[i + 1]);
            } catch (NumberFormatException e) {
                return null;
            }
            // NaN や無限大では、描く位置や大きさが決まらない
            if (Float.isNaN(value) || Float.isInfinite(value)) return null;
            values[i] = Math.max(-MAX_VALUE, Math.min(MAX_VALUE, value));
        }
        return new SignSpec(p[0], values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
    }

    public static boolean isSign(String text) {
        return parse(text) != null;
    }

    public String toText() {
        // 区切りの「|」は、URL の中では %7C にする (取り除くと別の URL になる。サーバーは %7C を「|」として扱う)
        return url.replace("|", "%7C")
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
        // -0.0004 などは "-0" になるので、"0" にする
        return s.isEmpty() || s.equals("-") || s.equals("-0") ? "0" : s;
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
        int end = 0;
        for (int i = 0; i < 4; i++) {
            end += base + (i < remainder ? 1 : 0);
            int cut = Math.max(start, end);
            // サロゲートペア (絵文字など) を 2 行に分けない (片方だけの行は、保存や送信で別の文字に置き換わる)。
            // 行が長くなりすぎない向きにずらす
            if (cut > start && cut < len && Character.isHighSurrogate(text.charAt(cut - 1)) && Character.isLowSurrogate(text.charAt(cut))) {
                cut = cut - start + 1 <= maxLineLength ? cut + 1 : cut - 1;
            }
            if (cut - start > maxLineLength) return null;
            result[i] = text.substring(start, cut);
            start = cut;
        }
        return result;
    }
}
