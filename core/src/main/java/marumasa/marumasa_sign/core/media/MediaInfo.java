package marumasa.marumasa_sign.core.media;

import marumasa.marumasa_sign.core.net.FilteringProxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 動画や音声の情報。ffprobe で調べる。
 */
final class MediaInfo {

    boolean hasVideo;
    boolean hasAudio;
    int width;
    int height;
    // 1 秒あたりのフレーム数。静止画なら 0
    double frameRate;
    // 長さ (秒)。分からなければ 0
    double duration;
    // 映像のフレーム数。分からなければ 0
    long frameCount;
    // 形式の名前 (FFmpeg での名前。hls, dash, flv, mpjpeg など)
    String formatName = "";

    // 1 枚だけの画像かどうか
    boolean isStillImage() {
        return hasVideo && !hasAudio && (frameCount == 1 || (frameRate <= 0 && duration <= 0));
    }

    // 終わりのないライブ配信かどうか (長さが分からず、画像でもない)
    boolean isLive() {
        return duration <= 0 && !isStillImage() && !isImageFormat();
    }

    // 時刻の情報を持たない、JPEG を並べただけの映像 (MJPEG) かどうか
    boolean isJpegSequence() {
        return formatName.equals("mpjpeg") || formatName.equals("jpeg_pipe") || formatName.equals("mjpeg");
    }

    private boolean isImageFormat() {
        // JPEG を並べた映像は画像の形式として読まれるが、画像ではない
        return !isJpegSequence() && (formatName.endsWith("_pipe") || formatName.equals("image2"));
    }

    private static double parseRate(String value) {
        try {
            final int slash = value.indexOf('/');
            if (slash < 0) return Double.parseDouble(value);
            final double denominator = Double.parseDouble(value.substring(slash + 1));
            return denominator == 0 ? 0 : Double.parseDouble(value.substring(0, slash)) / denominator;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseNumber(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @param owner この結果を使う再生。再生を止めたら ffprobe も止める (少しずつしか送らないサーバーで、いつまでも残らないように)
     */
    static MediaInfo probe(String url, MediaPlayer owner) throws IOException {
        // 結果を「項目=値」の形式で 1 行ずつ出力させる
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(Arrays.asList(
                Ffmpeg.requireFfprobe().getPath(),
                "-v", "error",
                "-protocol_whitelist", MediaPlayer.PROTOCOLS,
                "-rw_timeout", MediaPlayer.NETWORK_TIMEOUT,
                "-show_entries", "stream=codec_type,width,height,avg_frame_rate,r_frame_rate,nb_frames:format=duration,format_name",
                "-of", "default=noprint_wrappers=1",
                url
        )));
        FilteringProxy.apply(builder, url);
        // 情報を調べるだけなので、1 分で終わらなければ止める
        final Process process = Ffmpeg.start(builder.redirectErrorStream(false), 60);
        owner.track(process);
        process.getOutputStream().close();
        MediaPlayer.drain(process.getErrorStream(), null);

        final MediaInfo info = new MediaInfo();
        // いま読んでいるストリームの種類
        String type = "";
        boolean videoDone = false;
        final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                final int equals = line.indexOf('=');
                if (equals < 0) continue;
                final String key = line.substring(0, equals);
                final String value = line.substring(equals + 1).trim();
                if (key.equals("codec_type")) {
                    // 2 本目以降の映像は使わない
                    if (type.equals("video")) videoDone = true;
                    type = value;
                    if (value.equals("video")) info.hasVideo = true;
                    if (value.equals("audio")) info.hasAudio = true;
                } else if (key.equals("duration")) {
                    info.duration = parseNumber(value);
                } else if (key.equals("format_name")) {
                    info.formatName = value;
                } else if (type.equals("video") && !videoDone) {
                    if (key.equals("width")) info.width = (int) parseNumber(value);
                    else if (key.equals("height")) info.height = (int) parseNumber(value);
                    else if (key.equals("nb_frames")) info.frameCount = (long) parseNumber(value);
                    else if (key.equals("avg_frame_rate") && parseRate(value) > 0) info.frameRate = parseRate(value);
                    else if (key.equals("r_frame_rate") && info.frameRate <= 0) info.frameRate = parseRate(value);
                }
            }
        } finally {
            reader.close();
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (process.exitValue() != 0 || (!info.hasVideo && !info.hasAudio)) {
            throw new IOException("Not a playable media");
        }
        if (info.hasVideo && (info.width <= 0 || info.height <= 0)) {
            throw new IOException("Unknown video size");
        }
        return info;
    }
}
