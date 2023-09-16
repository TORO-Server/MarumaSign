package marumasa.marumasa_sign.util;

import com.google.common.io.BaseEncoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Queue;

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

    public static void load() {
        if (queue.size() == 0) return;
        String stringURL = queue.peek();
        getURL(stringURL);
        queue.remove();
    }

    public static byte[] getURLContent(String stringURL) throws IOException {

        String encodeURL = Utils.encodeURL(stringURL);

        if (encodeURL == null) return null;

        InputStream input = new URL(encodeURL).openStream();

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

            if (content == null) {
                ImageRegister.registerError(stringURL);
                return;
            }

            InputStream stream = new ByteArrayInputStream(content);

            if (Utils.isGif(content)) {
                ImageRegister.registerGif(stream, stringURL, path);
            } else {
                ImageRegister.registerDefault(stream, stringURL, path);
            }

        } catch (IOException e) {
            ImageRegister.registerError(stringURL);
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
