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

    // 先頭のバイト列と、展開後の URL や Content-Disposition の名前を見て上限バイト数を決める (0 以下なら読まない)
    public interface ContentCheck {
        int maxBytes(byte[] head, int length, String name);
    }

    public static byte[] download(String url) throws IOException {
        return download(url, (HeadCheck) null);
    }

    /**
     * URL の内容をダウンロードする。
     * check が先頭のバイト列を受け付けなかった場合や、画像を送り続ける配信だった場合は、残りを読まずに null を返す。
     */
    public static byte[] download(String url, HeadCheck check) throws IOException {
        return download(url, check, MAX_SIZE);
    }

    // maxSize はダウンロードする大きさの上限 (バイト)
    public static byte[] download(String url, final HeadCheck check, final int maxSize) throws IOException {
        if (check == null) {
            return download(url, (ContentCheck) null, maxSize);
        }
        return download(url, new ContentCheck() {
            @Override
            public int maxBytes(byte[] head, int length, String name) {
                return check.accept(head, length) ? maxSize : 0;
            }
        });
    }

    public static byte[] download(String url, ContentCheck check) throws IOException {
        return download(url, check, MAX_SIZE);
    }

    private static byte[] download(String url, ContentCheck check, int defaultMaxSize) throws IOException {
        String currentUrl = url;
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; ; redirects++) {
                connection = open(currentUrl);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "image/png, image/gif, image/jpeg, */*;q=0.5");
                connection.connect();

                final int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= 5) throw new IOException("Too many redirects");
                    final String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.isEmpty()) throw new IOException("HTTP " + status);
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    continue;
                }
                if (status / 100 != 2) throw new IOException("HTTP " + status);
                break;
            }

            // MJPEG の配信など、画像を送り続ける形式はファイルではないので読まない
            final String type = connection.getContentType();
            if (check != null && type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/x-mixed-replace")) {
                return null;
            }
            final String resolvedName = resolveName(connection, currentUrl, url);
            final InputStream input = connection.getInputStream();
            try {
                byte[] head = new byte[0];
                int maxSize = defaultMaxSize;
                if (check != null) {
                    head = readHead(input, 512);
                    maxSize = check.maxBytes(head, head.length, resolvedName);
                    if (maxSize <= 0) return null;
                }
                final long contentLength = connection.getContentLengthLong();
                if (contentLength > maxSize) {
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
            if (connection != null) connection.disconnect();
        }
    }

    // Content-Disposition またはリダイレクト後の最終 URL からファイル名 (または URL) を得る
    private static String resolveName(HttpURLConnection connection, String currentUrl, String originalUrl) {
        final String disposition = connection.getHeaderField("Content-Disposition");
        if (disposition != null) {
            final String fromHeader = parseDispositionFilename(disposition);
            if (fromHeader != null && fromHeader.indexOf('.') >= 0) return fromHeader;
        }
        final URL actual = connection.getURL();
        final String finalUrl = actual != null ? actual.toString() : currentUrl;
        if (finalUrl != null && finalUrl.indexOf('.') >= 0) return finalUrl;
        return originalUrl;
    }

    private static String parseDispositionFilename(String header) {
        final String lower = header.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("filename*=");
        if (idx >= 0) {
            String value = header.substring(idx + 10).trim();
            final int semi = value.indexOf(';');
            if (semi >= 0) value = value.substring(0, semi).trim();
            final int quote = value.indexOf("''");
            if (quote >= 0) value = value.substring(quote + 2);
            value = stripQuotes(value);
            try {
                return java.net.URLDecoder.decode(value, "UTF-8");
            } catch (Exception ignored) {
                return value;
            }
        }
        idx = lower.indexOf("filename=");
        if (idx >= 0) {
            String value = header.substring(idx + 9).trim();
            final int semi = value.indexOf(';');
            if (semi >= 0) value = value.substring(0, semi).trim();
            return stripQuotes(value);
        }
        return null;
    }

    private static String stripQuotes(String text) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
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
