package marumasa.marumasa_sign.core;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 記録 (ログ)。ライブラリ (Log4j など) は使わず、標準出力と標準エラー出力に 1 行ずつ書く。
 * Minecraft は標準出力と標準エラー出力を自分の記録 (latest.log) に流すので、ゲームの記録にも残る。
 * <p>
 * 看板の URL やサーバーの返事など、記録には誰でも書ける文字が入る。
 * 標準出力の先は古い Minecraft (1.18 まで) の Log4j で、記録する文字の中の「${jndi:...}」を実行してしまう (Log4Shell) ことがあるので、
 * 「${」は「$ {」に変える。改行などの制御文字は「\n」のような書き方に変え、記録の行を偽れないようにする。
 * 詳しい記録 (debug / trace) は、システムプロパティ marumasign.debug を true にしたときだけ書く。
 */
public final class SafeLog {

    private static final boolean DEBUG = Boolean.getBoolean("marumasign.debug");

    private final String name;

    SafeLog(String name) {
        this.name = name;
    }

    // 記録に書いてよい形にした文字
    public static String clean(Object message) {
        final String text = String.valueOf(message);
        StringBuilder result = null;
        for (int i = 0, length = text.length(); i < length; i++) {
            final char c = text.charAt(i);
            final String replacement;
            if (c == '$' && i + 1 < length && text.charAt(i + 1) == '{') {
                replacement = "$ ";
            } else if (c == '\n') {
                replacement = "\\n";
            } else if (c == '\r') {
                replacement = "\\r";
            } else if ((c < 0x20 && c != '\t') || (c >= 0x7F && c <= 0x9F) || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)) {
                // 端末の色を変える文字や、文字の向きを入れ替える文字も、そのままでは書かない
                replacement = String.format("\\u%04x", (int) c);
            } else {
                if (result != null) result.append(c);
                continue;
            }
            if (result == null) result = new StringBuilder(length + 16).append(text, 0, i);
            result.append(replacement);
        }
        return result == null ? text : result.toString();
    }

    public boolean isDebugEnabled() {
        return DEBUG;
    }

    public boolean isTraceEnabled() {
        return DEBUG;
    }

    public void trace(String message) {
        if (DEBUG) write(System.out, "TRACE", message, null);
    }

    public void debug(String message) {
        if (DEBUG) write(System.out, "DEBUG", message, null);
    }

    public void debug(String message, Throwable error) {
        if (DEBUG) write(System.out, "DEBUG", message, error);
    }

    public void info(String message) {
        write(System.out, "INFO", message, null);
    }

    public void warn(String message) {
        write(System.err, "WARN", message, null);
    }

    public void error(String message) {
        write(System.err, "ERROR", message, null);
    }

    public void error(String message, Throwable error) {
        write(System.err, "ERROR", message, error);
    }

    private void write(PrintStream out, String level, String message, Throwable error) {
        final StringBuilder text = new StringBuilder("[").append(name).append('/').append(level).append("] ").append(clean(message));
        if (error != null) {
            // 例外の説明にも外から来た文字が入るので、1 行ずつ無害にする
            final StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            for (String line : trace.toString().split("\r?\n")) {
                if (!line.isEmpty()) text.append(System.lineSeparator()).append(clean(line));
            }
        }
        synchronized (out) {
            out.println(text);
        }
    }
}
