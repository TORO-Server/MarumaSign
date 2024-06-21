package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Base64;

public class Utils {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    public static boolean isGif(byte[] bytes) {
        byte[] header = Arrays.copyOf(bytes, 6);
        String s = new String(header);
        return s.equals("GIF89a");
    }

    public static RenderLayer getRenderLayer(Identifier identifier) {
        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        return RenderLayer.getEntityTranslucent(identifier);
    }

    public static String encodeURL(String url) {
        try {
            URI uri = new URI(url);
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static void registerTexture(Identifier id, BufferedImage image) throws IOException {

        // BufferedImageをbyte配列に変換する
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", stream);
        byte[] bytes = stream.toByteArray();

        // テクスチャ 登録
        registerTexture(id, NativeImage.read(new ByteArrayInputStream(bytes)));
    }

    public static void registerTexture(Identifier id, NativeImage image) {
        // テクスチャ 登録
        client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
    }

    public static void destroyTexture(Identifier id) {
        // テクスチャ 削除
        client.getTextureManager().destroyTexture(id);
    }

    // キーバインド 作成
    public static KeyBinding createKeyBinding(String name, int code) {
        return new KeyBinding(
                // ID作成
                "key." + MarumaSign.MOD_ID + "." + name,

                // どのキーか設定
                InputUtil.Type.KEYSYM, code,

                // カテゴリ設定
                "key.categories." + MarumaSign.MOD_ID
        );
    }

    public static InputStream toPNG(InputStream inputStream) {
        try {
            // InputStream を BufferedImage に読み込む
            final BufferedImage bufferedImage = ImageIO.read(inputStream);

            // BufferedImage を PNG 形式の OutputStream に書き込む
            final ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", pngOutputStream);
            // PNG 形式の OutputStream から ByteArrayInputStream を作成して return
            return new ByteArrayInputStream(pngOutputStream.toByteArray());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static final Base64.Decoder decoder = Base64.getDecoder();

    public static byte[] Base64Decoder(String base64) {
        return decoder.decode(base64);
    }

    public static String UploadFile(String url, byte[] fileBytes, String filename) throws IOException {

        // POSTリクエストのボディを作成する
        String boundary = Long.toHexString(System.currentTimeMillis()); // ランダムなバウンダリーを生成
        String CRLF = "\r\n"; // 改行

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (
                OutputStream output = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        ) {
            // ファイルデータの書き込み
            writer.append("--").append(boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"reqtype\"").append(CRLF);
            writer.append(CRLF).append("fileupload").append(CRLF);

            writer.append("--").append(boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"").append(filename).append("\"").append(CRLF);
            writer.append("Content-Type: image/png").append(CRLF);
            writer.append(CRLF).flush();

            output.write(fileBytes);
            output.flush();

            writer.append(CRLF);
            writer.append("--").append(boundary).append("--").append(CRLF);
        }

        // レスポンスの取得
        int responseCode = conn.getResponseCode();
        MarumaSign.LOGGER.info("Response Code: %d".formatted(responseCode));

        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        ) {
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            MarumaSign.LOGGER.info("Response Body: %s".formatted(response));
            return response.toString();
        }
    }
}
