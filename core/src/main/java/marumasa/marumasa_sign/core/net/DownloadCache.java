package marumasa.marumasa_sign.core.net;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * ダウンロードした看板のファイルを、ゲームのフォルダ (marumasign/cache) に残しておく。
 * 次に同じ URL を読むときは、サーバーに「前に受け取ったものから変わったか」だけを問い合わせ (ETag / Last-Modified)、
 * 変わっていなければ (304) 本体を受け取らずに、残しておいたものを使う。
 * サーバーにつながらないときも、残しておいたものを使う。
 * 問い合わせに使う情報を返さないサーバーのファイルは、変わったかを確かめられないので残さない。
 */
final class DownloadCache {

    // 残しておく合計の大きさの上限。超えたら、最後に使ったのが古いものから消す。確認のときだけ変える
    static long maxTotal = 512L * 1024 * 1024;

    private static final String HEADER = "MarumaSign download cache 1";
    private static final Object LOCK = new Object();

    // 確認のときだけ、残しておく場所を差し替える
    static File directoryOverride;

    // 残しておいたファイル 1 つ
    static final class Entry {
        final File data;
        // ファイル名か、リダイレクトの後の URL (ファイルの種類の判定に使う)
        final String name;
        final String etag, lastModified;
        final long size;

        Entry(File data, String name, String etag, String lastModified, long size) {
            this.data = data;
            this.name = name;
            this.etag = etag;
            this.lastModified = lastModified;
            this.size = size;
        }

        byte[] read() throws IOException {
            final byte[] bytes = Files.readAllBytes(data.toPath());
            if (bytes.length != size) throw new IOException("The cached file was changed");
            // 最後に使った時刻として、消す順番に使う
            data.setLastModified(System.currentTimeMillis());
            return bytes;
        }
    }

    private DownloadCache() {
    }

    private static File directory() {
        if (directoryOverride != null) return directoryOverride;
        if ("false".equals(System.getProperty("marumasign.cache"))) return null;
        return MarumaSign.platform() == null ? null : new File(MarumaSign.platform().gameDirectory(), "marumasign/cache");
    }

    // URL に対応する、残しておいたファイル。なければ null
    static Entry lookup(String url) {
        final File directory = directory();
        if (directory == null) return null;
        final String key = key(url);
        final File meta = new File(directory, key + ".meta");
        final File data = new File(directory, key + ".data");
        if (!meta.isFile() || !data.isFile()) return null;
        try {
            final List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
            // 形式: 見出し, URL, 名前, ETag, Last-Modified, 大きさ
            if (lines.size() != 6 || !HEADER.equals(lines.get(0)) || !url.equals(lines.get(1))) return null;
            final long size = Long.parseLong(lines.get(5));
            if (data.length() != size) return null;
            return new Entry(data, lines.get(2), empty(lines.get(3)), empty(lines.get(4)), size);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * ダウンロードしたファイルを残す。変わったかを問い合わせる情報 (etag か lastModified) がなければ残さない。
     * 残せなくても、ダウンロードには影響しない。
     */
    static void store(String url, String name, String etag, String lastModified, byte[] content) {
        if (etag == null && lastModified == null) return;
        // 大きなファイル 1 つで、ほかのものが全部消えないように、上限の半分より大きいものは残さない
        if (content.length > maxTotal / 2) return;
        if (!fits(url) || !fits(name) || !fits(etag) || !fits(lastModified)) return;
        final File directory = directory();
        if (directory == null) return;
        synchronized (LOCK) {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                final String key = key(url);
                final File data = new File(directory, key + ".data");
                final File meta = new File(directory, key + ".meta");
                final File temporary = new File(directory, key + ".tmp");
                // 本体を先に置き換え、最後に説明のファイルを書く (途中で止まっても、大きさが合わないものは使われない)
                meta.delete();
                Files.write(temporary.toPath(), content);
                Files.move(temporary.toPath(), data.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.write(temporary.toPath(), (HEADER + "\n" + url + "\n" + name + "\n" + (etag == null ? "" : etag) + "\n"
                        + (lastModified == null ? "" : lastModified) + "\n" + content.length + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), meta.toPath(), StandardCopyOption.REPLACE_EXISTING);
                trim(directory);
            } catch (IOException | RuntimeException e) {
                MarumaSign.LOGGER.info("Could not keep a downloaded file (" + e + ")");
            }
        }
    }

    // 合計が上限を超えていれば、最後に使ったのが古いものから消す
    private static void trim(File directory) {
        final File[] files = directory.listFiles();
        if (files == null) return;
        final List<File> data = new ArrayList<>();
        long total = 0;
        for (File file : files) {
            if (file.getName().endsWith(".data")) {
                data.add(file);
                total += file.length();
            }
        }
        if (total <= maxTotal) return;
        final File[] sorted = data.toArray(new File[0]);
        final long[] used = new long[sorted.length];
        for (int i = 0; i < sorted.length; i++) used[i] = sorted[i].lastModified();
        final Integer[] order = new Integer[sorted.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        // 時刻は先に読んでおく (並べ替えの途中で、ほかのスレッドが使って時刻が変わっても順番が崩れないように)
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(used[a], used[b]);
            }
        });
        for (Integer index : order) {
            if (total <= maxTotal) break;
            final File file = sorted[index];
            final String key = file.getName().substring(0, file.getName().length() - ".data".length());
            total -= file.length();
            new File(directory, key + ".meta").delete();
            file.delete();
        }
    }

    // 1 行に書けるか (改行を含まないか)
    private static boolean fits(String text) {
        return text == null || (text.indexOf('\n') < 0 && text.indexOf('\r') < 0);
    }

    private static String empty(String text) {
        return text.isEmpty() ? null : text;
    }

    private static String key(String url) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 はどの Java にもある
            throw new IllegalStateException(e);
        }
    }
}
