package marumasa.marumasa_sign.core.image;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * メモリ上のバイト列を、ImageIO で画像として読む。
 * ImageIO.read(InputStream) は、既定では読んだ内容を一時ファイルに書きながら読む (ImageIO.setUseCache)。
 * 内容はすでにメモリにあるので、ファイルを使わずに読む。ImageIO.setUseCache は Minecraft や他の Mod にも効くので変えない。
 */
public final class ImageBytes {

    private ImageBytes() {
    }

    // 画像として読む。読める形式でなければ null
    public static BufferedImage read(byte[] data) throws IOException {
        // 読める形式なら、ImageIO.read がストリームを閉じる。読めなかったときも、メモリだけなので閉じなくてよい
        return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(data)));
    }
}
