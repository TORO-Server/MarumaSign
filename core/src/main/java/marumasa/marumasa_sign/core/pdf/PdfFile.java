package marumasa.marumasa_sign.core.pdf;

import marumasa.marumasa_sign.core.image.DecodedImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * PDF ファイル 1 つ。ページを画像にする。
 * PDF の読み込みと描画には PDFBox を使う (配布する jar の中では別のパッケージに移してある)。
 * 1 つのファイルを同時に複数のスレッドから描かないよう、操作はすべて同期する。
 * 埋め込まれていないフォントを使う PDF を初めて描くとき、PDFBox はパソコンのフォントをすべて調べて、
 * その結果をホームフォルダ (.pdfbox.cache) に保存する。フォントが多いと時間がかかるが、2 回目からは速い。
 */
public final class PdfFile {

    // ページを画像にするときの、長い辺の大きさ (ピクセル)
    public static final int PAGE_SIZE = 1024;
    // 読み込む PDF の大きさの上限
    public static final int MAX_SIZE = 64 * 1024 * 1024;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;

    private PdfFile(PDDocument document) throws IOException {
        this.document = document;
        this.pageCount = document.getNumberOfPages();
        if (pageCount <= 0) {
            document.close();
            throw new IOException("The PDF has no pages");
        }
        this.renderer = new PDFRenderer(document);
        // 大きな画像は、表示する大きさに合わせて間引いて読む (速く、使うメモリも少ない)
        renderer.setSubsamplingAllowed(true);
    }

    // 先頭のバイト列が PDF らしいかどうか
    public static boolean isPdf(byte[] head, int length) {
        // 仕様では先頭に置くが、前に余計なバイトが付いたファイルも多いので、先頭の近くから探す
        final int limit = Math.min(length, 1024) - 5;
        for (int i = 0; i <= limit; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F' && head[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    public static PdfFile open(byte[] content) throws IOException {
        try {
            return new PdfFile(PDDocument.load(content));
        } catch (RuntimeException e) {
            // 壊れたファイルでは、読み込みの途中で想定外の例外が出ることがある
            throw new IOException("Not a readable PDF", e);
        }
    }

    public static PdfFile open(File file) throws IOException {
        if (file.length() > MAX_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
        try {
            return new PdfFile(PDDocument.load(file));
        } catch (RuntimeException e) {
            throw new IOException("Not a readable PDF", e);
        }
    }

    public int pageCount() {
        return pageCount;
    }

    /**
     * ページを画像にする。長い辺が size ピクセルになる大きさで描く。
     *
     * @param index 0 から始まるページ番号
     */
    public synchronized DecodedImage render(int index, int size) throws IOException {
        final PDPage page = document.getPage(index);
        final PDRectangle box = page.getCropBox();
        final float longest = Math.max(box.getWidth(), box.getHeight());
        final float scale = longest > 0 ? size / longest : 1f;
        final BufferedImage image;
        try {
            image = renderer.renderImage(index, scale, ImageType.RGB);
        } catch (RuntimeException e) {
            throw new IOException("Failed to draw page " + (index + 1), e);
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) throw new IOException("Empty page " + (index + 1));
        final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new DecodedImage(width, height, new int[][]{pixels}, new int[]{1}, 0);
    }

    public synchronized void close() {
        try {
            document.close();
        } catch (IOException e) {
            // 閉じられなくても、メモリ上のファイルなので問題はない
        }
    }
}
