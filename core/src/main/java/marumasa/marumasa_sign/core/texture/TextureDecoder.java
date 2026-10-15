package marumasa.marumasa_sign.core.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 各種画像形式 (KTX2, WebP, DDS, AVIF, PNG, JPEG 等) のデコード窓口。
 */
public final class TextureDecoder {

    private static final int MAX_TEXTURE_SIZE = 8192;

    private TextureDecoder() {
    }

    /**
     * 画像バイト列をデコードして BufferedImage を返す。
     *
     * @param data     画像バイト列
     * @param mimeType MIME タイプ (null 可)
     * @return デコード結果。未対応形式なら null
     * @throws IOException 画像が壊れているか制限を超えている場合
     */
    public static BufferedImage decode(byte[] data, String mimeType) throws IOException {
        if (data == null || data.length == 0) return null;

        // 1. DDS
        if (DdsDecoder.isDds(data) || "image/vnd-ms.dds".equalsIgnoreCase(mimeType) || "image/dds".equalsIgnoreCase(mimeType)) {
            return DdsDecoder.decode(data);
        }

        // 2. KTX2
        if (Ktx2Decoder.isKtx2(data) || "image/ktx2".equalsIgnoreCase(mimeType)) {
            return Ktx2Decoder.decode(data);
        }


        // 3. WebP
        if (WebpDecoder.isWebp(data) || "image/webp".equalsIgnoreCase(mimeType)) {
            return WebpDecoder.decode(data);
        }


        // 4. AVIF
        if (AvifDecoder.isAvif(data) || "image/avif".equalsIgnoreCase(mimeType)) {
            final BufferedImage avif = AvifDecoder.decode(data);
            if (avif != null) return avif;
        }


        // 5. 標準形式 (PNG, JPEG 等)
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image != null) {
                if (image.getWidth() > MAX_TEXTURE_SIZE || image.getHeight() > MAX_TEXTURE_SIZE) {
                    throw new IOException("Image dimension exceeds limit: " + image.getWidth() + "x" + image.getHeight());
                }
                return image;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ImageIO failed: " + e, e);
        }

        return null;
    }
}
