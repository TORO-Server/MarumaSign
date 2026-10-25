package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.media.Ffmpeg;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * AVIF (EXT_texture_avif) 形式の画像を FFmpeg 経由でデコードする。
 * FFmpeg が利用できない場合は null を返し、コアの画像 (PNG/JPEG) へフォールバックさせる。
 */
public final class AvifDecoder {

    private AvifDecoder() {
    }

    /**
     * 先頭バイト列が AVIF (ISO BMFF ftyp avif/avis) 形式かどうか判定する。
     */
    public static boolean isAvif(byte[] data) {
        if (data == null || data.length < 16) return false;
        // 4〜7 バイト目が 'ftyp'
        if (data[4] != 'f' || data[5] != 't' || data[6] != 'y' || data[7] != 'p') {
            return false;
        }
        final int boxSize = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        final int limit = Math.min(data.length, (boxSize > 0) ? boxSize : data.length);
        for (int i = 8; i + 4 <= limit; i += 4) {
            final int brand = ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16)
                    | ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (brand == 0x61766966 || brand == 0x61766973) { // 'avif', 'avis'
                return true;
            }
        }
        return false;
    }

    /**
     * AVIF データをデコードする。
     *
     * @param data 画像バイト列
     * @return デコード結果。FFmpeg 不在または失敗時は null
     */
    public static BufferedImage decode(byte[] data) {
        // 画像の種類はモデルの中に書かれた mimeType だけで決まることもあるので、再生リストではないかも確かめる (Ffmpeg.isPlaylist)
        if (!Ffmpeg.isAvailable() || data == null || data.length == 0 || Ffmpeg.isPlaylist(data)) {
            return null;
        }

        File tempFile = null;
        try {
            // 本人だけが読める一時ファイルにする (同じパソコンのほかの利用者に中身を読まれないように)
            tempFile = java.nio.file.Files.createTempFile("ms_avif_", ".avif").toFile();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
            }

            final Process process = new ProcessBuilder(Arrays.asList(
                    Ffmpeg.requireFfmpeg().getPath(),
                    "-hide_banner", "-loglevel", "error", "-nostdin",
                    // 自分で書き出した一時ファイルだけを読ませる
                    "-protocol_whitelist", "file",
                    "-i", tempFile.getAbsolutePath(),
                    "-map", "0:v:0", "-frames:v", "1",
                    "-f", "image2pipe", "-c:v", "png", "pipe:1"
            )).redirectErrorStream(false).start();

            process.getOutputStream().close();
            final InputStream err = process.getErrorStream();
            final Thread errDrainer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final byte[] buf = new byte[1024];
                        while (err.read(buf) >= 0) {
                            // 読み捨て
                        }
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            err.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
            errDrainer.setDaemon(true);
            errDrainer.start();


            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final InputStream input = process.getInputStream();
            try {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > 64 * 1024 * 1024) break;
                }
            } finally {
                input.close();
            }

            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return null;
            }

            if (bytes.size() == 0) return null;
            return ImageBytes.read(bytes.toByteArray());
        } catch (Exception e) {
            return null;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }
}
