package marumasa.marumasa_sign.core.structure;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 壊れた構造物のファイルを読んでも、IOException (読めない) で終わり、ほかの例外や長い時間にならないかを確かめる。
 * 引数: 回数 ファイルまたはフォルダ...
 * 中身を展開してから、ビットを変える、切り詰める、範囲を写すなどして、もう一度圧縮して読む。
 */
public final class StructureFuzz {

    public static void main(String[] args) throws Exception {
        final int rounds = Integer.parseInt(args[0]);
        final List<File> files = new ArrayList<File>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), files);
        final Random random = new Random(1234);
        int unexpected = 0, ok = 0, rejected = 0;
        long slowest = 0;
        String slowestName = "";
        for (File file : files) {
            final byte[] original = Files.readAllBytes(file.toPath());
            final boolean gzip = original.length > 2 && (original[0] & 0xFF) == 0x1F && (original[1] & 0xFF) == 0x8B;
            final byte[] raw = gzip ? gunzip(original) : original;
            for (int round = 0; round < rounds; round++) {
                final byte[] mutated = mutate(raw, random);
                final byte[] input = gzip ? gzip(mutated) : mutated;
                final long start = System.nanoTime();
                try {
                    if (Boolean.getBoolean("fuzz.mesh")) {
                        StructureLoader.loadDetailed(input);
                    } else {
                        final Structure structure = StructureReader.read(input);
                        StructureFixer.fix(structure);
                    }
                    ok++;
                } catch (IOException e) {
                    rejected++;
                } catch (Throwable e) {
                    unexpected++;
                    System.out.println(file.getName() + " round " + round + ": " + e);
                    if (unexpected < 5) e.printStackTrace(System.out);
                }
                final long millis = (System.nanoTime() - start) / 1000000;
                if (millis > slowest) {
                    slowest = millis;
                    slowestName = file.getName() + " round " + round;
                }
            }
        }
        System.out.println(files.size() + " files: " + ok + " read, " + rejected + " rejected, " + unexpected + " unexpected errors; slowest "
                + slowest + " ms (" + slowestName + ")");
        if (unexpected > 0) System.exit(1);
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) collect(child, files);
        } else if (file.isFile()) {
            files.add(file);
        }
    }

    private static byte[] mutate(byte[] data, Random random) {
        final byte[] result = data.clone();
        switch (random.nextInt(4)) {
            case 0:
                // いくつかのバイトを変える
                for (int i = 0; i < 1 + random.nextInt(8) && result.length > 0; i++) result[random.nextInt(result.length)] = (byte) random.nextInt(256);
                return result;
            case 1:
                // 途中で切る
                return java.util.Arrays.copyOf(result, result.length == 0 ? 0 : random.nextInt(result.length));
            case 2: {
                // 長さや大きさに使われる 4 バイトを大きな値にする
                if (result.length < 8) return result;
                final int at = random.nextInt(result.length - 4);
                final int value = random.nextBoolean() ? 0x7FFFFFFF : random.nextInt();
                result[at] = (byte) (value >>> 24);
                result[at + 1] = (byte) (value >>> 16);
                result[at + 2] = (byte) (value >>> 8);
                result[at + 3] = (byte) value;
                return result;
            }
            default: {
                // 一部を別の場所に写す
                if (result.length < 16) return result;
                final int length = 1 + random.nextInt(Math.min(64, result.length / 2));
                System.arraycopy(result, random.nextInt(result.length - length), result, random.nextInt(result.length - length), length);
                return result;
            }
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        final GZIPInputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(data);
        gzip.close();
        return output.toByteArray();
    }
}
