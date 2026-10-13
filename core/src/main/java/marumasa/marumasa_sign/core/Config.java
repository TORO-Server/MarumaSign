package marumasa.marumasa_sign.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Config {

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 32;

    private final File file;

    private int maxThreads = 5;
    private String lastDirectory = "";

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
    private static final Pattern LAST_DIRECTORY_PATTERN = Pattern.compile("\"LastDirectory\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private void deserialize() {
        try {
            final String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            Matcher matcher = MAX_THREADS_PATTERN.matcher(json);
            if (matcher.find()) {
                maxThreads = Math.max(MIN_THREADS, Math.min(MAX_THREADS, Integer.parseInt(matcher.group(1))));
            }
            matcher = LAST_DIRECTORY_PATTERN.matcher(json);
            if (matcher.find()) {
                lastDirectory = matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to deserialize config", e);
        }
    }

    private void serialize() {
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create directory: " + parent);
            }
            final String json = "{\"MaxThreads\":" + maxThreads
                    + ",\"LastDirectory\":\"" + lastDirectory.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to serialize config", e);
        }
    }
}
