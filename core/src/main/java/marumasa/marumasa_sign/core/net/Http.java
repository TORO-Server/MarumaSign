package marumasa.marumasa_sign.core.net;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

    // URL の「#」から後ろ (フラグメント) を除く
    public static String withoutFragment(String url) {
        final int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    // URL の「#」から後ろ (フラグメント)。なければ空の文字列
    public static String fragment(String url) {
        final int hash = url.indexOf('#');
        return hash < 0 ? "" : url.substring(hash);
    }

    private static HttpURLConnection open(String url) throws IOException {
        return open(url, false);
    }

    /**
     * @param filtered 看板などに書かれた、誰でも書ける URL なら true。FilteringProxy を通し、
     *                 つなぐ直前に引いたアドレスを確かめて、そのアドレスにつなぐ (確かめた後に名前の行き先をすり替えられないように)
     */
    private static HttpURLConnection open(String url, boolean filtered) throws IOException {
        if (!isHttpUrl(url)) throw new IOException("Unsupported protocol scheme in URL: " + url);
        final String encoded;
        try {
            encoded = new URI(url).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + url);
        }
        final URL target = new URL(encoded);
        final HttpURLConnection connection = (HttpURLConnection) (filtered
                ? target.openConnection(FilteringProxy.proxy()) : target.openConnection());
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
        return readRest(input, new byte[0], -1, limit);
    }

    // 知らされた大きさ (Content-Length) を信じて、先に確保してよい大きさ。これより大きいものは、届いた量に合わせて広げる
    static final int TRUSTED_PREALLOCATION = 4 * 1024 * 1024;

    /**
     * head に続く残りを読み、head とつなげて返す。全体が limit バイトを超えたら IOException。
     * 大きさ (contentLength) が分かっていれば、その大きさの配列に直接読む (大きなファイルで、配列を何度も広げて写さないように)。
     * ただし先に確保するのは TRUSTED_PREALLOCATION までにし、その先は届いた量に合わせて (最大で contentLength まで) 広げる。
     * Content-Length はサーバーが自由に書けるので、そのまま確保すると、大きさだけを大きく書いて何も送らないサーバーの
     * いくつもの看板で、1 つあたり上限 (スプラットなら 200 MB) ずつメモリを確保させられていた。
     */
    private static byte[] readRest(InputStream input, byte[] head, long contentLength, int limit) throws IOException {
        if (contentLength >= head.length && contentLength <= limit) {
            byte[] all = new byte[(int) Math.min(contentLength, Math.max(TRUSTED_PREALLOCATION, head.length))];
            System.arraycopy(head, 0, all, 0, head.length);
            int filled = head.length;
            while (filled < contentLength) {
                if (filled == all.length) all = java.util.Arrays.copyOf(all, (int) Math.min(contentLength, 2L * all.length));
                final int read = input.read(all, filled, all.length - filled);
                // 知らされた大きさより前に接続が切れた。途中までのものを全体として扱うと、
                // 壊れたファイルを表示し、残しておく仕組み (DownloadCache) にも残して、次からも使ってしまう
                if (read < 0) throw new IOException("Connection closed after " + filled + " of " + contentLength + " bytes");
                filled += read;
            }
            // 知らされた大きさより長く送られてきたら、大きさが分からない場合と同じく、上限までは読む
            final int next = input.read();
            if (next < 0) return all;
            final ByteArrayOutputStream output = new ByteArrayOutputStream(all.length + 65536);
            output.write(all);
            output.write(next);
            return copyRest(input, output, limit);
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(65536, head.length));
        output.write(head);
        return copyRest(input, output, limit);
    }

    private static byte[] copyRest(InputStream input, ByteArrayOutputStream output, int limit) throws IOException {
        if (output.size() > limit) throw new IOException("Content size exceeds the limit of " + limit + " bytes.");
        final byte[] buffer = new byte[65536];
        int len;
        while ((len = input.read(buffer)) != -1) {
            if ((long) output.size() + len > limit) throw new IOException("Content size exceeds the limit of " + limit + " bytes.");
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
        return download(url, check, MAX_SIZE, 0L);
    }

    /**
     * deadline (System.nanoTime の値) までに読み終わらなければ IOException。
     */
    public static byte[] downloadBefore(String url, ContentCheck check, long deadline) throws IOException {
        return download(url, check, MAX_SIZE, deadline);
    }

    private static byte[] download(String url, ContentCheck check, int defaultMaxSize) throws IOException {
        return download(url, check, defaultMaxSize, 0L);
    }

    // 応答が返ってくるまで (リダイレクトを含む) の時間の上限
    private static final long RESPONSE_NANOS = 60L * 1000000000L;
    // 読み始めてからこの時間が過ぎても、この速さ (バイト/秒) より遅ければ諦める。
    // 1 バイトずつゆっくり送って、読み込みの枠をいつまでも埋めておくようなサーバーに付き合わないため
    private static final long SLOW_GRACE_NANOS = 30L * 1000000000L;
    private static final long MIN_BYTES_PER_SECOND = 16 * 1024;

    // 時間切れの接続を切る
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "MarumaSign-HttpWatchdog");
            thread.setDaemon(true);
            return thread;
        }
    });

    // 1 回のダウンロードの時間の見張り。期限 (until) を過ぎたら接続を切り、止まっている読み込みを終わらせる
    private static final class Guard implements Runnable {
        volatile HttpURLConnection connection;
        volatile long until;
        volatile boolean expired;

        @Override
        public void run() {
            final HttpURLConnection current = connection;
            if (current != null && System.nanoTime() - until > 0) {
                expired = true;
                current.disconnect();
            }
        }
    }

    // 読んだ量と時間を見て、遅すぎるときや期限を過ぎたときに止める
    private static final class PacedInputStream extends FilterInputStream {
        private final long start = System.nanoTime();
        private final long deadline;
        private long total;

        PacedInputStream(InputStream input, long deadline) {
            super(input);
            this.deadline = deadline;
        }

        private void count(long read) throws IOException {
            if (read > 0) total += read;
            final long now = System.nanoTime();
            if (deadline != 0 && now - deadline > 0) throw new IOException("Download timed out");
            final long elapsed = now - start;
            if (elapsed > SLOW_GRACE_NANOS && total * 1000000000L / elapsed < MIN_BYTES_PER_SECOND) {
                throw new IOException("Download is too slow");
            }
        }

        @Override
        public int read() throws IOException {
            final int value = super.read();
            count(value < 0 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final int read = super.read(buffer, offset, length);
            count(read);
            return read;
        }
    }

    /**
     * 手元や家の中のネットワークを指す URL は読まない (UrlPolicy)。リダイレクトの行き先も、つなぐ前に確かめる。
     */
    private static byte[] download(String url, ContentCheck check, int defaultMaxSize, long deadline) throws IOException {
        // 「#」から後ろ (PDF の「#page=3」など) は、読み込んだ後の表示のための指定で、サーバーには送られない。
        // 除いておき、指定だけが違う URL でも同じものとしてダウンロードし、残しておく (DownloadCache)
        url = withoutFragment(url);
        final Guard guard = new Guard();
        final long now = System.nanoTime();
        guard.until = deadline != 0 && deadline - now < RESPONSE_NANOS ? deadline : now + RESPONSE_NANOS;
        final ScheduledFuture<?> watch = WATCHDOG.scheduleWithFixedDelay(guard, 1, 1, TimeUnit.SECONDS);
        try {
            return download(url, check, defaultMaxSize, deadline, guard, true);
        } catch (IOException e) {
            if (guard.expired) throw new IOException("Download timed out", e);
            throw e;
        } finally {
            watch.cancel(false);
        }
    }

    /**
     * @param useCache 残しておいたもの (DownloadCache) を使ってよいか。false なら、問い合わせずに本体を受け取る
     */
    private static byte[] download(String url, ContentCheck check, int defaultMaxSize, long deadline, Guard guard,
                                   boolean useCache) throws IOException {
        // 看板の URL は誰でも書けるので、手元や家の中のネットワークは読まない (UrlPolicy)
        if (!UrlPolicy.isAllowed(url)) throw new IOException("Blocked URL: " + url);
        // 前にダウンロードして残しておいたもの (DownloadCache)。あれば、変わったかだけを問い合わせる
        final DownloadCache.Entry cached = useCache ? DownloadCache.lookup(url) : null;
        String currentUrl = url;
        HttpURLConnection connection = null;
        // 最後まで読めた接続は切らずに残し、同じサーバーへの次のダウンロードで使い回させる (接続と TLS の準備を省く)
        boolean reusable = false;
        try {
            for (int redirects = 0; ; redirects++) {
                final int status;
                if (redirects > 0 && !UrlPolicy.isAllowed(currentUrl)) throw new IOException("Blocked URL: " + currentUrl);
                try {
                    connection = open(currentUrl, true);
                    guard.connection = connection;
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Accept", "image/png, image/gif, image/jpeg, */*;q=0.5");
                    if (cached != null && cached.etag != null) connection.setRequestProperty("If-None-Match", cached.etag);
                    if (cached != null && cached.lastModified != null) {
                        connection.setRequestProperty("If-Modified-Since", cached.lastModified);
                    }
                    connection.connect();
                    status = connection.getResponseCode();
                } catch (IOException e) {
                    // サーバーにつながらない。残しておいたものがあれば、それを使う
                    if (cached != null) return fromCache(cached, check, defaultMaxSize);
                    throw e;
                }
                if (status == 304 && cached != null) {
                    // 変わっていない
                    try {
                        connection.getInputStream().close();
                        reusable = true;
                    } catch (IOException e) {
                        // 接続を使い回せないだけ
                    }
                    final byte[] kept;
                    try {
                        kept = cached.read();
                    } catch (IOException e) {
                        // 問い合わせている間に、残しておいたものが消されたか書き換えられた (ほかのダウンロードで上限を超えたときなど)。
                        // サーバーにはつながっているので、失敗にせず、残しておいたものを使わずに本体を受け取り直す
                        return download(url, check, defaultMaxSize, deadline, guard, false);
                    }
                    return checkCached(kept, cached.name, check, defaultMaxSize);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= 5) throw new IOException("Too many redirects");
                    final String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.isEmpty()) throw new IOException("HTTP " + status);
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    continue;
                }
                // サーバーの一時的な不具合なら、残しておいたものを使う (ファイルがなくなった 404 などでは使わない)
                if (status / 100 == 5 && cached != null) return fromCache(cached, check, defaultMaxSize);
                if (status / 100 != 2) throw new IOException("HTTP " + status);
                break;
            }

            // MJPEG の配信など、画像を送り続ける形式はファイルではないので読まない
            final String type = connection.getContentType();
            if (check != null && type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/x-mixed-replace")) {
                return null;
            }
            final String resolvedName = resolveName(connection, currentUrl, url);
            // 本体を読む間は、期限 (なければ遅すぎないか) だけを見る
            guard.until = deadline != 0 ? deadline : Long.MAX_VALUE;
            if (deadline == 0) guard.connection = null;
            final InputStream input = new PacedInputStream(connection.getInputStream(), deadline);
            final byte[] all;
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
                all = readRest(input, head, contentLength, maxSize);
                reusable = true;
            } finally {
                input.close();
            }
            DownloadCache.store(url, resolvedName, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"), all);
            return all;
        } finally {
            if (connection != null && !reusable) connection.disconnect();
        }
    }

    // 残しておいたものを、ダウンロードしたものと同じように確かめて返す
    private static byte[] fromCache(DownloadCache.Entry cached, ContentCheck check, int defaultMaxSize) throws IOException {
        return checkCached(cached.read(), cached.name, check, defaultMaxSize);
    }

    private static byte[] checkCached(byte[] content, String name, ContentCheck check, int defaultMaxSize) throws IOException {
        int maxSize = defaultMaxSize;
        if (check != null) {
            final byte[] head = java.util.Arrays.copyOf(content, Math.min(512, content.length));
            maxSize = check.maxBytes(head, head.length, name);
            if (maxSize <= 0) return null;
        }
        if (content.length > maxSize) throw new IOException("Content size exceeds the limit of " + maxSize + " bytes.");
        return content;
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
