package marumasa.marumasa_sign.core.pdf.cos;

/**
 * 間接参照を中身に置き換えるもの (PdfDocument)。
 */
public interface Resolver {

    /**
     * 参照なら中身を返す。参照でなければそのまま返す。見つからない参照は null (PDF の null と同じ扱い)。
     */
    Object resolve(Object value);
}
