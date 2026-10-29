package marumasa.marumasa_sign.core.pdf;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.jpeg.JpegReader;
import marumasa.marumasa_sign.core.pdf.cos.Keyword;
import marumasa.marumasa_sign.core.pdf.cos.Lexer;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfString;
import marumasa.marumasa_sign.core.pdf.crypt.CryptCheck;
import marumasa.marumasa_sign.core.pdf.filter.CcittFax;
import marumasa.marumasa_sign.core.pdf.font.CMap;
import marumasa.marumasa_sign.core.pdf.font.CMaps;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PDF の読み込みと描画 (core/pdf) の試験。CI で、メモリとスタックを小さくして (-Xmx512m -Xss512k) 動かす。
 * <ul>
 * <li>部品の確かめ: AES の既知の答え、語の切り分け、CMap、CCITT、JPEG</li>
 * <li>試験用の PDF (src/test/resources/pdf): 暗号化の方式ごとに開けるか、パスワードの要るものを拒むか、描いた色が合うか</li>
 * <li>その場で作る PDF: 機能ごとに描けるか (想定外の例外を出さないか)、描いた画素が合うか</li>
 * <li>悪意のある PDF: 輪、爆弾、深い入れ子などで、止まらずに短い時間で終わるか</li>
 * </ul>
 * どれも試験の厳しい動作 (marumasign.pdfStrict) で動かし、受け止めている想定外の例外も失敗にする。
 */
public final class PdfCheck {

    private static final File RESOURCES = new File("src/test/resources/pdf");
    // 悪意のある PDF 1 つにかけてよい時間
    private static final long MALICIOUS_TIMEOUT_SECONDS = 40;

    private static int failures;

