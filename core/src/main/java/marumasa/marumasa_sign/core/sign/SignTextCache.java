package marumasa.marumasa_sign.core.sign;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 看板 (ブロックエンティティ) ごとに、行をつなげた文字列を覚えておく。
 *
 * <p>描画のたびに、見えているすべての看板 (MarumaSign の看板でないものも) の文字をつなげて調べている。
 * 看板の文字は、書き換えられると別のオブジェクトに入れ替わる作り (Minecraft 1.20 以降の SignText) なので、
 * 表と裏の文字が前に調べたときと同じオブジェクトなら、文字列を作り直さずに、前のものを返せる。
 * 同じ String を返すので、そのあとの表の引き当て (ハッシュの計算と比較) も速くなる。</p>
 *
 * <p>看板が使われなくなれば、覚えていた文字列も一緒に破棄される。</p>
 */
public final class SignTextCache {

    private static final class Entry {
        Object front;
        Object back;
        String text;
    }

    // キーは看板のブロックエンティティ (同じものかどうかで見分ける)
    private static final Map<Object, Entry> ENTRIES = new WeakHashMap<Object, Entry>();

    private SignTextCache() {
    }

    /**
     * 看板の表と裏の文字が、前に put したときと同じオブジェクトなら、そのときの文字列を返す。そうでなければ null。
     */
    public static synchronized String get(Object sign, Object front, Object back) {
        final Entry entry = ENTRIES.get(sign);
        return entry != null && entry.front == front && entry.back == back ? entry.text : null;
    }

    /**
     * 看板の表と裏の文字からつなげた文字列を覚え、その文字列を返す。
     */
    public static synchronized String put(Object sign, Object front, Object back, String text) {
        Entry entry = ENTRIES.get(sign);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(sign, entry);
        }
        entry.front = front;
        entry.back = back;
        entry.text = text;
        return text;
    }
}
