package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.texture.DdsDecoder;
import marumasa.marumasa_sign.core.texture.Ktx2Decoder;
import marumasa.marumasa_sign.core.texture.WebpDecoder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/**
 * 画像の形式を見分けて、読み込む。
 * 形式は先頭のバイト列で見分ける。署名のない形式 (TGA、WBMP、XBM など) だけは、ファイル名の拡張子も使う。
 * 自分で読めない形式や、自分のデコーダが対応していない種類のファイルは、FFmpeg があれば FFmpeg に頼る。
 */
public final class StillFormats {

    public enum Format {
        PNG(false, null), GIF(false, null), JPEG(false, null), BMP(false, null),
        WEBP(true, ".webp"), DDS(true, ".dds"), KTX2(false, null), TIFF(true, ".tiff"), ICO(true, ".ico"), ANI(false, null),
        PSD(true, ".psd"), QOI(true, ".qoi"), PNM(true, ".pnm"), HDR(true, ".hdr"), SGI(true, ".sgi"), SUN(true, ".ras"),
        FARBFELD(true, ".ff"), ICNS(true, ".icns"), FITS(true, ".fits"), XPM(true, ".xpm"), XBM(true, ".xbm"),
        TGA(true, ".tga"), PCX(true, ".pcx"), WBMP(true, ".wbmp"),
        // FFmpeg でしか読めない形式
        AVIF(true, ".avif"), HEIC(true, ".heic"), JPEGXL(true, ".jxl"), JPEG2000(true, ".jp2"), EXR(true, ".exr"), DPX(true, ".dpx"),
        ILBM(true, ".iff"), XWD(true, ".xwd"), PGX(true, ".pgx"),
        SVG(false, null);

        // 自分のデコーダが失敗したときに、FFmpeg にやり直させるか
        final boolean ffmpegFallback;
        // FFmpeg に渡すときの拡張子
        final String extension;

        Format(boolean ffmpegFallback, String extension) {
            this.ffmpegFallback = ffmpegFallback;
            this.extension = extension;
        }
    }

    private StillFormats() {
    }

    /**
     * @return 形式。静止画の形式でなければ null
     */
    public static Format detect(byte[] h, int n, String name) {
        final Format format = detectBySignature(h, n, name);
        if (format == null) return detectByExtension(h, n, extensionOf(name));
        // FFmpeg でしか読めない形式は、FFmpeg がなければ画像として扱わない (動画の側の処理に任せる)
        if (needsFfmpeg(format) && !FfmpegImage.isAvailable()) return null;
        return format;
    }

    private static boolean needsFfmpeg(Format format) {
        switch (format) {
            case AVIF: case HEIC: case JPEGXL: case JPEG2000: case DPX: case ILBM: case XWD: case PGX:
                return true;
            default:
                return false;
        }
    }

