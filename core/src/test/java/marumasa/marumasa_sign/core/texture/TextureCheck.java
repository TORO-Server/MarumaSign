package marumasa.marumasa_sign.core.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * テクスチャファイルを読み込んで PNG として保存し、デコーダの動作を確認する。
 * 引数: [出力先ディレクトリ] [ファイル1] [ファイル2] ...
 */
public final class TextureCheck {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: TextureCheck <out-dir> <image-file...>");
            System.exit(1);
        }

        final File outDir = new File(args[0]);
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.err.println("Failed to create output directory: " + outDir);
            System.exit(1);
        }

        int successCount = 0;
        int failCount = 0;

        for (int i = 1; i < args.length; i++) {
            final File file = new File(args[i]);
            System.out.println("Testing: " + file.getName());
            try {
                final byte[] data = readFile(file);
                final BufferedImage img = TextureDecoder.decode(data, null);
                if (img == null) {
                    System.err.println("  Failed: decode returned null");
                    failCount++;
                    continue;
                }
                final String baseName = file.getName().replaceAll("\\.[^.]+$", "");
                final File outFile = new File(outDir, baseName + ".png");
                ImageIO.write(img, "png", outFile);
                System.out.println("  OK: " + img.getWidth() + "x" + img.getHeight() + " -> " + outFile.getName());
                successCount++;
            } catch (Exception e) {
                System.err.println("  Failed with exception: " + e.getMessage());
                e.printStackTrace(System.err);
                failCount++;
            }
        }

        System.out.println("Total: " + successCount + " succeeded, " + failCount + " failed");
        if (failCount > 0) {
            System.exit(1);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        final long len = file.length();
        if (len > 64 * 1024 * 1024) throw new IOException("File too large: " + file);
        final byte[] bytes = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                final int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return bytes;
    }
}
