/*
 * Copyright (c) 2008-2020, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package marumasa.marumasa_sign.core.texture.webp;

import java.awt.Point;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

/**
 * WebP のデコーダが使う補助。TwelveMonkeys ImageIO の YCbCrConverter と RasterUtils から、
 * 必要な部分だけを持ってきたもの。
 */
public final class WebpUtil {

    private static final int SCALEBITS = 16;
    private static final int ONE_HALF = 1 << (SCALEBITS - 1);

    // ITU-R BT.601 の YCbCr から RGB への変換表
    private static final int[] Cr_R_LUT = new int[256];
    private static final int[] Cb_B_LUT = new int[256];
    private static final int[] Cr_G_LUT = new int[256];
    private static final int[] Cb_G_LUT = new int[256];
    private static final int[] Y_LUT = new int[256];

    static {
        for (int i = 0, x = -128; i <= 255; i++, x++) {
            Cr_R_LUT[i] = ((int) (1.59603 * (1 << SCALEBITS) + 0.5) * x + ONE_HALF) >> SCALEBITS;
            Cb_B_LUT[i] = ((int) (2.01723 * (1 << SCALEBITS) + 0.5) * x + ONE_HALF) >> SCALEBITS;
            Cr_G_LUT[i] = -(int) (0.81297 * (1 << SCALEBITS) + 0.5) * x;
            Cb_G_LUT[i] = -(int) ((0.39176) * (1 << SCALEBITS) + 0.5) * x + ONE_HALF;
            Y_LUT[i] = ((int) (1.16438 * (1 << SCALEBITS) + 0.5) * (i - 16) + ONE_HALF) >> SCALEBITS;
        }
    }

    private WebpUtil() {
    }

    public static void convertRec601YCbCr2RGB(final byte[] yCbCr, final byte[] rgb, final int offset) {
        int y = yCbCr[offset] & 0xff;
        int cb = yCbCr[offset + 1] & 0xff;
        int cr = yCbCr[offset + 2] & 0xff;

        rgb[offset] = clamp(Y_LUT[y] + Cr_R_LUT[cr]);
        rgb[offset + 1] = clamp(Y_LUT[y] + (Cr_G_LUT[cr] + Cb_G_LUT[cb] >> SCALEBITS));
        rgb[offset + 2] = clamp(Y_LUT[y] + Cb_B_LUT[cb]);
    }

    private static byte clamp(final int val) {
        return (byte) Math.max(0, Math.min(255, val));
    }

    /**
     * バイト単位で読み書きできるラスタにする。int に詰めたラスタ (SinglePixelPackedSampleModel) は、
     * 同じデータをバイトとして見せるラスタで包む。
     */
    public static WritableRaster asByteRaster(final WritableRaster raster) {
        switch (raster.getTransferType()) {
            case DataBuffer.TYPE_BYTE:
                return raster;
            case DataBuffer.TYPE_INT:
                final SampleModel sampleModel = raster.getSampleModel();
                if (!(sampleModel instanceof SinglePixelPackedSampleModel)) {
                    throw new IllegalArgumentException("Requires SinglePixelPackedSampleModel");
                }
                final int bands = 4;
                final DataBufferInt buffer = (DataBufferInt) raster.getDataBuffer();
                final int w = raster.getWidth();
                final int h = raster.getHeight();
                final int size = buffer.getSize();
                return new WritableRaster(
                        new PixelInterleavedSampleModel(DataBuffer.TYPE_BYTE, w, h, bands, w * bands,
                                createBandOffsets((SinglePixelPackedSampleModel) sampleModel)),
                        new DataBuffer(DataBuffer.TYPE_BYTE, size * bands) {
                            final int[] MASKS = {0xffffff00, 0xffff00ff, 0xff00ffff, 0x00ffffff};

                            @Override
                            public int getElem(int bank, int i) {
                                final int index = i / bands;
                                final int shift = (i % bands) * 8;
                                return (buffer.getElem(index) >>> shift) & 0xff;
                            }

                            @Override
                            public void setElem(int bank, int i, int val) {
                                final int index = i / bands;
                                final int element = i % bands;
                                final int shift = element * 8;
                                final int value = (buffer.getElem(index) & MASKS[element]) | ((val & 0xff) << shift);
                                buffer.setElem(index, value);
                            }
                        }, new Point()) {
                };
            default:
                throw new IllegalArgumentException("Raster type " + raster.getTransferType() + " not supported");
        }
    }

    private static int[] createBandOffsets(final SinglePixelPackedSampleModel sampleModel) {
        final int[] masks = sampleModel.getBitMasks();
        final int[] offs = new int[masks.length];
        for (int i = 0; i < masks.length; i++) {
            int mask = masks[i];
            int off = 0;
            if (mask != 0) {
                while ((mask & 0xFF) == 0) {
                    mask >>>= 8;
                    off++;
                }
            }
            offs[i] = off;
        }
        return offs;
    }
}
