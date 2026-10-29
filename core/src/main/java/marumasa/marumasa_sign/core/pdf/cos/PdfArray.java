package marumasa.marumasa_sign.core.pdf.cos;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF の配列 ([ ... ])。get などは参照を中身に置き換えて返す。
 */
public final class PdfArray {

    private final Resolver resolver;
    private final List<Object> items;

    public PdfArray(Resolver resolver) {
        this(resolver, new ArrayList<>());
    }

    public PdfArray(Resolver resolver, List<Object> items) {
        this.resolver = resolver;
        this.items = items;
    }

    public void add(Object value) {
        items.add(value);
    }

    public int size() {
        return items.size();
    }

    public Object raw(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public Object get(int index) {
        final Object value = raw(index);
        return value instanceof PdfRef ? resolver.resolve(value) : value;
    }

    public PdfDict dict(int index) {
        final Object value = get(index);
        return value instanceof PdfDict ? (PdfDict) value : null;
    }

    public PdfArray array(int index) {
        final Object value = get(index);
        return value instanceof PdfArray ? (PdfArray) value : null;
    }

    public PdfStream stream(int index) {
        final Object value = get(index);
        return value instanceof PdfStream ? (PdfStream) value : null;
    }

    public String name(int index) {
        final Object value = get(index);
        return value instanceof PdfName ? ((PdfName) value).name : null;
    }

    public double number(int index, double fallback) {
        final Object value = get(index);
        if (!(value instanceof Number)) return fallback;
        final double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) ? fallback : number;
    }

    public int integer(int index, int fallback) {
        final Object value = get(index);
        return value instanceof Number ? Objects.toInt((Number) value) : fallback;
    }

    // すべてが数なら、その値。数でないものがあれば null
    public double[] numbers() {
        final double[] result = new double[items.size()];
        for (int i = 0; i < result.length; i++) {
            final Object value = get(i);
            if (!(value instanceof Number)) return null;
            result[i] = ((Number) value).doubleValue();
            if (Double.isNaN(result[i]) || Double.isInfinite(result[i])) return null;
        }
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < items.size() && i < 32; i++) {
            if (i > 0) text.append(' ');
            final Object value = items.get(i);
            text.append(value instanceof PdfDict || value instanceof PdfStream ? "<<...>>" : String.valueOf(value));
        }
        if (items.size() > 32) text.append(" ...");
        return text.append(']').toString();
    }
}
