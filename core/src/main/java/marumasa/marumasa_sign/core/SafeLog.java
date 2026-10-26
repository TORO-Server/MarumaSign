package marumasa.marumasa_sign.core;

import org.apache.logging.log4j.Logger;

/**
 * 記録 (ログ) に書く文字を無害にしてから、Minecraft のロガー (Log4j) に渡す。
 * 看板の URL やサーバーの返事など、記録には誰でも書ける文字が入る。
 * 古い Minecraft (1.18 まで) の Log4j は、記録する文字の中の「${jndi:...}」を実行してしまう (Log4Shell) ことがあるので、
 * 「${」は「$ {」に変える。改行などの制御文字は「\n」のような書き方に変え、記録の行を偽れないようにする。
 */
public final class SafeLog {

    private final Logger logger;

    SafeLog(Logger logger) {
        this.logger = logger;
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
        return logger.isDebugEnabled();
    }

    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    public void trace(String message) {
        if (logger.isTraceEnabled()) logger.trace(clean(message));
    }

    public void debug(String message) {
        if (logger.isDebugEnabled()) logger.debug(clean(message));
    }

    public void debug(String message, Throwable error) {
        if (logger.isDebugEnabled()) logger.debug(clean(message), error);
    }

    public void info(String message) {
        logger.info(clean(message));
    }

    public void warn(String message) {
        logger.warn(clean(message));
    }

    public void error(String message) {
        logger.error(clean(message));
    }

    public void error(String message, Throwable error) {
        logger.error(clean(message), error);
    }
}
