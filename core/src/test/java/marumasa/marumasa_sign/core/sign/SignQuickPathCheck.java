package marumasa.marumasa_sign.core.sign;

import java.util.Random;

/**
 * 看板 1 つにつき毎フレーム通る処理の近道が、近道を使わないときと同じ答えを出すことを確かめる。RuntimeLogicCheck から呼ぶ。
 * (MarumaSign の看板かどうかの軽い判定、アニメーション画像のコマ選び、看板の文字列の使い回し)
 */
public final class SignQuickPathCheck {

    private static int failures;

    private SignQuickPathCheck() {
    }

    // 失敗の数を返す
    public static int run() {
        failures = 0;
        looksLikeSign();
        frameIndex();
        textCache();
        return failures;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + "sign: " + message);
        if (!condition) failures++;
    }

    // 区切りの文字を端から数える、元の判定
    private static boolean countsEnough(String text) {
        if (text == null || text.length() < 2 * SignSpec.PARAMETER_COUNT - 1) return false;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '|' && ++count >= SignSpec.PARAMETER_COUNT - 1) return true;
        }
        return false;
    }

    private static void looksLikeSign() {
        final Random random = new Random(11);
        final char[] alphabet = {'|', '|', 'a', '1', '.', '-', '\uD83D', '\uDE00', '｜', ' '};
        boolean same = SignSpec.looksLikeSign(null) == countsEnough(null);
        String example = "";
        for (int n = 0; n < 200000; n++) {
            final int length = random.nextInt(40);
            final StringBuilder text = new StringBuilder();
            for (int i = 0; i < length; i++) {
                // 区切りの多い文字列と少ない文字列の両方を作る
                text.append(alphabet[random.nextInt(n % 2 == 0 ? alphabet.length : 3)]);
            }
            final String value = text.toString();
            if (SignSpec.looksLikeSign(value) != countsEnough(value)) {
                same = false;
                example = value;
            }
        }
        check(same, "the quick check for sign text counts separators like the plain loop" + (same ? "" : ": " + example));
    }

    private static void frameIndex() {
        final Random random = new Random(12);
        boolean same = true, detected = true;
        for (int n = 0; n < 3000; n++) {
            final int count = 1 + random.nextInt(12);
            final boolean uniform = n % 3 == 0;
            final int step = 1 + random.nextInt(200);
            final int[] frameEnd = new int[count];
            int time = 0;
            for (int i = 0; i < count; i++) {
                time += uniform ? step : 1 + random.nextInt(200);
                frameEnd[i] = time;
            }
            final int delay = TextureEntry.uniformDelay(frameEnd);
            if (uniform && count > 1 && delay != step) detected = false;
            if (count == 1 && delay != 0) detected = false;
            for (int t = 0; t < frameEnd[count - 1]; t++) {
                // 近道を使わない (delay = 0) ときと同じコマになる
                if (TextureEntry.frameIndex(frameEnd, delay, t) != TextureEntry.frameIndex(frameEnd, 0, t)) same = false;
            }
        }
        check(same && detected, "animation frames with equal delays are picked by division, like the search");
        // 長さの合計が int を超えそうなコマ (2 コマで 2^31 - 1 ミリ秒) でも、掛け算があふれない
        final int[] long2 = {Integer.MAX_VALUE / 2, Integer.MAX_VALUE - 1};
        check(TextureEntry.uniformDelay(long2) == Integer.MAX_VALUE / 2 && TextureEntry.uniformDelay(new int[]{5, 9}) == 0
                && TextureEntry.uniformDelay(new int[]{0, 0}) == 0, "equal delays are detected without overflow");
    }

    private static void textCache() {
        final Object sign = new Object(), other = new Object();
        final Object front = new Object(), back = new Object();
        final String text = new String("a|b");
        boolean right = SignTextCache.get(sign, front, back) == null;
        right &= SignTextCache.put(sign, front, back, text) == text;
        // 同じオブジェクトなら、同じ String をそのまま返す
        right &= SignTextCache.get(sign, front, back) == text;
        // 表か裏が別のオブジェクトに入れ替わっていたら、使い回さない (中身が同じに見えても)
        right &= SignTextCache.get(sign, new Object(), back) == null;
        right &= SignTextCache.get(sign, front, new Object()) == null;
        right &= SignTextCache.get(sign, new String("x"), new String("x")) == null;
        // 別の看板とは混ざらない
        right &= SignTextCache.get(other, front, back) == null;
        SignTextCache.put(other, front, back, "c|d");
        right &= SignTextCache.get(sign, front, back) == text && "c|d".equals(SignTextCache.get(other, front, back));
        // 書き換えられたあとは、新しいほうだけを覚えている
        final Object changed = new Object();
        SignTextCache.put(sign, changed, back, "e|f");
        right &= SignTextCache.get(sign, front, back) == null && "e|f".equals(SignTextCache.get(sign, changed, back));
        check(right, "joined sign text is reused only while the same text objects are on the sign");
    }
}
