package marumasa.marumasa_sign.core.pdf.function;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.io.IOException;

/**
 * Type 0 (標本を並べた表): 入力ごとの格子の点に出力の値を並べ、間は線形補間する
 * (Order 3 の 3 次補間も線形で代用する)。
 */
final class SampledFunction extends PdfFunction {

    // 表の値の数の上限 (Size の積 × 出力の数)。巨大な Size でメモリを使い切らないように
    private static final long MAX_SAMPLES = 1 << 24;

    private int[] size;
    private double[] encode;
    private double[] decode;
    // 0 から 1 にそろえた表の値
    private float[] samples;
    private int outputs;

    @Override
    protected void read(PdfDict dict, PdfStream stream, int depth, int[] count) throws IOException {
        if (range == null) throw new IOException("Sampled function without Range");
        outputs = range.length / 2;
        final int m = inputs();
        // 評価は 2^m 個の角の和なので、入力の多いものは扱わない
        if (m > 8) throw new IOException("Sampled function has too many inputs");
        final PdfArray sizeArray = dict.array("Size");
        if (sizeArray == null || sizeArray.size() < m) throw new IOException("Bad Size");
        size = new int[m];
        long total = outputs;
        for (int i = 0; i < m; i++) {
            size[i] = sizeArray.integer(i, 0);
            if (size[i] < 1) throw new IOException("Bad Size");
            total *= size[i];
            if (total > MAX_SAMPLES) throw new IOException("Sampled function is too large");
        }
        final int bits = dict.integer("BitsPerSample", 0);
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 12 && bits != 16 && bits != 24 && bits != 32) {
            throw new IOException("Bad BitsPerSample");
        }
        final PdfArray e = dict.array("Encode");
        encode = e == null ? null : e.numbers();
        if (encode == null || encode.length < 2 * m) {
            encode = new double[2 * m];
            for (int i = 0; i < m; i++) encode[2 * i + 1] = size[i] - 1;
        }
        final PdfArray d = dict.array("Decode");
        decode = d == null ? null : d.numbers();
        if (decode == null || decode.length < 2 * outputs) decode = range.clone();

        final byte[] data = stream.decode(limit());
        samples = new float[(int) total];
        final double max = bits == 32 ? 4294967295.0 : (1L << bits) - 1;
        long bit = 0;
        final long available = (long) data.length * 8;
        for (int i = 0; i < samples.length; i++, bit += bits) {
            // データが足りなければ、残りは 0
            if (bit + bits > available) break;
            samples[i] = (float) (read(data, bit, bits) / max);
        }
    }

    private static long read(byte[] data, long bit, int bits) {
        long value = 0;
        if ((bit & 7) == 0 && bits % 8 == 0) {
            int p = (int) (bit >> 3);
            for (int i = 0; i < bits / 8; i++) value = (value << 8) | (data[p++] & 0xFF);
            return value;
        }
        for (int i = 0; i < bits; i++) {
            final long b = bit + i;
            value = (value << 1) | ((data[(int) (b >> 3)] >> (7 - (b & 7))) & 1);
        }
        return value;
    }

    @Override
    public int outputs() {
        return outputs;
    }

    @Override
    protected void compute(double[] input, double[] output) {
        final int m = size.length;
        // 入力ごとの、格子の位置 (下の点と、そこからの割合)
        final int[] low = new int[m];
        final double[] fraction = new double[m];
        for (int i = 0; i < m; i++) {
            double e = interpolate(input[i], domain[2 * i], domain[2 * i + 1], encode[2 * i], encode[2 * i + 1]);
            e = clip(e, 0, size[i] - 1);
            low[i] = Math.min((int) Math.floor(e), Math.max(0, size[i] - 2));
            fraction[i] = size[i] == 1 ? 0 : e - low[i];
        }
        for (int j = 0; j < outputs && j < output.length; j++) output[j] = 0;
        // 2^m 個の角の重み付き和 (m は Domain の検査で 16 以下)
        final int corners = 1 << m;
        for (int corner = 0; corner < corners; corner++) {
            double weight = 1;
            long index = 0;
            long stride = 1;
            for (int i = 0; i < m; i++) {
                final boolean up = (corner & (1 << i)) != 0;
                if (up && size[i] == 1) {
                    weight = 0;
                    break;
                }
                weight *= up ? fraction[i] : 1 - fraction[i];
                index += (long) (low[i] + (up ? 1 : 0)) * stride;
                stride *= size[i];
            }
            if (weight == 0) continue;
            final long base = index * outputs;
            for (int j = 0; j < outputs && j < output.length; j++) output[j] += weight * samples[(int) (base + j)];
        }
        for (int j = 0; j < outputs && j < output.length; j++) {
            output[j] = decode[2 * j] + output[j] * (decode[2 * j + 1] - decode[2 * j]);
        }
    }
}
