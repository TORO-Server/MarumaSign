package marumasa.marumasa_sign.core.media;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 動画と音声の再生に使う FFmpeg の実行ファイルを探す。
 * 探す順番: システムプロパティ marumasign.ffmpeg、ゲームフォルダの marumasign/ffmpeg、PATH、よくあるインストール先。
 */
public final class Ffmpeg {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static boolean searched;
    private static File ffmpeg;
    private static File ffprobe;

    private Ffmpeg() {
    }

    // 再生に使える状態かどうか
    public static synchronized boolean isAvailable() {
        search();
        return ffmpeg != null && ffprobe != null;
    }

    // 見つからなければ例外を投げる (null のまま使って落ちないように)
    public static synchronized File requireFfmpeg() throws IOException {
        search();
        if (ffmpeg == null) throw new IOException("FFmpeg was not found");
        return ffmpeg;
    }

    static synchronized File requireFfprobe() throws IOException {
        search();
        if (ffprobe == null) throw new IOException("FFmpeg was not found");
        return ffprobe;
    }

    /**
     * ファイルの中身が、ほかのファイルや URL を読ませる再生リスト (HLS、DASH、ffconcat、IMF) かどうか。
     * 看板からダウンロードした画像は、一時ファイルにして FFmpeg に「ファイルだけを読む」設定で読ませる。
     * 中身が再生リストだと、そこに書かれたこのパソコンの中のファイル (動画など) まで読まれて看板に映るので、FFmpeg には渡さない。
     * FFmpeg が形式を見分けるときは、最初の 0 のバイトまでを文字として見るので、ここでもそこまでを (大文字と小文字を区別せずに) 探す。
     */
    public static boolean isPlaylist(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        final String text = new String(data, 0, end, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return text.contains("#extm3u") || text.contains("<mpd") || text.contains("ffconcat") || text.contains("<compositionplaylist");
    }

    private static String executable(String name) {
        return WINDOWS ? name + ".exe" : name;
    }

    private static void search() {
        if (searched) return;
        searched = true;
        try {
            searchDirectories();
        } catch (RuntimeException e) {
            // 環境変数やフォルダが読めない場合も、FFmpeg がないものとして続ける
            ffmpeg = null;
            ffprobe = null;
            MarumaSign.LOGGER.info("Could not search for FFmpeg (" + e + ")");
        }
    }

    private static void searchDirectories() {
        final List<File> directories = new ArrayList<File>();
        final String configured = System.getProperty("marumasign.ffmpeg");
        if (configured != null && !configured.isEmpty()) {
            final File file = new File(configured);
            directories.add(file.isDirectory() ? file : file.getParentFile());
        }
        if (MarumaSign.platform() != null) {
            directories.add(new File(MarumaSign.platform().gameDirectory(), "marumasign/ffmpeg"));
            directories.add(new File(MarumaSign.platform().gameDirectory(), "marumasign/ffmpeg/bin"));
        }
        final String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isEmpty()) directories.add(new File(entry));
            }
        }
        if (WINDOWS) {
            directories.add(new File("C:\\ffmpeg\\bin"));
            directories.add(new File(System.getenv("ProgramFiles") == null ? "C:\\Program Files" : System.getenv("ProgramFiles"), "ffmpeg\\bin"));
        } else {
            directories.add(new File("/opt/homebrew/bin"));
            directories.add(new File("/usr/local/bin"));
            directories.add(new File("/usr/bin"));
        }

        for (File directory : directories) {
            if (directory == null) continue;
            final File candidate = new File(directory, executable("ffmpeg"));
            final File probe = new File(directory, executable("ffprobe"));
            if (candidate.isFile() && probe.isFile() && runs(candidate)) {
                ffmpeg = candidate;
                ffprobe = probe;
                MarumaSign.LOGGER.info("FFmpeg found: " + candidate);
                return;
            }
        }
        MarumaSign.LOGGER.info("FFmpeg was not found. Videos and audio cannot be played.");
    }

    // 実際に起動できるかどうかを確かめる
    private static boolean runs(File executable) {
        try {
            final Process process = new ProcessBuilder(executable.getPath(), "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start();
            process.getOutputStream().close();
            // 出力を読み捨てないと、止まってしまうことがある
            final byte[] buffer = new byte[4096];
            while (process.getInputStream().read(buffer) >= 0) {
                // 読み捨てる
            }
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
