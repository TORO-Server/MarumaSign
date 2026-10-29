package marumasa.marumasa_sign.core.pdf.font;

/**
 * Unicode の文字でグリフを探せるフォント (代わりのフォントに使う)。
 */
interface UnicodeFont extends FontProgram {

    /**
     * 文字のグリフの番号。なければ 0。
     */
    int unicodeLookup(int codePoint);
}
