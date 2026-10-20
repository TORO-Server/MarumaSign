package marumasa.marumasa_sign.core.structure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * NBT (Minecraft のデータ形式) の読み取り。
 * Java 版はビッグエンディアン、統合版 (.mcstructure) はリトルエンディアンで書かれている。
 * 値は次の Java の型になる: Byte, Short, Integer, Long, Float, Double, byte[], String, List, Map, int[], long[]。
 */
public final class Nbt {

    public static final int END = 0;
    public static final int BYTE = 1;
    public static final int SHORT = 2;
    public static final int INT = 3;
    public static final int LONG = 4;
    public static final int FLOAT = 5;
    public static final int DOUBLE = 6;
    public static final int BYTE_ARRAY = 7;
    public static final int STRING = 8;
    public static final int LIST = 9;
    public static final int COMPOUND = 10;
    public static final int INT_ARRAY = 11;
    public static final int LONG_ARRAY = 12;

    // 入れ子の深さの上限 (Minecraft と同じ)
    private static final int MAX_DEPTH = 512;

    // 読み込んだ NBT の一番外側
    public static final class Root {
        public final String name;
        public final Map<String, Object> value;

        Root(String name, Map<String, Object> value) {
            this.name = name;
            this.value = value;
        }
    }

    private final byte[] data;
    private final boolean little;
    private int pos;

    private Nbt(byte[] data, boolean little) {
        this.data = data;
        this.little = little;
    }

