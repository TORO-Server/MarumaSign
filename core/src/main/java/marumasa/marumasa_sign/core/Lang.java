package marumasa.marumasa_sign.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * 画面やメッセージの翻訳。
 * リソースパックの仕組みはバージョンごとに異なるため、core が自前で読み込む。
 */
public final class Lang {

    private static final String DEFAULT_LANGUAGE = "en_us";

    private static final Properties fallback = load(DEFAULT_LANGUAGE);
    private static Properties current = fallback;
    private static String currentCode = DEFAULT_LANGUAGE;

    private Lang() {
    }

    private static Properties load(String code) {
        final Properties properties = new Properties();
        final InputStream stream = Lang.class.getResourceAsStream("/marumasa_sign/lang/" + code + ".properties");
        if (stream == null) return null;
        try {
            try {
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            MarumaSign.LOGGER.error("Failed to load language: " + code, e);
        }
        return properties;
    }

    // Minecraft で選択中の言語に切り替える
    public static synchronized void select(String code) {
        code = code == null ? DEFAULT_LANGUAGE : code.toLowerCase(Locale.ROOT);
        if (code.equals(currentCode)) return;
        currentCode = code;
        final Properties loaded = load(code);
        current = loaded != null ? loaded : fallback;
    }

    public static synchronized String get(String key) {
        String value = current.getProperty(key);
        if (value == null) value = fallback.getProperty(key);
        return value != null ? value : key;
    }

    public static String get(String key, Object... args) {
        return String.format(Locale.ROOT, get(key), args);
    }
}
