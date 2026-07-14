package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.type.AnimationFrame;
import marumasa.marumasa_sign.type.DecodedAnimation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static java.lang.System.arraycopy;

public final class GifDecoder {
    static final class BitReader {
        private int nextBitToRead;
        private int numberOfBitsToRead;
        private int bitMask;
        private byte[] bytes;

        private void init(final byte[] bytes) {
            this.bytes = bytes;
            nextBitToRead = 0;
        }

        private int read() {
            int byteIndex = nextBitToRead >>> 3;
            int bitsToShiftRight = nextBitToRead & 7;
            int byte0 = 0, byte1 = 0, byte2 = 0;
            if (byteIndex < bytes.length) {
                byte0 = bytes[byteIndex++] & 0xFF;
            }
            if (byteIndex < bytes.length) {
                byte1 = bytes[byteIndex++] & 0xFF;
            }
            if (byteIndex < bytes.length) {
                byte2 = bytes[byteIndex] & 0xFF;
            }
            int buffer = ((byte2 << 8 | byte1) << 8 | byte0) >>> bitsToShiftRight;
            nextBitToRead += numberOfBitsToRead;
            return buffer & bitMask;
        }

        private void setNumberOfBitsToRead(final int numberOfBitsToRead) {
            this.numberOfBitsToRead = numberOfBitsToRead;
            bitMask = (1 << numberOfBitsToRead) - 1;
        }
    }

    static final class CodeTable {
        private final int[][] table;
        private int initTableSize;
        private int initCodeSize;
        private int initCodeLimit;
        private int codeSize;
        private int nextCode;
        private int nextCodeLimit;
        private BitReader bitReader;

        public CodeTable() {
            table = new int[4096][1];
        }

        private int add(final int[] indices) {
            if (nextCode < 4096) {
                if (nextCode == nextCodeLimit && codeSize < 12) {
                    codeSize++;
                    bitReader.setNumberOfBitsToRead(codeSize);
                    nextCodeLimit = (1 << codeSize) - 1;
                }
                table[nextCode++] = indices;
            }
            return codeSize;
        }

        private int clear() {
            codeSize = initCodeSize;
            bitReader.setNumberOfBitsToRead(codeSize);
            nextCodeLimit = initCodeLimit;
            nextCode = initTableSize;
            return codeSize;
        }

        private void init(final GifFrame fr, final int[] activeColTbl, final BitReader br) {
            this.bitReader = br;
            final int numColors = activeColTbl.length;
            initCodeSize = fr.firstCodeSize;
            initCodeLimit = (1 << initCodeSize) - 1;
            initTableSize = fr.endOfInfoCode + 1;
            nextCode = initTableSize;
            for (int c = 0; c < fr.clearCode; c++) {
                table[c][0] = 0;
            }
            for (int c = numColors - 1; c >= 0; c--) {
                table[c][0] = activeColTbl[c];
            }
            table[fr.clearCode] = new int[]{fr.clearCode};
            table[fr.endOfInfoCode] = new int[]{fr.endOfInfoCode};
            if (fr.transpColFlag && fr.transpColIndex < numColors) {
                table[fr.transpColIndex][0] = 0;
            }
        }
    }

    static final class GifFrame {
        private int disposalMethod;
        private boolean transpColFlag;
        private int delay;
        private int transpColIndex;
        private int x;
        private int y;
        private int w;
        private int h;
        private int wh;
        private boolean hasLocColTbl;
        private boolean interlaceFlag;
        private int sizeOfLocColTbl;
        private int[] localColTbl;
        private int firstCodeSize;
        private int clearCode;
        private int endOfInfoCode;
        private byte[] data;
    }

    public static final class GifImage {
        public String header;
        private int w;
        private int h;
        private int wh;
        public boolean hasGlobColTbl;
        public int colorResolution;
        public boolean sortFlag;
        public int sizeOfGlobColTbl;
        public int bgColIndex;
        public int pxAspectRatio;
        public int[] globalColTbl;
        private final List<GifFrame> frames = new ArrayList<>(64);
        public String appId = "";
        public String appAuthCode = "";
        public int repetitions = 0;
        private final BitReader bits = new BitReader();
        private final CodeTable codes = new CodeTable();

