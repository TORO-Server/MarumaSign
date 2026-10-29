package marumasa.marumasa_sign.core.pdf.font;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;

/**
 * パソコンのフォントの一覧から代わりが見つからないときの、最後の手段のフォント。
 * Java が持つ論理フォント (SansSerif など) の輪郭を使う。グリフの番号は Unicode のコードポイントとする。
 * 読むのはパソコンに入っているフォントだけで、PDF の中のデータは Java のフォントの処理 (ネイティブのコード) には渡さない。
 */
final class AwtFallbackFont implements UnicodeFont {

    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    // em の大きさ。輪郭はこの大きさで作り、1 em = 1 に縮める
    private static final float SIZE = 1000;

    private final Font font;

    AwtFallbackFont(String family, int style) {
        this.font = new Font(family, style, (int) SIZE);
    }

    @Override
    public int glyphCount() {
        return Character.MAX_CODE_POINT + 1;
    }

    @Override
    public synchronized GeneralPath path(int codePoint) {
        final GeneralPath path = new GeneralPath();
        if (codePoint <= 0 || !Character.isValidCodePoint(codePoint)) return path;
        try {
            final GlyphVector vector = font.createGlyphVector(CONTEXT, new String(Character.toChars(codePoint)));
            path.append(vector.getOutline(), false);
            // Java の座標は y が下向きなので、上下を反転して 1 em = 1 にする
            path.transform(new AffineTransform(1 / SIZE, 0, 0, -1 / SIZE, 0, 0));
        } catch (RuntimeException | Error e) {
            // フォントが使えない環境 (フォントのないサーバーなど) では、何も描かない
            if (e instanceof VirtualMachineError) throw (VirtualMachineError) e;
        }
        return path;
    }

    @Override
    public synchronized double advance(int codePoint) {
        if (codePoint <= 0 || !Character.isValidCodePoint(codePoint)) return 0;
        try {
            return font.createGlyphVector(CONTEXT, new String(Character.toChars(codePoint))).getGlyphMetrics(0).getAdvance() / SIZE;
        } catch (RuntimeException | Error e) {
            if (e instanceof VirtualMachineError) throw (VirtualMachineError) e;
            return 0;
        }
    }

    @Override
    public int gidForName(String name) {
        final String text = FontData.unicode(name, false);
        return text == null || text.isEmpty() ? -1 : text.codePointAt(0);
    }

    @Override
    public String nameForGid(int gid) {
        return FontData.glyphName(gid);
    }

    @Override
    public String postScriptName() {
        return font.getFontName();
    }

    @Override
    public int unicodeLookup(int codePoint) {
        try {
            return font.canDisplay(codePoint) ? codePoint : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