    private PdfCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("marumasign.pdfStrict", "true");
        if (!Strict.ENABLED) throw new AssertionError("Strict mode must be enabled before the PDF classes load");
        final long start = System.nanoTime();
        run("AES / RC4 / password hashing", CryptCheck::run);
        run("lexer", PdfCheck::lexer);
        run("CMap", PdfCheck::cmap);
        run("CCITT", PdfCheck::ccitt);
        run("JPEG", PdfCheck::jpeg);
        run("encrypted fixtures", PdfCheck::encrypted);
        run("mixed fixture", PdfCheck::mixed);
        run("synthetic samples", PdfCheck::synthetic);
        run("rendering", PdfCheck::rendering);
        run("malicious samples", PdfCheck::malicious);
        System.out.println("PdfCheck finished in " + (System.nanoTime() - start) / 1_000_000 + " ms, failures: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        try {
            check.run();
            System.out.println("OK   " + name);
        } catch (Throwable e) {
            failures++;
            System.out.println("FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // ---------- 部品 ----------

    private static void lexer() {
        final Lexer lexer = new Lexer("12 -3.5 .5 --7 +4 1e3 0.0.1 (a\\(b\\)\\101\\\nc) <48 65 6>/A#20B [ << >> true null R"
                .getBytes(StandardCharsets.ISO_8859_1));
        expect(lexer.next().equals(12), "integer");
        expect(((Number) lexer.next()).doubleValue() == -3.5, "real");
        expect(((Number) lexer.next()).doubleValue() == 0.5, "leading dot");
        expect(((Number) lexer.next()).intValue() == -7, "double minus");
        expect(((Number) lexer.next()).intValue() == 4, "plus");
        expect(((Number) lexer.next()).doubleValue() == 1000, "exponent");
        expect(((Number) lexer.next()).doubleValue() == 0.0, "two dots");
        expect(new String(((PdfString) lexer.next()).bytes, StandardCharsets.ISO_8859_1).equals("a(b)Ac"), "literal string escapes");
        expect(new String(((PdfString) lexer.next()).bytes, StandardCharsets.ISO_8859_1).equals("He`"), "hex string with odd digits");
        expect(((PdfName) lexer.next()).name.equals("A B"), "name escape");
        expect(lexer.next() == Keyword.ARRAY_START, "array start");
        expect(lexer.next() == Keyword.DICT_START, "dict start");
        expect(lexer.next() == Keyword.DICT_END, "dict end");
        expect(Boolean.TRUE.equals(lexer.next()), "true");
        expect(lexer.next() == Lexer.NULL, "null");
        expect(lexer.next() instanceof Keyword, "keyword");
        expect(lexer.next() == null, "end");
        // どんな入力でも必ず終わる
        final Random random = new Random(1);
        for (int i = 0; i < 2000; i++) {
            final byte[] data = new byte[random.nextInt(200)];
            random.nextBytes(data);
            final Lexer l = new Lexer(data);
            int count = 0;
            while (l.next() != null) expect(++count <= data.length + 1, "lexer does not advance");
        }
    }

    private static void cmap() {
        final CMap cmap = CMaps.parse(("1 begincodespacerange <00> <80> <8140> <9ffc> endcodespacerange\n"
                + "2 begincidrange <8140> <817e> 633 <20> <7e> 1 endcidrange 1 begincidchar <8150> 9999 endcidchar")
                .getBytes(StandardCharsets.ISO_8859_1), false);
        final int[] code = new int[2];
        cmap.readCode(new byte[]{(byte) 0x81, 0x41}, 0, code);
        expect(code[0] == 0x8141 && code[1] == 2, "two-byte code");
        expect(cmap.lookup(0x8141, 2) == 634, "cidrange");
        expect(cmap.lookup(0x8150, 2) == 9999, "later cidchar overrides cidrange");
        expect(cmap.lookup(0x8151, 2) == 633 + 0x11, "range continues after the override");
        expect(cmap.lookup(0x41, 1) == 0x41 - 0x20 + 1, "one-byte code");
        final CMap predefined = CMaps.predefined("90ms-RKSJ-H");
        expect(predefined != null && predefined.lookup(0x8140, 2) == 633, "predefined 90ms-RKSJ-H");
        final CMap ucs2 = CMaps.predefined("Adobe-Japan1-UCS2");
        expect(ucs2 != null && ucs2.lookup(843, 2) == 0x3042, "Adobe-Japan1-UCS2 (CID 843 is HIRAGANA LETTER A)");
        final CMap vertical = CMaps.predefined("UniJIS-UCS2-V");
        expect(vertical != null && vertical.isVertical() && vertical.lookup(0x3042, 2) == 843, "UniJIS-UCS2-V uses UniJIS-UCS2-H");
    }

    private static void ccitt() throws IOException {
        final BufferedImage expected = ImageIO.read(new File(RESOURCES, "ccitt/img0.png"));
        final int width = expected.getWidth(), height = expected.getHeight();
        for (String[] variant : new String[][]{{"img0-group4.bin", "-1", "false"}, {"img0-group3.bin", "0", "false"}, {"img0-g32dfill.bin", "4", "true"}}) {
            final byte[] data = Files.readAllBytes(new File(RESOURCES, "ccitt/" + variant[0]).toPath());
            final PdfDict parameters = new PdfDict(value -> value);
            parameters.put("K", Integer.parseInt(variant[1]));
            parameters.put("Columns", width);
            parameters.put("Rows", height);
            parameters.put("EncodedByteAlign", Boolean.valueOf(variant[2]));
            final byte[] out = CcittFax.decode(data, parameters, width, height, 1 << 24);
            final int rowBytes = (width + 7) / 8;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final boolean black = ((out[y * rowBytes + x / 8] >> (7 - x % 8)) & 1) == 0;
                    // 試験の画像は、0 が黒の TIFF (BlackIsZero) から作ったので、PNG の白が符号の黒にあたる
                    final boolean white = (expected.getRGB(x, y) & 0xFFFFFF) != 0;
                    expect(black == white, variant[0] + " differs at " + x + "," + y);
                }
            }
        }
        // バイト境界にそろえた EOL のない G3 (1 次元): 行は白 29 (00000010) と黒 1 で、前の行の後ろに 0 の詰め物が 5 個ある。
        // 詰め物と白 29 の 0 が合わせて 11 個になるのを、EOL と取り違えてはいけない
        final PdfDict aligned = new PdfDict(value -> value);
        aligned.put("K", 0);
        aligned.put("Columns", 30);
        aligned.put("Rows", 4);
        aligned.put("EncodedByteAlign", Boolean.TRUE);
        final byte[] rows = CcittFax.decode(new byte[]{0x02, 0x40, 0x02, 0x40, 0x02, 0x40, 0x02, 0x40}, aligned, 30, 4, 1 << 20);
        expectWhite29Black1(rows, "byte-aligned G3");
        // EndOfLine なら、バイト境界にそろっていない EOL も EOL として読む (EncodedByteAlign と書いてあっても、そろえていない書き手)
        aligned.put("EndOfLine", Boolean.TRUE);
        expectWhite29Black1(CcittFax.decode(new byte[]{0x00, 0x10, 0x24, 0x00, 0x20, 0x48, 0x00, 0x40, (byte) 0x90, 0x00, (byte) 0x81, 0x20},
                aligned, 30, 4, 1 << 20), "G3 with unaligned EOLs");
    }

