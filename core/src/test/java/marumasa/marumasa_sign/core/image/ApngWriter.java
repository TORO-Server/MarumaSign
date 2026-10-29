package marumasa.marumasa_sign.core.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 試験用に、全体と同じ大きさのコマを並べた APNG (RGBA、8 ビット) を作る。
 */
public final class ApngWriter {

    private ApngWriter() {
    }

    /**
     * @param frames  コマごとの ARGB の画素 (width x height)
     * @param delayNum コマの表示時間の分子 (秒)
     * @param delayDen コマの表示時間の分母
     * @param blendOp 重ね方 (0: 置き換え、1: 透明度で重ねる)
     */
    public static byte[] write(int width, int height, int[][] frames, int delayNum, int delayDen, int blendOp) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        chunk(out, "IHDR", ints(width, height), (byte) 8, (byte) 6, (byte) 0, (byte) 0, (byte) 0);
        chunk(out, "acTL", ints(frames.length, 0));
        int sequence = 0;
        for (int f = 0; f < frames.length; f++) {
            final ByteArrayOutputStream control = new ByteArrayOutputStream();
            control.write(ints(sequence++, width, height, 0, 0));
            control.write(new byte[]{(byte) (delayNum >> 8), (byte) delayNum, (byte) (delayDen >> 8), (byte) delayDen, 0, (byte) blendOp});
            chunk(out, "fcTL", control.toByteArray());
            final byte[] image = deflate(frames[f], width, height);
            if (f == 0) {
                chunk(out, "IDAT", image);
            } else {
                final ByteArrayOutputStream data = new ByteArrayOutputStream();
                data.write(ints(sequence++));
                data.write(image);
                chunk(out, "fdAT", data.toByteArray());
            }
        }
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(int[] argb, int width, int height) {
        final byte[] raw = new byte[height * (1 + width * 4)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            raw[p++] = 0;
            for (int x = 0; x < width; x++) {
                final int c = argb[y * width + x];
                raw[p++] = (byte) (c >> 16);
                raw[p++] = (byte) (c >> 8);
                raw[p++] = (byte) c;
                raw[p++] = (byte) (c >>> 24);
            }
        }
        final Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] ints(int... values) {
        final byte[] bytes = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            for (int k = 0; k < 4; k++) bytes[i * 4 + k] = (byte) (values[i] >>> (24 - 8 * k));
        }
        return bytes;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data, byte... more) throws IOException {
        final byte[] body = new byte[data.length + more.length];
        System.arraycopy(data, 0, body, 0, data.length);
        System.arraycopy(more, 0, body, data.length, more.length);
        final byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(ints(body.length));
        out.write(name);
        out.write(body);
        final CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.write(ints((int) crc.getValue()));
    }
}