    // gzip や zlib で圧縮されていれば展開する。展開後の大きさが limit を超えたら IOException
    public static byte[] decompress(byte[] content, int limit) throws IOException {
        if (content.length >= 2 && (content[0] & 0xFF) == 0x1F && (content[1] & 0xFF) == 0x8B) {
            return readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(content), 65536), limit);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0x78 && (((content[0] & 0xFF) << 8) | (content[1] & 0xFF)) % 31 == 0) {
            return readAll(new InflaterInputStream(new java.io.ByteArrayInputStream(content)), limit);
        }
        return content;
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        try {
            final ByteArrayOutputStream output = new ByteArrayOutputStream(65536);
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > limit) throw new IOException("Decompressed size exceeds the limit of " + limit + " bytes.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    /**
     * 展開済みのバイト列を、一番外側が複合タグの NBT として読む。
     *
     * @param little リトルエンディアン (統合版) かどうか
     */
    public static Root read(byte[] data, boolean little) throws IOException {
        final Nbt reader = new Nbt(data, little);
        try {
            final int type = reader.u8();
            if (type != COMPOUND) throw new IOException("NBT does not start with a compound tag");
            final String name = reader.string();
            final Map<String, Object> value = reader.compound(0);
            return new Root(name, value);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Truncated NBT data");
        }
    }

    /**
     * 先頭が NBT の複合タグらしいかどうか。最初の名前と、その次のタグの種類と名前の長さを見る。
     *
     * @param little リトルエンディアンとして見るかどうか
     */
    public static boolean looksLikeNbt(byte[] head, int length, boolean little) {
        if (length < 6 || head[0] != COMPOUND) return false;
        final int nameLength = little ? (head[1] & 0xFF) | (head[2] & 0xFF) << 8 : (head[1] & 0xFF) << 8 | (head[2] & 0xFF);
        if (nameLength > 64) return false;
        int p = 3;
        for (int i = 0; i < nameLength; i++, p++) {
            if (p >= length) return true;
            final int c = head[p] & 0xFF;
            if (c < 0x20 || c > 0x7E) return false;
        }
        if (p + 3 > length) return true;
        final int type = head[p] & 0xFF;
        if (type < BYTE || type > LONG_ARRAY) return false;
        final int childLength = little ? (head[p + 1] & 0xFF) | (head[p + 2] & 0xFF) << 8 : (head[p + 1] & 0xFF) << 8 | (head[p + 2] & 0xFF);
        if (childLength == 0 || childLength > 64) return false;
        for (int i = 0; i < childLength && p + 3 + i < length; i++) {
            final int c = head[p + 3 + i] & 0xFF;
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private int u8() {
        return data[pos++] & 0xFF;
    }

    private short s16() {
        final int a = data[pos++] & 0xFF, b = data[pos++] & 0xFF;
        return (short) (little ? a | b << 8 : a << 8 | b);
    }

    private int s32() {
        final int a = data[pos] & 0xFF, b = data[pos + 1] & 0xFF, c = data[pos + 2] & 0xFF, d = data[pos + 3] & 0xFF;
        pos += 4;
        return little ? a | b << 8 | c << 16 | d << 24 : a << 24 | b << 16 | c << 8 | d;
    }

    private long s64() {
        final long first = s32() & 0xFFFFFFFFL, second = s32() & 0xFFFFFFFFL;
        return little ? first | second << 32 : first << 32 | second;
    }

    // 配列の長さ。残りのバイト数で収まらない長さなら IOException (壊れたファイルで大きな配列を作らないように)
    private int length(int elementSize) throws IOException {
        final int count = s32();
        if (count < 0 || (long) count * elementSize > data.length - pos) throw new IOException("Invalid NBT array length: " + count);
        return count;
    }

    private String string() throws IOException {
        final int length = s16() & 0xFFFF;
        if (length > data.length - pos) throw new IOException("Truncated NBT string");
        final String text = decodeModifiedUtf8(data, pos, length);
        pos += length;
        return text;
    }

    // Java の DataInput.readUTF と同じ「修正 UTF-8」を読む (統合版も同じ文字の並べ方で、不正な並びも 1 文字ずつ受け付ける)
    private static String decodeModifiedUtf8(byte[] bytes, int offset, int length) {
        final StringBuilder builder = new StringBuilder(length);
        final int end = offset + length;
        int i = offset;
        while (i < end) {
            final int a = bytes[i] & 0xFF;
            if (a < 0x80) {
                builder.append((char) a);
                i++;
            } else if ((a & 0xE0) == 0xC0 && i + 1 < end) {
                builder.append((char) ((a & 0x1F) << 6 | bytes[i + 1] & 0x3F));
                i += 2;
            } else if ((a & 0xF0) == 0xE0 && i + 2 < end) {
                builder.append((char) ((a & 0x0F) << 12 | (bytes[i + 1] & 0x3F) << 6 | bytes[i + 2] & 0x3F));
                i += 3;
            } else if ((a & 0xF8) == 0xF0 && i + 3 < end) {
                // 統合版は普通の UTF-8 で 4 バイトの文字を書くことがある
                final int code = (a & 0x07) << 18 | (bytes[i + 1] & 0x3F) << 12 | (bytes[i + 2] & 0x3F) << 6 | bytes[i + 3] & 0x3F;
                if (Character.isValidCodePoint(code)) builder.appendCodePoint(code);
                else builder.append('�');
                i += 4;
            } else {
                builder.append('�');
                i++;
            }
        }
        return builder.toString();
    }

    private Map<String, Object> compound(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT is nested too deeply");
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        while (true) {
            final int type = u8();
            if (type == END) return map;
            final String name = string();
            map.put(name, value(type, depth + 1));
        }
    }

    private Object value(int type, int depth) throws IOException {
        switch (type) {
            case BYTE:
                return (byte) u8();
            case SHORT:
                return s16();
            case INT:
                return s32();
            case LONG:
                return s64();
            case FLOAT:
                return Float.intBitsToFloat(s32());
            case DOUBLE:
                return Double.longBitsToDouble(s64());
            case BYTE_ARRAY: {
                final int count = length(1);
                final byte[] array = new byte[count];
                System.arraycopy(data, pos, array, 0, count);
                pos += count;
                return array;
            }
            case STRING:
                return string();
            case LIST: {
                if (depth > MAX_DEPTH) throw new IOException("NBT is nested too deeply");
                final int elementType = u8();
                final int count = length(elementType == END ? 0 : 1);
                final List<Object> list = new ArrayList<Object>(Math.min(count, 65536));
                for (int i = 0; i < count; i++) {
                    if (elementType == END) break;
                    list.add(value(elementType, depth + 1));
                }
                return list;
            }
            case COMPOUND:
                return compound(depth);
            case INT_ARRAY: {
                final int count = length(4);
                final int[] array = new int[count];
                for (int i = 0; i < count; i++) array[i] = s32();
                return array;
            }
            case LONG_ARRAY: {
                final int count = length(8);
                final long[] array = new long[count];
                for (int i = 0; i < count; i++) array[i] = s64();
                return array;
            }
            default:
                throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    // ---------- 読み取った値を取り出すための補助 ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    public static Map<String, Object> compound(Map<String, Object> parent, String name) {
        return compound(parent.get(name));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    public static List<Object> list(Map<String, Object> parent, String name) {
        return list(parent.get(name));
    }

    public static boolean has(Map<String, Object> parent, String name) {
        return parent.containsKey(name);
    }

    public static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static int integer(Map<String, Object> parent, String name, int fallback) {
        return integer(parent.get(name), fallback);
    }

    public static long longValue(Map<String, Object> parent, String name, long fallback) {
        final Object value = parent.get(name);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public static String string(Map<String, Object> parent, String name, String fallback) {
        final Object value = parent.get(name);
        return value instanceof String ? (String) value : fallback;
    }

    public static byte[] bytes(Map<String, Object> parent, String name) {
        final Object value = parent.get(name);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    // 整数の並び (int[]、long[]、byte[] またはその種類のリスト) を int[] にする。なければ null
    public static int[] ints(Object value) {
        if (value instanceof int[]) return (int[]) value;
        if (value instanceof byte[]) {
            final byte[] bytes = (byte[]) value;
            final int[] result = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) result[i] = bytes[i];
            return result;
        }
        if (value instanceof long[]) {
            final long[] longs = (long[]) value;
            final int[] result = new int[longs.length];
            for (int i = 0; i < longs.length; i++) result[i] = (int) longs[i];
            return result;
        }
        if (value instanceof List) {
            final List<?> list = (List<?>) value;
            final int[] result = new int[list.size()];
            for (int i = 0; i < result.length; i++) {
                final Object item = list.get(i);
                if (!(item instanceof Number)) return null;
                result[i] = ((Number) item).intValue();
            }
            return result;
        }
        return null;
    }

    // 小数の並び (double や float のリスト) を double[] にする。なければ null
    public static double[] doubles(Object value) {
        if (value instanceof List) {
            final List<?> list = (List<?>) value;
            final double[] result = new double[list.size()];
            for (int i = 0; i < result.length; i++) {
                final Object item = list.get(i);
                if (!(item instanceof Number)) return null;
                result[i] = ((Number) item).doubleValue();
            }
            return result;
        }
        final int[] ints = ints(value);
        if (ints == null) return null;
        final double[] result = new double[ints.length];
        for (int i = 0; i < ints.length; i++) result[i] = ints[i];
        return result;
    }
}
