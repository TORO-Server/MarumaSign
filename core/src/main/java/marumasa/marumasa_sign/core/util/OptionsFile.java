package marumasa.marumasa_sign.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Minecraft の options.txt から、指定した項目だけを読む。
 * 設定全体を読み直すと他の項目に影響するため、Mod のキーの割り当てはこれで個別に読む。
 */
public final class OptionsFile {

    private OptionsFile() {
    }

    // 項目の値を返す。ファイルや項目がなければ null
    public static String read(File gameDirectory, String key) {
        final File file = new File(gameDirectory, "options.txt");
        if (!file.isFile()) return null;
        final String prefix = key + ":";
        try {
            final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (IOException | RuntimeException e) {
            // 読めなければ既定のキーのままにする
        }
        return null;
    }
}
