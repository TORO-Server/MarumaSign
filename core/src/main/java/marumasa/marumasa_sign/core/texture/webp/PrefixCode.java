package marumasa.marumasa_sign.core.texture.webp;

import java.io.IOException;

/**
 * WebP 可逆圧縮の符号長から作る、標準形 (canonical) のハフマン符号。
 * 符号は短いものから、同じ長さなら記号の小さいものから順に割り当てる。ビット列には符号の上位ビットから入っている。
 *
 * 作る前に、符号長がちょうど隙間なく埋まった木になっているか (クラフトの不等式が等号で成り立つか) を確かめ、
 * 足りない木や多すぎる木は表を作らずに失敗にする。記号が 1 つだけのときは、0 ビットで読める符号にする。
 * (libwebp の CVE-2023-4863 は、壊れた符号長から表を作ったあとで木の正しさを確かめていたために、
 * 決まった大きさの表からはみ出して書き込んだもの。ここでは確かめてから、必要な大きさの配列を作る)
 */
final class PrefixCode {

    // 符号の長さの上限
    static final int MAX_LENGTH = 15;
    // 表を引くときに一度に見るビット数の上限
    private static final int LOOKUP_BITS = 8;

    // 記号が 1 つだけなら、その記号。そうでなければ -1
    private final int single;
    // 先頭の lookupBits ビット (読んだ順) から、記号 << 4 | 長さ を引く表。長さ 0 はもっと長い符号
    private final int lookupBits;
    private final int[] lookup;
    // lookupBits より長い符号を読むための、長さごとの最初の符号、その長さの符号の数、記号の並びでの位置
    private final int[] firstCode;
    private final int[] lengthCount;
    private final int[] lengthOffset;
    private final int[] sorted;
    private final int maxLength;

    private PrefixCode(int single) {
        this.single = single;
        this.lookupBits = 0;
        this.lookup = null;
        this.firstCode = null;
        this.lengthCount = null;
        this.lengthOffset = null;
        this.sorted = null;
        this.maxLength = 0;
    }

    private PrefixCode(int lookupBits, int[] lookup, int[] firstCode, int[] lengthCount, int[] lengthOffset, int[] sorted, int maxLength) {
        this.single = -1;
        this.lookupBits = lookupBits;
        this.lookup = lookup;
        this.firstCode = firstCode;
        this.lengthCount = lengthCount;
        this.lengthOffset = lengthOffset;
        this.sorted = sorted;
        this.maxLength = maxLength;
    }

    /**
     * 記号ごとの符号長 lengths[0..size) から符号を作る。正しくない符号長なら IOException。
     */
    static PrefixCode build(int[] lengths, int size) throws IOException {
        final int[] count = new int[MAX_LENGTH + 1];
        int used = 0;
        int last = -1;
        for (int s = 0; s < size; s++) {
            final int length = lengths[s];
            if (length < 0 || length > MAX_LENGTH) throw new IOException("Invalid WebP prefix code length");
            if (length > 0) {
                count[length]++;
                used++;
                last = s;
            }
        }
        if (used == 0) throw new IOException("Empty WebP prefix code");
        if (used == 1) return new PrefixCode(last);

        // 木が隙間なく埋まっているか。各記号の 2^(15 - 長さ) の和が 2^15 になればよい
        long kraft = 0;
        int maxLength = 0;
        for (int length = 1; length <= MAX_LENGTH; length++) {
            kraft += (long) count[length] << (MAX_LENGTH - length);
            if (count[length] > 0) maxLength = length;
        }
        if (kraft != 1L << MAX_LENGTH) throw new IOException("Incomplete or over-subscribed WebP prefix code");

        // 長さごとの最初の符号と、長さの順に並べた記号
        final int[] firstCode = new int[MAX_LENGTH + 2];
        final int[] lengthOffset = new int[MAX_LENGTH + 2];
        int code = 0;
        int offset = 0;
        for (int length = 1; length <= MAX_LENGTH; length++) {
            firstCode[length] = code;
            lengthOffset[length] = offset;
            code = (code + count[length]) << 1;
            offset += count[length];
        }
        final int[] sorted = new int[used];
        final int[] next = lengthOffset.clone();
        for (int s = 0; s < size; s++) {
            if (lengths[s] > 0) sorted[next[lengths[s]]++] = s;
        }

        // 短い符号は表で引く。表の位置は、符号を読んだ順 (上位ビットが先) に下位ビットから並べた値
        final int lookupBits = Math.min(maxLength, LOOKUP_BITS);
        final int[] lookup = new int[1 << lookupBits];
        for (int length = 1; length <= lookupBits; length++) {
            for (int i = 0; i < count[length]; i++) {
                final int symbol = sorted[lengthOffset[length] + i];
                final int reversed = Integer.reverse(firstCode[length] + i) >>> (32 - length);
                for (int fill = reversed; fill < lookup.length; fill += 1 << length) {
                    lookup[fill] = symbol << 4 | length;
                }
            }
        }
        return new PrefixCode(lookupBits, lookup, firstCode, count, lengthOffset, sorted, maxLength);
    }

    // 何ビットも読まずに決まる符号なら、その記号。そうでなければ -1
    int singleSymbol() {
        return single;
    }

    // 作った表の大きさ (メモリの見積もり用、int の数)
    int footprint() {
        return lookup == null ? 1 : lookup.length + sorted.length + 3 * (MAX_LENGTH + 2);
    }

    int read(LosslessBits bits) {
        if (single >= 0) return single;
        final int entry = lookup[bits.peek(lookupBits)];
        if (entry != 0) {
            bits.skip(entry & 15);
            return entry >>> 4;
        }
        // 長い符号。読んだ順に上位ビットから並べ直し、長さごとに最初の符号と比べる
        final int ahead = Integer.reverse(bits.peek(MAX_LENGTH)) >>> (32 - MAX_LENGTH);
        for (int length = lookupBits + 1; length <= maxLength; length++) {
            final int code = ahead >>> (MAX_LENGTH - length);
            final int index = code - firstCode[length];
            if (index >= 0 && index < lengthCount[length]) {
                bits.skip(length);
                return sorted[lengthOffset[length] + index];
            }
        }
        // 木は隙間なく埋まっているので、ここには来ない
        throw new IllegalStateException("Unreachable prefix code state");
    }
}
