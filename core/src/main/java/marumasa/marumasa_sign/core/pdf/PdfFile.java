package marumasa.marumasa_sign.core.pdf;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.render.DocumentCache;
import marumasa.marumasa_sign.core.pdf.render.PageRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * PDF ファイル 1 つ。ページを画像にする。
 * PDF の読み込み (cos) と描画 (render) は、ライブラリを使わずにこの Mod の中で行う。
 * 1 つのファイルを同時に複数のスレッドから描かないよう、操作はすべて同期する。
 */
public final class PdfFile {

    // ページを画像にするときの、長い辺の大きさ (ピクセル)
    public static final int PAGE_SIZE = 1024;
    // 読み込む PDF の大きさの上限
    public static final int MAX_SIZE = 64 * 1024 * 1024;

    // 1 ページを描く時間の上限 (ミリ秒)。超えたら、そこまでに描いた絵を使う
    private static final long RENDER_TIMEOUT = 20000;

    private PdfDocument document;
    private DocumentCache cache;
    private final int pageCount;
    // ファイルの大きさ (バイト)
    private final long size;

    private PdfFile(PdfDocument document, long size) throws IOException {
        this.document = document;
        this.cache = new DocumentCache(document);
        this.size = size;
        this.pageCount = document.pages().size();
        if (pageCount <= 0) throw new IOException("The PDF has no pages");
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
        if (content.length > MAX_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
        try {
            return new PdfFile(new PdfDocument(content), content.length);
        } catch (RuntimeException | StackOverflowError e) {
            // 想定外の例外も、読めない PDF として扱う
            throw new IOException("Not a readable PDF", e);
        }
    }

    public static PdfFile open(File file) throws IOException {
        if (file.length() > MAX_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
        return open(Files.readAllBytes(file.toPath()));
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
        if (document == null) throw new IOException("The PDF is closed");
        if (index < 0 || index >= pageCount) throw new IOException("No page " + (index + 1));
        final PdfDocument.Page page = document.pages().get(index);
        final double[] box = cropBox(page);
        final double longest = Math.max(box[2] - box[0], box[3] - box[1]);
        final double scale = longest > 0 ? size / longest : 1;
        final BufferedImage image;
        try {
            image = PageRenderer.render(cache, page, scale, RENDER_TIMEOUT);
        } catch (RuntimeException | StackOverflowError e) {
            throw new IOException("Failed to draw page " + (index + 1), e);
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) throw new IOException("Empty page " + (index + 1));
        return new DecodedImage(width, height, new int[][]{ArgbPixels.read(image)}, new int[]{1}, 0);
    }

    // ページの切り抜く範囲 (CropBox と MediaBox の重なり)
    private static double[] cropBox(PdfDocument.Page page) {
        final double[] media = page.mediaBox != null ? page.mediaBox : new double[]{0, 0, 612, 792};
        final double[] crop = page.cropBox;
        if (crop == null) return media;
        final double[] r = {Math.max(crop[0], media[0]), Math.max(crop[1], media[1]), Math.min(crop[2], media[2]), Math.min(crop[3], media[3])};
        return r[2] > r[0] && r[3] > r[1] ? r : media;
    }

    public synchronized void close() {
        // メモリ上のファイルなので、参照を捨てるだけでよい
        document = null;
        cache = null;
    }
}
