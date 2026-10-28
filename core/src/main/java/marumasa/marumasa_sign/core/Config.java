package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.util.Json;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Config {

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 32;

    private final File file;

    private int maxThreads = 5;
    private String lastDirectory = "";
    private int volume = 100;

    Config(File gameDirectory) {
        this.file = new File(new File(gameDirectory, "config"), MarumaSign.MOD_ID + ".json");
        if (file.exists()) {
            deserialize();
        } else {
            serialize();
        }
    }

    // 画像の同時ロード数 設定
    public void setMaxThreads(int value) {
        maxThreads = Math.max(MIN_THREADS, Math.min(MAX_THREADS, value));
        serialize();
    }

    // 画像の同時ロード数 取得
    public int getMaxThreads() {
        return maxThreads;
    }

    // 動画や音声の音量 (0 - 100)
    public int getVolume() {
        return volume;
    }

    public void setVolume(int value) {
        volume = Math.max(0, Math.min(100, value));
        serialize();
    }

    // 最後に画像を選んだフォルダ
    public String getLastDirectory() {
        return lastDirectory;
    }

    public void setLastDirectory(String value) {
        if (value == null) value = "";
        if (value.equals(lastDirectory)) return;
        lastDirectory = value;
        serialize();
    }

    private static final Pattern MAX_THREADS_PATTERN = Pattern.compile("\"MaxThreads\"\\s*:\\s*(-?\\d+)");
    private static final Pattern VOLUME_PATTERN = Pattern.compile("\"Volume\"\\s*:\\s*(-?\\d+)");
    private static final Pattern LAST_DIRECTORY_PATTERN = Pattern.compile("\"LastDirectory\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private void deserialize() {
        try {
            final String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 壊れた値 (int に収まらない数など) があっても、ほかの項目は読む
            maxThreads = number(MAX_THREADS_PATTERN, json, maxThreads, MIN_THREADS, MAX_THREADS);
            volume = number(VOLUME_PATTERN, json, volume, 0, 100);
            final Matcher matcher = LAST_DIRECTORY_PATTERN.matcher(json);
            if (matcher.find()) {
                // \n や \u00e9 などの書き方も、JSON として読む
                final Object value = Json.parse("\"" + matcher.group(1) + "\"");
                if (value instanceof String) lastDirectory = (String) value;
            }
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to deserialize config", e);
        }
    }

    // 数の項目を読み、min - max に収める。なければ fallback
    private static int number(Pattern pattern, String json, int fallback, int min, int max) {
        final Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) return fallback;
        // int に収まらない数も、そのまま比べて端に寄せる
        final BigInteger value = new BigInteger(matcher.group(1));
        if (value.compareTo(BigInteger.valueOf(min)) < 0) return min;
        if (value.compareTo(BigInteger.valueOf(max)) > 0) return max;
        return value.intValue();
    }

    private void serialize() {
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create directory: " + parent);
            }
            final String json = "{\"MaxThreads\":" + maxThreads
                    + ",\"Volume\":" + volume
                    + ",\"LastDirectory\":" + Json.write(lastDirectory) + "}";
            // 書いている途中で止まっても設定が消えないように、別のファイルに書いてから置き換える
            final File temporary = new File(file.getPath() + ".tmp");
            Files.write(temporary.toPath(), json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to serialize config", e);
        }
    }
}
