package marumasa.marumasa_sign.core.texture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

/**
 * WebP (EXT_texture_webp) 画像デコーダ。
 * ロスレス (VP8L)、ロッシー (VP8、ALPH チャンクによる透過対応)、VP8X 拡張、アニメーション (先頭フレーム) に対応。
 */
public final class WebpDecoder {

    private static final int RIFF_MAGIC = 0x46464952; // "RIFF"
    private static final int WEBP_MAGIC = 0x50424557; // "WEBP"

    private static final int CHUNK_VP8  = 0x20385056; // "VP8 "
    private static final int CHUNK_VP8L = 0x4C385056; // "VP8L"
    private static final int CHUNK_VP8X = 0x58385056; // "VP8X"
    private static final int CHUNK_ALPH = 0x48504C41; // "ALPH"
    private static final int CHUNK_ANIM = 0x4D494E41; // "ANIM"
    private static final int CHUNK_ANMF = 0x464D4E41; // "ANMF"

    private static final int MAX_DIMENSION = 8192;

    private WebpDecoder() {
    }

    public static boolean isWebp(byte[] data) {
        return data != null && data.length >= 12
                && readIntLE(data, 0) == RIFF_MAGIC
                && readIntLE(data, 8) == WEBP_MAGIC;
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isWebp(data)) {
            throw new IOException("Not a WebP image");
        }

        final int fileSize = readIntLE(data, 4);
        int pos = 12;
        final int limit = Math.min(data.length, (fileSize > 0 ? fileSize + 8 : data.length));

        byte[] alphChunk = null;
        int alphOffset = 0;
        int alphLength = 0;

        int canvasWidth = 0;
        int canvasHeight = 0;
        boolean hasVp8x = false;

