package marumasa.marumasa_sign.core.splat;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * ガウシアンスプラッティングの見た目改善の比較画像を生成する。
 * 1. compare_crop.png: (a) 変更前, (b) 切り落としのみ, (c) 切り落とし + 新選び方
 * 2. compare_crop_factor.png: CROP_FACTOR = 3 / 4 / 6
 */
public final class CompareCrop {

    private static final int CELL_SIZE = 512;
    private static final double YAW = 35.0;
    private static final double PITCH = 20.0;

    private static final String[] SCENE_NAMES = {
            "hornedlizard.spz",
            "racoonfamily.spz",
            "train.splat",
            "nike.splat",
            "plush.splat"
    };

    private static final String[] SCENE_PATHS = {
            "repos/spz/samples/hornedlizard.spz",
            "repos/spz/samples/racoonfamily.spz",
            "train.splat",
            "nike.splat",
            "plush.splat"
    };

    public static void main(String[] args) throws Exception {
        final File dataDir = new File("D:/Dev/MarumaSign-wt/splat-data");
        final File outDir = new File(dataDir, "preview");
        outDir.mkdirs();

        System.out.println("Generating compare_crop.png...");
        generateCompareCrop(dataDir, new File(outDir, "compare_crop.png"));

        System.out.println("Generating compare_crop_factor.png...");
        generateCompareCropFactor(dataDir, new File(outDir, "compare_crop_factor.png"));

        System.out.println("Comparison image generation COMPLETE.");
    }

    private static void generateCompareCrop(File dataDir, File outFile) throws Exception {
        final int cols = 3;
        final int rows = SCENE_PATHS.length;
        final int headerH = 40;
        final BufferedImage canvas = new BufferedImage(cols * CELL_SIZE, rows * CELL_SIZE + headerH, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x222222));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        final String[] colHeaders = {
                "(a) Before (No crop, area^1.0)",
                "(b) Crop only (CF=4, area^1.0)",
                "(c) Crop + New selection (CF=4, area^0.5)"
        };

        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(Color.WHITE);
        for (int c = 0; c < cols; c++) {
            g.drawString(colHeaders[c], c * CELL_SIZE + 20, 26);
        }

        for (int r = 0; r < rows; r++) {
            final File file = new File(dataDir, SCENE_PATHS[r]);
            final byte[] bytes = Files.readAllBytes(file.toPath());
            System.out.println("Processing scene " + (r + 1) + "/" + rows + ": " + SCENE_NAMES[r]);

            for (int c = 0; c < cols; c++) {
                if (c == 0) {
                    SplatLoader.cropEnabled = false;
                    SplatLoader.cropFactor = 4.0f;
                    SplatLoader.areaExponent = 1.0f;
                } else if (c == 1) {
                    SplatLoader.cropEnabled = true;
                    SplatLoader.cropFactor = 4.0f;
                    SplatLoader.areaExponent = 1.0f;
                } else {
                    SplatLoader.cropEnabled = true;
                    SplatLoader.cropFactor = 4.0f;
                    SplatLoader.areaExponent = 0.5f;
                }

                final SplatCloud cloud = SplatLoader.load(bytes, file.getName());
                final SplatScene scene = new SplatScene(cloud);
                final int[] frame = SplatPreview.render(scene, YAW, PITCH, CELL_SIZE);

                final BufferedImage cell = new BufferedImage(CELL_SIZE, CELL_SIZE, BufferedImage.TYPE_INT_ARGB);
                cell.setRGB(0, 0, CELL_SIZE, CELL_SIZE, frame, 0, CELL_SIZE);
                g.drawImage(cell, c * CELL_SIZE, headerH + r * CELL_SIZE, null);

                // ラベル (シーン名 + 点数)
                final String label = SCENE_NAMES[r] + " (" + cloud.count + " splats)";
                drawTextWithOutline(g, label, c * CELL_SIZE + 10, headerH + r * CELL_SIZE + 25);
            }
        }
        g.dispose();

        ImageIO.write(canvas, "png", outFile);
        System.out.println("Saved: " + outFile.getAbsolutePath());
    }

    private static void generateCompareCropFactor(File dataDir, File outFile) throws Exception {
        final int cols = 3;
        final int rows = SCENE_PATHS.length;
        final int headerH = 40;
        final BufferedImage canvas = new BufferedImage(cols * CELL_SIZE, rows * CELL_SIZE + headerH, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x222222));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        final float[] factors = {3.0f, 4.0f, 6.0f};
        final String[] colHeaders = {
                "CROP_FACTOR = 3 (tight)",
                "CROP_FACTOR = 4 (default)",
                "CROP_FACTOR = 6 (wide)"
        };

        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(Color.WHITE);
        for (int c = 0; c < cols; c++) {
            g.drawString(colHeaders[c], c * CELL_SIZE + 20, 26);
        }

        for (int r = 0; r < rows; r++) {
            final File file = new File(dataDir, SCENE_PATHS[r]);
            final byte[] bytes = Files.readAllBytes(file.toPath());
            System.out.println("Processing scene factor " + (r + 1) + "/" + rows + ": " + SCENE_NAMES[r]);

            for (int c = 0; c < cols; c++) {
                SplatLoader.cropEnabled = true;
                SplatLoader.cropFactor = factors[c];
                SplatLoader.areaExponent = 0.5f;

                final SplatCloud cloud = SplatLoader.load(bytes, file.getName());
                final SplatScene scene = new SplatScene(cloud);
                final int[] frame = SplatPreview.render(scene, YAW, PITCH, CELL_SIZE);

                final BufferedImage cell = new BufferedImage(CELL_SIZE, CELL_SIZE, BufferedImage.TYPE_INT_ARGB);
                cell.setRGB(0, 0, CELL_SIZE, CELL_SIZE, frame, 0, CELL_SIZE);
                g.drawImage(cell, c * CELL_SIZE, headerH + r * CELL_SIZE, null);

                final String label = SCENE_NAMES[r] + " (CF=" + (int) factors[c] + ", N=" + cloud.count + ")";
                drawTextWithOutline(g, label, c * CELL_SIZE + 10, headerH + r * CELL_SIZE + 25);
            }
        }
        g.dispose();

        // 状態を初期値に戻す
        SplatLoader.cropFactor = SplatLoader.DEFAULT_CROP_FACTOR;
        SplatLoader.areaExponent = SplatLoader.DEFAULT_AREA_EXPONENT;

        ImageIO.write(canvas, "png", outFile);
        System.out.println("Saved: " + outFile.getAbsolutePath());
    }

    private static void drawTextWithOutline(Graphics2D g, String text, int x, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(Color.BLACK);
        g.drawString(text, x - 1, y - 1);
        g.drawString(text, x + 1, y - 1);
        g.drawString(text, x - 1, y + 1);
        g.drawString(text, x + 1, y + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }
}