        private int[] decode(final GifFrame fr, final int[] activeColTbl) {
            codes.init(fr, activeColTbl, bits);
            bits.init(fr.data);
            final int clearCode = fr.clearCode, endCode = fr.endOfInfoCode;
            final int[] out = new int[fr.wh];
            final int[][] tbl = codes.table;
            int outPos = 0;
            codes.clear();
            int firstCode = bits.read();
            int code;
            if (firstCode == clearCode) {
                code = bits.read();
            } else {
                code = firstCode;
            }
            int[] pixels = tbl[code];
            arraycopy(pixels, 0, out, outPos, pixels.length);
            outPos += pixels.length;
            try {
                while (true) {
                    final int prevCode = code;
                    code = bits.read();
                    if (code == clearCode) {
                        codes.clear();
                        code = bits.read();
                        pixels = tbl[code];
                        arraycopy(pixels, 0, out, outPos, pixels.length);
                        outPos += pixels.length;
                        continue;
                    } else if (code == endCode) {
                        break;
                    }
                    final int[] prevVals = tbl[prevCode];
                    final int[] prevValsAndK = new int[prevVals.length + 1];
                    arraycopy(prevVals, 0, prevValsAndK, 0, prevVals.length);
                    if (code < codes.nextCode) {
                        pixels = tbl[code];
                        arraycopy(pixels, 0, out, outPos, pixels.length);
                        outPos += pixels.length;
                        prevValsAndK[prevVals.length] = tbl[code][0];
                    } else {
                        prevValsAndK[prevVals.length] = prevVals[0];
                        arraycopy(prevValsAndK, 0, out, outPos, prevValsAndK.length);
                        outPos += prevValsAndK.length;
                    }
                    codes.add(prevValsAndK);
                }
            } catch (final ArrayIndexOutOfBoundsException ignored) {
                System.out.println("DEBUG: ArrayIndexOutOfBoundsException in decode!");
                ignored.printStackTrace();
            }
            return out;
        }

        private int[] deinterlace(final int[] src, final GifFrame fr) {
            final int w = fr.w, h = fr.h, wh = fr.wh;
            final int[] dest = new int[src.length];
            final int set2Y = (h + 7) >>> 3;
            final int set3Y = set2Y + ((h + 3) >>> 3);
            final int set4Y = set3Y + ((h + 1) >>> 2);
            final int set2 = w * set2Y, set3 = w * set3Y, set4 = w * set4Y;
            final int w2 = w << 1, w4 = w2 << 1, w8 = w4 << 1;
            int from = 0, to = 0;
            for (; from < set2; from += w, to += w8) {
                arraycopy(src, from, dest, to, w);
            }
            for (to = w4; from < set3; from += w, to += w8) {
                arraycopy(src, from, dest, to, w);
            }
            for (to = w2; from < set4; from += w, to += w4) {
                arraycopy(src, from, dest, to, w);
            }
            for (to = w; from < wh; from += w, to += w2) {
                arraycopy(src, from, dest, to, w);
            }
            return dest;
        }

        public DecodedAnimation toDecodedAnimation() {
            AnimationCanvas canvas = new AnimationCanvas(w, h);
            List<AnimationFrame> animFrames = new ArrayList<>(frames.size());

            for (GifFrame fr : frames) {
                final int[] activeColTbl = fr.hasLocColTbl ? fr.localColTbl : globalColTbl;
                int[] pixels = decode(fr, activeColTbl);
                if (fr.interlaceFlag) {
                    pixels = deinterlace(pixels, fr);
                }

                // GIF disposal method: 
                // 0, 1 -> 0 (NONE)
                // 2 -> 1 (BACKGROUND)
                // 3 -> 2 (PREVIOUS)
                int disposal = 0;
                if (fr.disposalMethod == 2) {
                    disposal = 1;
                } else if (fr.disposalMethod == 3) {
                    disposal = 2;
                }

                // Blend: always BLEND (0)
                canvas.drawFrame(pixels, fr.x, fr.y, fr.w, fr.h, 0, disposal);

                // delay is in 1/100 seconds, convert to milliseconds (multiply by 10)
                int delayMs = fr.delay * 10;
                if (delayMs < 10) {
                    delayMs = 100; // standard fallback
                }

                animFrames.add(new AnimationFrame(canvas.getCanvasCopy(), delayMs));

                canvas.postDraw(fr.x, fr.y, fr.w, fr.h, disposal);
            }

            return new DecodedAnimation(w, h, repetitions, animFrames);
        }
    }

