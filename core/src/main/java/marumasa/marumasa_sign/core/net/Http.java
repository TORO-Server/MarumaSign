package marumasa.marumasa_sign.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 画像のダウンロード・アップロードと URL の短縮。
 */
public final class Http {

    // ダウンロード・アップロードするファイルの大きさの上限 (10MB)
    public static final int MAX_SIZE = 10 * 1024 * 1024;

    private static final String USER_AGENT = "Mozilla/5.0 (MarumaSign)";

    // 接続先。動作確認のときだけシステムプロパティで差し替える
    private static String uploadEndpoint() {
        return System.getProperty("marumasign.uploadUrl", "https://catbox.moe/user/api.php");
    }

    private static String shortenEndpoint() {
        return System.getProperty("marumasign.shortenUrl", "https://is.gd/create.php");
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

    public static byte[] download(String url) throws IOException {
        final HttpURLConnection connection = open(url);
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "image/png, image/gif, image/jpeg, */*;q=0.5");
            connection.connect();

            final int status = connection.getResponseCode();
            if (status / 100 != 2) throw new IOException("HTTP " + status);
            if (connection.getContentLength() > MAX_SIZE) {
                throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
            }
            final InputStream input = connection.getInputStream();
            try {
                return readLimited(input, MAX_SIZE);
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

    // 画像をアップロードして、その URL を返す
    public static String upload(byte[] fileBytes, String filename) throws IOException {
        if (fileBytes.length > MAX_SIZE) throw new IOException("File size exceeds the limit of " + MAX_SIZE + " bytes.");
        final String safeName = (filename == null ? "file.png" : filename)
                .replace("\"", "").replace("\r", "").replace("\n", "");
        final String boundary = "----MarumaSign" + Long.toHexString(System.nanoTime());
        final String crlf = "\r\n";

        final ByteArrayOutputStream body = new ByteArrayOutputStream(fileBytes.length + 512);
        body.write(("--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"reqtype\"" + crlf + crlf
                + "fileupload" + crlf
                + "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + safeName + "\"" + crlf
                + "Content-Type: application/octet-stream" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(fileBytes);
        body.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        final HttpURLConnection connection = open(uploadEndpoint());
        try {
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(body.size());
            final OutputStream output = connection.getOutputStream();
            try {
                body.writeTo(output);
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
        final HttpURLConnection connection = open(
                shortenEndpoint() + "?format=simple&url=" + URLEncoder.encode(url, "UTF-8"));
        try {
            connection.setRequestMethod("GET");
            final String result = readText(connection);
            if (!isHttpUrl(result)) throw new IOException("Unexpected response: " + result);
            return result;
        } finally {
            connection.disconnect();
        }
    }
}
