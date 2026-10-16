package marumasa.marumasa_sign.core.net;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 画像のダウンロード・アップロードと URL の短縮。
 */
public final class Http {

    // ダウンロード・アップロードするファイルの大きさの上限 (10MB)
    public static final int MAX_SIZE = 10 * 1024 * 1024;
    // アップロードできるファイルの大きさの上限 (アップロード先の上限と同じ 200MB)
    public static final long MAX_UPLOAD_SIZE = 200L * 1024 * 1024;

    private static final String USER_AGENT = "Mozilla/5.0 (MarumaSign)";

    // 接続先。動作確認のときだけシステムプロパティで差し替える
    private static String uploadEndpoint() {
        return System.getProperty("marumasign.uploadUrl", "https://catbox.moe/user/api.php");
    }

    // URL 短縮サービス。先頭から順に試し、最初に成功したものを使う。
    // どれも「URL を付けて GET すると、短縮した URL だけが本文で返る」形式
    private static final String[] SHORTENERS = {
            "https://is.gd/create.php?format=simple&url=",
            "https://v.gd/create.php?format=simple&url=",
            "https://tinyurl.com/api-create.php?url=",
            "https://da.gd/s?url=",
    };

    private static String[] shorteners() {
        final String override = System.getProperty("marumasign.shortenUrl");
        return override == null ? SHORTENERS : new String[]{override + "?format=simple&url="};
    }

    private Http() {
    }

    public static boolean isHttpUrl(String text) {
        return text != null && (text.startsWith("http://") || text.startsWith("https://"));
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (!isHttpUrl(url)) throw new IOException("Unsupported protocol scheme in URL: " + url);
        final String encoded;
        try {
            encoded = new URI(url).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + url);
        }
        final HttpURLConnection connection = (HttpURLConnection) new URL(encoded).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    // 先頭の最大 count バイトを読む
    private static byte[] readHead(InputStream input, int count) throws IOException {
        final byte[] buffer = new byte[count];
        int filled = 0;
        while (filled < count) {
            final int read = input.read(buffer, filled, count - filled);
            if (read < 0) break;
            filled += read;
        }
        return filled == count ? buffer : java.util.Arrays.copyOf(buffer, filled);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int total = 0;
        int len;
        while ((len = input.read(buffer)) != -1) {
            total += len;
            if (total > limit) throw new IOException("Content size exceeds the limit of " + limit + " bytes.");
            output.write(buffer, 0, len);
        }
        return output.toByteArray();
    }

    // ダウンロードの途中で、先頭のバイト列を見て続けるかどうかを決める
    public interface HeadCheck {
        boolean accept(byte[] head, int length);
    }

    public static byte[] download(String url) throws IOException {
        return download(url, null);
    }

    /**
     * URL の内容をダウンロードする。
     * check が先頭のバイト列を受け付けなかった場合や、画像を送り続ける配信だった場合は、残りを読まずに null を返す。
     */
    public static byte[] download(String url, HeadCheck check) throws IOException {
        return download(url, check, MAX_SIZE);
    }

    // maxSize はダウンロードする大きさの上限 (バイト)
    public static byte[] download(String url, HeadCheck check, int maxSize) throws IOException {
        final HttpURLConnection connection = open(url);
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "image/png, image/gif, image/jpeg, */*;q=0.5");
            connection.connect();

            final int status = connection.getResponseCode();
            if (status / 100 != 2) throw new IOException("HTTP " + status);
            // MJPEG の配信など、画像を送り続ける形式はファイルではないので読まない
            final String type = connection.getContentType();
            if (check != null && type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/x-mixed-replace")) {
                return null;
            }
            final InputStream input = connection.getInputStream();
            try {
                byte[] head = new byte[0];
                if (check != null) {
                    head = readHead(input, 16);
                    if (!check.accept(head, head.length)) return null;
                }
                if (connection.getContentLength() > maxSize) {
                    throw new IOException("Content size exceeds the limit of " + maxSize + " bytes.");
                }
                final byte[] rest = readLimited(input, maxSize - head.length);
                if (head.length == 0) return rest;
                final byte[] all = new byte[head.length + rest.length];
                System.arraycopy(head, 0, all, 0, head.length);
                System.arraycopy(rest, 0, all, head.length, rest.length);
                return all;
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(HttpURLConnection connection) throws IOException {
        final int status = connection.getResponseCode();
        final InputStream input = status / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) throw new IOException("HTTP " + status);
        final String body;
        try {
            body = new String(readLimited(input, 65536), StandardCharsets.UTF_8).trim();
        } finally {
            input.close();
        }
        if (status / 100 != 2) throw new IOException("HTTP " + status + ": " + body);
        return body;
    }

    // ファイルをアップロードして、その URL を返す。大きなファイルでもメモリに載せずに送る
    public static String upload(File file) throws IOException {
        final long size = file.length();
        if (size > MAX_UPLOAD_SIZE) throw new IOException("File size exceeds the limit of " + MAX_UPLOAD_SIZE + " bytes.");
        final String safeName = file.getName().replace("\"", "").replace("\r", "").replace("\n", "");
        final String boundary = "----MarumaSign" + Long.toHexString(System.nanoTime());
        final String crlf = "\r\n";

        final byte[] head = ("--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"reqtype\"" + crlf + crlf
                + "fileupload" + crlf
                + "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + safeName + "\"" + crlf
                + "Content-Type: application/octet-stream" + crlf + crlf).getBytes(StandardCharsets.UTF_8);
        final byte[] tail = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);

        final HttpURLConnection connection = open(uploadEndpoint());
        try {
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(head.length + size + tail.length);
            final OutputStream output = connection.getOutputStream();
            try {
                output.write(head);
                final InputStream input = new FileInputStream(file);
                try {
                    final byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                    }
                } finally {
                    input.close();
                }
                output.write(tail);
            } finally {
                output.close();
            }
            final String result = readText(connection);
            if (!isHttpUrl(result)) throw new IOException("Unexpected response: " + result);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    // URL を短縮する
    public static String shorten(String url) throws IOException {
        IOException failure = null;
        for (String service : shorteners()) {
            try {
                return shorten(service, url);
            } catch (IOException e) {
                // 次のサービスを試す
                failure = e;
            }
        }
        throw failure != null ? failure : new IOException("No URL shortener is available");
    }

    private static String shorten(String service, String url) throws IOException {
        final HttpURLConnection connection = open(service + URLEncoder.encode(url, "UTF-8"));
        try {
            connection.setRequestMethod("GET");
            final String result = readText(connection);
            // 改行や空白を含むものは URL ではなくエラーメッセージ
            if (!isHttpUrl(result) || result.length() > 64 || result.indexOf(' ') >= 0 || result.indexOf('\n') >= 0) {
                throw new IOException("Unexpected response: " + result);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }
}
