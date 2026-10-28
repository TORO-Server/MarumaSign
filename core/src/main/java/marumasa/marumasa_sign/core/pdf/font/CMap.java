package marumasa.marumasa_sign.core.pdf.font;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CMap: 文字列のバイト列を文字コードに区切り、CID (または Unicode) にする。
 * 対応は範囲のまま持ち、&lt;0000&gt; &lt;FFFF&gt; のような大きな範囲を展開しない
 * (展開すると、小さなファイルで大量のメモリを使わせられる)。
 */
public final class CMap {

    // 入れ子 (usecmap) の深さの上限
    static final int MAX_USE_DEPTH = 4;
    // 1 つの CMap の対応の数の上限
    static final int MAX_ENTRIES = 1 << 20;

    private final String name;
    private int wmode;
    private CMap parent;
    // 文字コードの範囲 (バイトごとの下限と上限)
    private final List<int[]> codespaceLow = new ArrayList<>();
    private final List<int[]> codespaceHigh = new ArrayList<>();
    // 書かれた順の対応 {バイト数, 始まり, 終わり, 値}。値は CID か Unicode のコードポイント (複数の文字なら -2)
    private final List<int[]> pending = new ArrayList<>();
    // バイト数ごとの、重なりを除いて並べた対応
    private final int[][] starts = new int[5][];
    private final int[][] ends = new int[5][];
    private final int[][] values = new int[5][];
    private boolean built;

    CMap(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public int wmode() {
        return wmode;
    }

    public boolean isVertical() {
        return wmode == 1;
    }

    void setWmode(int value) {
        wmode = value;
    }

    void setParent(CMap parent) {
        this.parent = parent;
        if (codespaceLow.isEmpty() && parent != null) {
            codespaceLow.addAll(parent.codespaceLow);
            codespaceHigh.addAll(parent.codespaceHigh);
        }
    }

    void addCodespace(byte[] low, byte[] high) {
        if (low.length == 0 || low.length > 4 || high.length != low.length) return;
        final int[] l = new int[low.length], h = new int[low.length];
        for (int i = 0; i < low.length; i++) {
            l[i] = low[i] & 0xFF;
            h[i] = high[i] & 0xFF;
        }
        codespaceLow.add(l);
        codespaceHigh.add(h);
    }

    void addCodespace(int length, int low, int high) {
        final byte[] l = new byte[length], h = new byte[length];
        for (int i = 0; i < length; i++) {
            l[i] = (byte) (low >> (8 * (length - 1 - i)));
            h[i] = (byte) (high >> (8 * (length - 1 - i)));
        }
        addCodespace(l, h);
    }

    void add(int length, int start, int end, int value) {
        if (length < 1 || length > 4 || end < start || pending.size() >= MAX_ENTRIES) return;
        pending.add(new int[]{length, start, end, value});
        built = false;
    }

    boolean hasCodespace() {
        return !codespaceLow.isEmpty();
    }

    /**
     * 文字列の position から 1 つの文字コードを読む。結果は {コード, バイト数}。
     */
    public void readCode(byte[] bytes, int position, int[] result) {
        if (codespaceLow.isEmpty()) {
            // 範囲の書かれていない CMap は 2 バイト (Identity と同じ)
            readFixed(bytes, position, 2, result);
            return;
        }
        int code = 0;
        final int available = Math.min(4, bytes.length - position);
        for (int n = 1; n <= available; n++) {
            code = (code << 8) | (bytes[position + n - 1] & 0xFF);
            for (int r = 0; r < codespaceLow.size(); r++) {
                final int[] low = codespaceLow.get(r);
                if (low.length != n) continue;
                if (matches(bytes, position, low, codespaceHigh.get(r))) {
                    result[0] = code;
                    result[1] = n;
                    return;
                }
            }
        }
        // どの範囲にも合わない: 最も短い範囲の長さだけ進める
        int shortest = 4;
        for (int[] low : codespaceLow) shortest = Math.min(shortest, low.length);
        readFixed(bytes, position, Math.min(shortest, Math.max(1, available)), result);
    }

    private static boolean matches(byte[] bytes, int position, int[] low, int[] high) {
        for (int i = 0; i < low.length; i++) {
            final int b = bytes[position + i] & 0xFF;
            if (b < low[i] || b > high[i]) return false;
        }
        return true;
    }

    private static void readFixed(byte[] bytes, int position, int n, int[] result) {
        int code = 0;
        final int available = Math.min(n, bytes.length - position);
        for (int i = 0; i < available; i++) code = (code << 8) | (bytes[position + i] & 0xFF);
        result[0] = code;
        result[1] = Math.max(1, available);
    }

    /**
     * 文字コード (length バイト) の値。なければ -1。
     */
    public int lookup(int code, int length) {
        return lookup(code, length, 0);
    }

    private int lookup(int code, int length, int depth) {
        build();
        if (length >= 1 && length <= 4 && starts[length] != null) {
            final int[] s = starts[length];
            int lo = 0, hi = s.length - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (code < s[mid]) {
                    hi = mid - 1;
                } else if (code > ends[length][mid]) {
                    lo = mid + 1;
                } else {
                    final int value = values[length][mid];
                    return value < 0 ? value : value + (code - s[mid]);
                }
            }
        }
        return parent != null && depth < MAX_USE_DEPTH ? parent.lookup(code, length, depth + 1) : -1;
    }

    // 書かれた順に、後のものが前のものを上書きするように、重なりのない範囲の並びにする
    private synchronized void build() {
        if (built) return;
        for (int length = 1; length <= 4; length++) {
            final TreeMap<Integer, int[]> map = new TreeMap<>();
            for (int[] entry : pending) {
                if (entry[0] != length) continue;
                paint(map, entry[1], entry[2], entry[3]);
            }
            if (map.isEmpty()) {
                starts[length] = null;
                continue;
            }
            final int n = map.size();
            starts[length] = new int[n];
            ends[length] = new int[n];
            values[length] = new int[n];
            int i = 0;
            for (Map.Entry<Integer, int[]> e : map.entrySet()) {
                starts[length][i] = e.getKey();
                ends[length][i] = e.getValue()[0];
                values[length][i] = e.getValue()[1];
                i++;
            }
        }
        built = true;
    }

    // map: 始まり -> {終わり, 始まりの値}。[start, end] を value から始まる値で塗る
    private static void paint(TreeMap<Integer, int[]> map, int start, int end, int value) {
        // start より前から続いて重なる範囲を切る
        final Map.Entry<Integer, int[]> before = map.floorEntry(start);
        if (before != null && before.getValue()[0] >= start && before.getKey() < start) {
            final int[] old = before.getValue();
            final int oldEnd = old[0];
            map.put(before.getKey(), new int[]{start - 1, old[1]});
            if (oldEnd > end) {
                map.put(end + 1, new int[]{oldEnd, shift(old[1], end + 1 - before.getKey())});
            }
        }
        // 中にある範囲を取り除く (はみ出す分は残す)
        while (true) {
            final Map.Entry<Integer, int[]> inside = map.ceilingEntry(start);
            if (inside == null || inside.getKey() > end) break;
            map.remove(inside.getKey());
            final int[] old = inside.getValue();
            if (old[0] > end) {
                map.put(end + 1, new int[]{old[0], shift(old[1], end + 1 - inside.getKey())});
                break;
            }
        }
        map.put(start, new int[]{end, value});
    }

    private static int shift(int value, int offset) {
        return value < 0 ? value : value + offset;
    }
}
