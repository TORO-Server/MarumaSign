package marumasa.marumasa_sign.core.pdf.cos;

import marumasa.marumasa_sign.core.pdf.filter.Filters;

import java.io.IOException;

/**
 * PDF のストリーム (辞書と、圧縮されたままのデータ)。暗号化されたファイルでは、データは復号したもの。
 */
public final class PdfStream {

    public final PdfDict dict;
    // 圧縮されたままのデータ (/Filter を戻す前)
    private final byte[] encoded;

    public PdfStream(PdfDict dict, byte[] encoded) {
        this.dict = dict;
        this.encoded = encoded;
    }

    public byte[] encoded() {
        return encoded;
    }

    /**
     * /Filter をすべて戻したデータ。
     *
     * @param limit 戻したデータの大きさの上限 (バイト)。超えたら IOException (圧縮爆弾でメモリを使い切らないように)
     */
    public byte[] decode(int limit) throws IOException {
        return Filters.decode(encoded, dict, limit, false);
    }

    /**
     * 画像のための戻し方。DCTDecode などの画像の圧縮は戻さずに残す (戻すのは画像を作る側)。
     */
    public byte[] decodeExceptImage(int limit) throws IOException {
        return Filters.decode(encoded, dict, limit, true);
    }

    @Override
    public String toString() {
        return "stream " + dict + " (" + encoded.length + " bytes)";
    }
}
