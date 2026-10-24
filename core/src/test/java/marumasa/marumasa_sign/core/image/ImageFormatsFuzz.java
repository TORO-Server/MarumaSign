package marumasa.marumasa_sign.core.image;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 壊したファイルを読ませて、想定外の例外 (IOException 以外)、長い時間、メモリ不足にならないかを確かめる。
 * 引数: 回数、ファイルまたはフォルダ…。問題が 1 つでもあれば終了コード 1 で終わる。
 */
public final class ImageFormatsFuzz {

    private static final long TIMEOUT_SECONDS = 20;

    public static void main(String[] args) throws Exception {
        final int iterations = Integer.parseInt(args[0]);
        final List<File> files = new ArrayList<File>();
        for (int i = 1; i < args.length; i++) {
            final File f = new File(args[i]);
            if (f.isDirectory()) {
                final File[] children = f.listFiles();
                if (children != null) {
                    Arrays.sort(children);
                    for (File c : children) if (c.isFile() && c.length() < 3_000_000) files.add(c);
                }
            } else {
                files.add(f);
            }
        }
        final ExecutorService executor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "fuzz");
            t.setDaemon(true);
            return t;
        });
        int problems = 0;
        long total = 0, ok = 0, rejected = 0;
        long slowest = 0;
        String slowestName = "";
        for (File file : files) {
            final byte[] original = Files.readAllBytes(file.toPath());
            final Random random = new Random(file.getName().hashCode());
            for (int n = 0; n < iterations; n++) {
                final byte[] mutated = mutate(original, random, n);
                final long start = System.nanoTime();
                final Future<Object> future = executor.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return ImageLoader.decode(mutated, file.getName());
                    }
                });
                total++;
                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    ok++;
                } catch (TimeoutException e) {
                    future.cancel(true);
                    problems++;
                    System.out.println("TIMEOUT " + file.getName() + " iteration " + n);
                    write(file, n, mutated);
                } catch (ExecutionException e) {
                    final Throwable cause = e.getCause();
                    if (cause instanceof java.io.IOException) {
                        rejected++;
                    } else {
                        problems++;
                        System.out.println("UNEXPECTED " + file.getName() + " iteration " + n + ": " + cause);
                        if (!(cause instanceof OutOfMemoryError)) cause.printStackTrace(System.out);
                        write(file, n, mutated);
                    }
                }
                final long ms = (System.nanoTime() - start) / 1000000;
                if (ms > slowest) {
                    slowest = ms;
                    slowestName = file.getName() + "#" + n;
                }
            }
        }
        System.out.println("runs " + total + ", decoded " + ok + ", rejected " + rejected + ", problems " + problems
                + ", slowest " + slowest + " ms (" + slowestName + ")");
        System.exit(problems == 0 ? 0 : 1);
    }

    private static void write(File file, int n, byte[] data) throws java.io.IOException {
        final File dir = new File("build/fuzz-failures");
        dir.mkdirs();
        Files.write(new File(dir, n + "_" + file.getName()).toPath(), data);
    }

    private static byte[] mutate(byte[] original, Random random, int n) {
        byte[] data = original.clone();
        switch (n % 5) {
            case 0: {
                // 数バイトを書き換える
                final int count = 1 + random.nextInt(8);
                for (int i = 0; i < count; i++) data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                break;
            }
            case 1: {
                // 途中で切る
                data = Arrays.copyOf(data, random.nextInt(data.length));
                break;
            }
            case 2: {
                // 先頭の 64 バイト (見出し) を重点的に壊す
                final int count = 1 + random.nextInt(6);
                for (int i = 0; i < count; i++) data[random.nextInt(Math.min(64, data.length))] = (byte) random.nextInt(256);
                break;
            }
            case 3: {
                // 数の大きいバイトで埋める (大きさや長さの欄を巨大にする)
                final int p = random.nextInt(Math.min(128, data.length));
                for (int i = 0; i < 4 && p + i < data.length; i++) data[p + i] = (byte) 0xFF;
                break;
            }
            default: {
                // 一部を別の場所へコピーする
                final int length = 1 + random.nextInt(Math.min(64, data.length));
                System.arraycopy(data, random.nextInt(data.length - length + 1), data, random.nextInt(data.length - length + 1), length);
                break;
            }
        }
        return data;
    }
}
