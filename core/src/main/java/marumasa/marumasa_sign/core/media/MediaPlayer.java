package marumasa.marumasa_sign.core.media;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.net.FilteringProxy;
import marumasa.marumasa_sign.core.net.UrlPolicy;
import marumasa.marumasa_sign.core.util.Clock;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 動画や音声を 1 つ再生する。
 * FFmpeg を別のプロセスとして動かし、映像は生のフレームを、音声は PCM を、
 * どちらもこのコンピューター内の通信 (TCP) で受け取る。
 * 標準出力のパイプは OS のバッファが小さく、生の映像を流すには遅いため使わない。
 * どの形式を読めるか、GPU でデコードできるかは FFmpeg に任せている。
 */
public final class MediaPlayer {

    // FFmpeg に許可する通信の種類。ローカルのファイルなどは読ませない。
    // http と https の行き先は FilteringProxy が確かめる (手元や家の中のネットワークにはつながせない)
    static final String PROTOCOLS = "http,https,tcp,tls,crypto,httpproxy";
    // 通信が止まってから諦めるまでの時間 (マイクロ秒)。止まったライブ配信を待ち続けないようにする
    static final String NETWORK_TIMEOUT = "15000000";

    // ライブ配信が途切れたとき、つなぎ直すまでの待ち時間 (ミリ秒) とその上限
    private static final long RECONNECT_MILLIS = 2000;
    private static final long MAX_RECONNECT_MILLIS = 30000;
    // この時間 (ミリ秒) 以上続いた再生のあとは、待ち時間を最初の長さに戻す
    private static final long STABLE_MILLIS = 30000;
    // 映像の表示がこれ (マイクロ秒) より遅れたら、遅れを取り戻すのをやめて今の位置から表示し直す
    private static final long RESYNC_MICROS = 1000000;
    // ライブ配信で、この時間 (ミリ秒) 映像も音声も届かなければ、止まったものとしてつなぎ直す。
    // FFmpeg は、配信が最初からやり直された HLS などを、終了せずにいつまでも待つことがある
    private static final long STALL_MILLIS = 15000;

    // 映像の長辺の上限 (ピクセル)。これより大きい映像は縮小して受け取る
    private static final int MAX_VIDEO_SIZE = 1280;
    private static final double MAX_FRAME_RATE = 60.0;

    // 映像を受け取る通信の受信バッファ (バイト)
    private static final int VIDEO_BUFFER_SIZE = 8 * 1024 * 1024;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE_FRAME = CHANNELS * 2;

    public interface Listener {
        // 映像の大きさなどが分かり、再生を始められるようになったとき。映像がなければ width, height は 0
        void onReady(int width, int height);

        void onFailed(String reason);
    }

    private final String url;
    private final Listener listener;

    private volatile boolean stopped;
    private volatile Process process;
    private volatile ServerSocket audioServer;
    private volatile ServerSocket videoServer;
    private volatile SourceDataLine line;
    // 音量 (0 - 1)
    private volatile float gain = 1f;

    private int width;
    private int height;

    // 表示待ちのフレーム。映像のスレッドが書き、描画のスレッドが取り出す
    private final Object frameLock = new Object();
    private int[] pendingFrame;
    private boolean frameAvailable;

    // 時計: 音声があれば、実際に鳴った位置に映像を合わせる
    private volatile boolean audioRunning;
    private volatile long audioWrittenFrames;
    private long startNanos;
    // 最後に映像か音声が届いた時刻 (System.nanoTime)
    private volatile long lastDataNanos;

