package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ブロックの形とテクスチャ (Minecraft のリソース) を読む。
 * Mojang が配布しているクライアントの jar (決まった版) から読む。どのバージョンの Minecraft で表示しても同じ見た目になるように、
 * 実行中の Minecraft のリソースは使わない。
 * jar は、ゲームフォルダの versions にあればそれを使い、なければ Mojang のサーバーから marumasign/assets にダウンロードする。
 */
public final class GameAssets {

    // 使う版。ブロックの性質の表 (blocks.txt.gz) と同じ版にする
    public static final String VERSION = "26.3";
    private static final String SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87";
    private static final long SIZE = 41483720L;
    private static final String URL = "https://piston-data.mojang.com/v1/objects/" + SHA1 + "/client.jar";

    private static volatile GameAssets instance;
    private static final Object LOCK = new Object();

    private final ZipFile zip;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<String, byte[]>();
    private static final byte[] MISSING = new byte[0];

    private GameAssets(ZipFile zip) {
        this.zip = zip;
    }

    /**
     * リソースを読めるようにする。初めてのときは jar を探すかダウンロードするので、時間がかかる。
     * メインスレッドからは呼ばないこと。
     */
    public static GameAssets get() throws IOException {
        GameAssets assets = instance;
        if (assets != null) return assets;
        synchronized (LOCK) {
            if (instance == null) instance = new GameAssets(new ZipFile(locate()));
            return instance;
        }
    }

    private static File gameDirectory() {
        final String override = System.getProperty("marumasign.gameDir");
        if (override != null) return new File(override);
        if (MarumaSign.platform() != null) return MarumaSign.platform().gameDirectory();
        return new File(".");
    }

    private static File locate() throws IOException {
        final String override = System.getProperty("marumasign.assetJar");
        if (override != null) {
            final File file = new File(override);
            if (!file.isFile()) throw new IOException("Asset jar not found: " + override);
            return file;
        }
        final File game = gameDirectory();
        final File directory = new File(game, "marumasign/assets");
        final File target = new File(directory, "client-" + VERSION + ".jar");
        final File verified = new File(directory, "client-" + VERSION + ".verified");
        if (target.isFile() && target.length() == SIZE && verified.isFile()) return target;

        // ランチャーがすでに同じ版を持っていれば、それを写す
        File parent = game.getAbsoluteFile();
        for (int depth = 0; depth < 3 && parent != null; depth++, parent = parent.getParentFile()) {
            final File candidate = new File(parent, "versions/" + VERSION + "/" + VERSION + ".jar");
            if (candidate.isFile() && candidate.length() == SIZE && SHA1.equals(sha1(candidate))) {
                copy(candidate, target, verified);
                return target;
            }
        }
        if (target.isFile() && target.length() == SIZE && SHA1.equals(sha1(target))) {
            markVerified(verified);
            return target;
        }
        download(target, verified);
        return target;
    }

    private static void copy(File source, File target, File verified) throws IOException {
        target.getParentFile().mkdirs();
        final File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        final InputStream input = new FileInputStream(source);
        try {
            write(input, temporary, null);
        } finally {
            input.close();
        }
        replace(temporary, target);
        markVerified(verified);
    }

    private static void download(File target, File verified) throws IOException {
        MarumaSign.LOGGER.info("Downloading block textures (Minecraft " + VERSION + " client, " + SIZE / (1024 * 1024) + " MB)");
        target.getParentFile().mkdirs();
        final File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        final HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (MarumaSign)");
        final MessageDigest digest = sha1Digest();
        try {
            if (connection.getResponseCode() / 100 != 2) throw new IOException("HTTP " + connection.getResponseCode());
            final InputStream input = connection.getInputStream();
            try {
                write(input, temporary, digest);
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
        if (temporary.length() != SIZE || !SHA1.equals(hex(digest.digest()))) {
            temporary.delete();
            throw new IOException("Downloaded client jar does not match the expected checksum");
        }
        replace(temporary, target);
        markVerified(verified);
        MarumaSign.LOGGER.info("Downloaded block textures");
    }

    private static void write(InputStream input, File file, MessageDigest digest) throws IOException {
        final OutputStream output = new FileOutputStream(file);
        try {
            final byte[] buffer = new byte[65536];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > SIZE) throw new IOException("Client jar is larger than expected");
                output.write(buffer, 0, read);
                if (digest != null) digest.update(buffer, 0, read);
            }
        } finally {
            output.close();
        }
    }

