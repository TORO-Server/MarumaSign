package marumasa.marumasa_sign.core.media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 画面で使う、動画や音声についての補助。
 */
public final class MediaTools {

    // 動画や音声として扱う拡張子 (FFmpeg がないときの判定に使う)
    private static final String[] MEDIA_EXTENSIONS = {
            ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".flv", ".wmv", ".ts", ".m2ts", ".mpg", ".mpeg", ".3gp", ".ogv",
            ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".opus", ".flac", ".wma",
            ".webp", ".avif", ".heic", ".tif", ".tiff",
    };

    private MediaTools() {
    }

    public static boolean hasMediaExtension(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : MEDIA_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * 動画などから、プレビュー用の画像を 1 枚取り出す。
     * 利用者が自分で選んだファイルにも使うため、ローカルのファイルも読ませる。
     *
     * @param source ファイルのパスまたは URL
     * @return 取り出せなければ null (音声だけの場合など)
     */
    public static BufferedImage thumbnail(String source) throws IOException {
        if (!Ffmpeg.isAvailable()) return null;
        final Process process = new ProcessBuilder(Arrays.asList(
                Ffmpeg.ffmpeg().getPath(),
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-protocol_whitelist", "file," + MediaPlayer.PROTOCOLS,
                "-i", source,
                "-map", "0:v:0", "-frames:v", "1",
                // 大きすぎる映像は縮小する
                "-vf", "scale='min(640,iw)':-2",
                "-f", "image2pipe", "-c:v", "png", "pipe:1"
        )).redirectErrorStream(false).start();
        process.getOutputStream().close();
        MediaPlayer.drain(process.getErrorStream(), null);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final InputStream input = process.getInputStream();
        try {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > 16 * 1024 * 1024) break;
            }
        } finally {
            input.close();
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (bytes.size() == 0) return null;
        return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
    }

    // FFmpeg で再生できるファイルかどうか
    public static boolean isPlayable(File file) {
        if (!Ffmpeg.isAvailable()) return hasMediaExtension(file.getName());
        try {
            final Process process = new ProcessBuilder(Arrays.asList(
                    Ffmpeg.ffprobe().getPath(), "-v", "error",
                    "-show_entries", "stream=codec_type", "-of", "default=noprint_wrappers=1",
                    file.getPath()
            )).redirectErrorStream(true).start();
            process.getOutputStream().close();
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final InputStream input = process.getInputStream();
            try {
                final byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            } finally {
                input.close();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            final String text = output.toString("UTF-8");
            return process.exitValue() == 0 && (text.contains("codec_type=video") || text.contains("codec_type=audio"));
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
