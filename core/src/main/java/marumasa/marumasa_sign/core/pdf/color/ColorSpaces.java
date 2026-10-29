package marumasa.marumasa_sign.core.pdf.color;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;
import marumasa.marumasa_sign.core.pdf.cos.PdfString;
import marumasa.marumasa_sign.core.pdf.function.PdfFunction;

import java.io.IOException;

/**
 * 色空間の名前や配列から、色空間を作る。
 */
public final class ColorSpaces {

    // 入れ子 (Indexed の元の色空間など) の深さの上限。自分自身を指す色空間で止まらないように
    private static final int MAX_DEPTH = 8;

    private ColorSpaces() {
    }

    /**
     * @param value     名前 (/DeviceRGB など) か配列 ([/Indexed ...] など)
     * @param resources 名前で指す色空間を探す、リソースの辞書 (なければ null)
     */
    public static PdfColorSpace create(Object value, PdfDict resources) throws IOException {
        return create(value, resources, 0);
    }

    private static PdfColorSpace create(Object value, PdfDict resources, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Color spaces are nested too deeply");
        if (value instanceof PdfName) {
            final String name = ((PdfName) value).name;
            final PdfColorSpace device = device(name);
            if (device != null) return device;
            if (name.equals("Pattern")) return new SpecialSpaces.Pattern(null);
            // リソースの /ColorSpace にある名前
            final PdfDict named = resources == null ? null : resources.dict("ColorSpace");
            final Object definition = named == null ? null : named.get(name);
            if (definition == null) throw new IOException("Unknown color space " + name);
            return create(definition, resources, depth + 1);
        }
        if (!(value instanceof PdfArray)) throw new IOException("Bad color space");
        final PdfArray array = (PdfArray) value;
        final String family = array.name(0);
        if (family == null) throw new IOException("Bad color space");
        switch (family) {
            case "DeviceGray":
            case "G":
            case "DeviceRGB":
            case "RGB":
            case "DeviceCMYK":
            case "CMYK":
                return device(family);
            case "CalGray":
                return new CieSpaces.CalGray(array.dict(1));
            case "CalRGB":
                return new CieSpaces.CalRgb(array.dict(1));
            case "Lab":
                return new CieSpaces.Lab(array.dict(1));
            case "ICCBased":
                return iccBased(array, resources, depth);
            case "Indexed":
            case "I":
                return indexed(array, resources, depth);
            case "Separation":
                return separation(array, resources, depth);
            case "DeviceN":
                return deviceN(array, resources, depth);
            case "Pattern":
                return new SpecialSpaces.Pattern(array.size() > 1 ? create(array.get(1), resources, depth + 1) : null);
            default:
                throw new IOException("Unknown color space " + family);
        }
    }

    public static PdfColorSpace device(String name) {
        switch (name) {
            case "DeviceGray":
            case "G":
            case "CalGray":
                return DeviceSpaces.GRAY;
            case "DeviceRGB":
            case "RGB":
            case "CalRGB":
                return DeviceSpaces.RGB;
            case "DeviceCMYK":
            case "CMYK":
                return DeviceSpaces.CMYK;
            default:
                return null;
        }
    }

    // 埋め込まれた ICC プロファイルは使わない (PdfColorSpace の説明を参照)。代わりの色空間か、成分の数に合う Device の色空間
    private static PdfColorSpace iccBased(PdfArray array, PdfDict resources, int depth) throws IOException {
        final PdfStream stream = array.stream(1);
        final PdfDict dict = stream == null ? array.dict(1) : stream.dict;
        if (dict == null) throw new IOException("Bad ICCBased color space");
        final int n = dict.integer("N", 0);
        final Object alternate = dict.get("Alternate");
        if (alternate != null) {
            try {
                final PdfColorSpace space = create(alternate, resources, depth + 1);
                if (space.components() == n || n == 0) return space;
            } catch (IOException e) {
                // 代わりが使えなければ、成分の数で決める
            }
        }
        switch (n) {
            case 1:
                return DeviceSpaces.GRAY;
            case 3:
                return DeviceSpaces.RGB;
            case 4:
                return DeviceSpaces.CMYK;
            default:
                throw new IOException("Bad ICCBased component count " + n);
        }
    }

    private static PdfColorSpace indexed(PdfArray array, PdfDict resources, int depth) throws IOException {
        if (array.size() < 4) throw new IOException("Bad Indexed color space");
        final PdfColorSpace base = create(array.get(1), resources, depth + 1);
        if (base instanceof SpecialSpaces.Indexed || base instanceof SpecialSpaces.Pattern) throw new IOException("Bad Indexed base");
        final int hival = array.integer(2, -1);
        if (hival < 0) throw new IOException("Bad Indexed hival");
        final Object lookup = array.get(3);
        final byte[] table;
        if (lookup instanceof PdfString) {
            table = ((PdfString) lookup).bytes;
        } else if (lookup instanceof PdfStream) {
            table = ((PdfStream) lookup).decode(1 << 20);
        } else {
            throw new IOException("Bad Indexed lookup");
        }
        return new SpecialSpaces.Indexed(base, hival, table);
    }

    private static PdfColorSpace separation(PdfArray array, PdfDict resources, int depth) throws IOException {
        if (array.size() < 4) throw new IOException("Bad Separation color space");
        final String name = array.name(1);
        final PdfColorSpace alternate = create(array.get(2), resources, depth + 1);
        checkAlternate(alternate);
        final PdfFunction function = PdfFunction.create(array.get(3));
        return new SpecialSpaces.Tint(1, alternate, function, "None".equals(name));
    }

    private static PdfColorSpace deviceN(PdfArray array, PdfDict resources, int depth) throws IOException {
        if (array.size() < 4) throw new IOException("Bad DeviceN color space");
        final PdfArray names = array.array(1);
        if (names == null || names.size() == 0 || names.size() > 32) throw new IOException("Bad DeviceN names");
        boolean allNone = true;
        for (int i = 0; i < names.size(); i++) {
            if (!"None".equals(names.name(i))) allNone = false;
        }
        final PdfColorSpace alternate = create(array.get(2), resources, depth + 1);
        checkAlternate(alternate);
        final PdfFunction function = PdfFunction.create(array.get(3));
        return new SpecialSpaces.Tint(names.size(), alternate, function, allNone);
    }

    private static void checkAlternate(PdfColorSpace alternate) throws IOException {
        if (alternate instanceof SpecialSpaces.Indexed || alternate instanceof SpecialSpaces.Pattern
                || alternate instanceof SpecialSpaces.Tint) {
            throw new IOException("Bad alternate color space");
        }
    }
}