    public static DecodedAnimation read(final byte[] in) throws IOException {
        final GifImage img = new GifImage();
        GifFrame frame = null;
        int pos = readHeader(in, img);
        pos = readLogicalScreenDescriptor(img, in, pos);
        if (img.hasGlobColTbl) {
            img.globalColTbl = new int[img.sizeOfGlobColTbl];
            pos = readColTbl(in, img.globalColTbl, pos);
        }
        while (pos < in.length) {
            final int block = in[pos] & 0xFF;
            switch (block) {
                case 0x21:
                    if (pos + 1 >= in.length) {
                        throw new IOException("Unexpected end of file.");
                    }
                    switch (in[pos + 1] & 0xFF) {
                        case 0xFE:
                            pos = readTextExtension(in, pos);
                            break;
                        case 0xFF:
                            pos = readAppExt(img, in, pos);
                            break;
                        case 0x01:
                            frame = null;
                            pos = readTextExtension(in, pos);
                            break;
                        case 0xF9:
                            if (frame == null) {
                                frame = new GifFrame();
                                img.frames.add(frame);
                            }
                            pos = readGraphicControlExt(frame, in, pos);
                            break;
                        default:
                            pos = readTextExtension(in, pos);
                            break;
                    }
                    break;
                case 0x2C:
                    if (frame == null) {
                        frame = new GifFrame();
                        img.frames.add(frame);
                    }
                    pos = readImgDescr(frame, in, pos);
                    if (frame.hasLocColTbl) {
                        frame.localColTbl = new int[frame.sizeOfLocColTbl];
                        pos = readColTbl(in, frame.localColTbl, pos);
                    }
                    pos = readImgData(frame, in, pos);
                    frame = null;
                    break;
                case 0x3B:
                    return img.toDecodedAnimation();
                default:
                    final double progress = 1.0 * pos / in.length;
                    if (progress < 0.9) {
                        throw new IOException("Unknown block at: " + pos);
                    }
                    pos = in.length;
            }
        }
        return img.toDecodedAnimation();
    }

