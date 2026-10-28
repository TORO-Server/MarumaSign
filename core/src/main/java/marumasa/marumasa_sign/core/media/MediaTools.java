package marumasa.marumasa_sign.core.media;

import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.net.FilteringProxy;
import marumasa.marumasa_sign.core.net.UrlPolicy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
            ".mjpeg", ".mjpg",
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
        // 「HTTP://」のような大文字の書き方も URL として扱う (ファイルとして扱うと、確かめずにファイルも読ませる設定になる)
        final String lower = source.toLowerCase(Locale.ROOT);
        final boolean remote = lower.startsWith("http://") || lower.startsWith("https://");
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(Arrays.asList(
                Ffmpeg.requireFfmpeg().getPath(),
                "-hide_banner", "-loglevel", "error", "-nostdin",
                // URL の中身 (再生リストなど) からは、このパソコンの中のファイルを読ませない
                "-protocol_whitelist", remote ? MediaPlayer.PROTOCOLS : "file," + MediaPlayer.PROTOCOLS,
                // ライブ配信が途中で止まっても、待ち続けない
                "-rw_timeout", MediaPlayer.NETWORK_TIMEOUT,
                "-i", source,
                "-map", "0:v:0", "-frames:v", "1",
                // 大きすぎる映像は縮小する
                "-vf", "scale='min(640,iw)':-2",
                "-f", "image2pipe", "-c:v", "png", "pipe:1"
        )));
        // ほかの再生と同じく通信の行き先を確かめる (ファイルの中から URL を読むこともある)
        if (remote && !UrlPolicy.isAllowed(source)) {
            throw new IOException("Blocked URL: " + source);
        }
        FilteringProxy.apply(builder, source);
        final Process process = builder.redirectErrorStream(false).start();
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
        return ImageBytes.read(bytes.toByteArray());
    }

    // FFmpeg で再生できるファイルかどうか
    public static boolean isPlayable(File file) {
        if (!Ffmpeg.isAvailable()) return hasMediaExtension(file.getName());
        try {
            // 選んだファイルが再生リストなら、中の URL も読みに行くので、再生と同じく通信の種類と行き先を確かめる
            final ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(Arrays.asList(
                    Ffmpeg.requireFfprobe().getPath(), "-v", "error",
                    "-protocol_whitelist", "file," + MediaPlayer.PROTOCOLS,
                    "-rw_timeout", MediaPlayer.NETWORK_TIMEOUT,
                    "-show_entries", "stream=codec_type", "-of", "default=noprint_wrappers=1",
                    file.getAbsolutePath()
            )));
            FilteringProxy.apply(builder, file.getAbsolutePath());
            final Process process = builder.redirectErrorStream(true).start();
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
