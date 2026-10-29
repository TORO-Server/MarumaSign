package marumasa.marumasa_sign.core.pdf.cos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * PDF の辞書 (&lt;&lt; /Key value &gt;&gt;)。キーは名前の中身 ("/" を除いたもの)。
 * get などは参照を中身に置き換えて返す。値が null の項目は、ないものとして扱う (仕様どおり)。
 */
public final class PdfDict {

    private final Resolver resolver;
    private final Map<String, Object> entries = new LinkedHashMap<>();

    public PdfDict(Resolver resolver) {
        this.resolver = resolver;
    }

    public Resolver resolver() {
        return resolver;
    }

    public void put(String key, Object value) {
        if (value == null) {
            entries.remove(key);
        } else {
            entries.put(key, value);
        }
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    public Set<String> keys() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }

    // 参照を置き換えない、そのままの値
    public Object raw(String key) {
        return entries.get(key);
    }

    public Object get(String key) {
        final Object value = entries.get(key);
        return value instanceof PdfRef ? resolver.resolve(value) : value;
    }

    // key がなければ、略した名前 alternate で探す (インライン画像の /W と /Width など)
    public Object get(String key, String alternate) {
        final Object value = get(key);
        return value != null ? value : get(alternate);
    }

    public PdfDict dict(String key) {
        final Object value = get(key);
        return value instanceof PdfDict ? (PdfDict) value : null;
    }

    public PdfArray array(String key) {
        final Object value = get(key);
        return value instanceof PdfArray ? (PdfArray) value : null;
    }

    public PdfStream stream(String key) {
        final Object value = get(key);
        return value instanceof PdfStream ? (PdfStream) value : null;
    }

    // 名前の中身。名前でなければ null
    public String name(String key) {
        final Object value = get(key);
        return value instanceof PdfName ? ((PdfName) value).name : null;
    }

    public boolean isName(String key, String expected) {
        return expected.equals(name(key));
    }

    public byte[] string(String key) {
        final Object value = get(key);
        return value instanceof PdfString ? ((PdfString) value).bytes : null;
    }

    public int integer(String key, int fallback) {
        final Object value = get(key);
        return value instanceof Number ? Objects.toInt((Number) value) : fallback;
    }

    public double number(String key, double fallback) {
        final Object value = get(key);
        if (!(value instanceof Number)) return fallback;
        final double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) ? fallback : number;
    }

    public boolean bool(String key, boolean fallback) {
        final Object value = get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override
    public String toString() {
        final StringBuilder text = new StringBuilder("<<");
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            text.append(" /").append(entry.getKey()).append(' ');
            final Object value = entry.getValue();
            text.append(value instanceof PdfDict || value instanceof PdfStream ? "<<...>>" : String.valueOf(value));
        }
        return text.append(" >>").toString();
    }
}