    public static DecodedAnimation read(final InputStream is) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final byte[] temp = new byte[4096];
        int read;
        while ((read = is.read(temp, 0, temp.length)) != -1) {
            buffer.write(temp, 0, read);
        }
        return read(buffer.toByteArray());
    }

    static int readAppExt(final GifImage img, final byte[] in, int i) {
        img.appId = new String(in, i + 3, 8);
        img.appAuthCode = new String(in, i + 11, 3);
        i += 14;
        final int subBlockSize = in[i] & 0xFF;
        if (subBlockSize == 3) {
            img.repetitions = in[i + 2] & 0xFF | (in[i + 3] & 0xFF) << 8;
            return i + 5;
        }
        while ((in[i] & 0xFF) != 0) {
            i += (in[i] & 0xFF) + 1;
        }
        return i + 1;
    }

    static int readColTbl(final byte[] in, final int[] colors, int i) {
        final int numColors = colors.length;
        for (int c = 0; c < numColors; c++) {
            final int a = 0xFF;
            final int r = in[i++] & 0xFF;
            final int g = in[i++] & 0xFF;
            final int b = in[i++] & 0xFF;
            colors[c] = ((a << 8 | r) << 8 | g) << 8 | b;
        }
        return i;
    }

    static int readGraphicControlExt(final GifFrame fr, final byte[] in, final int i) {
        fr.disposalMethod = (in[i + 3] & 0b00011100) >>> 2;
        fr.transpColFlag = (in[i + 3] & 1) == 1;
        fr.delay = in[i + 4] & 0xFF | (in[i + 5] & 0xFF) << 8;
        fr.transpColIndex = in[i + 6] & 0xFF;
        return i + 8;
    }

    static int readHeader(final byte[] in, final GifImage img) throws IOException {
        if (in.length < 6) {
            throw new IOException("Image is truncated.");
        }
        img.header = new String(in, 0, 6);
        if (!img.header.equals("GIF87a") && !img.header.equals("GIF89a")) {
            throw new IOException("Invalid GIF header.");
        }
        return 6;
    }

    static int readImgData(final GifFrame fr, final byte[] in, int i) {
        final int fileSize = in.length;
        final int minCodeSize = in[i++] & 0xFF;
        final int clearCode = 1 << minCodeSize;
        fr.firstCodeSize = minCodeSize + 1;
        fr.clearCode = clearCode;
        fr.endOfInfoCode = clearCode + 1;
        final int imgDataSize = readImgDataSize(in, i);
        final byte[] imgData = new byte[imgDataSize + 2];
        int imgDataPos = 0;
        int subBlockSize = in[i] & 0xFF;
        while (subBlockSize > 0) {
            try {
                final int nextSubBlockSizePos = i + subBlockSize + 1;
                final int nextSubBlockSize = in[nextSubBlockSizePos] & 0xFF;
                arraycopy(in, i + 1, imgData, imgDataPos, subBlockSize);
                imgDataPos += subBlockSize;
                i = nextSubBlockSizePos;
                subBlockSize = nextSubBlockSize;
            } catch (final Exception e) {
                subBlockSize = fileSize - i - 1;
                arraycopy(in, i + 1, imgData, imgDataPos, subBlockSize);
                imgDataPos += subBlockSize;
                i += subBlockSize + 1;
                break;
            }
        }
        fr.data = imgData;
        i++;
        return i;
    }

    static int readImgDataSize(final byte[] in, int i) {
        final int fileSize = in.length;
        int imgDataPos = 0;
        int subBlockSize = in[i] & 0xFF;
        while (subBlockSize > 0) {
            try {
                final int nextSubBlockSizePos = i + subBlockSize + 1;
                final int nextSubBlockSize = in[nextSubBlockSizePos] & 0xFF;
                imgDataPos += subBlockSize;
                i = nextSubBlockSizePos;
                subBlockSize = nextSubBlockSize;
            } catch (final Exception e) {
                subBlockSize = fileSize - i - 1;
                imgDataPos += subBlockSize;
                break;
            }
        }
        return imgDataPos;
    }

    static int readImgDescr(final GifFrame fr, final byte[] in, int i) {
        fr.x = in[++i] & 0xFF | (in[++i] & 0xFF) << 8;
        fr.y = in[++i] & 0xFF | (in[++i] & 0xFF) << 8;
        fr.w = in[++i] & 0xFF | (in[++i] & 0xFF) << 8;
        fr.h = in[++i] & 0xFF | (in[++i] & 0xFF) << 8;
        fr.wh = fr.w * fr.h;
        final byte b = in[++i];
        fr.hasLocColTbl = (b & 0b10000000) >>> 7 == 1;
        fr.interlaceFlag = (b & 0b01000000) >>> 6 == 1;
        final int colTblSizePower = (b & 7) + 1;
        fr.sizeOfLocColTbl = 1 << colTblSizePower;
        return ++i;
    }

    static int readLogicalScreenDescriptor(final GifImage img, final byte[] in, final int i) {
        img.w = in[i] & 0xFF | (in[i + 1] & 0xFF) << 8;
        img.h = in[i + 2] & 0xFF | (in[i + 3] & 0xFF) << 8;
        img.wh = img.w * img.h;
        final byte b = in[i + 4];
        img.hasGlobColTbl = (b & 0b10000000) >>> 7 == 1;
        final int colResPower = ((b & 0b01110000) >>> 4) + 1;
        img.colorResolution = 1 << colResPower;
        img.sortFlag = (b & 0b00001000) >>> 3 == 1;
        final int globColTblSizePower = (b & 7) + 1;
        img.sizeOfGlobColTbl = 1 << globColTblSizePower;
        img.bgColIndex = in[i + 5] & 0xFF;
        img.pxAspectRatio = in[i + 6] & 0xFF;
        return i + 7;
    }

    static int readTextExtension(final byte[] in, final int pos) {
        int i = pos + 2;
        int subBlockSize = in[i++] & 0xFF;
        while (subBlockSize != 0 && i < in.length) {
            i += subBlockSize;
            subBlockSize = in[i++] & 0xFF;
        }
        return i;
    }
}
