package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class Utils {

    private static final Minecraft client = Minecraft.getInstance();

    public static boolean isGif(byte[] bytes) {
        if (bytes == null || bytes.length < 6) return false;
        byte[] header = Arrays.copyOf(bytes, 6);
        String s = new String(header);
        return s.equals("GIF89a");
    }

    public static boolean isWebp(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    public static boolean isAnimatedWebp(byte[] bytes) {
        if (!isWebp(bytes)) return false;
        int pos = 12;
        while (pos < bytes.length - 8) {
            String type = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            int size = (bytes[pos + 4] & 0xFF) | ((bytes[pos + 5] & 0xFF) << 8)
                     | ((bytes[pos + 6] & 0xFF) << 16) | ((bytes[pos + 7] & 0xFF) << 24);
            if (type.equals("VP8X")) {
                if (pos + 8 < bytes.length) {
                    int flags = bytes[pos + 8] & 0xFF;
                    return (flags & 0x02) != 0;
                }
            }
            pos += 8 + size;
            if (size % 2 != 0) {
                pos++;
            }
            if (size < 0) break;
        }
        return false;
    }

    public static boolean isApng(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        byte[] pngSig = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != pngSig[i]) return false;
        }
        int pos = 8;
        while (pos < bytes.length - 8) {
            int length = ((bytes[pos] & 0xFF) << 24) | ((bytes[pos + 1] & 0xFF) << 16)
                    | ((bytes[pos + 2] & 0xFF) << 8) | (bytes[pos + 3] & 0xFF);
            if (pos + 4 >= bytes.length) break;
            String type = new String(bytes, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL")) {
                return true;
            }
            pos += 8 + length + 4;
            if (length < 0) break; // prevent infinite loop
        }
        return false;
    }

    public static void registerTexture(Identifier id, int width, int height, int[] pixels) throws IOException {
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = pixels[x + y * width];
                image.setPixel(x, y, argb);
            }
        }
        registerTexture(id, image);
    }

    public static RenderType getRenderLayer(Identifier identifier) {
        return RenderTypes.entityTranslucent(identifier);
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

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MarumaSign.MOD_ID, "key_category"));

    public static void registerTexture(Identifier id, NativeImage image) {
        // テクスチャ 登録
        client.getTextureManager().register(id, new DynamicTexture(() -> "marumasasign_texture", image));
    }

    public static void destroyTexture(Identifier id) {
        // テクスチャ 削除
        client.getTextureManager().release(id);
    }

    // キーバインド 作成
    public static KeyMapping createKeyBinding(String name, int code) {
        return new KeyMapping(
                // ID作成
                "key." + MarumaSign.MOD_ID + "." + name,

                // どのキーか設定
                InputConstants.Type.KEYSYM, code,

                // カテゴリ設定
                CATEGORY
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
        String sanitizedFilename = filename == null ? "file.png" : filename.replace("\"", "").replace("\r", "").replace("\n", "");

        // POSTリクエストのボディを作成する
        String boundary = Long.toHexString(System.currentTimeMillis()); // ランダムなバウンダリーを生成
        String CRLF = "\r\n"; // 改行

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
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
            writer.append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"").append(sanitizedFilename).append("\"").append(CRLF);
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
