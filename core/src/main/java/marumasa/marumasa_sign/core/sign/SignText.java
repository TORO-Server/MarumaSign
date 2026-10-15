package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.util.Json;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * アイテムのデータに保存されている看板の文字を、書かれている文字だけにする。
 * 保存の形式は Minecraft のバージョンによって違い、文字そのままのことも、
 * JSON などで書かれたテキストコンポーネントのこともある。
 */
public final class SignText {

    // JSON として読めなかったデータから、文字の部分を取り出す
    private static final Pattern TEXT_FIELD = Pattern.compile("\"?text\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private SignText() {
    }

    /**
     * 看板 1 行分のデータを、書かれている文字にする。
     */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        final char first = raw.charAt(0);
        if (first != '{' && first != '[' && first != '"') return raw;
        try {
            final StringBuilder builder = new StringBuilder();
            flatten(Json.parse(raw), builder);
            return builder.toString();
        } catch (IOException e) {
            // キーに引用符のない形式など
            final Matcher matcher = TEXT_FIELD.matcher(raw);
            final StringBuilder builder = new StringBuilder();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                builder.append(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
            return found ? builder.toString() : raw;
        }
    }

    // テキストコンポーネントの中の文字を、順につなげる
    private static void flatten(Object value, StringBuilder builder) {
        if (value instanceof String) {
            builder.append((String) value);
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) flatten(item, builder);
        } else if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final Object text = map.get("text");
            if (text instanceof String) builder.append((String) text);
            flatten(map.get("extra"), builder);
        }
    }
}
