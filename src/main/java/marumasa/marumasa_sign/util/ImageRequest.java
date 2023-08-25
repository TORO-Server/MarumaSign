package marumasa.marumasa_sign.util;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.TextureURLProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

public class ImageRequest extends Thread {
    private static final Queue<String> queue = new ArrayDeque<>();

    private static boolean isRunning = false;

    public static short waitTime = 100;


    public static void open(
            // 画像のURL
            String stringURL
    ) {
        queue.add(stringURL);
        if (!isRunning) new ImageRequest().start();
    }

    public ImageRequest() {
        this.setName("GetImage thread");
    }

    public void run() {
        isRunning = true;
        while (queue.size() != 0) {
            String stringURL = queue.remove();
            getURL(stringURL);
            Utils.sleep(waitTime);
        }
        isRunning = false;
    }

    public static byte[] getURLContent(String stringURL) throws IOException {

        InputStream input = new URL(
                URLEncoder.encode(stringURL, StandardCharsets.UTF_8)
        ).openStream();

        // ByteArrayOutputStream 書き込み
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) != -1) {
            output.write(buffer, 0, len);
        }
        // InputStreamとByteArrayOutputStreamを閉じる
        input.close();
        output.close();

        // byte配列に変換
        return output.toByteArray();
    }

    public static void getURL(String stringURL) {
        String path = URLtoPath(stringURL);


        try {
            final byte[] content = getURLContent(stringURL);

            InputStream stream = new ByteArrayInputStream(content);

            if (Utils.isGif(content)) {
                ImageRegister.registerGif(stream, stringURL, path);
            } else {
                ImageRegister.registerDefault(stream, stringURL, path);
            }

        } catch (IOException e) {
            // URL から 画像を読み込めなかったら

            TextureURLProvider.failureTextureURL(stringURL);

            MarumaSign.LOGGER.warn("Failure: " + stringURL);
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
