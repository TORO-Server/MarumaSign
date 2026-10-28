package marumasa.marumasa_sign.core.image;

import marumasa.marumasa_sign.core.image.jpeg.JpegReader;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * メモリ上のバイト列を画像として読む。
 * JPEG は、ImageIO (C で書かれた libjpeg を使う) に渡さず、Java だけで書いた JpegReader で読む
 * (誰でも作れるファイルで、libjpeg の不具合を突いてメモリを壊されないように)。それ以外の形式は ImageIO で読む。
 * ImageIO.read(InputStream) は、既定では読んだ内容を一時ファイルに書きながら読む (ImageIO.setUseCache)。
 * 内容はすでにメモリにあるので、ファイルを使わずに読む。ImageIO.setUseCache は Minecraft や他の Mod にも効くので変えない。
 */
public final class ImageBytes {

    // JPEG の幅と高さの上限 (使う側の上限のうち一番大きいもの。3D モデルのテクスチャの 8192)。使う側は、さらに自分の上限で確かめる
    private static final int MAX_JPEG_SIDE = 8192;

    private ImageBytes() {
    }

    // 画像として読む。読める形式でなければ null
    public static BufferedImage read(byte[] data) throws IOException {
        if (isJpeg(data)) return readJpeg(data);
        // 読める形式なら、ImageIO.read がストリームを閉じる。読めなかったときも、メモリだけなので閉じなくてよい
        return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(data)));
    }

    public static boolean isJpeg(byte[] data) {
        return data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    // 先頭の大きさを確かめてから戻す (大きさを偽ったファイルでメモリ不足にならないように)
    private static BufferedImage readJpeg(byte[] data) throws IOException {
        final int max = MAX_JPEG_SIDE;
        final int[] size = JpegReader.size(data);
        if (size == null) throw new IOException("Not a JPEG image");
        if (size[0] <= 0 || size[1] <= 0 || size[0] > max || size[1] > max) {
            throw new IOException("Image exceeds safety limits (max " + max + "x" + max + "): " + size[0] + "x" + size[1]);
        }
        final JpegReader.Image jpeg = JpegReader.decode(data, 1, (long) max * max, -1);
        final BufferedImage image = new BufferedImage(jpeg.width, jpeg.height, BufferedImage.TYPE_INT_RGB);
        final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(JpegReader.toArgb(jpeg), 0, pixels, 0, pixels.length);
        return image;
    }
}
