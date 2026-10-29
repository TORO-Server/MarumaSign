package marumasa.marumasa_sign.core.pdf.cos;

import java.nio.charset.StandardCharsets;

/**
 * PDF の文字列。中身はバイト列のまま持つ (文字コードは使う場所によって違う)。
 */
public final class PdfString {

    public final byte[] bytes;

    public PdfString(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * 文字として読む。先頭に UTF-16 の BOM があれば UTF-16、なければ PDFDocEncoding (ほぼ ISO-8859-1)。
     * 表示には使わず、名前の比較や記録に使う程度なので、PDFDocEncoding の 0x80 から先の違いは気にしない。
     */
    public String text() {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        return "(" + text() + ")";
    }
}
