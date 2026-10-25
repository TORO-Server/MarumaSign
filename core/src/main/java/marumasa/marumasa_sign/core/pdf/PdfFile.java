package marumasa.marumasa_sign.core.pdf;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import org.apache.pdfbox.io.MemoryUsageSetting;
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
 * 埋め込まれていないフォントの代わりは、SystemFonts の一覧から探す (PDFBox の標準の一覧は、作るのに時間がかかるため)。
 */
public final class PdfFile {

    // ページを画像にするときの、長い辺の大きさ (ピクセル)
    public static final int PAGE_SIZE = 1024;
    // 読み込む PDF の大きさの上限
    public static final int MAX_SIZE = 64 * 1024 * 1024;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;
    // ファイルの大きさ (バイト)
    private final long size;

    private PdfFile(PDDocument document, long size) throws IOException {
        this.document = document;
        this.size = size;
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

    // PDF の中の圧縮されたデータを展開するのに使ってよいメモリの上限。
    // 小さなファイルが展開すると何 GB にもなる (圧縮爆弾) PDF で、ゲームのメモリを使い切らないようにする
    private static final long MAX_SCRATCH = 256L * 1024 * 1024;

    private static MemoryUsageSetting memory() {
        return MemoryUsageSetting.setupMainMemoryOnly(MAX_SCRATCH);
    }

    public static PdfFile open(byte[] content) throws IOException {
        SystemFonts.install();
        try {
            return new PdfFile(PDDocument.load(content, "", null, null, memory()), content.length);
        } catch (RuntimeException e) {
            // 壊れたファイルでは、読み込みの途中で想定外の例外が出ることがある
            throw new IOException("Not a readable PDF", e);
        }
    }

    public static PdfFile open(File file) throws IOException {
        if (file.length() > MAX_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
        SystemFonts.install();
        try {
            return new PdfFile(PDDocument.load(file, memory()), file.length());
        } catch (RuntimeException e) {
            throw new IOException("Not a readable PDF", e);
        }
    }

    public int pageCount() {
        return pageCount;
    }

    /**
     * URL やファイルの名前の末尾の「#page=ページ番号」から、最初に表示するページ (0 から始まる) を求める。
     * ブラウザの PDF ビューアと同じ書き方 (RFC 8118) で、「#page=3&zoom=50」のようにほかの指定と並んでいてもよい。
     * 指定がないか、正しくない値なら 0 (最初のページ)。ページの数は見ないので、範囲に収めるのは {@link #clampPage} で行う
     */
    public static int requestedPage(String url) {
        if (url == null) return 0;
        final int hash = url.indexOf('#');
        if (hash < 0) return 0;
        for (String parameter : url.substring(hash + 1).split("&")) {
            final int equals = parameter.indexOf('=');
            if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("page")) continue;
            // 「007」のような先頭の 0 は数の大きさに関係しないので除く
            final String value = parameter.substring(equals + 1).trim().replaceFirst("^0+(?=\\d)", "");
            if (value.isEmpty() || !isDigits(value)) return 0;
            // int に収まらないほど大きな値は、最後のページを指したものとして扱う
            if (value.length() > 9) return Integer.MAX_VALUE;
            final int number = Integer.parseInt(value);
            return number >= 1 ? number - 1 : 0;
        }
        return 0;
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }

    // ページ番号 (0 から始まる) を、このファイルにあるページの範囲に収める。ブラウザと同じく、多すぎれば最後のページ
    public int clampPage(int index) {
        return Math.max(0, Math.min(pageCount - 1, index));
    }

    public long size() {
        return size;
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
        return new DecodedImage(width, height, new int[][]{ArgbPixels.read(image)}, new int[]{1}, 0);
    }

    public synchronized void close() {
        try {
            document.close();
        } catch (IOException e) {
            // 閉じられなくても、メモリ上のファイルなので問題はない
        }
    }
}
