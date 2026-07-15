package marumasa.marumasa_sign.image;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.util.Utils;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class ImageRequest {
    private static final Queue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger activeDownloads = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool(
        r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("MarumaSign-ImageLoader");
            return thread;
        }
    );

    public static int queueSize() {
        return queue.size();
    }

    public static void add(
            // 画像のURL
            String stringURL
    ) {
        if (!queue.contains(stringURL)) {
            queue.add(stringURL);
        }
    }

    public static void load(int maxThreads) {
        while (activeDownloads.get() < maxThreads && !queue.isEmpty()) {
            String stringURL = queue.poll();
            if (stringURL == null) break;
            activeDownloads.incrementAndGet();
            executor.submit(() -> {
                try {
                    getURL(stringURL);
                } finally {
                    activeDownloads.decrementAndGet();
                }
            });
        }
    }

    public static byte[] getURLContent(String stringURL) throws IOException {
        if (stringURL == null) return null;
        if (!stringURL.startsWith("http://") && !stringURL.startsWith("https://")) {
            throw new IOException("Unsupported protocol scheme in URL: " + stringURL);
        }

        // ダウンロードサイズの上限を10MBに設定 (10 * 1024 * 1024 bytes)
        final long MAX_SIZE = 1024 * 1024 * 10;

        final String encodeURL = Utils.encodeURL(stringURL);
        if (encodeURL == null) return null;

        HttpURLConnection connection = null;
        try {
            // 接続オブジェクトを生成
            connection = (HttpURLConnection) new URL(encodeURL).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            // ヘッダーを設定
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (MarumaSign)");
            connection.setRequestProperty("Accept", "image/png, image/gif, image/jpeg");

            // 接続を確立
            connection.connect();

            final long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_SIZE) {
                throw new IOException("Content size (" + contentLength + " bytes) exceeds the limit of " + MAX_SIZE + " bytes.");
            }

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[8192];
                int len;
                long downloadedBytes = 0;

                while ((len = input.read(buffer)) != -1) {
                    downloadedBytes += len;
                    if (downloadedBytes > MAX_SIZE) {
                        throw new IOException("Content size exceeds the limit during download.");
                    }
                    output.write(buffer, 0, len);
                }
                // byte配列に変換して返す
                return output.toByteArray();
            }

        } finally {
            // 接続を閉じる
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static void getURL(String stringURL) {
        String path = URLtoPath(stringURL);

        // ログ出力
        MarumaSign.LOGGER.info("Start: " + stringURL);

        try {
            final byte[] content = getURLContent(stringURL);

            if (content == null) {
                ImageRegister.registerError(stringURL);
                MarumaSign.LOGGER.warn("Failure: " + stringURL);
                return;
            }

            InputStream stream = new ByteArrayInputStream(content);

            if (Utils.isGif(content) || Utils.isApng(content) || Utils.isAnimatedWebp(content)) {
                ImageRegister.registerAnimation(content, stringURL, path);
            } else {

                final Identifier identifier = Identifier.fromNamespaceAndPath(MarumaSign.MOD_ID, path);

                if (ImageRegister.registerDefault(stream, stringURL, identifier)) {
                    // ログ出力
                    MarumaSign.LOGGER.info("Load: " + stringURL + " : " + identifier);
                } else {
                    // エラーログ出力
                    ImageRegister.registerError(stringURL);
                    MarumaSign.LOGGER.warn("is not image: " + stringURL);
                }
            }

        } catch (IOException e) {
            ImageRegister.registerError(stringURL);
            MarumaSign.LOGGER.warn("Failure: " + stringURL);
            // 原因を表示
            MarumaSign.LOGGER.error(e.getMessage(), e);
        }
    }

    // URLを Identifier で使える ID に変換
    public static String URLtoPath(String stringURL) {
        String path;
        if (stringURL.length() > 150) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(stringURL.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                path = "hash_" + BaseEncoding.base32().encode(hash).toLowerCase().replace('=', '_');
            } catch (java.security.NoSuchAlgorithmException e) {
                path = BaseEncoding.base32().encode(stringURL.getBytes()).toLowerCase().replace('=', '_');
            }
        } else {
            path = BaseEncoding.base32().encode(stringURL.getBytes()).toLowerCase().replace('=', '_');
        }
        return path;
    }
}
