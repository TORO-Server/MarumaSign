package marumasa.marumasa_sign.lib.logging;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.SafeLog;

/**
 * PDFBox が使う Apache Commons Logging の LogFactory の代わり。MarumaSign のロガーに書く。
 * PDF の内容による警告はゲームの問題ではないので、エラーも含めて詳細な記録 (debug) にする。
 */
public final class LogFactory {

    private LogFactory() {
    }

    public static Log getLog(Class<?> owner) {
        return new Forward("PDF (" + owner.getSimpleName() + "): ");
    }

    private static final class Forward implements Log {
        private final String prefix;

        Forward(String prefix) {
            this.prefix = prefix;
        }

        private static SafeLog logger() {
            return MarumaSign.LOGGER;
        }

        @Override
        public boolean isDebugEnabled() {
            return logger().isDebugEnabled();
        }

        @Override
        public boolean isTraceEnabled() {
            return logger().isTraceEnabled();
        }

        @Override
        public boolean isWarnEnabled() {
            return logger().isDebugEnabled();
        }

        @Override
        public void trace(Object message) {
            logger().trace(prefix + message);
        }

        @Override
        public void debug(Object message) {
            logger().debug(prefix + message);
        }

        @Override
        public void debug(Object message, Throwable error) {
            logger().debug(prefix + message, error);
        }

        @Override
        public void info(Object message) {
            logger().debug(prefix + message);
        }

        @Override
        public void warn(Object message) {
            logger().debug(prefix + message);
        }

        @Override
        public void warn(Object message, Throwable error) {
            logger().debug(prefix + message, error);
        }

        @Override
        public void error(Object message) {
            logger().debug(prefix + message);
        }

        @Override
        public void error(Object message, Throwable error) {
            logger().debug(prefix + message, error);
        }
    }
}