    private static void replace(File temporary, File target) throws IOException {
        target.delete();
        if (!temporary.renameTo(target)) throw new IOException("Could not move " + temporary + " to " + target);
    }

    private static void markVerified(File verified) {
        try {
            new FileOutputStream(verified).close();
        } catch (IOException e) {
            // 次にもう一度確かめるだけ
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha1(File file) throws IOException {
        final MessageDigest digest = sha1Digest();
        final InputStream input = new FileInputStream(file);
        try {
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        final StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format("%02x", b & 0xFF));
        return builder.toString();
    }

    /**
     * jar に入っていないリソース (ランチャーが別にダウンロードするもの。Unifont など) を読む。
     * ランチャーの assets フォルダにあればそれを、なければ Mojang のサーバーから marumasign/assets にダウンロードする。
     * メインスレッドからは呼ばないこと。
     */
    static byte[] object(String hash, long size) throws IOException {
        final File game = gameDirectory().getAbsoluteFile();
        final String relative = "objects/" + hash.substring(0, 2) + "/" + hash;
        // 確認用: ランチャーの assets/objects の場所を指定する
        final String objects = System.getProperty("marumasign.assetObjects");
        if (objects != null) {
            final File candidate = new File(objects, hash.substring(0, 2) + "/" + hash);
            if (candidate.isFile() && candidate.length() == size) {
                final byte[] bytes = java.nio.file.Files.readAllBytes(candidate.toPath());
                if (hash.equals(hex(sha1Digest().digest(bytes)))) return bytes;
            }
        }
        File parent = game;
        for (int depth = 0; depth < 3 && parent != null; depth++, parent = parent.getParentFile()) {
            final File candidate = new File(parent, "assets/" + relative);
            if (candidate.isFile() && candidate.length() == size) {
                final byte[] bytes = java.nio.file.Files.readAllBytes(candidate.toPath());
                if (hash.equals(hex(sha1Digest().digest(bytes)))) return bytes;
            }
        }
        final File cached = new File(game, "marumasign/assets/" + hash);
        if (cached.isFile() && cached.length() == size) {
            final byte[] bytes = java.nio.file.Files.readAllBytes(cached.toPath());
            if (hash.equals(hex(sha1Digest().digest(bytes)))) return bytes;
        }
        final HttpURLConnection connection = (HttpURLConnection) new URL("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (MarumaSign)");
        final byte[] bytes;
        try {
            if (connection.getResponseCode() / 100 != 2) throw new IOException("HTTP " + connection.getResponseCode());
            final InputStream input = connection.getInputStream();
            try {
                final ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
                final byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > size) throw new IOException("Asset is larger than expected");
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
        if (bytes.length != size || !hash.equals(hex(sha1Digest().digest(bytes)))) throw new IOException("Downloaded asset does not match the expected checksum");
        cached.getParentFile().mkdirs();
        final File temporary = new File(cached.getParentFile(), hash + ".tmp");
        final OutputStream output = new FileOutputStream(temporary);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
        replace(temporary, cached);
        return bytes;
    }

    // jar の中のファイルを読む (assets/minecraft/... など)。なければ null
    public byte[] read(String path) throws IOException {
        final byte[] cached = cache.get(path);
        if (cached != null) return cached == MISSING ? null : cached;
        final ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            cache.put(path, MISSING);
            return null;
        }
        final InputStream input = zip.getInputStream(entry);
        final byte[] bytes;
        try {
            final ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(256, entry.getSize()));
            final byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            bytes = output.toByteArray();
        } finally {
            input.close();
        }
        cache.put(path, bytes);
        return bytes;
    }

    // 名前空間付きの名前 (minecraft:block/stone) を、jar の中の位置にする
    static String path(String id, String folder, String extension) {
        String namespace = "minecraft";
        String name = id;
        final int colon = id.indexOf(':');
        if (colon >= 0) {
            namespace = id.substring(0, colon);
            name = id.substring(colon + 1);
        }
        return "assets/" + namespace + "/" + folder + "/" + name + extension;
    }
}
