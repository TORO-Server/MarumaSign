package marumasa.marumasa_sign.lib.logging;

/**
 * PDFBox が使う Apache Commons Logging の Log の代わり。
 * 配布する jar では、PDFBox の中の org.apache.commons.logging への参照をこのパッケージに付け替える。
 * PDFBox の警告は読めないフォントなど PDF の内容によるもので、ゲームの動作には関係しないため、詳細な記録として扱う。
 */
public interface Log {

    boolean isDebugEnabled();

    boolean isTraceEnabled();

    boolean isWarnEnabled();

    void trace(Object message);

    void debug(Object message);

    void debug(Object message, Throwable error);

    void info(Object message);

    void warn(Object message);

    void warn(Object message, Throwable error);

    void error(Object message);

    void error(Object message, Throwable error);
}
