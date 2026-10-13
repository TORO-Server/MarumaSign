package marumasa.marumasa_sign.boot;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 実行中の Minecraft のバージョンに合った実装を選んで起動する。
 * この jar には全バージョン分の実装が入っているため、合うものだけを読み込む。
 */
public final class Boot {

    private static boolean started;

    private Boot() {
    }

    public static synchronized void start(String loader, String minecraftVersion) {
        if (started) return;
        started = true;

        if (minecraftVersion == null) {
            MarumaSign.LOGGER.error("Could not detect the Minecraft version. MarumaSign is disabled.");
            return;
        }
        final String era = loadTable().getProperty(loader + "." + minecraftVersion);
        if (era == null) {
            MarumaSign.LOGGER.error("MarumaSign does not support Minecraft " + minecraftVersion + " on " + loader + ".");
            return;
        }
        try {
            Class.forName("marumasa.marumasa_sign." + loader + "." + era + ".Entry").getMethod("start").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            MarumaSign.LOGGER.error("Failed to start MarumaSign for Minecraft " + minecraftVersion + " on " + loader, e);
        }
    }

    // 「ローダー.バージョン = 実装の名前」の対応表
    private static Properties loadTable() {
        final Properties table = new Properties();
        final InputStream stream = Boot.class.getResourceAsStream("/marumasa_sign/targets.properties");
        if (stream == null) return table;
        try {
            try {
                table.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            MarumaSign.LOGGER.error("Failed to load the target table", e);
        }
        return table;
    }

    // ---------- バージョンの検出 ----------
    // ローダーの API はバージョンごとに違うので、リフレクションで順に試す

    static String staticField(String className, String fieldName) {
        try {
            final Field field = Class.forName(className).getField(fieldName);
            final Object value = field.get(null);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    // クラスの static メソッドを呼び、その結果に対して残りのメソッドを順に呼ぶ
    static String staticChain(String className, String... methodNames) {
        try {
            Object value = null;
            Class<?> type = Class.forName(className);
            for (String name : methodNames) {
                final Method method = type.getMethod(name);
                value = method.invoke(value);
                if (value == null) return null;
                type = value.getClass();
            }
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static final Pattern VERSION_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    // Minecraft 1.14 以降の jar に入っている version.json から読む
    static String fromVersionJson() {
        final InputStream stream = Boot.class.getClassLoader().getResourceAsStream("version.json");
        if (stream == null) return null;
        try {
            try {
                final byte[] buffer = new byte[4096];
                final int length = stream.read(buffer);
                if (length <= 0) return null;
                final Matcher matcher = VERSION_ID.matcher(new String(buffer, 0, length, StandardCharsets.UTF_8));
                return matcher.find() ? matcher.group(1) : null;
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
