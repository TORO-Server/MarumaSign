package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.media.Ffmpeg;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 自分では読めない静止画 (AVIF、HEIC、JPEG XL、JPEG 2000、EXR、DPX など) を、FFmpeg に PNG に直してもらって読む。
 * FFmpeg がなければ使えない。
 */
final class FfmpegImage {

    // 受け取る PNG の大きさの上限
    private static final int MAX_OUTPUT = 128 * 1024 * 1024;
    // FFmpeg の実行時間の上限 (秒)
    private static final long TIMEOUT_SECONDS = 12;
    // 標準エラー出力から読む長さの上限 (文字数)
    private static final int MAX_LOG = 20000;

    private FfmpegImage() {
    }

    static boolean isAvailable() {
        return Ffmpeg.isAvailable();
    }

    // 標準エラー出力を読み捨てる (読まないと FFmpeg が止まることがある)。先頭の部分は、失敗の理由や入力の情報として残す
    private static Thread drain(final InputStream stream, final StringBuilder into) {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        synchronized (into) {
                            if (into.length() < MAX_LOG) into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException e) {
                    // プロセスが終わったときの読み取りの失敗は無視する
                } finally {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // 閉じられなくても影響はない
                    }
                }
            }
        }, "MarumaSign-FfmpegImage");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * @param extension 内容だけでは見分けにくい形式のための、ファイルの拡張子 (「.dpx」のように。なければ null)
     */
    static DecodedImage decode(byte[] data, String extension) throws IOException {
        if (!Ffmpeg.isAvailable()) throw new IOException("FFmpeg was not found");
        // 画像の代わりに再生リストを渡されて、このパソコンの中のファイルを読まされないように (Ffmpeg.isPlaylist)
        if (Ffmpeg.isPlaylist(data)) throw new IOException("Not an image (a playlist)");
        final File directory = Files.createTempDirectory("marumasign_image_").toFile();
        final File input = new File(directory, "input" + (extension == null ? "" : extension));
        try {
            Files.write(input.toPath(), data);
            final StringBuilder log = new StringBuilder();
            byte[] png = run(input, false, log);
            // HEIC や AVIF の透明度は、別の映像として入っている。あれば、重ねてやり直す
            if (png.length > 0 && hasAlphaStream(log.toString())) {
                final byte[] withAlpha = run(input, true, new StringBuilder());
                if (withAlpha.length > 0) png = withAlpha;
            }
            if (png.length == 0) {
                MarumaSign.LOGGER.info("FFmpeg could not decode the image: " + lastLines(log));
                throw new IOException("FFmpeg could not decode the image");
            }
            final BufferedImage image = ImageBytes.read(png);
            if (image == null) throw new IOException("FFmpeg produced an unreadable image");
            FormatUtil.checkSize(image.getWidth(), image.getHeight());
            return FormatUtil.still(image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
    }

    // 2 つ目の映像がグレー (または名前が Alpha) なら、それが透明度
    private static boolean hasAlphaStream(String log) {
        return log.contains("title           : Alpha") || java.util.regex.Pattern.compile("Stream #0:1[^\\n]*Video:[^\\n]*gray").matcher(log).find();
    }

    private static String lastLines(StringBuilder log) {
        final String[] lines = log.toString().trim().split("\\R");
        final StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) result.append(lines[i]).append(' ');
        return result.toString().trim();
    }

    // 最初の絵を PNG にして返す。読めなければ空の配列
    private static byte[] run(File input, boolean alpha, StringBuilder log) throws IOException {
        final String scale = "scale='min(" + ImageLoader.MAX_STATIC_SIZE + ",iw)':'min(" + ImageLoader.MAX_STATIC_SIZE
                + ",ih)':force_original_aspect_ratio=decrease";
        final List<String> command = new ArrayList<String>(Arrays.asList(
                Ffmpeg.requireFfmpeg().getPath(),
                // 入力の情報 (透明度の映像があるか) を読むため、ログは info にする
                "-hide_banner", "-loglevel", alpha ? "error" : "info", "-nostdin",
                // 自分で書き出した一時ファイルだけを読ませる (ファイルの中から別の場所を読ませない)
                "-protocol_whitelist", "file",
                "-i", input.getAbsolutePath()));
        if (alpha) {
            command.addAll(Arrays.asList("-filter_complex", "[0:v:0][0:v:1]alphamerge," + scale + "[out]", "-map", "[out]"));
        } else {
            // 大きすぎる絵は、縦横比を保って縮める
            command.addAll(Arrays.asList("-map", "0:v:0", "-vf", scale));
        }
        command.addAll(Arrays.asList("-frames:v", "1", "-f", "image2pipe", "-c:v", "png", "pipe:1"));
        final Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        process.getOutputStream().close();
        final Thread logReader = drain(process.getErrorStream(), log);

        // 壊れたファイルで FFmpeg が終わらなくなることがあるので、出力は別のスレッドで読み、時間が来たら止める
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final boolean[] tooLarge = {false};
        final Thread outputReader = new Thread(new Runnable() {
            @Override
            public void run() {
                final InputStream stream = process.getInputStream();
                try {
                    final byte[] buffer = new byte[65536];
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        synchronized (bytes) {
                            bytes.write(buffer, 0, read);
                            if (bytes.size() > MAX_OUTPUT) {
                                tooLarge[0] = true;
                                process.destroyForcibly();
                                return;
                            }
                        }
                    }
                } catch (IOException e) {
                    // プロセスが止められたときの読み取りの失敗は無視する
                } finally {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // 閉じられなくても影響はない
                    }
                }
            }
        }, "MarumaSign-FfmpegImage-output");
        outputReader.setDaemon(true);
        outputReader.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("FFmpeg timed out");
            }
            // 出力とログを読み終えるのを待つ
            outputReader.join(5000);
            logReader.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        synchronized (bytes) {
            if (tooLarge[0]) throw new IOException("FFmpeg output is too large");
            return bytes.toByteArray();
        }
    }
}