    private static Format detectBySignature(byte[] h, int n, String name) {
        if (n < 2) return null;
        final int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
        if (n >= 8 && b0 == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return Format.PNG;
        if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return Format.GIF;
        if (n >= 3 && b0 == 0xFF && b1 == 0xD8 && (h[2] & 0xFF) == 0xFF) return Format.JPEG;
        if (h[0] == 'B' && h[1] == 'M') return Format.BMP;
        if (n >= 12 && FormatUtil.startsWith(h, n, "RIFF")) {
            if (FormatUtil.matchesAt(h, n, 8, "WEBP")) return Format.WEBP;
            if (FormatUtil.matchesAt(h, n, 8, "ACON")) return Format.ANI;
            return null;
        }
        if (FormatUtil.startsWith(h, n, "DDS ")) return Format.DDS;
        if (n >= 12 && b0 == 0xAB && h[1] == 'K' && h[2] == 'T' && h[3] == 'X' && h[4] == ' ' && h[5] == '2' && h[6] == '0') return Format.KTX2;
        if (TiffDecoder.looksLikeTiff(h, n)) return Format.TIFF;
        if (IcoDecoder.looksLikeIco(h, n)) return Format.ICO;
        if (PsdDecoder.looksLikePsd(h, n)) return Format.PSD;
        if (FormatUtil.startsWith(h, n, "qoif")) return Format.QOI;
        if (HdrDecoder.looksLikeHdr(h, n)) return Format.HDR;
        if (RasterDecoders.looksLikeSgi(h, n)) return Format.SGI;
        if (RasterDecoders.looksLikeSun(h, n)) return Format.SUN;
        if (FormatUtil.startsWith(h, n, "farbfeld")) return Format.FARBFELD;
        if (IcnsDecoder.looksLikeIcns(h, n)) return Format.ICNS;
        if (RasterDecoders.looksLikeFits(h, n)) return Format.FITS;
        if (TextImages.looksLikeXpm(h, n)) return Format.XPM;
        if (PnmDecoder.looksLikePnm(h, n)) return Format.PNM;
        if (PcxDecoder.looksLikePcx(h, n)) return Format.PCX;
        if (n >= 4 && b0 == 0x76 && b1 == 0x2F && h[2] == 0x31 && h[3] == 0x01) return Format.EXR;
        if (FormatUtil.startsWith(h, n, "SDPX") || FormatUtil.startsWith(h, n, "XPDS")) return Format.DPX;
        if (n >= 12 && FormatUtil.startsWith(h, n, "FORM") && (FormatUtil.matchesAt(h, n, 8, "ILBM") || FormatUtil.matchesAt(h, n, 8, "PBM ")
                || FormatUtil.matchesAt(h, n, 8, "ACBM"))) {
            return Format.ILBM;
        }
        // JPEG XL: コードストリームそのもの (FF 0A) と、ISO BMFF の箱に入ったもの
        if (b0 == 0xFF && b1 == 0x0A) return Format.JPEGXL;
        if (n >= 12 && b0 == 0 && h[1] == 0 && h[2] == 0 && h[3] == 0x0C && h[4] == 'J' && h[5] == 'X' && h[6] == 'L' && h[7] == ' ') return Format.JPEGXL;
        // JPEG 2000: JP2 の箱と、コードストリームそのもの (FF 4F FF 51)
        if (n >= 12 && b0 == 0 && h[1] == 0 && h[2] == 0 && h[3] == 0x0C && h[4] == 'j' && h[5] == 'P' && h[6] == ' ' && h[7] == ' ') return Format.JPEG2000;
        if (n >= 4 && b0 == 0xFF && b1 == 0x4F && (h[2] & 0xFF) == 0xFF && (h[3] & 0xFF) == 0x51) return Format.JPEG2000;
        // ISO BMFF (AVIF と HEIC)。連続した画像 (avis など) は動画として扱う
        if (n >= 16 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return isoImage(h, n);
        if (SvgDecoder.looksLikeSvg(h, n)) return Format.SVG;
        return null;
    }

    private static Format isoImage(byte[] h, int n) {
        final long size = FormatUtil.u32be(h, 0);
        final int end = (int) Math.min(n, size > 0 ? size : n);
        boolean avif = false, heic = false;
        for (int i = 8; i + 4 <= end; i += 4) {
            // 先頭の 4 バイトは主なブランド、そのあと 4 バイト (バージョン) を挟んで、互換ブランドが並ぶ
            if (i == 12) continue;
            final String brand = new String(h, i, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            switch (brand) {
                case "avis": case "msf1": case "hevc": case "hevx": return null;
                case "avif": avif = true; break;
                case "heic": case "heix": case "heim": case "heis": case "mif1": heic = true; break;
                default: break;
            }
        }
        return avif ? Format.AVIF : (heic ? Format.HEIC : null);
    }

    private static Format detectByExtension(byte[] h, int n, String extension) {
        if (extension == null) {
            return null;
        }
        switch (extension) {
            case "tga": case "icb": case "vda": case "vst": case "targa":
                return n >= 18 ? Format.TGA : null;
            case "svg":
                // 先頭に長いコメントなどがあって、最初の 512 バイトに <svg が出てこない SVG
                return n > 0 && h[0] != '{' && (h[0] == '<' || (h[0] & 0xFF) == 0xEF || (h[0] & 0xFF) <= ' ') ? Format.SVG : null;
            case "svgz":
                return SvgDecoder.isGzip(h, n) ? Format.SVG : null;
            case "wbmp":
                return Format.WBMP;
            case "xbm":
                return Format.XBM;
            case "pgx":
                return FfmpegImage.isAvailable() ? Format.PGX : null;
            case "xwd":
                return FfmpegImage.isAvailable() ? Format.XWD : null;
            default:
                return null;
        }
    }

    // 「https://host/a.tga?x=1#page=2」や「C:\dir\a.TGA」から、小文字の拡張子を取り出す。なければ null
    static String extensionOf(String name) {
        if (name == null) return null;
        String text = name;
        final int fragment = text.indexOf('#');
        if (fragment >= 0) text = text.substring(0, fragment);
        final int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        text = text.substring(slash + 1);
        final int dot = text.lastIndexOf('.');
        if (dot < 0 || dot == text.length() - 1) return null;
        return text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 画像として読む。
     *
     * @return PNG と GIF は呼び出し側が読むので null
     */
    public static DecodedImage decode(byte[] content, String name) throws IOException {
        if (content == null) throw new IOException("No content");
        final Format format = detect(content, Math.min(content.length, 512), name);
        if (format == null) return null;
        if (format == Format.PNG || format == Format.GIF) return null;
        try {
            return decodeNative(format, content);
        } catch (IOException | RuntimeException e) {
            // 自分のデコーダが対応していない種類のファイルは、FFmpeg にやり直させる
            if (format.ffmpegFallback && !needsFfmpeg(format) && FfmpegImage.isAvailable()) {
                try {
                    return FfmpegImage.decode(content, format.extension);
                } catch (IOException second) {
                    MarumaSign.LOGGER.info("FFmpeg could not read the " + format + " image either (" + second.getMessage() + ")");
                }
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Invalid " + format + " image: " + e, e);
        }
    }

    private static DecodedImage decodeNative(Format format, byte[] d) throws IOException {
        switch (format) {
            case JPEG:
                return JpegDecoder.decode(d);
            case BMP: {
                BmpDecoder.checkHeader(d);
                BufferedImage image = null;
                try {
                    image = ImageBytes.read(d);
                } catch (IOException | RuntimeException e) {
                    // ImageIO が読めない種類の BMP は、自分で読む
                }
                if (image != null) {
                    FormatUtil.checkSize(image.getWidth(), image.getHeight());
                    return FormatUtil.still(image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
                }
                return BmpDecoder.decodeFile(d);
            }
            case WEBP: {
                final DecodedImage animated = WebpDecoder.decodeAnimated(d);
                if (animated != null) return animated;
                return fromImage(WebpDecoder.decode(d));
            }
            case DDS:
                return fromImage(DdsDecoder.decode(d));
            case KTX2:
                return fromImage(Ktx2Decoder.decode(d));
            case TIFF:
                return TiffDecoder.decode(d);
            case ICO:
                return IcoDecoder.decode(d);
            case ANI:
                return IcoDecoder.decodeAni(d);
            case PSD:
                return PsdDecoder.decode(d);
            case QOI:
                return QoiDecoder.decode(d);
            case PNM:
                return PnmDecoder.decode(d);
            case HDR:
                return HdrDecoder.decode(d);
            case SGI:
                return RasterDecoders.decodeSgi(d);
            case SUN:
                return RasterDecoders.decodeSun(d);
            case FARBFELD:
                return RasterDecoders.decodeFarbfeld(d);
            case ICNS:
                return IcnsDecoder.decode(d);
            case FITS:
                return RasterDecoders.decodeFits(d);
            case XPM:
                return TextImages.decodeXpm(d);
            case XBM:
                return TextImages.decodeXbm(d);
            case SVG:
                return SvgDecoder.decode(d);
            case EXR:
                return ExrDecoder.decode(d);
            case TGA:
                return TgaDecoder.decode(d);
            case PCX:
                return PcxDecoder.decode(d);
            case WBMP:
                return RasterDecoders.decodeWbmp(d);
            default:
                // FFmpeg でしか読めない形式
                return FfmpegImage.decode(d, format.extension);
        }
    }

    private static DecodedImage fromImage(BufferedImage image) throws IOException {
        if (image == null) throw new IOException("Not an image");
        FormatUtil.checkSize(image.getWidth(), image.getHeight());
        return FormatUtil.still(image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
    }
}
