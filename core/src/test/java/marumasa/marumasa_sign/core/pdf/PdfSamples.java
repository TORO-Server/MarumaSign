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
        samples.addAll(rendering());
        return samples;
    }

    /**
     * 描いた結果の画素を確かめる PDF (PdfCheck の rendering)。{名前, 中身}。どれも 200 x 200 ポイントのページで、200 画素の大きさで描く。
     */
    static List<String[]> rendering() {
        final List<String[]> samples = new ArrayList<>();
        // 細い線: 太さ 0.1 の横線、太さ 0 の縦線、高さ 0.3 の長方形は、どれも画素に合わせた 1 画素の濃い線になる
        samples.add(new String[]{"thin-lines", page("<< >>",
                "0 0 1 RG 0.1 w 20 150.3 m 180 150.3 l S 0 w 100.3 20 m 100.3 120 l S 1 0 0 rg 20 50.2 160 0.3 re f")});
        // 太い線の線の調整: 縦と横の線は、輪郭が画素の境目に合う (端がぼやけない)
        samples.add(new String[]{"stroke-adjust", page("<< >>", "0 g 3 w 20 100.4 m 180 100.4 l S")});
        // 縦と横で拡大率の違う CTM: 線の太さもユーザー空間で変わる (縦に 0.1 倍なので、横線は細い)
        samples.add(new String[]{"anisotropic-stroke", page("<< >>", "q 10 0 0 0.1 0 100 cm 0 g 2 w 2 0 m 18 0 l S Q")});
        // 太さを決めないパターンの線は、いちばん細い線 (パターンの行列で 10 倍にしても太くならない)。
        // poppler は、間隔が BBox と同じパターンだけ別の描き方 (太さ 1) をするので、間隔を BBox より広くして比べる
        samples.add(new String[]{"pattern-hairline", page("<< /Pattern << /P1 5 0 R >> >>", "/Pattern cs /P1 scn 0 0 200 200 re f",
                stream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 20 /Matrix [10 0 0 10 0 0]",
                        "0 0 1 RG 0 5 m 10 5 l S"))});
        // 文字の基準線は画素の境目に合う: 基準線 y = 100.5 の I の下の端は、ぼやけない
        samples.add(new String[]{"glyph-baseline", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 40 Tf 20 100.5 Td (I) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")});
        // WinAnsi の 240 (nbspace) は空白の幅を持つ
        samples.add(new String[]{"nbspace-width", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 50 Tf 10 100 Td (\240\240\240\240\240I) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")});
        // 代わりのフォントにない文字 (名前のないコード 1) は、.notdef の四角を描かない
        samples.add(new String[]{"missing-glyph", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (\\001\\001) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")});
        // 転送関数 (TR) で色を反転する: 赤は水色になる (塗りの色、シェーディング、パターン)
        samples.add(new String[]{"transfer-function", page("<< /ExtGState << /T1 5 0 R >> /Shading << /S 6 0 R >> /Pattern << /P 7 0 R >> >>",
                "q /T1 gs 1 0 0 rg 10 10 50 50 re f Q q /T1 gs 70 10 50 50 re W n /S sh Q q /T1 gs /Pattern cs /P scn 130 10 50 50 re f Q",
                "<< /Type /ExtGState /TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >> >>",
                "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] /Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [1 0 0] /N 1 >> >>",
                stream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << >>", "1 0 0 rg 0 0 10 10 re f"))});
        // リンクの枠 (外観なし、Border と C)
        samples.add(new String[]{"link-border", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Annots [5 0 R] >>",
                stream("", "0 g"),
                "<< /Type /Annot /Subtype /Link /Rect [100 100 180 140] /Border [0 0 2] /C [1 0 0] >>")});
        // 外観 (AP) のない注釈は、決まった形で描く: 四角形 (IC で塗り、C の枠)、マーカー (Multiply)、手書きの線、取り消し線。
        // Border も BS もないリンクの枠は描かない
        samples.add(new String[]{"annotation-defaults", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Annots [5 0 R 6 0 R 7 0 R 8 0 R 9 0 R] >>",
                stream("", "0 1 1 rg 170 150 20 40 re f"),
                "<< /Type /Annot /Subtype /Square /Rect [10 110 90 190] /C [1 0 0] /IC [0 0 1] /Border [0 0 4] >>",
                "<< /Type /Annot /Subtype /Highlight /Rect [110 150 190 190] /QuadPoints [110 190 190 190 110 150 190 150] /C [1 1 0] >>",
                "<< /Type /Annot /Subtype /Ink /Rect [10 10 90 90] /InkList [[20 20 80 80]] /C [0 0 1] /BS << /W 3 >> >>",
                "<< /Type /Annot /Subtype /StrikeOut /Rect [110 70 190 90] /QuadPoints [110 90 190 90 110 70 190 70] /C [1 0 0] >>",
                "<< /Type /Annot /Subtype /Link /Rect [110 10 190 50] /C [0 1 0] >>")});
        // 色のない (d1) Type 3 のグリフの線は、塗りの色で描く
        samples.add(new String[]{"type3-uncolored-stroke", page("<< /Font << /T3 5 0 R >> >>",
                "1 0 0 rg 0 0 1 RG BT /T3 100 Tf 50 50 Td (a) Tj ET",
                "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /a 6 0 R >> "
                        + "/Encoding << /Differences [97 /a] >> /FirstChar 97 /LastChar 97 /Widths [1000] >>",
                stream("", "1000 0 0 0 1000 1000 d1 100 w 100 100 800 800 re S"))});
        // 埋め込まれていない Arial (Identity-H) の CID は、Windows の Arial のグリフの番号 (16 はハイフン)
        samples.add(new String[]{"standard-glyph-order", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 80 Td <0010> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /Arial /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Arial /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor << /Type /FontDescriptor /FontName /Arial /Flags 32 /FontBBox [-665 -325 2000 1006] /ItalicAngle 0 "
                        + "/Ascent 905 /Descent -212 /CapHeight 716 /StemV 80 >> /W [16 [333]] >>")});
        // ToUnicode があれば、グリフの並びより優先する (pdf.js と同じ): CID 16 (並びではハイフン) を I にする
        samples.add(new String[]{"standard-glyph-order-tounicode", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 80 Td <0010> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /Arial /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Arial /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor << /Type /FontDescriptor /FontName /Arial /Flags 32 /FontBBox [-665 -325 2000 1006] /ItalicAngle 0 "
                        + "/Ascent 905 /Descent -212 /CapHeight 716 /StemV 80 >> /W [16 [278]] >>",
                stream("", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /U def "
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <0010> <0049> endbfchar "
                        + "endcmap CMapName currentdict /CMap defineresource pop end end"))});
        // 埋め込まれていない、知らない名前の CID フォントは、性質 (ここではセリフ) の近い標準のフォントで描く: l の下にセリフがある
        samples.add(new String[]{"type0-fallback", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 150 Tf 60 40 Td <0001> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /FooBook /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /FooBook /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor << /Type /FontDescriptor /FontName /FooBook /Flags 34 /FontBBox [0 -200 1000 900] /ItalicAngle 0 "
                        + "/Ascent 900 /Descent -200 /CapHeight 700 /StemV 80 >> /DW 500 >>",
                stream("", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /U def "
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <0001> <006C> endbfchar "
                        + "endcmap CMapName currentdict /CMap defineresource pop end end"))});
        // 傾いていない画像は、掛かる画素の外側の境目まで広げる: 端の画素も塗り、画素の中心に掛からない細い画像も消えない
        samples.add(new String[]{"image-snap", page("<< /XObject << /Im 5 0 R >> >>",
                "q 49.8 0 0 50.3 20.6 30.6 cm /Im Do Q q 49.8 0 0 0.3 20.6 10.6 cm /Im Do Q",
                stream("/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8", latin1(new byte[]{0})))});
        // ページは分離したグループ (背景は透明): 何もないところの Difference は元の色 (白と混ぜた水色ではない)、青の上では混ざる
        samples.add(new String[]{"page-group-backdrop", page("<< /ExtGState << /D 5 0 R >> >>",
                "0 0 1 rg 100 10 80 80 re f q /D gs 1 0 0 rg 20 20 120 50 re f Q",
                "<< /Type /ExtGState /BM /Difference >>")});
        // ノックアウトのグループ (分離していないものと、分離したもの): 中のものは前のものに重ならず、グループの初めの背景に重ねて置き換える。
        // 不透明度 0 のものは、形の中を初めの背景に戻す
        for (String isolated : new String[]{"false", "true"}) {
            samples.add(new String[]{"knockout-" + ("true".equals(isolated) ? "isolated" : "nonisolated"), page(
                    "<< /XObject << /G 5 0 R >> >>", "0 1 0 rg 0 0 200 40 re f /G Do",
                    stream("/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /K true /I " + isolated + " >> "
                                    + "/Resources << /ExtGState << /A50 << /ca 0.5 >> /A0 << /ca 0 >> >> >>",
                            "1 0 0 rg 20 10 100 110 re f /A50 gs 0 0 1 rg 70 20 100 100 re f /A0 gs 140 0 40 200 re f"))});
        }
        // シェーディングのパターンの BBox (クリップ) と Background (シェーディングの外の色)。sh では Background を使わない
        final String backgroundShading = "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [50 0 150 0] /BBox [0 0 180 180] /Background [0 0 1] "
                + "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [1 0 0] /N 1 >> >>";
        samples.add(new String[]{"shading-pattern-bbox", page("<< /Pattern << /P 5 0 R >> >>", "/Pattern cs /P scn 0 0 200 200 re f",
                "<< /PatternType 2 /Shading " + backgroundShading + " >>")});
        samples.add(new String[]{"shading-sh-bbox", page("<< /Shading << /S 5 0 R >> >>", "/S sh", backgroundShading)});
        // 埋め込まれていない Arial の CIDToGIDMap: CID 1 は、元のフォントのグリフ 16 (ハイフン)
        samples.add(new String[]{"standard-glyph-order-cidtogidmap", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 80 Td <0001> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /Arial /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Arial /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor << /Type /FontDescriptor /FontName /Arial /Flags 32 /FontBBox [-665 -325 2000 1006] /ItalicAngle 0 "
                        + "/Ascent 905 /Descent -212 /CapHeight 716 /StemV 80 >> /W [1 [333]] /CIDToGIDMap 7 0 R >>",
                stream("", latin1(new byte[]{0, 0, 0, 16})))});
        // ToUnicode が Identity-H という名前なら、文字のコードを Unicode とみなす: 002D はハイフン
        samples.add(new String[]{"identity-tounicode-name", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 80 Td <002D> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /FooSans /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode /Identity-H >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /FooSans /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor << /Type /FontDescriptor /FontName /FooSans /Flags 32 /FontBBox [-665 -325 2000 1006] /ItalicAngle 0 "
                        + "/Ascent 905 /Descent -212 /CapHeight 716 /StemV 80 >> /W [45 [333]] >>")});
        // TrueType の集合 (.ttc) をそのまま埋め込んだもの: FontName (接頭辞を除いて L) のフォントを選ぶ
        samples.add(new String[]{"truetype-collection", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td <0001> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+L /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+L /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor 7 0 R /CIDToGIDMap /Identity /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /ABCDEF+L /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile2 8 0 R >>",
                stream("", latin1(trueTypeCollection())))});
        // Length1 が間違っている (eexec の途中を指す) Type 1 フォント: 平文の終わりを探し直す
        final byte[][] type1 = type1Font();
        samples.add(new String[]{"type1-bad-length1", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (a) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /T /FirstChar 97 /LastChar 97 /Widths [1000] /FontDescriptor 6 0 R >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile 7 0 R >>",
                stream("/Length1 " + (type1[0].length - 4) + " /Length2 " + type1[1].length + " /Length3 0",
                        latin1(type1[0]) + latin1(type1[1])))});
        // 最後の検査の値 (Adler-32) だけが壊れた FlateDecode は、戻せた分を全部使う
        final byte[] badChecksum = deflate("1 0 0 rg 50 50 100 100 re f".getBytes(StandardCharsets.ISO_8859_1));
        badChecksum[badChecksum.length - 1] ^= 0x55;
        samples.add(new String[]{"flate-bad-checksum", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>",
                stream("/Filter /FlateDecode", latin1(badChecksum)))});
        // 埋め込んだ TrueType の、知らないグリフの名前 (post の表がない g1) は、ToUnicode の文字を cmap で引く
        samples.add(new String[]{"truetype-tounicode-name", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (a) Tj ET",
                "<< /Type /Font /Subtype /TrueType /BaseFont /L /FirstChar 97 /LastChar 97 /Widths [1000] /FontDescriptor 6 0 R "
                        + "/Encoding << /BaseEncoding /WinAnsiEncoding /Differences [97 /g1] >> /ToUnicode 8 0 R >>",
                "<< /Type /FontDescriptor /FontName /L /Flags 32 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile2 7 0 R >>",
                stream("", latin1(squareTrueType(null, false, 0, true))),
                stream("", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /U def "
                        + "1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <61> <0041> endbfchar "
                        + "endcmap CMapName currentdict /CMap defineresource pop end end"))});
        // 埋め込まれていない、知らない名前のフォントの FontWeight 700 は、太字の代わりのフォントで描く
        samples.add(new String[]{"fallback-weight", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (I) Tj ET",
                "<< /Type /Font /Subtype /TrueType /BaseFont /FooSans /FirstChar 73 /LastChar 73 /Widths [278] /FontDescriptor 6 0 R "
                        + "/Encoding /WinAnsiEncoding >>",
                "<< /Type /FontDescriptor /FontName /FooSans /Flags 32 /FontWeight 700 /FontBBox [0 -200 1000 900] /ItalicAngle 0 "
                        + "/Ascent 900 /Descent -200 /CapHeight 700 /StemV 140 >>")});
        // Widths の範囲 (FirstChar 98 から) の外のコード 97 の幅は、フォントの中の幅 (1000) ではなく MissingWidth (なければ 0)
        samples.add(new String[]{"missing-width", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 50 Tf 50 50 Td (aa) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /T /FirstChar 98 /LastChar 98 /Widths [500] /FontDescriptor 6 0 R >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile 7 0 R >>",
                stream("/Length1 " + type1[0].length + " /Length2 " + type1[1].length + " /Length3 0",
                        latin1(type1[0]) + latin1(type1[1])))});
        // FontFile (Type 1 の鍵) に CFF を入れたもの: 中身の形で読む
        samples.add(new String[]{"cff-in-fontfile", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td <0002> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /T /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /T /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor 7 0 R /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile 8 0 R >>",
                stream("", latin1(squareCff())))});
        // フォントにない文字 (b) は .notdef で描く。CharStrings の最初のグリフ (a の四角) ではない
        final byte[][] notdefLast = type1Font(true, false);
        samples.add(new String[]{"type1-notdef-order", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (b) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /T /FirstChar 98 /LastChar 98 /Widths [1000] /FontDescriptor 6 0 R "
                        + "/Encoding << /Differences [98 /b] >> >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile 7 0 R >>",
                stream("/Length1 " + notdefLast[0].length + " /Length2 " + notdefLast[1].length + " /Length3 0",
                        latin1(notdefLast[0]) + latin1(notdefLast[1])))});
        // 壊れた (head の unitsPerEm が 0 の) 埋め込みの TrueType は使わず、代わりのフォントで描く
        samples.add(new String[]{"truetype-broken-head", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (a) Tj ET",
                "<< /Type /Font /Subtype /TrueType /BaseFont /L /FirstChar 97 /LastChar 97 /Widths [556] /FontDescriptor 6 0 R "
                        + "/Encoding /WinAnsiEncoding >>",
                "<< /Type /FontDescriptor /FontName /L /Flags 32 /FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 900 /Descent -200 "
                        + "/CapHeight 700 /StemV 80 /FontFile2 7 0 R >>",
                stream("", latin1(withUnitsPerEm(lsbTrueType(), 0))))});
        // 標準 14 フォントのコード 32 を .notdef にしたもの: 空白の幅にする
        samples.add(new String[]{"notdef-space", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (I I) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Differences [32 /.notdef] >> >>")});
        // Subrs を 2 回書いた Type 1 フォント: 最初のものを使う (2 つ目は、高い解像度のときだけのもの)
        final byte[][] twoSubrs = type1Font(false, true);
        samples.add(new String[]{"type1-two-subrs", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td (a) Tj ET",
                "<< /Type /Font /Subtype /Type1 /BaseFont /T /FirstChar 97 /LastChar 97 /Widths [1000] /FontDescriptor 6 0 R >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile 7 0 R >>",
                stream("/Length1 " + twoSubrs[0].length + " /Length2 " + twoSubrs[1].length + " /Length3 0",
                        latin1(twoSubrs[0]) + latin1(twoSubrs[1])))});
        // OpenType に入った、CID の決まっていない CFF を使う CIDFontType0 の CIDToGIDMap (CID 1 を、四角のグリフ 2 にする)
        samples.add(new String[]{"cff-cidtogidmap", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td <0001> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /T /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /T /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor 7 0 R /CIDToGIDMap 8 0 R /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile3 9 0 R >>",
                stream("", latin1(new byte[]{0, 0, 0, 2})),
                stream("/Subtype /OpenType", latin1(openType(squareCff()))))});
        // そのままの (OpenType に入っていない) CFF では、CIDToGIDMap を使わず、CID がそのまま GID (CID 2 は四角のグリフ 2)
        samples.add(new String[]{"bare-cff-cid", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td <0002> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /T /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /T /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor 7 0 R /CIDToGIDMap 8 0 R /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /T /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile3 9 0 R >>",
                stream("", latin1(new byte[]{0, 0, 0, 1, 0, 1})),
                stream("/Subtype /Type1C", latin1(squareCff())))});
        // TrueType のグリフの原点は xMin - lsb (グリフ 1 は xMin 500 だが lsb 0 なので、原点から描かれる)
        samples.add(new String[]{"truetype-lsb", page("<< /Font << /F1 5 0 R >> >>", "0 g BT /F1 100 Tf 50 50 Td <0001> Tj ET",
                "<< /Type /Font /Subtype /Type0 /BaseFont /L /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /L /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor 7 0 R /CIDToGIDMap /Identity /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /L /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 "
                        + "/CapHeight 1000 /StemV 80 /FontFile2 8 0 R >>",
                stream("", latin1(lsbTrueType())))});
        // PNG の予測 (Sub) の 1 画素のバイト数は切り上げ: RGB の 4 ビットは 12 ビットなので 2 バイト前を足す。
        // 赤 (F 0 0) と青 (0 0 F) の行 F0 00 0F を、Sub で F0 00 1F にしたもの
        samples.add(new String[]{"png-predictor-rgb4", page("<< /XObject << /I 5 0 R >> >>", "q 200 0 0 200 0 0 cm /I Do Q",
                stream("/Type /XObject /Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 4 /Filter /FlateDecode "
                                + "/DecodeParms << /Predictor 11 /Colors 3 /BitsPerComponent 4 /Columns 2 >>",
                        latin1(deflate(new byte[]{1, (byte) 0xF0, 0, 0x1F}))))});
        // EOL を行ごとに置いた G4 (EndOfLine true): 8 x 4 の画像の上の 2 行の右半分が黒
        samples.add(new String[]{"ccitt-g4-eol", page("<< /XObject << /I 5 0 R >> >>", "q 200 0 0 200 0 0 cm /I Do Q",
                stream("/Type /XObject /Subtype /Image /Width 8 /Height 4 /ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /CCITTFaxDecode "
                                + "/DecodeParms << /K -1 /Columns 8 /Rows 4 /EndOfLine true >>",
                        latin1(new byte[]{0x00, 0x13, 0x6C, 0x00, 0x70, 0x01, 0x33, 0x0D, (byte) 0xC0, 0x06, 0x00, 0x20, 0x02})))});
        // 圧縮したインライン画像の後ろに、ASCII でない文字の文字列がある: EI を見つけて、後ろの青い四角も描く
        samples.add(new String[]{"inline-image-latin1", page("<< /Font << /F1 5 0 R >> >>",
                "q 100 0 0 100 0 100 cm BI /W 2 /H 2 /CS /RGB /BPC 8 /F /Fl ID "
                        + latin1(deflate(new byte[]{(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0}))
                        + " EI Q BT /F1 12 Tf 10 10 Td (café) Tj ET 0 0 1 rg 100 0 100 100 re f",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")});
        // 増分更新でオブジェクトストリームに入れたページ (青) と、参照の切れた番号 (99): 表を作り直しても、新しい版を使う
        samples.add(new String[]{"incremental-object-stream", objectStreamUpdate()});
        // 表示しない層の 2 つの区切りの間で、参照の切れた番号 (99) を読む: 表を作り直した後も、層の辞書は同じもので、右も描かない
        samples.add(new String[]{"hidden-layer-after-repair", build(
                "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R "
                        + "/Resources << /Properties << /OC1 5 0 R /OC2 6 0 R >> /ExtGState << /G 99 0 R >> >> >>",
                stream("", "/OC /OC1 BDC 1 0 0 rg 0 0 100 200 re f EMC /G gs /OC /OC2 BDC 1 0 0 rg 100 0 100 200 re f EMC"),
                "<< /Type /OCG /Name (A) >>",
                "<< /Type /OCMD /OCGs [5 0 R] >>")});
        // ページの MediaBox [0 0 0 0] は、ないものとして親の MediaBox (200 x 200) を使う。赤は左下の 4 分の 1 だけ
        samples.add(new String[]{"zero-media-box", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 0 0] /Contents 4 0 R >>",
                stream("", "1 0 0 rg 0 0 100 100 re f"))});
        // 90 の倍数でない Rotate (135) は回さない (pdf.js と poppler と同じ)。左下の赤い四角は左下に描く
        samples.add(new String[]{"rotate-not-multiple-of-90", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Rotate 135 /Contents 4 0 R >>",
                stream("", "1 0 0 rg 0 0 100 50 re f"))});
        return samples;
    }

    /**
     * 増分更新のある PDF。最初の版は赤く塗るページ (3 番) で、ページの木 (2 番) に参照の切れた番号 (99) がある。
     * 2 つ目の版は、相互参照ストリームとオブジェクトストリーム (5 番) で、ページを青く塗るもの (内容は 6 番) に置き換える。
     */
    static String objectStreamUpdate() {
        final StringBuilder out = new StringBuilder(build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources 99 0 R >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>",
                stream("", "1 0 0 rg 0 0 200 200 re f")));
        final int previous = out.lastIndexOf("\nxref\n") + 1;
        final int objectStream = out.length();
        out.append("5 0 obj\n").append(stream("/Type /ObjStm /N 1 /First 4",
                "3 0\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R >>")).append("\nendobj\n");
        final int content = out.length();
        out.append("6 0 obj\n").append(stream("", "0 0 1 rg 0 0 200 200 re f")).append("\nendobj\n");
        final int xref = out.length();
        // /W [1 4 1] の行: 3 番 (5 番の 0 番目)、5 番、6 番、7 番 (この相互参照ストリーム)
        final ByteArrayOutputStream rows = new ByteArrayOutputStream();
        final int[][] entries = {{2, 5, 0}, {1, objectStream, 0}, {1, content, 0}, {1, xref, 0}};
        for (int[] entry : entries) write(rows, entry[0], entry[1] >>> 24, entry[1] >>> 16 & 0xFF, entry[1] >>> 8 & 0xFF, entry[1] & 0xFF, entry[2]);
        out.append("7 0 obj\n").append(stream("/Type /XRef /Size 8 /Index [3 1 5 3] /W [1 4 1] /Root 1 0 R /Prev " + previous,
                latin1(rows.toByteArray()))).append("\nendobj\nstartxref\n").append(xref).append("\n%%EOF\n");
        return out.toString();
    }

    /**
     * 3 つのグリフ (.notdef、空、0 から 1000 の四角) の CFF (CID の決まっていないもの)。
     */
    static byte[] squareCff() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 0 0 rmoveto 1000 hlineto 1000 vlineto -1000 hlineto endchar
        final byte[] square = {(byte) 139, (byte) 139, 21, (byte) 250, 124, 6, (byte) 250, 124, 7, (byte) 254, 124, 6, 14};
        final int charStrings = 4 + 6 + 22 + 2 + 2;
        final int privateOffset = charStrings + 2 + 1 + 4 + 2 + square.length;
        // ヘッダー、名前の INDEX ("T")
        write(out, 1, 0, 4, 1, 0, 1, 1, 1, 2, 'T');
        // Top DICT の INDEX: CharStrings (17) と Private (18) を、5 バイトの整数で
        write(out, 0, 1, 1, 1, 18);
        write(out, 29, 0, 0, 0, charStrings, 17, 29, 0, 0, 0, 2, 29, 0, 0, 0, privateOffset, 18);
        // 文字列と大域サブルーチンの INDEX (空)
        write(out, 0, 0, 0, 0);
        // CharStrings の INDEX
        write(out, 0, 3, 1, 1, 2, 3, 3 + square.length, 14, 14);
        out.write(square, 0, square.length);
        // Private DICT: defaultWidthX 0
        write(out, 139, 20);
        return out.toByteArray();
    }

    static byte[][] type1Font() {
        return type1Font(false, false);
    }

    /**
     * a が 0 から 1000 の四角の Type 1 フォント。{平文の部分, eexec で暗号化した部分}。
     *
     * @param notdefLast CharStrings で、.notdef を a の後に書くか
     * @param twoSubrs   a の形を Subrs 0 に置き、Subrs を 2 回書くか (2 つ目の Subrs 0 は何も描かない。解像度で選び直すフォントの形)
     */
    static byte[][] type1Font(boolean notdefLast, boolean twoSubrs) {
        final String clear = "%!PS-AdobeFont-1.0: T 001\n11 dict begin\n/FontName /T def\n/FontType 1 def\n/PaintType 0 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 0 1000 1000} readonly def\n"
                + "/Encoding 256 array 0 1 255 {1 index exch /.notdef put} for dup 97 /a put readonly def\n"
                + "currentdict end\ncurrentfile eexec\n";
        // hsbw 0 1000、rmoveto 0 0、hlineto 1000、vlineto 1000、hlineto -1000、closepath、endchar (初めの 4 バイトは捨てる分)
        final byte[] square = charString(0, 0, 0, 0, 139, 250, 124, 13, 139, 139, 21, 250, 124, 6, 250, 124, 7, 254, 124, 6, 9, 14);
        // .notdef: hsbw 0 0、endchar
        final byte[] notdef = charString(0, 0, 0, 0, 139, 139, 13, 14);
        // Subrs 0 に四角を置くとき: a は hsbw 0 1000、callsubr 0、endchar
        final byte[] squareSubr = charString(0, 0, 0, 0, 139, 139, 21, 250, 124, 6, 250, 124, 7, 254, 124, 6, 9, 11);
        final byte[] emptySubr = charString(0, 0, 0, 0, 11);
        final byte[] callSquare = charString(0, 0, 0, 0, 139, 250, 124, 13, 139, 10, 14);
        final ByteArrayOutputStream secret = new ByteArrayOutputStream();
        final String head = "dup /Private 8 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n";
        writeText(secret, head);
        if (twoSubrs) {
            for (byte[] subr : new byte[][]{squareSubr, emptySubr}) {
                writeText(secret, "/Subrs 1 array\ndup 0 " + subr.length + " RD ");
                secret.write(subr, 0, subr.length);
                writeText(secret, " NP\nND\n");
            }
        } else {
            writeText(secret, "/Subrs 0 array ND\n");
        }
        writeText(secret, "2 index /CharStrings 2 dict dup begin\n");
        for (int i = 0; i < 2; i++) {
            final boolean a = (i == 0) == notdefLast;
            final byte[] glyph = a ? (twoSubrs ? callSquare : square) : notdef;
            writeText(secret, (a ? "/a " : "/.notdef ") + glyph.length + " RD ");
            secret.write(glyph, 0, glyph.length);
            writeText(secret, " ND\n");
        }
        writeText(secret, "end\nend\nreadonly put\nnoaccess put\ndup /FontName get exch definefont pop\nmark currentfile closefile\n");
        final byte[] plain = secret.toByteArray();
        // eexec の暗号化 (初めに 4 バイトを足す)
        final byte[] encrypted = new byte[plain.length + 4];
        int r = 55665;
        for (int i = 0; i < encrypted.length; i++) {
            final int c = (i < 4 ? 0 : plain[i - 4] & 0xFF) ^ (r >> 8);
            encrypted[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return new byte[][]{clear.getBytes(StandardCharsets.ISO_8859_1), encrypted};
    }

    // Type 1 の文字の手順を暗号化したもの
    private static byte[] charString(int... bytes) {
        final byte[] out = new byte[bytes.length];
        int r = 4330;
        for (int i = 0; i < bytes.length; i++) {
            final int c = bytes[i] ^ (r >> 8);
            out[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * CFF (3 つのグリフ) を OpenType に入れたもの (FreeType も読めるように、head、hhea、hmtx、maxp も付ける)。
     */
    static byte[] openType(byte[] cff) {
        // 幅 1000、lsb 0 を 3 つ
        final byte[] hmtx = {0x03, (byte) 0xE8, 0, 0, 0x03, (byte) 0xE8, 0, 0, 0x03, (byte) 0xE8, 0, 0};
        final byte[] maxp = {0, 0, 0x50, 0, 0, 3};
        return sfnt(0x4F54544F, new String[]{"CFF ", "head", "hhea", "hmtx", "maxp"},
                new byte[][]{cff, head(0, 0, 1000, 1000), hhea(3), hmtx, maxp});
    }

    // head の表 (単位 1000、範囲は引数、短い loca)
    private static byte[] head(int xMin, int yMin, int xMax, int yMax) {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        write(head, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x5F, 0x0F, 0x3C, 0xF5, 0, 0, 0x03, 0xE8);
        for (int i = 0; i < 16; i++) head.write(0);
        write(head, xMin >> 8, xMin & 0xFF, yMin >> 8, yMin & 0xFF, xMax >> 8, xMax & 0xFF, yMax >> 8, yMax & 0xFF, 0, 0, 0, 8, 0, 2, 0, 0, 0, 0);
        return head.toByteArray();
    }

    // hhea の表 (高さ 1000、hmtx の幅の数は引数)
    private static byte[] hhea(int metrics) {
        final ByteArrayOutputStream hhea = new ByteArrayOutputStream();
        write(hhea, 0, 1, 0, 0, 0x03, 0xE8, 0, 0, 0, 0, 0x03, 0xE8);
        for (int i = 12; i < 34; i++) hhea.write(0);
        write(hhea, metrics >> 8, metrics & 0xFF);
        return hhea.toByteArray();
    }

    // 表を並べたフォントのファイル (sfnt)
    private static byte[] sfnt(int version, String[] tags, byte[][] tables) {
        return sfnt(version, tags, tables, 0);
    }

    // 表を並べたフォントのファイル。base はファイルの中での、このフォントの位置 (表の位置はファイルの初めから数える)
    private static byte[] sfnt(int version, String[] tags, byte[][] tables, int base) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, version >>> 24, version >> 16 & 0xFF, version >> 8 & 0xFF, version & 0xFF, 0, tables.length, 0, 0, 0, 0, 0, 0);
        int offset = base + 12 + 16 * tables.length;
        for (int i = 0; i < tables.length; i++) {
            for (int k = 0; k < 4; k++) out.write(tags[i].charAt(k));
            write(out, 0, 0, 0, 0, offset >> 24, offset >> 16 & 0xFF, offset >> 8 & 0xFF, offset & 0xFF,
                    0, 0, tables[i].length >> 8, tables[i].length & 0xFF);
            offset += (tables[i].length + 3) & ~3;
        }
        for (byte[] table : tables) {
            out.write(table, 0, table.length);
            for (int k = table.length; k % 4 != 0; k++) out.write(0);
        }
        return out.toByteArray();
    }

    /**
     * 2 つのグリフの TrueType。グリフ 1 は x 500 から 900、y 0 から 400 の四角だが、hmtx の lsb は 0。
     */
    static byte[] lsbTrueType() {
        return squareTrueType(null, false, 0, false);
    }

    /**
     * 2 つのフォントの TrueType の集合 (.ttc)。最初の "Other" はグリフ 1 が空で、2 つ目の "L" は lsbTrueType と同じ。
     */
    static byte[] trueTypeCollection() {
        final int header = 12 + 4 * 2;
        final byte[] first = squareTrueType("Other", true, header, false);
        final byte[] second = squareTrueType("L", false, header + first.length, false);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int at = header + first.length;
        write(out, 't', 't', 'c', 'f', 0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, header, at >> 24, at >> 16 & 0xFF, at >> 8 & 0xFF, at & 0xFF);
        out.write(first, 0, first.length);
        out.write(second, 0, second.length);
        return out.toByteArray();
    }

    /**
     * lsbTrueType の形の TrueType。
     *
     * @param name  PostScript 名 (name の表に書く。null なら name の表なし)
     * @param empty グリフ 1 を空にするか
     * @param base  ファイルの中での、このフォントの位置
     * @param cmap  Unicode の cmap (A をグリフ 1 に) を付けるか
     */
    private static byte[] squareTrueType(String name, boolean empty, int base, boolean cmap) {
        final byte[] maxp = {0, 0, 0x50, 0, 0, 2};
        // 幅 1000、lsb 0 (グリフ 0)、lsb 0 (グリフ 1)
        final byte[] hmtx = {0x03, (byte) 0xE8, 0, 0, 0, 0};
        final ByteArrayOutputStream glyf = new ByteArrayOutputStream();
        // 輪郭 1 つ、xMin 500 yMin 0 xMax 900 yMax 400、終わりの点 3、命令なし、4 点とも曲線の上 (座標は 2 バイトの差分)
        write(glyf, 0, 1, 0x01, 0xF4, 0, 0, 0x03, 0x84, 0x01, 0x90, 0, 3, 0, 0, 1, 1, 1, 1,
                0x01, 0xF4, 0x01, 0x90, 0, 0, 0xFE, 0x70, 0, 0, 0, 0, 0x01, 0x90, 0, 0);
        // loca (短い形、2 で割った値): グリフ 0 は空
        final byte[] loca = {0, 0, 0, 0, 0, (byte) (empty ? 0 : glyf.size() / 2)};
        if (cmap) {
            // (3, 1) の形式 4: A (0x41) をグリフ 1 に。最後の区切りは 0xFFFF
            final ByteArrayOutputStream table = new ByteArrayOutputStream();
            write(table, 0, 0, 0, 1, 0, 3, 0, 1, 0, 0, 0, 12);
            write(table, 0, 4, 0, 32, 0, 0, 0, 4, 0, 4, 0, 1, 0, 0);
            write(table, 0, 0x41, 0xFF, 0xFF, 0, 0, 0, 0x41, 0xFF, 0xFF, 0xFF, 0xC0, 0, 1, 0, 0, 0, 0);
            return sfnt(0x00010000, new String[]{"cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp"},
                    new byte[][]{table.toByteArray(), glyf.toByteArray(), head(500, 0, 900, 400), hhea(1), hmtx, loca, maxp}, base);
        }
        if (name == null) {
            return sfnt(0x00010000, new String[]{"glyf", "head", "hhea", "hmtx", "loca", "maxp"},
                    new byte[][]{glyf.toByteArray(), head(500, 0, 900, 400), hhea(1), hmtx, loca, maxp}, base);
        }
        // name の表: Macintosh の英語の PostScript 名 (名前の番号 6) だけ
        final ByteArrayOutputStream names = new ByteArrayOutputStream();
        write(names, 0, 0, 0, 1, 0, 18, 0, 1, 0, 0, 0, 0, 0, 6, 0, name.length(), 0, 0);
        for (int i = 0; i < name.length(); i++) names.write(name.charAt(i));
        return sfnt(0x00010000, new String[]{"glyf", "head", "hhea", "hmtx", "loca", "maxp", "name"},
                new byte[][]{glyf.toByteArray(), head(500, 0, 900, 400), hhea(1), hmtx, loca, maxp, names.toByteArray()}, base);
    }

    // フォントの head の表の unitsPerEm を書き換えたもの
    static byte[] withUnitsPerEm(byte[] font, int units) {
        final byte[] copy = font.clone();
        final int tables = ((copy[4] & 0xFF) << 8) | (copy[5] & 0xFF);
        for (int i = 0; i < tables; i++) {
            final int record = 12 + 16 * i;
            if (copy[record] == 'h' && copy[record + 1] == 'e' && copy[record + 2] == 'a' && copy[record + 3] == 'd') {
                final int offset = ((copy[record + 8] & 0xFF) << 24) | ((copy[record + 9] & 0xFF) << 16) | ((copy[record + 10] & 0xFF) << 8)
                        | (copy[record + 11] & 0xFF);
                copy[offset + 18] = (byte) (units >> 8);
                copy[offset + 19] = (byte) units;
            }
        }
        return copy;
    }

    private static void write(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) out.write(b);
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
        // 幅も高さもほとんどない MediaBox (拡大率が極端になり、巨大な画像を作ろうとした)
        samples.add(new String[]{"tiny-media-box", build(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 0.000001 0] /Contents 4 0 R >>",
                stream("", "0 g 0 0 0.000001 0.000001 re f"))});
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
        // /Length が次のストリームを指すことを 20000 回繰り返したもの (読む入れ子でスタックを使い切っていた)
        final String[] chain = new String[20004];
        chain[0] = "<< /Type /Catalog /Pages 2 0 R >>";
        chain[1] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>";
        chain[2] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>";
        for (int i = 3; i < chain.length - 1; i++) chain[i] = "<< /Length " + (i + 2) + " 0 R >>\nstream\n0 g\nendstream";
        chain[chain.length - 1] = "4";
        samples.add(new String[]{"length-chain", build(chain)});
        // V 5 (256 ビット) なのに R 4 の暗号化の辞書: R 4 の鍵の作り方に 32 バイトの鍵の長さを渡して、範囲の外を読んでいた
        final String padding = "(" + new String(new char[32]).replace('\0', 'A') + ")";
        samples.add(new String[]{"encrypt-v5-r4", page("<< >>", "0 g", "<< /Filter /Standard /V 5 /R 4 /Length 256 /CF << /StdCF "
                + "<< /CFM /AESV3 >> >> /StmF /StdCF /StrF /StdCF /O " + padding + " /U " + padding + " /P -4 >>")
                .replace("/Root 1 0 R >>", "/Root 1 0 R /Encrypt 5 0 R /ID [<00> <00>] >>")});
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
