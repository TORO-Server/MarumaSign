package marumasa.marumasa_sign.core.pdf.font;

import java.awt.geom.GeneralPath;

/**
 * フォントの中身 (TrueType、CFF、Type 1)。グリフの番号から輪郭を作る。
 */
public interface FontProgram {

    int glyphCount();

    /**
     * グリフの輪郭。単位は em (1 em = 1.0、フォントの行列をかけたもの)。ないグリフや壊れたグリフは空の輪郭。
     */
    GeneralPath path(int gid);

    /**
     * グリフの送り幅 (em)。
     */
    double advance(int gid);

    /**
     * 名前のグリフの番号。なければ -1。
     */
    int gidForName(String name);

    /**
     * 番号のグリフの名前。分からなければ null。
     */
    String nameForGid(int gid);

    String postScriptName();
}