    // 30 x 4 の画像の各行が、白 29 と黒 1 か
    private static void expectWhite29Black1(byte[] rows, String what) {
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 30; x++) {
                final boolean black = ((rows[y * 4 + x / 8] >> (7 - x % 8)) & 1) == 0;
                expect(black == (x == 29), what + " row " + y + " differs at " + x);
            }
        }
    }

    private static void jpeg() throws IOException {
        final Random random = new Random(3);
        final BufferedImage image = new BufferedImage(97, 61, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (x * 2 << 16) | (y * 4 << 8) | ((x + y) & 0xFF) ^ (random.nextInt(16)));
            }
        }
        for (boolean progressive : new boolean[]{false, true}) {
            final byte[] data = encode(image, progressive);
            final JpegReader.Image mine = JpegReader.decode(data, 1, 1 << 20, -1);
            final BufferedImage reference = ImageIO.read(new java.io.ByteArrayInputStream(data));
            int max = 0;
            for (int y = 0; y < mine.height; y++) {
                for (int x = 0; x < mine.width; x++) {
                    final int rgb = reference.getRGB(x, y);
                    for (int c = 0; c < 3; c++) {
                        final int a = mine.samples[(y * mine.width + x) * 3 + c] & 0xFF;
                        final int b = (rgb >> (16 - 8 * c)) & 0xFF;
                        max = Math.max(max, Math.abs(a - b));
                    }
                }
            }
            expect(max <= 6, (progressive ? "progressive" : "baseline") + " JPEG differs from ImageIO by " + max);
            // 縮小して読む
            final JpegReader.Image small = JpegReader.decode(data, 8, 1 << 20, -1);
            expect(small.width == 13 && small.height == 8, "scaled JPEG size " + small.width + "x" + small.height);
            // 壊したデータでは IOException だけ
            for (int i = 0; i < 300; i++) {
                final byte[] broken = data.clone();
                for (int k = 0; k < 4; k++) broken[random.nextInt(broken.length)] = (byte) random.nextInt(256);
                try {
                    JpegReader.decode(broken, 1 + random.nextInt(2), 1 << 20, -1);
                } catch (IOException e) {
                    // 想定どおり
                }
            }
        }
    }

    private static byte[] encode(BufferedImage image, boolean progressive) throws IOException {
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            if (progressive) param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // ---------- 試験用の PDF ----------

    private static void encrypted() throws IOException {
        for (String name : new String[]{"small.pdf", "small-RC4-40.pdf", "small-RC4-128.pdf", "small-AES-128.pdf", "small-AES-256-R5.pdf", "small-AES-256.pdf"}) {
            final PdfFile pdf = PdfFile.open(new File(RESOURCES, name));
            final DecodedImage image = pdf.render(0, 200);
            pdf.close();
            // 左下の赤い四角 (10, 10, 80, 40 ポイント) と、文字の黒
            expect(image.width == 200 && image.height == 100, name + ": size " + image.width + "x" + image.height);
            expect(close(image.frames[0][70 * 200 + 50], 0xFFFF0000), name + ": red square is " + Integer.toHexString(image.frames[0][70 * 200 + 50]));
            boolean dark = false;
            for (int y = 40; y < 70; y++) {
                for (int x = 100; x < 190; x++) dark |= (image.frames[0][y * 200 + x] & 0xFF) < 64;
            }
            expect(dark, name + ": text is not drawn");
        }
        try {
            PdfFile.open(new File(RESOURCES, "small-password.pdf"));
            throw new AssertionError("a PDF that needs a password must be refused");
        } catch (IOException e) {
            // 想定どおり
        }
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 24) return false;
        }
        return true;
    }

    private static void mixed() throws IOException {
        final PdfFile pdf = PdfFile.open(new File(RESOURCES, "mixed.pdf"));
        expect(pdf.pageCount() == 2, "page count");
        final DecodedImage first = pdf.render(0, 400);
        final DecodedImage second = pdf.render(1, 400);
        pdf.close();
        expect(first.width == 400 && first.height == 300, "first page size");
        // 2 ページ目は 300 x 400 のページを 90 度回したもの
        expect(second.width == 400 && second.height == 300, "rotated page size " + second.width + "x" + second.height);
    }

    private static void synthetic() throws Exception {
        for (String[] sample : PdfSamples.synthetic()) {
            final PdfFile pdf = PdfFile.open(sample[1].getBytes(StandardCharsets.ISO_8859_1));
            final DecodedImage image = pdf.render(0, 200);
            pdf.close();
            expect(image.width == 200 && image.height == 200, sample[0] + ": size");
            if (sample[0].equals("annotations-and-oc")) {
                // 表示しない層の赤は描かれず、注釈の枠は描かれる
                expect(!close(image.frames[0][100 * 200 + 100], 0xFFFF0000), "hidden optional content was drawn");
                // 外観の枠 (BBox 10 x 10 を Rect 100 x 100 に広げたもの) の左の辺は x = 60
                expect(close(image.frames[0][100 * 200 + 60], 0xFF0000FF), "annotation appearance was not drawn: "
                        + Integer.toHexString(image.frames[0][100 * 200 + 60]));
            }
            if (sample[0].equals("text-and-paths")) {
                expect(close(image.frames[0][185 * 200 + 15], 0xFFFF0000), "red rectangle");
            }
        }
    }

    // ---------- 描いた画素 ----------

    private static void rendering() throws IOException {
        final StringBuilder problems = new StringBuilder();
        for (String[] sample : PdfSamples.rendering()) {
            final PdfFile pdf = PdfFile.open(sample[1].getBytes(StandardCharsets.ISO_8859_1));
            final DecodedImage image = pdf.render(0, 200);
            pdf.close();
            try {
                checkRendering(sample[0], image);
            } catch (AssertionError e) {
                problems.append(sample[0]).append(": ").append(e.getMessage()).append("; ");
            }
        }
        expect(problems.length() == 0, problems.toString());
    }

    // 画素 (x, y) の色 (RGB)
    private static int pixel(DecodedImage image, int x, int y) {
        return image.frames[0][y * image.width + x] & 0xFFFFFF;
    }

    // 灰色の明るさ (0 から 255、RGB の平均)
    private static int gray(DecodedImage image, int x, int y) {
        final int p = pixel(image, x, y);
        return (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3;
    }

    private static void expectColor(DecodedImage image, int x, int y, int rgb, String what) {
        expect(close(pixel(image, x, y), rgb), what + " at " + x + "," + y + " is " + Integer.toHexString(pixel(image, x, y)));
    }

    private static void checkRendering(String name, DecodedImage image) {
        expect(image.width == 200 && image.height == 200, "size " + image.width + "x" + image.height);
        switch (name) {
            case "thin-lines":
                // 太さ 0.1 の横線 (画素の y = 49.7) は、y = 50 の 1 画素の濃い線
                expectColor(image, 100, 50, 0x0000FF, "thin horizontal line");
                expect(gray(image, 100, 49) > 230 && gray(image, 100, 51) > 230, "thin horizontal line is not one pixel wide");
                // 太さ 0 の縦線 (x = 100.3) は、x = 100 の 1 画素
                expectColor(image, 100, 130, 0x0000FF, "zero-width vertical line");
                // 高さ 0.3 の長方形 (画素の y = 149.5 から 149.8) は、y = 150 の 1 画素
                expectColor(image, 60, 150, 0xFF0000, "thin rectangle");
                break;
            case "stroke-adjust":
                // 太さ 3 の線 (画素の y = 98.1 から 101.1) は、y = 98 から 100 の 3 画素にそろう
                expect(gray(image, 100, 98) < 8 && gray(image, 100, 100) < 8, "wide line edge is blurred: " + gray(image, 100, 98));
                expect(gray(image, 100, 97) > 247 && gray(image, 100, 101) > 247, "wide line is not adjusted: " + gray(image, 100, 101));
                break;
            case "anisotropic-stroke":
                // 線の太さは縦に 0.2 ポイント: 線の 5 ポイント上は白い
                expect(gray(image, 100, 95) > 247, "line width is not transformed per axis: " + gray(image, 100, 95));
                expect(gray(image, 100, 100) < 250 || gray(image, 100, 99) < 250, "thin line is not drawn");
                break;
            case "pattern-hairline": {
                // パターンの横線 (ページの y = 50) は細い: 4 画素離れたところは白い
                expect(gray(image, 100, 146) > 247 && gray(image, 100, 154) > 247, "pattern line is too wide: " + gray(image, 100, 146));
                boolean drawn = false;
                for (int y = 147; y <= 153; y++) drawn |= (pixel(image, 100, y) & 0xFF) > 128 && gray(image, 100, y) < 200;
                expect(drawn, "pattern line is not drawn");
                break;
            }
            case "glyph-baseline":
                // 基準線 (画素の y = 99.5) は y = 99 に切り下げる: I の縦棒の下の端 (y = 98) は濃く、y = 99 は白い
                expect(gray(image, 25, 98) < 30, "glyph bottom is blurred: " + gray(image, 25, 98));
                expect(gray(image, 25, 99) > 230, "glyph is not snapped to the pixel grid: " + gray(image, 25, 99));
                break;
            case "nbspace-width": {
                // 5 つの nbspace (幅 278) の後の I は、x = 80 より右
                expect(gray(image, 16, 90) > 230, "nbspace has no width");
                boolean found = false;
                for (int x = 80; x < 95; x++) found |= gray(image, x, 90) < 64;
                expect(found, "I after the spaces is missing");
                break;
            }
            case "missing-glyph":
            case "type1-notdef-order":
                for (int y = 50; y < 150; y += 2) {
                    for (int x = 40; x < 160; x += 2) expect(gray(image, x, y) > 200, "notdef glyph is drawn at " + x + "," + y);
                }
                break;
            case "transfer-function":
                expectColor(image, 35, 165, 0x00FFFF, "inverted red");
                expectColor(image, 95, 165, 0x00FFFF, "inverted shading");
                expectColor(image, 155, 165, 0x00FFFF, "inverted pattern");
                break;
            case "link-border":
                // 太さ 2 の赤い枠の左の辺 (x = 100)
                expectColor(image, 100, 80, 0xFF0000, "link border");
                expect(gray(image, 140, 80) > 247, "link is filled");
                break;
            case "annotation-defaults":
                expectColor(image, 50, 50, 0x0000FF, "square interior");
                expectColor(image, 11, 50, 0xFF0000, "square border");
                expectColor(image, 150, 30, 0xFFFF00, "highlight");
                expectColor(image, 180, 30, 0x00FF00, "highlight multiplied with cyan");
                expectColor(image, 50, 150, 0x0000FF, "ink");
                expectColor(image, 150, 120, 0xFF0000, "strike out");
                expect(gray(image, 110, 170) > 247 && gray(image, 150, 150) > 247, "link without a border is framed");
                break;
            case "type3-uncolored-stroke":
                // グリフの四角の左の辺 (x = 60) は、塗りの色 (赤) で描く
                expectColor(image, 60, 100, 0xFF0000, "uncolored Type 3 stroke");
                break;
            case "standard-glyph-order":
            case "standard-glyph-order-cidtogidmap":
            case "identity-tounicode-name": {
                // CID 16 はハイフン: 字の高さの真ん中あたりに、途切れない横棒がある (四角の .notdef なら、辺は縦だけ)
                boolean bar = false;
                for (int y = 86; y <= 100; y++) {
                    boolean row = true;
                    for (int x = 58; x <= 74; x++) row &= gray(image, x, y) < 100;
                    bar |= row;
                }
                expect(bar, "hyphen is not drawn");
                break;
            }
            case "standard-glyph-order-tounicode": {
                // I の縦棒は、ハイフンの高さより上 (画素の y = 60) まで伸びる
                boolean stem = false;
                for (int x = 52; x <= 75; x++) stem |= gray(image, x, 60) < 64;
                expect(stem, "ToUnicode is not preferred to the standard glyph order");
                break;
            }
            case "type0-fallback": {
                // l の下の端 (画素の y = 158) のセリフは、縦棒 (13 画素ほど) より広い
                int dark = 0;
                for (int x = 30; x < 140; x++) dark += gray(image, x, 158) < 128 ? 1 : 0;
                expect(dark > 25, "fallback for a serif CID font has no serif: " + dark);
                break;
            }
            case "image-snap":
                // 画像 (x = 20.6 から 70.4、画素の y = 119.1 から 169.4) は、x = 20 から 70、y = 119 から 169 の画素を塗る
                for (int x : new int[]{20, 70}) expect(gray(image, x, 140) < 8, "image edge is not snapped at x = " + x + ": " + gray(image, x, 140));
                for (int y : new int[]{119, 169}) expect(gray(image, 40, y) < 8, "image edge is not snapped at y = " + y + ": " + gray(image, 40, y));
                expect(gray(image, 19, 140) > 247 && gray(image, 71, 140) > 247 && gray(image, 40, 118) > 247 && gray(image, 40, 170) > 247,
                        "image is too large");
                // 高さ 0.3 の画像 (画素の y = 189.1 から 189.4) は、y = 189 の 1 画素
                expect(gray(image, 40, 189) < 8, "thin image is lost: " + gray(image, 40, 189));
                break;
            case "page-group-backdrop":
                expectColor(image, 50, 150, 0xFF0000, "difference over nothing");
                expectColor(image, 120, 150, 0xFF00FF, "difference over blue");
                break;
            case "knockout-nonisolated":
            case "knockout-isolated":
                expectColor(image, 45, 130, 0xFF0000, "red");
                // 半透明の青は、赤に重ならない (紫ではなく、白の上の薄い青)
                expectColor(image, 95, 130, 0x8080FF, "translucent blue over red");
                // 不透明度 0 の長方形の中は、初めの背景 (白と緑) に戻る
                expectColor(image, 160, 130, 0xFFFFFF, "knocked out area over white");
                expectColor(image, 160, 170, 0x00FF00, "knocked out area over green");
                break;
            case "shading-pattern-bbox":
                expectColor(image, 100, 100, 0xFF0000, "shading");
                expectColor(image, 20, 100, 0x0000FF, "shading background");
                expectColor(image, 190, 100, 0xFFFFFF, "shading outside the BBox");
                expectColor(image, 100, 10, 0xFFFFFF, "shading outside the BBox");
                break;
            case "shading-sh-bbox":
                expectColor(image, 100, 100, 0xFF0000, "shading");
                expectColor(image, 20, 100, 0xFFFFFF, "background of sh");
                expectColor(image, 190, 100, 0xFFFFFF, "shading outside the BBox");
                break;
            case "type1-bad-length1":
            case "type1-two-subrs":
                // a は四角 (ページの 50 から 150)
                expect(gray(image, 100, 100) < 30, "Type 1 font with a wrong Length1 is not drawn");
                break;
            case "flate-bad-checksum":
                expectColor(image, 100, 100, 0xFF0000, "content with a bad Adler-32");
                break;
            case "fallback-weight": {
                // I の縦棒の太さ (画素の y = 120): 普通の太さなら 9 画素ほど、太字なら 14 画素ほど
                int dark = 0;
                for (int x = 40; x < 100; x++) dark += gray(image, x, 120) < 128 ? 1 : 0;
                expect(dark >= 12, "fallback font is not bold: " + dark);
                break;
            }
            case "missing-width":
                // 2 つの a は、同じ所 (ページの x = 50 から 100) に重なる
                expect(gray(image, 75, 125) < 30, "glyph is not drawn");
                expect(gray(image, 125, 125) > 230, "width outside Widths is not MissingWidth");
                break;
            case "truetype-broken-head": {
                // 代わりのフォントの a (ページの x = 50 から 105、y = 50 から 102 のあたり) が描かれる
                boolean drawn = false;
                for (int y = 100; y < 150; y += 2) {
                    for (int x = 52; x < 104; x += 2) drawn |= gray(image, x, y) < 64;
                }
                expect(drawn, "broken embedded font is used instead of a substitute");
                break;
            }
            case "notdef-space":
                // 2 つ目の I は、I と空白の幅 (0.278 + 0.278) の先 (縦棒は x = 115 から 124 のあたり)
                expect(gray(image, 119, 120) < 64 && gray(image, 91, 120) > 230, "code 32 mapped to .notdef has no space width");
                break;
            case "cff-cidtogidmap":
                // CID 1 は CIDToGIDMap で四角のグリフ 2 (ページの 50 から 150)
                expect(gray(image, 100, 100) < 30, "CIDToGIDMap of an OpenType CFF font is ignored");
                break;
            case "bare-cff-cid":
            case "cff-in-fontfile":
                // CID 2 は、そのまま四角のグリフ 2
                expect(gray(image, 100, 100) < 30, "CID of a bare CFF font is not used as GID");
                break;
            case "truetype-lsb":
            case "truetype-collection":
            case "truetype-tounicode-name":
                // 四角は原点 (x = 50) から 40 ポイントの幅
                expect(gray(image, 60, 130) < 30, "left side bearing is ignored");
                expect(gray(image, 130, 130) > 230, "glyph is drawn at xMin");
                break;
            case "png-predictor-rgb4":
                // 2 バイト前を足せば、左は赤、右は青 (1 バイト前を足すと、左が赤紫になる)
                expectColor(image, 50, 100, 0xFF0000, "left pixel of the PNG-predicted 4-bit RGB image");
                expectColor(image, 150, 100, 0x0000FF, "right pixel of the PNG-predicted 4-bit RGB image");
                break;
            case "ccitt-g4-eol":
                expectColor(image, 150, 25, 0x000000, "black run of the first G4 row after an EOL");
                expectColor(image, 150, 75, 0x000000, "black run of the second G4 row after an EOL");
                expectColor(image, 50, 25, 0xFFFFFF, "white run of the first G4 row");
                expectColor(image, 150, 125, 0xFFFFFF, "white third G4 row");
                break;
            case "ccitt-rows-short":
                expectColor(image, 150, 75, 0x000000, "black run of the second G4 row");
                expectColor(image, 150, 125, 0xFFFFFF, "row after Rows of a CCITT image");
                expectColor(image, 150, 175, 0xFFFFFF, "last row of a CCITT image with fewer Rows");
                break;
            case "short-image-data":
                expectColor(image, 50, 75, 0x808080, "rows of an image with data");
                expectColor(image, 50, 125, 0xFFFFFF, "rows of an image without data");
                break;
            case "inline-image-latin1":
                expectColor(image, 50, 50, 0xFF0000, "inline image");
                expectColor(image, 150, 150, 0x0000FF, "rectangle after the inline image");
                break;
            case "incremental-object-stream":
                expectColor(image, 100, 100, 0x0000FF, "page updated in an object stream (an older revision is drawn)");
                break;
            case "hidden-layer-after-repair":
                expectColor(image, 50, 100, 0xFFFFFF, "hidden optional content");
                expectColor(image, 150, 100, 0xFFFFFF, "hidden optional content after the cross-reference table is rebuilt");
                break;
            case "zero-media-box":
                expectColor(image, 50, 150, 0xFF0000, "red square at the bottom left");
                expectColor(image, 150, 50, 0xFFFFFF, "page with MediaBox [0 0 0 0] is magnified");
                break;
            case "rotate-not-multiple-of-90":
                expectColor(image, 50, 175, 0xFF0000, "page with /Rotate 135 is rotated");
                break;
            default:
                throw new AssertionError("no check for " + name);
        }
    }

    private static void malicious() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "PdfCheck");
            thread.setDaemon(true);
            return thread;
        });
        final StringBuilder problems = new StringBuilder();
        for (String[] sample : PdfSamples.malicious()) {
            final byte[] data = sample[1].getBytes(StandardCharsets.ISO_8859_1);
            final long start = System.nanoTime();
            final Future<?> future = executor.submit(() -> {
                try {
                    final PdfFile pdf = PdfFile.open(data);
                    try {
                        pdf.render(0, 300);
                    } catch (IOException e) {
                        if (e.getCause() instanceof StackOverflowError) throw new AssertionError("stack overflow", e);
                    }
                    pdf.close();
                } catch (IOException e) {
                    if (e.getCause() instanceof StackOverflowError) throw new AssertionError("stack overflow", e);
                }
                return null;
            });
            try {
                future.get(MALICIOUS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                System.out.println("     " + sample[0] + ": " + (System.nanoTime() - start) / 1_000_000 + " ms");
            } catch (TimeoutException e) {
                future.cancel(true);
                problems.append(sample[0]).append(": timeout; ");
            } catch (ExecutionException e) {
                problems.append(sample[0]).append(": ").append(e.getCause()).append("; ");
                e.getCause().printStackTrace(System.out);
            }
        }
        executor.shutdownNow();
        expect(problems.length() == 0, problems.toString());
    }
}
