package marumasa.marumasa_sign.core.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * 試験に使う PDF を、その場で作る。
 * 機能を確かめるもの (圧縮していないので、ファジングの種にも向く) と、悪意のあるもの (輪、爆弾、深い入れ子、巨大な数) がある。
 */
final class PdfSamples {

    private PdfSamples() {
    }

    /**
     * オブジェクトを並べて、相互参照表とトレーラーを付けた PDF にする。objects[i] は「i+1 0 obj」の中身。
     * 1 番はカタログ、2 番はページの木、3 番は 1 枚目のページとする決まりで書く。
     */
    static String build(String... objects) {
        final StringBuilder out = new StringBuilder("%PDF-1.7\n%âãÏÓ\n");
        final int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = out.length();
            out.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        final int xref = out.length();
        out.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) out.append(String.format("%010d 00000 n \n", offset));
        out.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return out.toString();
    }

    static String stream(String dict, String data) {
        return "<< " + dict + " /Length " + data.length() + " >>\nstream\n" + data + "\nendstream";
    }

    // 1 ページの PDF (4 番以降にリソースのオブジェクトを置ける)
    static String page(String resources, String content, String... more) {
        final String[] objects = new String[4 + more.length];
        objects[0] = "<< /Type /Catalog /Pages 2 0 R >>";
        objects[1] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>";
        objects[2] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources " + resources + " /Contents 4 0 R >>";
        objects[3] = stream("", content);
        System.arraycopy(more, 0, objects, 4, more.length);
        return build(objects);
    }

    static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    static byte[] deflate(byte[] data) {
        final Deflater deflater = new Deflater(9);
        deflater.setInput(data);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    /**
     * 機能を確かめる、圧縮していない PDF。{名前, 中身}。
     */
    static List<String[]> synthetic() {
        final List<String[]> samples = new ArrayList<>();
        samples.add(new String[]{"text-and-paths", page(
                "<< /Font << /F1 5 0 R /F2 6 0 R >> /ExtGState << /G1 7 0 R >> >>",
                "q 1 0 0 rg 10 10 80 80 re f Q q 0 0 1 RG 4 w 1 J 1 j [6 3] 0 d 20 150 m 180 150 l 100 190 l h S Q\n"
                        + "q /G1 gs 0 1 0 rg 50 50 100 100 re f Q\n"
                        + "BT /F1 18 Tf 20 120 Td (Hello PDF) Tj 0 -20 Td [(Ke) 120 (rning)] TJ /F2 12 Tf 2 Tr (Bold) Tj ET\n"
                        + "q 0.5 0 0 0.5 100 0 cm 0 0 0 1 k 0 0 m 50 100 l 100 0 l f* Q",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>",
                "<< /Type /ExtGState /ca 0.5 /CA 0.5 /BM /Multiply >>")});
        samples.add(new String[]{"shadings", page(
                "<< /Shading << /S1 5 0 R /S2 6 0 R /S3 7 0 R >> /Pattern << /P1 8 0 R >> >>",
                "q 0 0 100 100 re W n /S1 sh Q q 100 0 100 100 re W n /S2 sh Q q 0 100 100 100 re W n /S3 sh Q\n"
                        + "q /Pattern cs /P1 scn 100 100 100 100 re f Q",
                "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 100 0] /Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> /Extend [true true] >>",
                "<< /ShadingType 3 /ColorSpace /DeviceGray /Coords [150 50 0 150 50 50] /Function << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >> >>",
                stream("/ShadingType 4 /ColorSpace /DeviceRGB /BitsPerCoordinate 8 /BitsPerComponent 8 /BitsPerFlag 8 /Decode [0 255 0 255 0 1 0 1 0 1]",
                        latin1(new byte[]{0, 0, 100, (byte) 255, 0, 0, 0, 100, 100, 0, (byte) 255, 0, 0, 50, (byte) 200, 0, 0, (byte) 255})),
                "<< /PatternType 2 /Shading << /ShadingType 2 /ColorSpace /DeviceCMYK /Coords [100 100 200 200] "
                        + "/Function << /FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1 0 1] /Length 1 >> >> >>")});
        samples.add(new String[]{"tiling-and-type3", page(
                "<< /Pattern << /P1 5 0 R >> /Font << /T3 6 0 R >> /XObject << /X1 8 0 R >> >>",
                "q /Pattern cs /P1 scn 0 0 200 100 re f Q BT /T3 20 Tf 20 150 Td (abab) Tj ET q 100 0 0 50 50 110 cm /X1 Do Q",
                stream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 25 /YStep 25", "1 0 0 rg 0 0 10 10 re f 0 0 1 rg 10 10 10 10 re f"),
                "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /a 7 0 R /b 7 0 R >> "
                        + "/Encoding << /Differences [97 /a /b] >> /FirstChar 97 /LastChar 98 /Widths [1000 1000] >>",
                stream("", "1000 0 0 0 1000 1000 d1 0 0 1000 1000 re f"),
                stream("/Type /XObject /Subtype /Form /BBox [0 0 1 1] /Group << /S /Transparency >>", "0 1 1 rg 0 0 1 1 re f"))});
        samples.add(new String[]{"images", page(
                "<< /XObject << /I1 5 0 R /I2 6 0 R /M1 7 0 R >> /ColorSpace << /Ix [/Indexed /DeviceRGB 1 <ff000000ff00>] >> >>",
                "q 80 0 0 80 10 10 cm /I1 Do Q q 80 0 0 80 110 10 cm /I2 Do Q q 0 0 1 rg 80 0 0 80 10 110 cm /M1 Do Q\n"
                        + "q 80 0 0 80 110 110 cm BI /W 2 /H 2 /CS /Ix /BPC 8 ID \u0000\u0001\u0001\u0000 EI Q",
                stream("/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8",
                        latin1(new byte[]{(byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 0})),
                stream("/Type /XObject /Subtype /Image /Width 4 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 2 /Decode [1 0]",
                        latin1(new byte[]{(byte) 0x1B})),
                stream("/Type /XObject /Subtype /Image /Width 8 /Height 2 /ImageMask true", latin1(new byte[]{(byte) 0xAA, 0x55})))});
        samples.add(new String[]{"annotations-and-oc", build(
                "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [6 0 R] /D << /OFF [6 0 R] >> >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Properties << /OC1 6 0 R >> >> /Annots [5 0 R] >>",
                stream("", "/OC /OC1 BDC 1 0 0 rg 0 0 200 200 re f EMC 0 g 10 10 20 20 re f"),
                "<< /Type /Annot /Subtype /Square /Rect [50 50 150 150] /AP << /N 7 0 R >> >>",
                "<< /Type /OCG /Name (Hidden) >>",
                stream("/Type /XObject /Subtype /Form /BBox [0 0 10 10]", "0 0 1 RG 1 w 1 1 8 8 re S"))});
        return samples;
    }

    /**
     * 悪意のある PDF。{名前, 中身}。どれも、短い時間で読み終える (描き終える) か、IOException で拒むべきもの。
     */
    static List<String[]> malicious() {
        final List<String[]> samples = new ArrayList<>();
        // 深い入れ子の配列 (スタックの使い切り)
        final StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 200000; i++) nested.append('[');
        // フォントとして参照させて、読み込ませる
        samples.add(new String[]{"deep-arrays", page("<< /Font << /F 5 0 R >> >>", "BT /F 9 Tf (a) Tj ET " + nested.substring(0, 5000), nested.toString())});
        final StringBuilder dicts = new StringBuilder();
        for (int i = 0; i < 100000; i++) dicts.append("<< /A ");
        samples.add(new String[]{"deep-dicts", page("<< /Font << /F 5 0 R >> >>", "BT /F 9 Tf (a) Tj ET", dicts.toString())});
        // 自分自身を描くフォーム、互いに描き合うフォーム
        samples.add(new String[]{"recursive-form", page("<< /XObject << /X 5 0 R >> >>", "/X Do",
                stream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << /XObject << /X 5 0 R /Y 6 0 R >> >>", "/X Do /Y Do"),
                stream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << /XObject << /X 5 0 R >> >>", "/X Do /X Do"))});
        // 自分自身を使うパターン
        samples.add(new String[]{"recursive-pattern", page("<< /Pattern << /P 5 0 R >> >>", "/Pattern cs /P scn 0 0 200 200 re f",
                stream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << /Pattern << /P 5 0 R >> >>",
                        "/Pattern cs /P scn 0 0 10 10 re f"))});
        // 自分自身を描く Type 3 のグリフ
        samples.add(new String[]{"recursive-type3", page("<< /Font << /T 5 0 R >> >>", "BT /T 10 Tf (a) Tj ET",
                "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1 1] /FontMatrix [1 0 0 1 0 0] /CharProcs << /a 6 0 R >> "
                        + "/Encoding << /Differences [97 /a] >> /FirstChar 97 /LastChar 97 /Widths [1] /Resources << /Font << /T 5 0 R >> >> >>",
                stream("", "1 0 0 0 1 1 d1 BT /T 1 Tf (aaaa) Tj ET"))});
        // ページの木の輪
        samples.add(new String[]{"page-tree-cycle", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")});
        // /Length が自分自身を指すストリーム
        samples.add(new String[]{"self-length", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>",
                "<< /Length 4 0 R >>\nstream\n0 g 0 0 10 10 re f\nendstream")});
        // 展開すると巨大になるデータ (圧縮爆弾)
        final byte[] zeros = new byte[64 * 1024 * 1024];
        final String bomb = latin1(deflate(zeros));
        samples.add(new String[]{"flate-bomb", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents [4 0 R 4 0 R 4 0 R] >>",
                "<< /Filter /FlateDecode /Length " + bomb.length() + " >>\nstream\n" + bomb + "\nendstream")});
        // 何段にも重ねた Flate (1 段目の展開で上限を超える)
        final String twice = latin1(deflate(deflate(zeros)));
        samples.add(new String[]{"double-flate-bomb", page("<< >>", "0 g",
                "<< /Filter [/FlateDecode /FlateDecode] /Length " + twice.length() + " >>\nstream\n" + twice + "\nendstream")});
        // 巨大な画像の寸法
        samples.add(new String[]{"huge-image", page("<< /XObject << /I 5 0 R >> >>", "q 100 0 0 100 0 0 cm /I Do Q",
                stream("/Type /XObject /Subtype /Image /Width 2000000000 /Height 2000000000 /ColorSpace /DeviceRGB /BitsPerComponent 8", "abc"))});
        // 細かすぎる点線 (Java2D が膨大な図形を作る)
        samples.add(new String[]{"tiny-dashes", page("<< >>", "[0.00001 0.00001] 0 d 0 0 m 1000000 1000000 l 0 1000000 l h S")});
        // 巨大な点線の位相 (Java 8 の Java2D は、位相から区切りを 1 つずつ引くので終わらなくなる)
        samples.add(new String[]{"huge-dash-phase", page("<< >>", "[3 2 1] 1e30 d 10 10 m 500 400 l S [5] 99999999999 d 0 0 m 300 0 l S")});
        // 巨大な Size の関数
        samples.add(new String[]{"huge-sampled-function", page("<< /Shading << /S 5 0 R >> >>", "/S sh",
                "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 1 0] /Function 6 0 R >>",
                stream("/FunctionType 0 /Domain [0 1 0 1 0 1] /Range [0 1 0 1 0 1] /Size [65535 65535 65535] /BitsPerSample 8", "x"))});
        // 自分自身を含むつなぎ合わせの関数
        samples.add(new String[]{"recursive-function", page("<< /Shading << /S 5 0 R >> >>", "/S sh",
                "<< /ShadingType 2 /ColorSpace /DeviceGray /Coords [0 0 1 0] /Function 6 0 R >>",
                "<< /FunctionType 3 /Domain [0 1] /Functions [6 0 R 6 0 R] /Bounds [0.5] /Encode [0 1 0 1] >>")});
        // 命令が大量にある内容
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < 300000; i++) many.append("q 1 0 0 1 0 0 cm Q ");
        samples.add(new String[]{"many-operators", page("<< >>", many.toString())});
        // q の深い積み重ね
        final StringBuilder saves = new StringBuilder();
        for (int i = 0; i < 100000; i++) saves.append("q ");
        samples.add(new String[]{"deep-q", page("<< >>", saves + "0 0 10 10 re f")});
        // 相互参照表の /Prev の輪
        final String cyclic = page("<< >>", "0 g");
        final int xrefAt = cyclic.indexOf("xref\n");
        samples.add(new String[]{"xref-prev-cycle", cyclic.replace("/Root 1 0 R >>", "/Root 1 0 R /Prev " + xrefAt + " >>")});
        // 巨大な数を並べた相互参照表
        samples.add(new String[]{"xref-huge-count", cyclic.replaceFirst("xref\n0 \\d+", "xref\n0 2000000000")});
        // CCITT の行の爆弾 (高さだけ巨大)
        samples.add(new String[]{"ccitt-rows", page("<< /XObject << /I 5 0 R >> >>", "q 100 0 0 100 0 0 cm /I Do Q",
                stream("/Type /XObject /Subtype /Image /Width 65536 /Height 65536 /ImageMask true /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 65536 >>",
                        "\u0080\u0080\u0080\u0080\u0080\u0080\u0080\u0080"))});
        // 巨大な範囲の CMap
        samples.add(new String[]{"huge-cmap-range", page("<< /Font << /F 5 0 R >> >>", "BT /F 10 Tf <0000ffff> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding 6 0 R /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /X "
                        + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /W [0 4294967295 500] >>] >>",
                stream("/Type /CMap", "1 begincodespacerange <00000000> <ffffffff> endcodespacerange 1 begincidrange <00000000> <ffffffff> 0 endcidrange"))});
        return samples;
    }
}
