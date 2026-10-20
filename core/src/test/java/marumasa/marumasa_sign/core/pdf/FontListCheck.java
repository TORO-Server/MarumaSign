package marumasa.marumasa_sign.core.pdf;

import org.apache.pdfbox.pdmodel.font.FontCache;
import org.apache.pdfbox.pdmodel.font.FontInfo;
import org.apache.pdfbox.pdmodel.font.FontProvider;
import org.apache.pdfbox.pdmodel.font.PDPanoseClassification;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SystemFonts が作るフォントの一覧が、PDFBox の標準の一覧 (FileSystemFontProvider) と同じ内容・同じ順番になるかを確かめる。
 * 前に読んだ結果を使わない場合と、使う場合の両方で比べる。1 つでも違えば終了コード 1 で終わる。
 * PDFBox の一覧はすべてのフォントファイルを最後まで読むので、フォントの多いパソコンでは 1 分ほどかかる。
 */
public final class FontListCheck {

    public static void main(String[] args) throws Exception {
        final File directory = Files.createTempDirectory("marumasign-font-check").toFile();
        // PDFBox は一覧をホームフォルダに保存するので、確認の間だけ別の場所にする
        System.setProperty("pdfbox.fontcache", directory.getPath());
        final File cache = new File(directory, "pdf-fonts.cache");
        int failures = 0;
        try {
            long start = System.nanoTime();
            final List<String> reference = describe(pdfboxProvider().getFontInfo());
            System.out.println("PDFBox: " + reference.size() + " fonts in " + (System.nanoTime() - start) / 1000000 + " ms");

            for (String pass : new String[]{"without the saved list", "with the saved list"}) {
                start = System.nanoTime();
                final List<String> actual = describe(SystemFonts.create(cache).getFontInfo());
                System.out.println("SystemFonts " + pass + ": " + actual.size() + " fonts in "
                        + (System.nanoTime() - start) / 1000000 + " ms");
                final int count = Math.max(reference.size(), actual.size());
                int differences = 0;
                for (int i = 0; i < count; i++) {
                    final String expected = i < reference.size() ? reference.get(i) : "(none)";
                    final String found = i < actual.size() ? actual.get(i) : "(none)";
                    if (!expected.equals(found)) {
                        if (differences++ < 5) System.out.println("  #" + i + " PDFBox: " + expected + "\n  #" + i + " ours:   " + found);
                    }
                }
                if (differences > 0) {
                    failures++;
                    System.out.println("FAIL: " + differences + " differences " + pass);
                } else {
                    System.out.println("ok: the same " + count + " fonts in the same order " + pass);
                }
            }
        } finally {
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
        if (failures > 0) System.exit(1);
    }

    // PDFBox の標準の一覧。公開されていないクラスなので、reflection で作る
    private static FontProvider pdfboxProvider() throws Exception {
        final Class<?> type = Class.forName("org.apache.pdfbox.pdmodel.font.FileSystemFontProvider");
        final Constructor<?> constructor = type.getDeclaredConstructor(FontCache.class);
        constructor.setAccessible(true);
        return (FontProvider) constructor.newInstance(new FontCache());
    }

    // 代わりのフォントを選ぶのに使われる項目を、1 行の文字列にする
    private static List<String> describe(List<? extends FontInfo> fonts) {
        final List<String> result = new ArrayList<String>();
        for (FontInfo info : fonts) {
            // PDFBox は、読めなかったファイルを "*skipexception*" などの名前で一覧に残す (読み直さないための印で、代わりのフォントには選ばれない)
            if (info.getPostScriptName() != null && info.getPostScriptName().startsWith("*skip")) continue;
            final PDPanoseClassification panose = info.getPanose();
            result.add(String.format(Locale.ROOT, "%s %s ros=%s family=%d weight=%d codePages=%08x,%08x mac=%d panose=%s",
                    info.getPostScriptName(), info.getFormat(),
                    info.getCIDSystemInfo() == null ? "-" : info.getCIDSystemInfo().getRegistry() + "-"
                            + info.getCIDSystemInfo().getOrdering() + "-" + info.getCIDSystemInfo().getSupplement(),
                    info.getFamilyClass(), info.getWeightClass(), info.getCodePageRange1(), info.getCodePageRange2(),
                    info.getMacStyle(), panose == null ? "-" : Arrays.toString(panose.getBytes())));
        }
        return result;
    }
}
