package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BigTIFF の 64 ビットの値で足し算があふれても、範囲の確認をすり抜けないことを確かめる (引数は要らない)。
 * 読めないファイルが FFmpeg に回って隠れないように、TIFF のデコーダを直接呼ぶ。問題があれば終了コード 1 で終わる。
 */
public final class TiffCheck {

    private static int failed;

    public static void main(String[] args) throws Exception {
        // 以前は長さを切り詰められず、絵が読めなかった
        expectPixels("huge strip length", bigTiff(8, Long.MAX_VALUE, false));
        // 以前は項目を読み飛ばせず、絵が読めなかった
        expectPixels("huge value offset", bigTiff(8, 2, true));
        // 以前は掛け算があふれて、負の数のまま読み進めていた
        try {
            TiffDecoder.decode(bigTiff(Long.MIN_VALUE, 2, false));
            fail("negative entry count was decoded");
        } catch (IOException e) {
            // 期待どおり
        }
        System.out.println("TIFF checks: " + failed + " problems");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void expectPixels(String label, byte[] tiff) {
        try {
            final DecodedImage image = TiffDecoder.decode(tiff);
            if (image.frames[0][0] != 0xFF404040 || image.frames[0][1] != 0xFF808080) fail(label + ": wrong pixels");
        } catch (IOException | RuntimeException e) {
            fail(label + ": " + e);
        }
    }

    // 幅 2、高さ 1、8 ビットのグレーの BigTIFF を作る
    private static byte[] bigTiff(long declaredEntries, long stripLength, boolean hugeValueOffset) {
        final int entries = hugeValueOffset ? 8 : 7;
        final int ifd = 16;
        final int data = ifd + 8 + entries * 20 + 8;
        final ByteBuffer out = ByteBuffer.allocate(data + 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(ifd);
        // 項目数が負のときは、int に直したあとも項目が読まれるように、下位のビットに本来の数を残す
        out.putLong(declaredEntries < 0 ? declaredEntries | entries : entries);
        entry(out, 256, 3, 1, 2);
        entry(out, 257, 3, 1, 1);
        entry(out, 258, 3, 1, 8);
        entry(out, 262, 3, 1, 1);
        entry(out, 273, 16, 1, data);
        entry(out, 277, 3, 1, 1);
        entry(out, 279, 16, 1, stripLength);
        // 8 バイトに入らない値 (16 バイト) を、ファイルのはるか先に置いたと書く
        if (hugeValueOffset) entry(out, 339, 3, 8, Long.MAX_VALUE);
        out.putLong(0);
        out.put((byte) 0x40).put((byte) 0x80);
        return out.array();
    }

    private static void entry(ByteBuffer out, int tag, int type, long count, long value) {
        out.putShort((short) tag).putShort((short) type).putLong(count).putLong(value);
    }

    private static void fail(String message) {
        System.out.println("FAILED: " + message);
        failed++;
    }
}
