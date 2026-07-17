package marumasa.marumasa_sign.animation;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class WebpDecoder {

    private static Method getPixelMethod = null;
    static {
        try {
            getPixelMethod = NativeImage.class.getMethod("getPixelColor", int.class, int.class);
        } catch (NoSuchMethodException e) {
            try {
                getPixelMethod = NativeImage.class.getMethod("getPixelRGBA", int.class, int.class);
            } catch (NoSuchMethodException ex) {
                // Ignore, will handle invocation error
            }
        }
    }

    private static int getPixelColor(NativeImage image, int x, int y) {
        if (getPixelMethod == null) return 0;
        try {
            return (Integer) getPixelMethod.invoke(image, x, y);
        } catch (Exception e) {
            return 0;
        }
    }

    static class WebpFrame {
        int x;
        int y;
        int w;
        int h;
        int duration;
        int blendMethod;
        int disposalMethod;
        byte[] frameData;
    }

    public static DecodedAnimation read(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] temp = new byte[4096];
        int read;
        while ((read = is.read(temp, 0, temp.length)) != -1) {
            bos.write(temp, 0, read);
        }
        return read(bos.toByteArray());
    }

    public static DecodedAnimation read(byte[] bytes) throws IOException {
        if (bytes.length < 12) {
            throw new IOException("Invalid WebP: Too short");
        }
        String riff = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        String webp = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
        if (!riff.equals("RIFF") || !webp.equals("WEBP")) {
            throw new IOException("Invalid WebP signature");
        }

        byte[] vp8xData = null;
        int canvasWidth = 0;
        int canvasHeight = 0;
        int numPlays = 0;

        List<WebpFrame> webpFrames = new ArrayList<>();

        int pos = 12;
        while (pos < bytes.length - 8) {
            String type = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            int size = readInt32LE(bytes, pos + 4);
            pos += 8;

            if (pos + size > bytes.length) {
                // Truncated chunk, stop parsing
                break;
            }

            byte[] data = new byte[size];
            System.arraycopy(bytes, pos, data, 0, size);

            if (type.equals("VP8X")) {
                vp8xData = data;
                if (data.length >= 10) {
                    canvasWidth = readInt24LE(data, 4) + 1;
                    canvasHeight = readInt24LE(data, 7) + 1;
                }
            } else if (type.equals("ANIM")) {
                if (data.length >= 6) {
                    numPlays = readInt16LE(data, 4);
                }
            } else if (type.equals("ANMF")) {
                if (data.length >= 16) {
                    WebpFrame frame = new WebpFrame();
                    frame.x = readInt24LE(data, 0) * 2;
                    frame.y = readInt24LE(data, 3) * 2;
                    frame.w = readInt24LE(data, 6) + 1;
                    frame.h = readInt24LE(data, 9) + 1;
                    frame.duration = readInt24LE(data, 12);
                    
                    int flags = data[15] & 0xFF;
                    frame.disposalMethod = flags & 0x01;
                    frame.blendMethod = (flags & 0x02) >>> 1;
                    
                    int frameDataSize = size - 16;
                    frame.frameData = new byte[frameDataSize];
                    System.arraycopy(data, 16, frame.frameData, 0, frameDataSize);
                    
                    webpFrames.add(frame);
                }
            }

            pos += size;
            if (size % 2 != 0) {
                pos++; // padding byte
            }
        }

        if (canvasWidth <= 0 || canvasHeight <= 0) {
            // Fallback for non-extended WebP (static VP8/VP8L)
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                NativeImage staticImage = NativeImage.read(bais);
                canvasWidth = staticImage.getWidth();
                canvasHeight = staticImage.getHeight();
                
                int[] pixels = new int[canvasWidth * canvasHeight];
                for (int y = 0; y < canvasHeight; y++) {
                    for (int x = 0; x < canvasWidth; x++) {
                        int abgr = getPixelColor(staticImage, x, y);
                        pixels[x + y * canvasWidth] = abgrToArgb(abgr);
                    }
                }
                staticImage.close();

                List<AnimationFrame> frames = new ArrayList<>();
                frames.add(new AnimationFrame(pixels, 100));
                return new DecodedAnimation(canvasWidth, canvasHeight, 0, frames);
            }
        }

        AnimationCanvas canvas = new AnimationCanvas(canvasWidth, canvasHeight);
        List<AnimationFrame> frames = new ArrayList<>();

        for (WebpFrame frame : webpFrames) {
            byte[] singleWebp = reconstructWebp(vp8xData, frame.w, frame.h, frame.frameData);
            
            NativeImage frameImage;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(singleWebp)) {
                frameImage = NativeImage.read(bais);
            }
            if (frameImage == null) {
                throw new IOException("Failed to decode WebP frame");
            }

            int[] pixels = new int[frame.w * frame.h];
            for (int y = 0; y < frame.h; y++) {
                for (int x = 0; x < frame.w; x++) {
                    int abgr = getPixelColor(frameImage, x, y);
                    pixels[x + y * frame.w] = abgrToArgb(abgr);
                }
            }
            frameImage.close();

            canvas.drawFrame(pixels, frame.x, frame.y, frame.w, frame.h, frame.blendMethod, frame.disposalMethod);

            int delayMs = frame.duration;
            if (delayMs < 10) {
                delayMs = 100;
            }

            frames.add(new AnimationFrame(canvas.getCanvasCopy(), delayMs));

            canvas.postDraw(frame.x, frame.y, frame.w, frame.h, frame.disposalMethod);
        }

        return new DecodedAnimation(canvasWidth, canvasHeight, numPlays, frames);
    }

    private static byte[] reconstructWebp(byte[] originalVp8xData, int frameW, int frameH, byte[] frameData) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        bos.write(new byte[4]); // size placeholder
        bos.write("WEBP".getBytes(StandardCharsets.US_ASCII));

        byte[] vp8x = new byte[10];
        if (originalVp8xData != null && originalVp8xData.length >= 10) {
            System.arraycopy(originalVp8xData, 0, vp8x, 0, 10);
        }
        // Clear animation flag
        vp8x[0] = (byte) (vp8x[0] & ~0x02);
        
        int wMinus1 = frameW - 1;
        int hMinus1 = frameH - 1;
        vp8x[4] = (byte) (wMinus1 & 0xFF);
        vp8x[5] = (byte) ((wMinus1 >>> 8) & 0xFF);
        vp8x[6] = (byte) ((wMinus1 >>> 16) & 0xFF);
        vp8x[7] = (byte) (hMinus1 & 0xFF);
        vp8x[8] = (byte) ((hMinus1 >>> 8) & 0xFF);
        vp8x[9] = (byte) ((hMinus1 >>> 16) & 0xFF);

        writeChunk(bos, "VP8X", vp8x);
        bos.write(frameData);

        byte[] webpBytes = bos.toByteArray();
        int riffSize = webpBytes.length - 8;
        webpBytes[4] = (byte) (riffSize & 0xFF);
        webpBytes[5] = (byte) ((riffSize >>> 8) & 0xFF);
        webpBytes[6] = (byte) ((riffSize >>> 16) & 0xFF);
        webpBytes[7] = (byte) ((riffSize >>> 24) & 0xFF);

        return webpBytes;
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(type.getBytes(StandardCharsets.US_ASCII));
        int len = data.length;
        out.write((byte) (len & 0xFF));
        out.write((byte) ((len >>> 8) & 0xFF));
        out.write((byte) ((len >>> 16) & 0xFF));
        out.write((byte) ((len >>> 24) & 0xFF));
        out.write(data);
        if (len % 2 != 0) {
            out.write(0);
        }
    }

    private static int readInt16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt24LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
    }

    private static int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
