package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.font.PdfFont;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 1 つの文書の中で、ページをまたいで使い回すもの (フォントと、表示する内容の層の設定)。
 */
public final class DocumentCache {

    // 覚えておくフォントの数の上限
    private static final int MAX_FONTS = 512;

    private final PdfDocument document;
    private final Map<PdfDict, PdfFont> fonts = new IdentityHashMap<>();
    private final Map<PdfDict, Boolean> brokenFonts = new IdentityHashMap<>();
    // 表示しない層 (OCG)
    private Map<PdfDict, Boolean> hiddenGroups;

    public DocumentCache(PdfDocument document) {
        this.document = document;
    }

    PdfDocument document() {
        return document;
    }

    PdfFont font(PdfDict dict) {
        final PdfFont cached = fonts.get(dict);
        if (cached != null) return cached;
        if (brokenFonts.containsKey(dict)) return null;
        try {
            final PdfFont font = PdfFont.create(dict);
            if (fonts.size() >= MAX_FONTS) fonts.clear();
            fonts.put(dict, font);
            return font;
        } catch (IOException | RuntimeException e) {
            brokenFonts.put(dict, Boolean.TRUE);
            return null;
        }
    }

    /**
     * 層 (OCG) が表示されないものか。既定の設定 (/OCProperties の /D) に従う。
     */
    boolean isHidden(PdfDict group) {
        if (hiddenGroups == null) {
            hiddenGroups = new IdentityHashMap<>();
            final PdfDict catalog = document.catalog();
            final PdfDict properties = catalog == null ? null : catalog.dict("OCProperties");
            final PdfDict config = properties == null ? null : properties.dict("D");
            if (config != null) {
                final boolean baseOff = config.isName("BaseState", "OFF");
                if (baseOff) {
                    final PdfArray all = properties.array("OCGs");
                    for (int i = 0; all != null && i < all.size(); i++) {
                        final PdfDict g = all.dict(i);
                        if (g != null) hiddenGroups.put(g, Boolean.TRUE);
                    }
                    final PdfArray on = config.array("ON");
                    for (int i = 0; on != null && i < on.size(); i++) {
                        final PdfDict g = on.dict(i);
                        if (g != null) hiddenGroups.remove(g);
                    }
                } else {
                    final PdfArray off = config.array("OFF");
                    for (int i = 0; off != null && i < off.size(); i++) {
                        final PdfDict g = off.dict(i);
                        if (g != null) hiddenGroups.put(g, Boolean.TRUE);
                    }
                }
            }
        }
        return hiddenGroups.containsKey(group);
    }

    /**
     * 内容の層の指定 (OCG か OCMD) が、表示しないものか。
     */
    boolean isHiddenContent(PdfDict properties) {
        return isHiddenContent(properties, 0);
    }

    private boolean isHiddenContent(PdfDict properties, int depth) {
        if (properties == null || depth > 16) return false;
        if (properties.isName("Type", "OCMD")) {
            final PdfArray expression = properties.array("VE");
            if (expression != null) return !visible(expression, depth + 1);
            final Object groups = properties.get("OCGs");
            final String policy = properties.name("P") == null ? "AnyOn" : properties.name("P");
            int count = 0, on = 0;
            if (groups instanceof PdfDict) {
                count = 1;
                on = isHidden((PdfDict) groups) ? 0 : 1;
            } else if (groups instanceof PdfArray) {
                final PdfArray list = (PdfArray) groups;
                for (int i = 0; i < list.size(); i++) {
                    final PdfDict g = list.dict(i);
                    if (g == null) continue;
                    count++;
                    if (!isHidden(g)) on++;
                }
            }
            if (count == 0) return false;
            switch (policy) {
                case "AllOn":
                    return on != count;
                case "AnyOff":
                    return on == count;
                case "AllOff":
                    return on != 0;
                default:
                    return on == 0;
            }
        }
        return isHidden(properties);
    }

    // 表示の式 [/And ...] [/Or ...] [/Not ...] の値
    private boolean visible(PdfArray expression, int depth) {
        if (depth > 16 || expression.size() == 0) return true;
        final String op = expression.name(0);
        if (op == null) return true;
        boolean result = op.equals("And");
        for (int i = 1; i < expression.size(); i++) {
            final Object item = expression.get(i);
            final boolean value = item instanceof PdfArray ? visible((PdfArray) item, depth + 1)
                    : !(item instanceof PdfDict) || !isHidden((PdfDict) item);
            if (op.equals("Not")) return !value;
            if (op.equals("And")) result &= value;
            else result |= value;
        }
        return result;
    }
}
