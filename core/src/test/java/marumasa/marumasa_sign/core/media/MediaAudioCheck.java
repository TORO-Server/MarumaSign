package marumasa.marumasa_sign.core.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.spi.MixerProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 動画の音の出力 (SourceDataLine) が、再生を止めたあとに開いたまま残らないことを確かめる。
 * 音の出ない環境でも確かめられるように、試験用の音の出力 (FakeMixerProvider) を Java Sound に登録して使う。
 * 出力を開いている途中で再生を止めたとき、以前は止める側がまだその出力を知らず、開いたまま残っていた。
 * FFmpeg がなければ試さずに終える。1 つでも違えば終了コード 1 で終わる。
 */
public final class MediaAudioCheck {

    private static int failures;

    // 開いた出力 (試験用のもの)
    static final List<FakeLine> LINES = new CopyOnWriteArrayList<FakeLine>();
    // これが 0 になるまで、出力を開くのを待たせる (null なら待たせない)
    static volatile CountDownLatch openGate;
    // 出力を開き始めたら 0 になる
    static volatile CountDownLatch opening;

    private MediaAudioCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (!Ffmpeg.isAvailable()) {
            System.out.println("skip: FFmpeg was not found, so the audio output was not checked");
            return;
        }
        System.setProperty("marumasign.allowLocalUrls", "true");
        installFakeMixer();
        final byte[] video = video();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, video.length);
                final OutputStream output = exchange.getResponseBody();
                try {
                    output.write(video);
                } catch (IOException e) {
                    // 再生を止めた
                } finally {
                    output.close();
                }
            }
        });
        server.start();
        final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/video.mp4";
        try {
            checkStoppedWhilePlaying(url);
            checkStoppedWhileOpening(url);
        } finally {
            server.stop(0);
        }
        System.out.println(failures == 0 ? "All media audio checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    // 鳴っている途中で止めたら、出力を閉じる
    private static void checkStoppedWhilePlaying(String url) throws Exception {
        LINES.clear();
        openGate = null;
        opening = new CountDownLatch(1);
        final MediaPlayer player = new MediaPlayer(url, new QuietListener());
        player.start();
        final boolean opened = opening.await(30, TimeUnit.SECONDS);
        check("the audio output is opened", opened);
        // 少し鳴らしてから止める
        waitUntil(new Condition() {
            @Override
            public boolean met() {
                return !LINES.isEmpty() && LINES.get(0).written > 0;
            }
        }, 10);
        player.stop();
        final boolean closed = waitForClosed();
        check("the audio output is closed when playback is stopped (" + openLines() + " open)", closed);
    }

    // 出力を開いている途中で止めても、開いた出力が残らない
    private static void checkStoppedWhileOpening(String url) throws Exception {
        LINES.clear();
        openGate = new CountDownLatch(1);
        opening = new CountDownLatch(1);
        final MediaPlayer player = new MediaPlayer(url, new QuietListener());
        player.start();
        try {
            final boolean opened = opening.await(30, TimeUnit.SECONDS);
            check("the audio output starts to open", opened);
            player.stop();
            // 再生のスレッドが後片付け (2 回目の closeAttempt) を終えるまで、出力を開き終えないでおく
            Thread.sleep(1000);
        } finally {
            openGate.countDown();
        }
        final boolean closed = waitForClosed();
        check("an audio output opened while stopping is closed (" + openLines() + " open)", closed);
    }

    private interface Condition {
        boolean met();
    }

    private static boolean waitUntil(Condition condition, int seconds) throws InterruptedException {
        final long until = System.nanoTime() + seconds * 1000000000L;
        while (System.nanoTime() < until) {
            if (condition.met()) return true;
            Thread.sleep(20);
        }
        return condition.met();
    }

    // 作った出力がどれも開き終えてから (開いている途中は、まだ閉じていないとは言えない)、どれも閉じられるのを待つ
    private static boolean waitForClosed() throws InterruptedException {
        waitUntil(new Condition() {
            @Override
            public boolean met() {
                for (FakeLine line : LINES) {
                    if (!line.opened) return false;
                }
                return !LINES.isEmpty();
            }
        }, 10);
        return waitUntil(new Condition() {
            @Override
            public boolean met() {
                return !LINES.isEmpty() && openLines() == 0;
            }
        }, 5);
    }

    private static int openLines() {
        int open = 0;
        for (FakeLine line : LINES) {
            if (line.isOpen()) open++;
        }
        return open;
    }

    /**
     * Java Sound が試験用の音の出力を見つけられるようにする。Java Sound は、使うスレッドのクラスローダーから
     * META-INF/services の MixerProvider を探すので、それを書いたフォルダを加えたクラスローダーにする
     * (再生のスレッドは、このスレッドのクラスローダーを引き継ぐ)。
     */
    private static void installFakeMixer() throws IOException {
        final File directory = Files.createTempDirectory("marumasign-fake-mixer").toFile();
        directory.deleteOnExit();
        final File services = new File(directory, "META-INF/services");
        if (!services.mkdirs()) throw new IOException("Could not make " + services);
        final File file = new File(services, MixerProvider.class.getName());
        Files.write(file.toPath(), (FakeMixerProvider.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        file.deleteOnExit();
        services.deleteOnExit();
        services.getParentFile().deleteOnExit();
        // ほかの音の出力 (Java 8 の PulseAudio など) があっても、これを既定にする
        System.setProperty(SourceDataLine.class.getName(), FakeMixerProvider.class.getName() + "#" + FakeMixer.INFO.getName());
        Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[]{directory.toURI().toURL()},
                MediaAudioCheck.class.getClassLoader()));
    }

    // 音のある 4 秒の動画
    private static byte[] video() throws Exception {
        final File file = File.createTempFile("marumasign-audio-check", ".mp4");
        try {
            final Process process = new ProcessBuilder(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner",
                    "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=32x32:rate=10:duration=4",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-pix_fmt", "yuv420p", "-shortest", file.getPath()))
                    .redirectErrorStream(true).start();
            process.getInputStream().close();
            process.waitFor();
            return Files.readAllBytes(file.toPath());
        } finally {
            file.delete();
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }

    private static final class QuietListener implements MediaPlayer.Listener {
        @Override
        public void onReady(int width, int height) {
        }

        @Override
        public void onFailed(String reason) {
            System.out.println("  playback failed: " + reason);
        }
    }

    // ---------- 試験用の音の出力 ----------

    public static final class FakeMixerProvider extends MixerProvider {
        private static final FakeMixer MIXER = new FakeMixer();

        public FakeMixerProvider() {
        }

        @Override
        public Mixer.Info[] getMixerInfo() {
            return new Mixer.Info[]{FakeMixer.INFO};
        }

        @Override
        public Mixer getMixer(Mixer.Info info) {
            if (info == null || info.equals(FakeMixer.INFO)) return MIXER;
            throw new IllegalArgumentException("Unknown mixer: " + info);
        }
    }

    static final class FakeMixer implements Mixer {
        static final Mixer.Info INFO = new Mixer.Info("MarumaSign fake mixer", "MarumaSign", "Test output", "1") {
        };
        private static final AudioFormat FORMAT = new AudioFormat(48000, 16, 2, true, false);
        private static final DataLine.Info LINE = new DataLine.Info(SourceDataLine.class, FORMAT);

        @Override
        public Mixer.Info getMixerInfo() {
            return INFO;
        }

        @Override
        public Line.Info[] getSourceLineInfo() {
            return new Line.Info[]{LINE};
        }

        @Override
        public Line.Info[] getTargetLineInfo() {
            return new Line.Info[0];
        }

        @Override
        public Line.Info[] getSourceLineInfo(Line.Info info) {
            return isLineSupported(info) ? new Line.Info[]{LINE} : new Line.Info[0];
        }

        @Override
        public Line.Info[] getTargetLineInfo(Line.Info info) {
            return new Line.Info[0];
        }

        @Override
        public boolean isLineSupported(Line.Info info) {
            return LINE.matches(info);
        }

        @Override
        public Line getLine(Line.Info info) {
            if (!isLineSupported(info)) throw new IllegalArgumentException("Unsupported line: " + info);
            final FakeLine line = new FakeLine();
            LINES.add(line);
            return line;
        }

        @Override
        public int getMaxLines(Line.Info info) {
            return AudioSystemNotSpecified.VALUE;
        }

        @Override
        public Line[] getSourceLines() {
            return LINES.toArray(new Line[0]);
        }

        @Override
        public Line[] getTargetLines() {
            return new Line[0];
        }

        @Override
        public void synchronize(Line[] lines, boolean maintainSync) {
            throw new IllegalArgumentException("Not supported");
        }

        @Override
        public void unsynchronize(Line[] lines) {
            throw new IllegalArgumentException("Not supported");
        }

        @Override
        public boolean isSynchronizationSupported(Line[] lines, boolean maintainSync) {
            return false;
        }

        @Override
        public Line.Info getLineInfo() {
            return new Line.Info(Mixer.class);
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }

        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("Not supported");
        }

        @Override
        public void addLineListener(LineListener listener) {
        }

        @Override
        public void removeLineListener(LineListener listener) {
        }
    }

    private static final class AudioSystemNotSpecified {
        static final int VALUE = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
    }

    // 書いた量の分だけ時間をかけて (実際に鳴らすのと同じ速さで) 受け取る出力
    static final class FakeLine implements SourceDataLine {
        private volatile boolean open;
        // 開き終えたことがあるか
        volatile boolean opened;
        private volatile boolean running;
        private AudioFormat format = FakeMixer.FORMAT;
        private int bufferSize = 48000 * 4 / 5;
        volatile long written;
        private long startNanos;

        @Override
        public void open(AudioFormat format, int bufferSize) {
            final CountDownLatch started = opening;
            if (started != null) started.countDown();
            final CountDownLatch gate = openGate;
            if (gate != null) {
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            this.format = format;
            this.bufferSize = bufferSize;
            open = true;
            opened = true;
        }

        @Override
        public void open(AudioFormat format) {
            open(format, bufferSize);
        }

        @Override
        public void open() {
            open(format, bufferSize);
        }

        @Override
        public int write(byte[] b, int off, int len) {
            if (!open) return 0;
            if (startNanos == 0) startNanos = System.nanoTime();
            written += len;
            // 書いた量が、鳴らした時間より 0.2 秒以上先に行かないように待つ
            final long frameNanos = 1000000000L / (long) format.getFrameRate();
            final long due = startNanos + (written / format.getFrameSize() - (long) (format.getFrameRate() / 5)) * frameNanos;
            final long wait = due - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return len;
        }

        @Override
        public void drain() {
        }

        @Override
        public void flush() {
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isActive() {
            return running;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public int getBufferSize() {
            return bufferSize;
        }

        @Override
        public int available() {
            return bufferSize;
        }

        @Override
        public int getFramePosition() {
            return (int) getLongFramePosition();
        }

        @Override
        public long getLongFramePosition() {
            if (startNanos == 0) return 0;
            final long played = (long) ((System.nanoTime() - startNanos) / 1e9 * format.getFrameRate());
            return Math.min(played, written / format.getFrameSize());
        }

        @Override
        public long getMicrosecondPosition() {
            return (long) (getLongFramePosition() * 1e6 / format.getFrameRate());
        }

        @Override
        public float getLevel() {
            return AudioSystemNotSpecified.VALUE;
        }

        @Override
        public Line.Info getLineInfo() {
            return FakeMixer.LINE;
        }

        @Override
        public void close() {
            open = false;
            running = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }

        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("Not supported");
        }

        @Override
        public void addLineListener(LineListener listener) {
        }

        @Override
        public void removeLineListener(LineListener listener) {
        }
    }
}