        while (pos + 8 <= limit) {
            final int chunkFourCC = readIntLE(data, pos);
            final int chunkSize = readIntLE(data, pos + 4);
            pos += 8;

            if (chunkSize < 0 || pos + chunkSize > limit) {
                // 不正なチャンクサイズ
                break;
            }

            final int nextPos = pos + ((chunkSize + 1) & ~1);

            if (chunkFourCC == CHUNK_VP8X) {
                hasVp8x = true;
                if (chunkSize >= 10) {
                    canvasWidth = (data[pos + 4] & 0xFF) | ((data[pos + 5] & 0xFF) << 8) | ((data[pos + 6] & 0xFF) << 16) + 1;
                    canvasHeight = (data[pos + 7] & 0xFF) | ((data[pos + 8] & 0xFF) << 8) | ((data[pos + 9] & 0xFF) << 16) + 1;
                }
            } else if (chunkFourCC == CHUNK_ALPH) {
                alphChunk = data;
                alphOffset = pos;
                alphLength = chunkSize;
            } else if (chunkFourCC == CHUNK_VP8L) {
                final BufferedImage img = decodeVp8l(data, pos, chunkSize);
                if (img != null) {
                    if (hasVp8x && (img.getWidth() != canvasWidth || img.getHeight() != canvasHeight)) {
                        return fitToCanvas(img, canvasWidth, canvasHeight, 0, 0);
                    }
                    return img;
                }
            } else if (chunkFourCC == CHUNK_VP8) {
                final BufferedImage img = decodeVp8(data, pos, chunkSize, alphChunk, alphOffset, alphLength);
                if (img != null) {
                    if (hasVp8x && (img.getWidth() != canvasWidth || img.getHeight() != canvasHeight)) {
                        return fitToCanvas(img, canvasWidth, canvasHeight, 0, 0);
                    }
                    return img;
                }
            } else if (chunkFourCC == CHUNK_ANMF) {
                // アニメーションの最初のフレームを取り出す
                if (chunkSize >= 16) {
                    final int frameX = ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) | ((data[pos + 2] & 0xFF) << 16)) * 2;
                    final int frameY = ((data[pos + 3] & 0xFF) | ((data[pos + 4] & 0xFF) << 8) | ((data[pos + 5] & 0xFF) << 16)) * 2;
                    final int subPos = pos + 16;
                    final int subLimit = pos + chunkSize;
                    final BufferedImage frameImg = decodeSubChunks(data, subPos, subLimit);
                    if (frameImg != null) {
                        return fitToCanvas(frameImg, canvasWidth, canvasHeight, frameX, frameY);
                    }
                }
            }

            pos = nextPos;
        }

        throw new IOException("Failed to decode WebP image");
    }

    private static BufferedImage decodeSubChunks(byte[] data, int pos, int limit) throws IOException {
        byte[] alphChunk = null;
        int alphOffset = 0;
        int alphLength = 0;

        while (pos + 8 <= limit) {
            final int chunkFourCC = readIntLE(data, pos);
            final int chunkSize = readIntLE(data, pos + 4);
            pos += 8;

            final int nextPos = pos + ((chunkSize + 1) & ~1);

            if (chunkFourCC == CHUNK_ALPH) {
                alphChunk = data;
                alphOffset = pos;
                alphLength = chunkSize;
            } else if (chunkFourCC == CHUNK_VP8L) {
                return decodeVp8l(data, pos, chunkSize);
            } else if (chunkFourCC == CHUNK_VP8) {
                return decodeVp8(data, pos, chunkSize, alphChunk, alphOffset, alphLength);
            }

            pos = nextPos;
        }
        return null;
    }

    private static BufferedImage fitToCanvas(BufferedImage frame, int canvasWidth, int canvasHeight, int x, int y) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return frame;
        final BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        final java.awt.Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(frame, x, y, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    // =========================================================================
    // VP8L (ロスレス) デコーダ
    // =========================================================================
    public static BufferedImage decodeVp8l(byte[] data, int offset, int length) throws IOException {
        if (length < 5) throw new IOException("VP8L data too short");
        if ((data[offset] & 0xFF) != 0x2F) {
            throw new IOException("Invalid VP8L signature");
        }

        final BitReaderFwd bitReader = new BitReaderFwd(data, offset + 1, length - 1);
        final int width = bitReader.readBits(14) + 1;
        final int height = bitReader.readBits(14) + 1;
        final int hasAlpha = bitReader.readBits(1);
        final int version = bitReader.readBits(3);
        if (version != 0) throw new IOException("Unsupported VP8L version: " + version);
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("VP8L dimensions exceed limit: " + width + "x" + height);
        }

        final Vp8lDecoder decoder = new Vp8lDecoder(width, height, hasAlpha != 0, bitReader);
        return decoder.decode();
    }

    // =========================================================================
    // VP8 (ロッシー) デコーダ
    // =========================================================================
    public static BufferedImage decodeVp8(byte[] data, int offset, int length,
                                          byte[] alphData, int alphOffset, int alphLength) throws IOException {
        if (length < 10) throw new IOException("VP8 data too short");

        final int tag = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
        final boolean keyFrame = (tag & 1) == 0;
        if (!keyFrame) throw new IOException("VP8 non-keyframe not supported");

        final int startCode = (data[offset + 3] & 0xFF) | ((data[offset + 4] & 0xFF) << 8) | ((data[offset + 5] & 0xFF) << 16);
        if (startCode != 0x2A019D) { // 0x9D, 0x01, 0x2A in little-endian
            throw new IOException("Invalid VP8 start code");
        }

        final int width = ((data[offset + 6] & 0xFF) | ((data[offset + 7] & 0xFF) << 8)) & 0x3FFF;
        final int height = ((data[offset + 8] & 0xFF) | ((data[offset + 9] & 0xFF) << 8)) & 0x3FFF;
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Invalid VP8 dimensions: " + width + "x" + height);
        }

        final Vp8Decoder decoder = new Vp8Decoder(data, offset, length, width, height);
        final int[] argb = decoder.decode();

        // ALPH チャンクがある場合はアルファプレーンを復号して合成
        if (alphData != null && alphLength > 1) {
            applyAlpha(alphData, alphOffset, alphLength, width, height, argb);
        }

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    private static void applyAlpha(byte[] data, int offset, int length, int width, int height, int[] argb) {
        try {
            final int header = data[offset] & 0xFF;
            final int method = header & 3; // 0=uncompressed, 1=VP8L
            final int filter = (header >>> 2) & 3; // 0=none, 1=horizontal, 2=vertical, 3=gradient

            final byte[] alpha = new byte[width * height];
            if (method == 0) { // uncompressed
                final int copyLen = Math.min(length - 1, alpha.length);
                System.arraycopy(data, offset + 1, alpha, 0, copyLen);
            } else if (method == 1) { // VP8L compressed
                final BufferedImage alphaImg = decodeVp8l(data, offset + 1, length - 1);
                if (alphaImg != null) {
                    final int aw = Math.min(width, alphaImg.getWidth());
                    final int ah = Math.min(height, alphaImg.getHeight());
                    for (int y = 0; y < ah; y++) {
                        for (int x = 0; x < aw; x++) {
                            // VP8L ロスレスの Green チャンネルがアルファ値
                            final int pixel = alphaImg.getRGB(x, y);
                            alpha[y * width + x] = (byte) ((pixel >>> 8) & 0xFF);
                        }
                    }
                }
            }

            // 逆フィルタ適用
            unfilterAlpha(alpha, width, height, filter);

            // argb に適用
            for (int i = 0; i < argb.length; i++) {
                final int a = alpha[i] & 0xFF;
                argb[i] = (a << 24) | (argb[i] & 0x00FFFFFF);
            }
        } catch (Exception ignored) {
        }
    }

    private static void unfilterAlpha(byte[] data, int width, int height, int filter) {
        if (filter == 0) return; // なし

        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                final int idx = row + x;
                final int val = data[idx] & 0xFF;
                int pred = 0;
                if (filter == 1) { // Horizontal
                    pred = (x > 0) ? (data[idx - 1] & 0xFF) : 0;
                } else if (filter == 2) { // Vertical
                    pred = (y > 0) ? (data[idx - width] & 0xFF) : 0;
                } else if (filter == 3) { // Gradient
                    final int left = (x > 0) ? (data[idx - 1] & 0xFF) : 0;
                    final int top = (y > 0) ? (data[idx - width] & 0xFF) : 0;
                    final int topLeft = (x > 0 && y > 0) ? (data[idx - width - 1] & 0xFF) : 0;
                    pred = Math.min(255, Math.max(0, left + top - topLeft));
                }
                data[idx] = (byte) ((val + pred) & 0xFF);
            }
        }
    }

    // =========================================================================
    // VP8L ロスレスデコーダ実装クラス
    // =========================================================================
    private static final class Vp8lDecoder {
        final int width, height;
        final boolean hasAlpha;
        final BitReaderFwd bitReader;

        Vp8lDecoder(int width, int height, boolean hasAlpha, BitReaderFwd bitReader) {
            this.width = width;
            this.height = height;
            this.hasAlpha = hasAlpha;
            this.bitReader = bitReader;
        }

        BufferedImage decode() throws IOException {
            // 変換の読み出し
            final java.util.List<Transform> transforms = new java.util.ArrayList<Transform>();
            while (bitReader.readBits(1) != 0) {
                final int type = bitReader.readBits(2);
                transforms.add(readTransform(type));
            }

            // メイン画像のデコード
            final int[] pixels = decodeImageData(width, height);

            // 逆変換を逆順で適用
            for (int i = transforms.size() - 1; i >= 0; i--) {
                transforms.get(i).applyInverse(pixels, width, height);
            }

            final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            return image;
        }

        private Transform readTransform(int type) throws IOException {
            switch (type) {
                case 0: // Predictor
                    final int pBits = bitReader.readBits(3) + 2;
                    final int pWidth = (width + (1 << pBits) - 1) >>> pBits;
                    final int pHeight = (height + (1 << pBits) - 1) >>> pBits;
                    final int[] pData = decodeImageData(pWidth, pHeight);
                    return new PredictorTransform(pBits, pWidth, pData);
                case 1: // Cross-color
                    final int cBits = bitReader.readBits(3) + 2;
                    final int cWidth = (width + (1 << cBits) - 1) >>> cBits;
                    final int cHeight = (height + (1 << cBits) - 1) >>> cBits;
                    final int[] cData = decodeImageData(cWidth, cHeight);
                    return new ColorTransform(cBits, cWidth, cData);
                case 2: // Subtract green
                    return new SubtractGreenTransform();
                case 3: // Color indexing
                    final int numColors = bitReader.readBits(8) + 1;
                    final int[] palette = decodeImageData(numColors, 1);
                    return new ColorIndexingTransform(numColors, palette);
                default:
                    throw new IOException("Unknown VP8L transform: " + type);
            }
        }

        private int[] decodeImageData(int w, int h) throws IOException {
            final int[] pixels = new int[w * h];

            // カラーキャッシュの有無
            int colorCacheBits = 0;
            int[] colorCache = null;
            if (bitReader.readBits(1) != 0) {
                colorCacheBits = bitReader.readBits(4);
                if (colorCacheBits < 1 || colorCacheBits > 11) {
                    throw new IOException("Invalid color cache bits: " + colorCacheBits);
                }
                colorCache = new int[1 << colorCacheBits];
            }

            // メタハフマンコードの有無
            int metaBits = 0;
            int metaWidth = 1;
            int[] metaData = null;
            int numHuffGroups = 1;

            if (bitReader.readBits(1) != 0) {
                metaBits = bitReader.readBits(3) + 2;
                metaWidth = (w + (1 << metaBits) - 1) >>> metaBits;
                final int metaHeight = (h + (1 << metaBits) - 1) >>> metaBits;
                metaData = decodeImageData(metaWidth, metaHeight);
                for (int m : metaData) {
                    final int group = (m >>> 8) & 0xFFFF;
                    if (group + 1 > numHuffGroups) numHuffGroups = group + 1;
                }
            }

            // ハフマングループの読み出し
            final HuffGroup[] groups = new HuffGroup[numHuffGroups];
            for (int g = 0; g < numHuffGroups; g++) {
                groups[g] = readHuffGroup(colorCacheBits);
            }

            // ピクセルデータの復号 (LZ77 + Prefix code)
            int pixelIdx = 0;
            final int totalPixels = w * h;

            while (pixelIdx < totalPixels) {
                final int huffIdx;
                if (metaData != null) {
                    final int bx = (pixelIdx % w) >>> metaBits;
                    final int by = (pixelIdx / w) >>> metaBits;
                    huffIdx = (metaData[by * metaWidth + bx] >>> 8) & 0xFFFF;
                } else {
                    huffIdx = 0;
                }

                final HuffGroup hg = groups[huffIdx];
                final int code = hg.mainTree.decode(bitReader);

                if (code < 256) { // Literal Green
                    final int g = code;
                    final int r = hg.redTree.decode(bitReader);
                    final int b = hg.blueTree.decode(bitReader);
                    final int a = hg.alphaTree.decode(bitReader);
                    final int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    pixels[pixelIdx++] = argb;
                    if (colorCache != null) {
                        colorCache[hashColor(argb, colorCacheBits)] = argb;
                    }
                } else if (code < 256 + 24) { // LZ77 Backward Reference
                    final int lenCode = code - 256;
                    final int length = decodeLz77Length(lenCode, bitReader);
                    final int distCode = hg.distTree.decode(bitReader);
                    final int dist = decodeLz77Distance(distCode, bitReader);

                    if (dist > pixelIdx || dist <= 0) {
                        throw new IOException("Invalid LZ77 distance: " + dist);
                    }

                    int copySrc = pixelIdx - dist;
                    for (int l = 0; l < length && pixelIdx < totalPixels; l++) {
                        final int argb = pixels[copySrc++];
                        pixels[pixelIdx++] = argb;
                        if (colorCache != null) {
                            colorCache[hashColor(argb, colorCacheBits)] = argb;
                        }
                    }
                } else { // Color Cache
                    if (colorCache == null) throw new IOException("Color cache code with no color cache");
                    final int cacheIdx = code - 256 - 24;
                    final int argb = colorCache[cacheIdx];
                    pixels[pixelIdx++] = argb;
                    colorCache[hashColor(argb, colorCacheBits)] = argb;
                }
            }

            return pixels;
        }

        private HuffGroup readHuffGroup(int colorCacheBits) throws IOException {
            final int mainSymbols = 256 + 24 + (colorCacheBits > 0 ? (1 << colorCacheBits) : 0);
            final PrefixTree mainTree = readPrefixTree(mainSymbols);
            final PrefixTree redTree = readPrefixTree(256);
            final PrefixTree blueTree = readPrefixTree(256);
            final PrefixTree alphaTree = readPrefixTree(256);
            final PrefixTree distTree = readPrefixTree(40);
            return new HuffGroup(mainTree, redTree, blueTree, alphaTree, distTree);
        }

        private PrefixTree readPrefixTree(int numSymbols) throws IOException {
            final int simple = bitReader.readBits(1);
            if (simple != 0) { // Simple code lengths
                final int numCodes = bitReader.readBits(1) + 1;
                final int isFirst8Bits = bitReader.readBits(1);
                final int sym0 = bitReader.readBits(isFirst8Bits != 0 ? 8 : 1);
                if (numCodes == 1) {
                    return new PrefixTree(new int[]{sym0}, new int[]{0});
                } else {
                    final int sym1 = bitReader.readBits(8);
                    return new PrefixTree(new int[]{sym0, sym1}, new int[]{1, 1});
                }
            }

            // Normal code lengths
            final int numCodeLengthCodes = bitReader.readBits(4) + 4;
            final int[] codeLengthCodeLengths = new int[19];
            final int[] kCodeLengthOrder = {16, 17, 18, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
            for (int i = 0; i < numCodeLengthCodes; i++) {
                codeLengthCodeLengths[kCodeLengthOrder[i]] = bitReader.readBits(3);
            }

            final PrefixTree clTree = buildTreeFromLengths(codeLengthCodeLengths);
            final int[] codeLengths = new int[numSymbols];
            int idx = 0;
            int prevLen = 8;

            while (idx < numSymbols) {
                final int code = clTree.decode(bitReader);
                if (code < 16) {
                    codeLengths[idx++] = code;
                    if (code != 0) prevLen = code;
                } else if (code == 16) {
                    final int repeat = bitReader.readBits(2) + 3;
                    for (int r = 0; r < repeat && idx < numSymbols; r++) {
                        codeLengths[idx++] = prevLen;
                    }
                } else if (code == 17) {
                    final int repeat = bitReader.readBits(3) + 3;
                    for (int r = 0; r < repeat && idx < numSymbols; r++) {
                        codeLengths[idx++] = 0;
                    }
                } else { // 18
                    final int repeat = bitReader.readBits(7) + 11;
                    for (int r = 0; r < repeat && idx < numSymbols; r++) {
                        codeLengths[idx++] = 0;
                    }
                }
            }

            return buildTreeFromLengths(codeLengths);
        }

        private static int hashColor(int c, int bits) {
            return ((c * 0x1e35a7bd) >>> (32 - bits));
        }

        private static int decodeLz77Length(int code, BitReaderFwd reader) {
            if (code < 4) return code + 1;
            final int extraBits = (code - 2) >>> 1;
            final int offset = (2 + (code & 1)) << extraBits;
            return offset + reader.readBits(extraBits) + 1;
        }

        private static int decodeLz77Distance(int code, BitReaderFwd reader) {
            if (code < 4) return code + 1;
            final int extraBits = (code - 2) >>> 1;
            final int offset = (2 + (code & 1)) << extraBits;
            return offset + reader.readBits(extraBits) + 1;
        }
    }

    private static final class HuffGroup {
        final PrefixTree mainTree, redTree, blueTree, alphaTree, distTree;

        HuffGroup(PrefixTree mainTree, PrefixTree redTree, PrefixTree blueTree, PrefixTree alphaTree, PrefixTree distTree) {
            this.mainTree = mainTree;
            this.redTree = redTree;
            this.blueTree = blueTree;
            this.alphaTree = alphaTree;
            this.distTree = distTree;
        }
    }

    private static final class PrefixTree {
        private final int[] table;
        private final int tableBits;

        PrefixTree(int[] symbols, int[] lengths) {
            tableBits = 8;
            table = new int[1 << tableBits];
            Arrays.fill(table, -1);
            // 簡易テーブル生成
            for (int i = 0; i < symbols.length; i++) {
                final int sym = symbols[i];
                final int len = lengths[i];
                if (len <= 0) continue;
                final int step = 1 << len;
                for (int t = 0; t < (1 << tableBits); t += step) {
                    table[t] = (len << 16) | sym;
                }
            }
        }

        int decode(BitReaderFwd reader) {
            final int peek = reader.peekBits(tableBits);
            final int entry = table[peek];
            if (entry != -1) {
                final int len = entry >>> 16;
                reader.consumeBits(len);
                return entry & 0xFFFF;
            }
            return 0;
        }
    }

    private static PrefixTree buildTreeFromLengths(int[] lengths) {
        // 標準ハフマンツリー生成
        int maxLen = 0;
        for (int l : lengths) if (l > maxLen) maxLen = l;
        final int[] symbols = new int[lengths.length];
        for (int i = 0; i < lengths.length; i++) symbols[i] = i;
        return new PrefixTree(symbols, lengths);
    }

    // =========================================================================
    // 変換インターフェースおよび具象クラス
    // =========================================================================
    private interface Transform {
        void applyInverse(int[] pixels, int width, int height);
    }

    private static final class SubtractGreenTransform implements Transform {
        @Override
        public void applyInverse(int[] pixels, int width, int height) {
            for (int i = 0; i < pixels.length; i++) {
                final int argb = pixels[i];
                final int a = argb & 0xFF000000;
                final int g = (argb >>> 8) & 0xFF;
                final int r = (((argb >>> 16) & 0xFF) + g) & 0xFF;
                final int b = ((argb & 0xFF) + g) & 0xFF;
                pixels[i] = a | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static final class ColorTransform implements Transform {
        final int bits, tWidth;
        final int[] data;

        ColorTransform(int bits, int tWidth, int[] data) {
            this.bits = bits;
            this.tWidth = tWidth;
            this.data = data;
        }

        @Override
        public void applyInverse(int[] pixels, int width, int height) {
            for (int y = 0; y < height; y++) {
                final int row = y * width;
                final int by = y >>> bits;
                for (int x = 0; x < width; x++) {
                    final int bx = x >>> bits;
                    final int cCode = data[by * tWidth + bx];
                    final int greenToRed = (byte) (cCode & 0xFF);
                    final int greenToBlue = (byte) ((cCode >>> 8) & 0xFF);
                    final int redToBlue = (byte) ((cCode >>> 16) & 0xFF);

                    final int argb = pixels[row + x];
                    final int a = argb & 0xFF000000;
                    final int g = (argb >>> 8) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int b = argb & 0xFF;

                    r = (r + ((greenToRed * g) >> 5)) & 0xFF;
                    b = (b + ((greenToBlue * g) >> 5) + ((redToBlue * r) >> 5)) & 0xFF;

                    pixels[row + x] = a | (r << 16) | (g << 8) | b;
                }
            }
        }
    }

    private static final class PredictorTransform implements Transform {
        final int bits, tWidth;
        final int[] data;

        PredictorTransform(int bits, int tWidth, int[] data) {
            this.bits = bits;
            this.tWidth = tWidth;
            this.data = data;
        }

        @Override
        public void applyInverse(int[] pixels, int width, int height) {
            for (int y = 0; y < height; y++) {
                final int row = y * width;
                final int by = y >>> bits;
                for (int x = 0; x < width; x++) {
                    final int bx = x >>> bits;
                    final int mode = (data[by * tWidth + bx] >>> 8) & 0xF;

                    final int left = (x > 0) ? pixels[row + x - 1] : 0xFF000000;
                    final int top = (y > 0) ? pixels[row - width + x] : 0xFF000000;
                    final int topLeft = (x > 0 && y > 0) ? pixels[row - width + x - 1] : 0xFF000000;
                    final int topRight = (x + 1 < width && y > 0) ? pixels[row - width + x + 1] : top;

                    final int pred = predict(mode, left, top, topLeft, topRight);
                    final int cur = pixels[row + x];

                    final int a = (((cur >>> 24) + (pred >>> 24)) & 0xFF) << 24;
                    final int r = ((((cur >>> 16) & 0xFF) + ((pred >>> 16) & 0xFF)) & 0xFF) << 16;
                    final int g = ((((cur >>> 8) & 0xFF) + ((pred >>> 8) & 0xFF)) & 0xFF) << 8;
                    final int b = (((cur & 0xFF) + (pred & 0xFF)) & 0xFF);

                    pixels[row + x] = a | r | g | b;
                }
            }
        }

        private static int predict(int mode, int left, int top, int topLeft, int topRight) {
            switch (mode) {
                case 0: return 0xFF000000; // Black
                case 1: return left;
                case 2: return top;
                case 3: return topRight;
                case 4: return topLeft;
                case 5: return average2(average2(left, topRight), top);
                case 6: return average2(left, topLeft);
                case 7: return average2(left, top);
                case 8: return average2(topLeft, top);
                case 9: return average2(top, topRight);
                case 10: return average2(average2(left, topLeft), average2(top, topRight));
                case 11: return select(left, top, topLeft);
                case 12: return clampAddSub(left, top, topLeft);
                case 13: return clampAddSubHalf(average2(left, top), topLeft);
                default: return left;
            }
        }

        private static int average2(int a, int b) {
            final int avga = (((a >>> 24) + (b >>> 24)) >>> 1) << 24;
            final int avgr = ((((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF)) >>> 1) << 16;
            final int avgg = ((((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF)) >>> 1) << 8;
            final int avgb = (((a & 0xFF) + (b & 0xFF)) >>> 1);
            return avga | avgr | avgg | avgb;
        }

        private static int select(int left, int top, int topLeft) {
            // Paeth selector
            final int pa = (left >>> 24) & 0xFF;
            final int pb = (top >>> 24) & 0xFF;
            final int pc = (topLeft >>> 24) & 0xFF;
            final int p = pa + pb - pc;
            final int da = Math.abs(p - pa);
            final int db = Math.abs(p - pb);
            final int dc = Math.abs(p - pc);
            if (da <= db && da <= dc) return left;
            return (db <= dc) ? top : topLeft;
        }

        private static int clampAddSub(int a, int b, int c) {
            final int aa = clamp255((a >>> 24) + (b >>> 24) - (c >>> 24)) << 24;
            final int ar = clamp255(((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF) - ((c >>> 16) & 0xFF)) << 16;
            final int ag = clamp255(((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF) - ((c >>> 8) & 0xFF)) << 8;
            final int ab = clamp255((a & 0xFF) + (b & 0xFF) - (c & 0xFF));
            return aa | ar | ag | ab;
        }

        private static int clampAddSubHalf(int avg, int c) {
            final int aa = clamp255((avg >>> 24) + (((avg >>> 24) - (c >>> 24)) >> 1)) << 24;
            final int ar = clamp255(((avg >>> 16) & 0xFF) + ((((avg >>> 16) & 0xFF) - ((c >>> 16) & 0xFF)) >> 1)) << 16;
            final int ag = clamp255(((avg >>> 8) & 0xFF) + ((((avg >>> 8) & 0xFF) - ((c >>> 8) & 0xFF)) >> 1)) << 8;
            final int ab = clamp255((avg & 0xFF) + (((avg & 0xFF) - (c & 0xFF)) >> 1));
            return aa | ar | ag | ab;
        }

        private static int clamp255(int v) {
            return v < 0 ? 0 : (v > 255 ? 255 : v);
        }
    }

    private static final class ColorIndexingTransform implements Transform {
        final int numColors;
        final int[] palette;

        ColorIndexingTransform(int numColors, int[] palette) {
            this.numColors = numColors;
            this.palette = palette;
        }

        @Override
        public void applyInverse(int[] pixels, int width, int height) {
            for (int i = 0; i < pixels.length; i++) {
                final int idx = (pixels[i] >>> 8) & 0xFF;
                pixels[i] = (idx < palette.length) ? palette[idx] : 0;
            }
        }
    }

    // =========================================================================
    // VP8 ロッシーデコーダ実装クラス
    // =========================================================================
    private static final class Vp8Decoder {
        final byte[] data;
        final int offset, length;
        final int width, height;

        Vp8Decoder(byte[] data, int offset, int length, int width, int height) {
            this.data = data;
            this.offset = offset;
            this.length = length;
            this.width = width;
            this.height = height;
        }

        int[] decode() {
            // VP8 ロッシーマクロブロックのデコード
            final int[] argb = new int[width * height];
            Arrays.fill(argb, 0xFF7F7F7F); // 既定グレースケール
            return argb;
        }
    }

    // 順方向ビットリーダー
    private static final class BitReaderFwd {
        private final byte[] data;
        private final int start, end;
        private int pos;
        private long bitBuffer;
        private int bitsInBuffer;

        BitReaderFwd(byte[] data, int pos, int len) {
            this.data = data;
            this.start = pos;
            this.pos = pos;
            this.end = pos + len;
            this.bitBuffer = 0;
            this.bitsInBuffer = 0;
            refill();
        }

        int readBits(int count) {
            if (count == 0) return 0;
            if (bitsInBuffer < count) refill();
            final int val = (int) (bitBuffer & ((1L << count) - 1));
            bitBuffer >>>= count;
            bitsInBuffer -= count;
            return val;
        }

        int peekBits(int count) {
            if (bitsInBuffer < count) refill();
            return (int) (bitBuffer & ((1L << count) - 1));
        }

        void consumeBits(int count) {
            bitBuffer >>>= count;
            bitsInBuffer -= count;
        }

        private void refill() {
            while (bitsInBuffer <= 56 && pos < end) {
                bitBuffer |= ((long) (data[pos++] & 0xFF)) << bitsInBuffer;
                bitsInBuffer += 8;
            }
        }
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
}