    public MediaPlayer(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void setGain(float value) {
        gain = Math.max(0f, Math.min(1f, value));
    }

    public void start() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                MediaPlayer.this.run();
            }
        }, "MarumaSign-Media");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        closeAttempt();
    }

    // この再生のために動かしたプロセス (ffprobe など)。止めたとき (closeAttempt) に一緒に止める
    void track(Process running) {
        process = running;
        // 動かしている間に止められていたら、closeAttempt はこのプロセスを知らないので、ここで止める
        if (stopped) running.destroyForcibly();
    }

    // 1 回分の再生で使った FFmpeg と通信と音声の出力を閉じる
    private void closeAttempt() {
        final Process running = process;
        if (running != null) running.destroyForcibly();
        for (ServerSocket server : new ServerSocket[]{audioServer, videoServer}) {
            if (server == null) continue;
            try {
                server.close();
            } catch (IOException e) {
                // 既に閉じている
            }
        }
        final SourceDataLine openLine = line;
        if (openLine != null) {
            openLine.stop();
            openLine.close();
        }
    }

    /**
     * 新しいフレームがあれば dst に写して true を返す。描画のスレッドから呼ぶ。
     */
    public boolean pollFrame(int[] dst) {
        synchronized (frameLock) {
            if (!frameAvailable) return false;
            System.arraycopy(pendingFrame, 0, dst, 0, Math.min(dst.length, pendingFrame.length));
            frameAvailable = false;
            return true;
        }
    }

    private void run() {
        boolean ready = false;
        try {
            if (!UrlPolicy.isAllowed(url)) throw new IOException("Blocked URL: " + url);
            final MediaInfo info = MediaInfo.probe(url, this);
            if (stopped) return;

            double frameRate = 0;
            if (info.hasVideo) {
                // 大きすぎる映像は縮小する。幅と高さは偶数にそろえる
                final double scale = Math.min(1.0, (double) MAX_VIDEO_SIZE / Math.max(info.width, info.height));
                width = Math.max(2, (int) Math.round(info.width * scale) & ~1);
                height = Math.max(2, (int) Math.round(info.height * scale) & ~1);
                frameRate = info.isStillImage() ? 0 : Math.min(MAX_FRAME_RATE, info.frameRate > 0 ? info.frameRate : 30.0);
                synchronized (frameLock) {
                    pendingFrame = new int[width * height];
                }
            }
            listener.onReady(width, height);
            ready = true;
            if (info.isLive()) {
                playLive(info, frameRate);
            } else {
                play(info, frameRate);
            }
        } catch (Exception e) {
            if (!stopped) {
                MarumaSign.LOGGER.info("Media failure: " + url + " (" + e + ")");
                // 再生が始まったあとの失敗では、最後のフレームをそのまま残す
                if (!ready) listener.onFailed(String.valueOf(e.getMessage()));
            }
        } catch (Throwable e) {
            MarumaSign.LOGGER.error("Unexpected error while playing " + url, e);
            if (!ready) listener.onFailed(String.valueOf(e));
        } finally {
            stop();
        }
    }

    // ライブ配信は終わりがないので、途切れたら少し待ってつなぎ直す
    private void playLive(MediaInfo info, double frameRate) {
        startWatchdog();
        long wait = RECONNECT_MILLIS;
        while (!stopped) {
            final long started = Clock.millis();
            lastDataNanos = System.nanoTime();
            try {
                play(info, frameRate);
                if (!stopped) MarumaSign.LOGGER.info("Live stream ended, reconnecting: " + url);
            } catch (IOException e) {
                if (!stopped) MarumaSign.LOGGER.info("Live stream failed, reconnecting: " + url + " (" + e.getMessage() + ")");
            } finally {
                closeAttempt();
            }
            if (Clock.millis() - started > STABLE_MILLIS) wait = RECONNECT_MILLIS;
            final long until = Clock.millis() + wait;
            while (!stopped && Clock.millis() < until) {
                sleep(100);
            }
            wait = Math.min(MAX_RECONNECT_MILLIS, wait * 2);
        }
    }

    // 映像も音声も長く届かないときに FFmpeg を止める。止まると playLive がつなぎ直す
    private void startWatchdog() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopped) {
                    sleep(1000);
                    final Process running = process;
                    if (running != null && running.isAlive()
                            && System.nanoTime() - lastDataNanos > STALL_MILLIS * 1000000L) {
                        MarumaSign.LOGGER.info("Live stream stalled: " + url);
                        running.destroyForcibly();
                        lastDataNanos = System.nanoTime();
                    }
                }
            }
        }, "MarumaSign-MediaWatchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void play(MediaInfo info, double frameRate) throws IOException {
        final List<String> command = new ArrayList<String>();
        command.add(Ffmpeg.requireFfmpeg().getPath());
        add(command, "-hide_banner", "-loglevel", "error", "-nostdin");
        add(command, "-protocol_whitelist", PROTOCOLS);
        add(command, "-rw_timeout", NETWORK_TIMEOUT);
        // 使える GPU のデコーダーがあれば使う
        add(command, "-hwaccel", "auto");
        final boolean loop = frameRate > 0 || !info.hasVideo;
        if (loop && info.duration > 0) add(command, "-stream_loop", "-1");
        // MJPEG の配信には時刻の情報がないので、届いた時刻を使う
        if (info.isLive() && info.isJpegSequence()) add(command, "-use_wallclock_as_timestamps", "1");
        add(command, "-i", url);

        if (info.hasVideo) {
            String filter = "scale=" + width + ":" + height + ":flags=bilinear";
            // 一定の間隔のフレームにする。映像が音声より遅れて始まる場合は、最初のフレームを繰り返して
            // 時刻 0 からそろえる (フレームの番号で表示する時刻を決めているため)
            if (frameRate > 0) filter += ",fps=fps=" + frameRate + ":start_time=0";
            add(command, "-map", "0:v:0", "-vf", filter);
            if (frameRate <= 0) add(command, "-frames:v", "1");
            final ServerSocket server = new ServerSocket();
            // 1 フレームが数 MB あるので、受信バッファを大きくしておく
            server.setReceiveBufferSize(VIDEO_BUFFER_SIZE);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            videoServer = server;
            // リトルエンディアンの int として読むと ARGB になる並び
            add(command, "-pix_fmt", "bgra", "-f", "rawvideo", "tcp://127.0.0.1:" + server.getLocalPort());
        }
        if (info.hasAudio) {
            final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            audioServer = server;
            // 途切れた部分は無音で埋め、時刻 0 から隙間なく並べる (映像と同じ時刻の基準にする)
            add(command, "-map", "0:a:0", "-vn", "-af", "aresample=async=1:first_pts=0",
                    "-ac", String.valueOf(CHANNELS), "-ar", String.valueOf(SAMPLE_RATE),
                    "-f", "s16le", "tcp://127.0.0.1:" + server.getLocalPort());
            startAudioThread(server);
        }

        final ProcessBuilder builder = new ProcessBuilder(command);
        FilteringProxy.apply(builder, url);
        final Process started = Ffmpeg.start(builder.redirectErrorStream(false));
        // 起動している間に止められていたら、ここで止める (stop は、まだこのプロセスを知らない。止めないと、
        // 誰も読まない映像の接続に書き込もうとして止まったまま、いつまでも残る)
        track(started);
        started.getOutputStream().close();
        final StringBuilder errors = new StringBuilder();
        drain(started.getErrorStream(), errors);
        startNanos = System.nanoTime();

        drain(started.getInputStream(), null);
        if (info.hasVideo) {
            final Socket socket = acceptVideo(videoServer, started);
            if (socket != null) {
                try {
                    readFrames(socket.getInputStream(), frameRate);
                } finally {
                    socket.close();
                }
            }
        }
        try {
            started.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!stopped && started.exitValue() != 0 && errors.length() > 0) {
            throw new IOException(errors.toString().trim());
        }
    }

    // FFmpeg からの映像の接続を待つ。接続する前に FFmpeg が終了した (URL が開けなかったなど) 場合は null
    private Socket acceptVideo(ServerSocket server, Process started) throws IOException {
        server.setSoTimeout(500);
        while (!stopped) {
            try {
                return server.accept();
            } catch (SocketTimeoutException e) {
                if (!started.isAlive()) return null;
            }
        }
        return null;
    }

    private static void add(List<String> command, String... arguments) {
        for (String argument : arguments) {
            command.add(argument);
        }
    }

    // ---------- 映像 ----------

    private void readFrames(InputStream stream, double frameRate) throws IOException {
        final DataInputStream input = new DataInputStream(stream);
        final byte[] bytes = new byte[width * height * 4];
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final int[] pixels = new int[width * height];
        final long frameMicros = frameRate > 0 ? (long) (1000000.0 / frameRate) : 0;
        long lastShown = 0;

        for (long index = 0; !stopped; index++) {
            try {
                input.readFully(bytes);
            } catch (EOFException e) {
                return;
            }
            lastDataNanos = System.nanoTime();
            // 最初のフレームが届いた時点を再生の開始とする (それまでの時間は FFmpeg の準備にかかった時間)
            if (index == 0) startNanos = System.nanoTime();
            if (frameMicros > 0) {
                final long due = index * frameMicros;
                // 通信が止まるなどして大きく遅れたときは、遅れた分を時計から差し引いて、ここから表示し直す。
                // 音声に合わせている場合は、音声も同じだけ止まっているので、そのままでよい
                if (!usesAudioClock() && clockMicros() - due > RESYNC_MICROS) {
                    startNanos += (clockMicros() - due) * 1000;
                    lastShown = due;
                }
                // 表示する時刻まで待つ
                long now;
                while (!stopped && (now = clockMicros()) < due) {
                    sleep(Math.min(10, Math.max(1, (due - now) / 1000)));
                }
                // 遅れているフレームは飛ばす。ただし、長く止まって見えないように時々は表示する
                if (clockMicros() > due + frameMicros * 2 && due - lastShown < 250000) continue;
                lastShown = due;
            }
            buffer.clear();
            buffer.asIntBuffer().get(pixels);
            synchronized (frameLock) {
                System.arraycopy(pixels, 0, pendingFrame, 0, pixels.length);
                frameAvailable = true;
            }
        }
    }

    // 再生位置 (マイクロ秒)
    private long clockMicros() {
        final SourceDataLine open = line;
        if (audioRunning && open != null) {
            return open.getMicrosecondPosition();
        }
        return (System.nanoTime() - startNanos) / 1000;
    }

    // 実際に鳴った音声の位置を時計にしているかどうか
    private boolean usesAudioClock() {
        return audioRunning && line != null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 音声 ----------

    private void startAudioThread(final ServerSocket server) {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Socket socket = server.accept();
                    try {
                        playAudio(socket.getInputStream());
                    } finally {
                        socket.close();
                    }
                } catch (Exception e) {
                    if (!stopped) MarumaSign.LOGGER.info("Audio stopped: " + url + " (" + e + ")");
                } finally {
                    audioRunning = false;
                }
            }
        }, "MarumaSign-Audio");
        thread.setDaemon(true);
        thread.start();
    }

    private void playAudio(InputStream input) throws IOException {
        final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
        SourceDataLine open = null;
        try {
            open = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
            // 0.2 秒分のバッファ。小さいほど映像とのずれが少ない
            open.open(format, SAMPLE_RATE * BYTES_PER_SAMPLE_FRAME / 5);
            open.start();
        } catch (Exception e) {
            // 音を出せない環境では、読み捨てて映像だけを再生する
            MarumaSign.LOGGER.info("Audio output is not available (" + e + ")");
            open = null;
        }
        line = open;
        audioRunning = open != null;

        final byte[] buffer = new byte[BYTES_PER_SAMPLE_FRAME * 2048];
        int filled = 0;
        while (!stopped) {
            final int read = input.read(buffer, filled, buffer.length - filled);
            if (read < 0) break;
            lastDataNanos = System.nanoTime();
            filled += read;
            // 左右そろった単位でだけ書き込む
            final int usable = filled - filled % BYTES_PER_SAMPLE_FRAME;
            if (usable == 0) continue;
            if (open != null) {
                applyGain(buffer, usable, gain);
                open.write(buffer, 0, usable);
                audioWrittenFrames += usable / BYTES_PER_SAMPLE_FRAME;
            }
            System.arraycopy(buffer, usable, buffer, 0, filled - usable);
            filled -= usable;
        }
        if (open != null && !stopped) open.drain();
    }

    private static void applyGain(byte[] buffer, int length, float gain) {
        if (gain >= 0.999f) return;
        for (int i = 0; i + 1 < length; i += 2) {
            final int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            final int scaled = (int) (sample * gain);
            buffer[i] = (byte) scaled;
            buffer[i + 1] = (byte) (scaled >> 8);
        }
    }

    // ---------- 補助 ----------

    // ストリームを最後まで読む。into があれば、内容の先頭を文字列として残す
    static void drain(final InputStream stream, final StringBuilder into) {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        if (into != null && into.length() < 2000) {
                            synchronized (into) {
                                into.append(new String(buffer, 0, read, "UTF-8"));
                            }
                        }
                    }
                } catch (IOException e) {
                    // プロセスが終了した
                }
            }
        }, "MarumaSign-MediaDrain");
        thread.setDaemon(true);
        thread.start();
    }
}
