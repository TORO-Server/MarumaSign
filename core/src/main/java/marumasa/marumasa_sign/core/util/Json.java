package marumasa.marumasa_sign.core.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON の読み取り。Minecraft のバージョンごとに同梱ライブラリが違うため、自前で持つ。
 * オブジェクトは Map、配列は List、数値は Double、文字列は String、真偽値は Boolean、null は null になる。
 */
public final class Json {

    // 入れ子の深さの上限
    private static final int MAX_DEPTH = 256;

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) throws IOException {
        final Json json = new Json(text);
        // 先頭に BOM が付いていることがある
        if (text.length() > 0 && text.charAt(0) == '﻿') json.pos = 1;
        final Object value = json.value(0);
        json.skipSpace();
        if (json.pos != text.length()) throw json.error("Unexpected trailing text");
        return value;
    }

    private IOException error(String message) {
        return new IOException("Invalid JSON at " + pos + ": " + message);
    }

    private void skipSpace() {
        while (pos < text.length()) {
            final char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            pos++;
        }
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw error("Too deep");
        skipSpace();
        if (pos >= text.length()) throw error("Unexpected end");
        final char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String word) throws IOException {
        if (!text.startsWith(word, pos)) throw error("Expected " + word);
        pos += word.length();
    }

    private Map<String, Object> object(int depth) throws IOException {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++;
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != '"') throw error("Expected a name");
            final String name = string();
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != ':') throw error("Expected ':'");
            pos++;
            map.put(name, value(depth + 1));
            skipSpace();
            if (pos >= text.length()) throw error("Unexpected end");
            final char c = text.charAt(pos++);
            if (c == '}') return map;
            if (c != ',') throw error("Expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws IOException {
        final List<Object> list = new ArrayList<Object>();
        pos++;
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value(depth + 1));
            skipSpace();
            if (pos >= text.length()) throw error("Unexpected end");
            final char c = text.charAt(pos++);
            if (c == ']') return list;
            if (c != ',') throw error("Expected ',' or ']'");
        }
    }

    private String string() throws IOException {
        final StringBuilder builder = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length()) throw error("Unterminated string");
            final char c = text.charAt(pos++);
            if (c == '"') return builder.toString();
            if (c != '\\') {
                builder.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("Unterminated string");
            final char escaped = text.charAt(pos++);
            switch (escaped) {
                case 'b':
                    builder.append('\b');
                    break;
                case 'f':
                    builder.append('\f');
                    break;
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) throw error("Invalid escape");
                    try {
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("Invalid escape");
                    }
                    pos += 4;
                    break;
                default:
                    // \" \\ \/ はそのままの文字
                    builder.append(escaped);
            }
        }
    }

    private Double number() throws IOException {
        final int start = pos;
        while (pos < text.length()) {
            final char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
            else break;
        }
        if (start == pos) throw error("Unexpected character");
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("Invalid number");
        }
    }

    // 読み取った値を JSON の文字列に戻す
    public static String write(Object value) {
        final StringBuilder builder = new StringBuilder();
        write(value, builder);
        return builder.toString();
    }

    private static void write(Object value, StringBuilder builder) {
        if (value instanceof Map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) builder.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), builder);
                builder.append(':');
                write(entry.getValue(), builder);
            }
            builder.append('}');
        } else if (value instanceof List) {
            builder.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) builder.append(',');
                first = false;
                write(item, builder);
            }
            builder.append(']');
        } else if (value instanceof String) {
            writeString((String) value, builder);
        } else if (value instanceof Number) {
            final double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                builder.append('0');
            } else if (number == Math.rint(number) && Math.abs(number) < 1e15) {
                // 整数は小数点なしで書く (番号として読まれる値のため)
                builder.append((long) number);
            } else {
                builder.append(number);
            }
        } else {
            builder.append(String.valueOf(value));
        }
    }

    private static void writeString(String text, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                builder.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                builder.append(String.format("\\u%04x", (int) c));
            } else {
                builder.append(c);
            }
        }
        builder.append('"');
    }

    // 以下は読み取った値を取り出すための補助

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    public static Map<String, Object> object(Map<String, Object> parent, String name) {
        return object(parent.get(name));
    }

    public static List<Object> array(Map<String, Object> parent, String name) {
        return array(parent.get(name));
    }

    public static int integer(Map<String, Object> parent, String name, int fallback) {
        final Object value = parent.get(name);
        return value instanceof Double ? (int) Math.round((Double) value) : fallback;
    }

    public static float number(Map<String, Object> parent, String name, float fallback) {
        final Object value = parent.get(name);
        return value instanceof Double ? ((Double) value).floatValue() : fallback;
    }

    public static String string(Map<String, Object> parent, String name, String fallback) {
        final Object value = parent.get(name);
        return value instanceof String ? (String) value : fallback;
    }

    public static boolean bool(Map<String, Object> parent, String name, boolean fallback) {
        final Object value = parent.get(name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    // 決まった長さの数値の配列。長さが違えば fallback を返す
    public static float[] numbers(Map<String, Object> parent, String name, float[] fallback) {
        final List<Object> list = array(parent.get(name));
        if (list.size() != fallback.length) return fallback.clone();
        final float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            final Object value = list.get(i);
            if (!(value instanceof Double)) return fallback.clone();
            result[i] = ((Double) value).floatValue();
        }
        return result;
    }

    // 長さが決まっていない数値の配列
    public static float[] numbers(Object value) {
        final List<Object> list = array(value);
        final float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            final Object item = list.get(i);
            result[i] = item instanceof Double ? ((Double) item).floatValue() : 0f;
        }
        return result;
    }
}
