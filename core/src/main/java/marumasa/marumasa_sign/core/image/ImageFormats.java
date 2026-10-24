package marumasa.marumasa_sign.core.image;

import marumasa.marumasa_sign.core.image.format.StillFormats;

import java.io.IOException;

/**
 * 静止画の形式の窓口。
 * 先頭のバイト列 (と名前の拡張子) から形式を見分け、読めるものはピクセルにする。
 * 実際の読み込みは image.format パッケージのデコーダが行う。
 */
public final class ImageFormats {

    private ImageFormats() {
    }

    /**
     * 先頭のバイト列が、読める静止画の形式かどうか。
     *
     * @param name ファイル名や URL。署名のない形式 (TGA など) の判定に使う。なければ null
     */
    public static boolean isSupported(byte[] head, int length, String name) {
        return StillFormats.detect(head, length, name) != null;
    }

    /**
     * 画像として読む。
     *
     * @return PNG と GIF は ImageLoader が読むので、そのときと、読める形式ではなかったときは null
     */
    public static DecodedImage decode(byte[] content, String name) throws IOException {
        return StillFormats.decode(content, name);
    }
}
