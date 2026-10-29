package marumasa.marumasa_sign.core.pdf;

/**
 * 試験用の「厳しい」動作。システムプロパティ marumasign.pdfStrict が true のときだけ働く。
 * <p>
 * PDF の読み込みと描画は、壊れたファイルでも止まらないよう、想定外の例外 (NullPointerException など) も受け止めて続ける。
 * それでは実装の不具合が隠れてしまうので、ファジングの試験ではこれを有効にし、受け止めた想定外の例外を外に投げる。
 */
public final class Strict {

    public static final boolean ENABLED = Boolean.getBoolean("marumasign.pdfStrict");

    private Strict() {
    }

    /**
     * 受け止めた例外が想定外のものなら、厳しい動作のときに投げ直す。
     */
    public static void check(Throwable e) {
        if (!ENABLED || e == null) return;
        if (e instanceof java.io.IOException) return;
        // 壊れたフォントのデータ (範囲の外を読んだなど) は、想定している失敗
        if (e.getClass().getName().endsWith("FontFormatError")) return;
        throw new UnexpectedFailure(e);
    }

    /**
     * 試験で見つける、想定外の失敗。
     */
    public static final class UnexpectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnexpectedFailure(Throwable cause) {
            super("Unexpected " + cause, cause);
        }
    }
}
