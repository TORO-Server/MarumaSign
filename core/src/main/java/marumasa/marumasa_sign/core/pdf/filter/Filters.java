package marumasa.marumasa_sign.core.pdf.filter;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ストリームの /Filter を戻す。
 * どのフィルタも、戻したデータが上限 (limit) を超えたら IOException にして、小さなファイルが巨大なデータに
 * なるもの (圧縮爆弾) でメモリを使い切らないようにする。
 */
public final class Filters {

    // 画像を作る側で戻す、画像のための圧縮
    public static final String DCT = "DCTDecode";
    public static final String JPX = "JPXDecode";
    public static final String JBIG2 = "JBIG2Decode";
    public static final String CCITT = "CCITTFaxDecode";

    private Filters() {
    }

    /**
     * 1 つのフィルタと、その /DecodeParms。
     */
    public static final class Step {
        public final String name;
        public final PdfDict parameters;

        Step(String name, PdfDict parameters) {
            this.name = name;
            this.parameters = parameters;
        }
    }

    // 略した名前 (インライン画像で使う) を、正式な名前にする
    public static String fullName(String name) {
        switch (name) {
            case "AHx":
                return "ASCIIHexDecode";
            case "A85":
                return "ASCII85Decode";
            case "LZW":
                return "LZWDecode";
            case "Fl":
                return "FlateDecode";
            case "RL":
                return "RunLengthDecode";
            case "CCF":
                return CCITT;
            case "DCT":
                return DCT;
            default:
                return name;
        }
    }

    /**
     * /Filter と /DecodeParms を順に並べたもの (インライン画像の略した鍵 /F と /DP にも対応)。
     */
    public static List<Step> steps(PdfDict dict) {
        final List<Step> steps = new ArrayList<>();
        final Object filter = dict.get("Filter", "F");
        final Object parameters = dict.get("DecodeParms", "DP");
        if (filter instanceof PdfName) {
            steps.add(new Step(fullName(((PdfName) filter).name), parameters instanceof PdfDict ? (PdfDict) parameters
                    : parameters instanceof PdfArray ? ((PdfArray) parameters).dict(0) : null));
        } else if (filter instanceof PdfArray) {
            final PdfArray filters = (PdfArray) filter;
            // 何十ものフィルタを重ねたものは、悪意のあるファイルなので打ち切る
            for (int i = 0; i < filters.size() && i < 16; i++) {
                final String name = filters.name(i);
                if (name == null) continue;
                PdfDict step = null;
                if (parameters instanceof PdfArray) step = ((PdfArray) parameters).dict(i);
                else if (parameters instanceof PdfDict && filters.size() == 1) step = (PdfDict) parameters;
                steps.add(new Step(fullName(name), step));
            }
        }
        return steps;
    }

    public static boolean isImageFilter(String name) {
        return name.equals(DCT) || name.equals(JPX) || name.equals(JBIG2) || name.equals(CCITT);
    }

    /**
     * フィルタを戻す。
     *
     * @param stopAtImage 画像のための圧縮 (DCTDecode など) が出てきたら、そこで止める (その前までを戻したものを返す)
     */
    public static byte[] decode(byte[] data, PdfDict dict, int limit, boolean stopAtImage) throws IOException {
        byte[] result = data;
        for (Step step : steps(dict)) {
            if (isImageFilter(step.name)) {
                if (stopAtImage) return result;
                throw new IOException("Image filter in a non-image stream: " + step.name);
            }
            result = apply(step, result, limit);
        }
        if (result.length > limit) throw new IOException("Decoded data is too large");
        return result;
    }

    private static byte[] apply(Step step, byte[] data, int limit) throws IOException {
        switch (step.name) {
            case "FlateDecode":
                return Predictor.apply(Flate.decode(data, limit), step.parameters, limit);
            case "LZWDecode": {
                final int earlyChange = step.parameters == null ? 1 : step.parameters.integer("EarlyChange", 1);
                return Predictor.apply(Lzw.decode(data, earlyChange != 0, limit), step.parameters, limit);
            }
            case "ASCIIHexDecode":
                return AsciiFilters.hex(data);
            case "ASCII85Decode":
                return AsciiFilters.base85(data, limit);
            case "RunLengthDecode":
                return AsciiFilters.runLength(data, limit);
            case "Crypt":
                // 復号はファイルを読むときに済ませてある
                return data;
            default:
                throw new IOException("Unsupported filter: " + step.name);
        }
    }

    /**
     * 最後のフィルタが画像のための圧縮なら、それを返す。
     */
    public static Step imageStep(PdfDict dict) {
        final List<Step> steps = steps(dict);
        for (Step step : steps) {
            if (isImageFilter(step.name)) return step;
        }
        return null;
    }
}
