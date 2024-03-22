package marumasa.marumasa_sign.util;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.MarumaSignClient;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class ImageRequest {
    private static final Queue<String> queue = new ArrayDeque<>();

    public static int queueSize() {
        return queue.size();
    }


    public static void add(
            // 画像のURL
            String stringURL
    ) {
        queue.add(stringURL);
    }

    record ImageLoader(String stringURL) implements Runnable {
        public void run() {
            getURL(stringURL);
        }
    }

    public static void load(int maxThreads) {

        // 読み込みスレッド作成
        final Thread[] threadList = new Thread[maxThreads];
        for (int i = 0; i < threadList.length; i++) {
            if (queue.size() == 0) break;
            String stringURL = queue.remove();
            threadList[i] = new Thread(new ImageLoader(stringURL));
            threadList[i].setName(String.format("ImageLoader-%d", i));
        }
        try {

            // 読み込み開始
            for (Thread thread : threadList)
                if (thread != null) thread.start();
                else break;

            // 読み込み終わるまで待機
            for (Thread thread : threadList)
                if (thread != null) thread.join();
                else break;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getURLContent(String stringURL) throws IOException {

        final String encodeURL = Utils.encodeURL(stringURL);

        if (encodeURL == null) return null;

        // 接続オブジェクトを生成
        final HttpURLConnection connection = (HttpURLConnection) new URL(encodeURL).openConnection();

        // ヘッダーを設定
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (MarumaSign)");// ヘッダを設定

        Map<String, List<String>> headerNames = connection.getHeaderFields();
        for (String k : headerNames.keySet()) {
            MarumaSign.LOGGER.info(k + ": " + headerNames.get(k));
        }

        // 接続を確立
        connection.connect();

        // InputStreamを取得
        final InputStream input = connection.getInputStream();
        // ByteArrayOutputStream 書き込み
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) != -1) {
            output.write(buffer, 0, len);
        }
        // InputStreamとByteArrayOutputStreamを閉じる
        input.close();
        output.close();

        // 接続を閉じる
        connection.disconnect();


        // byte配列に変換
        return output.toByteArray();
    }

    public static void getURL(String stringURL) {
        String path = URLtoPath(stringURL);


        try {
            final byte[] content = getURLContent(stringURL);

            if (content == null) {
                ImageRegister.registerError(stringURL);
                MarumaSign.LOGGER.warn("Failure: " + stringURL);
                return;
            }

            InputStream stream = new ByteArrayInputStream(content);

            if (Utils.isGif(content)) {
                ImageRegister.registerGif(stream, stringURL, path);
            } else {

                final Identifier identifier = new Identifier(MarumaSign.MOD_ID, path);

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
        // base32 に変換
        String base32 = BaseEncoding.base32().encode(stringURL.getBytes());
        // Identifier は 大文字使えないので すべて小文字にする
        base32 = base32.toLowerCase();
        // Identifier は イコール という文字が使えないので アンダーバー に置き換える
        base32 = base32.replace('=', '_');
        return base32;
    }
}
