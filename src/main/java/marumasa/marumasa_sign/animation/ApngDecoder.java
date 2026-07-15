package marumasa.marumasa_sign.animation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public class ApngDecoder {

    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    static class RawChunk {
        String type;
        byte[] data;

        RawChunk(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    static class ApngFrame {
        int x;
        int y;
        int w;
        int h;
        int delayNum;
        int delayDen;
        int disposalOp;
        int blendOp;
        byte[] imageData; // Concatenated IDAT or fdAT (without seq num) data
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
        if (bytes.length < 8) {
            throw new IOException("Invalid PNG: Too short");
        }
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw new IOException("Invalid PNG signature");
            }
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(8);

        byte[] ihdrData = null;
        List<RawChunk> metadataChunks = new ArrayList<>();
        List<ApngFrame> apngFrames = new ArrayList<>();
        
        int numFrames = 1;
        int numPlays = 0; // 0 means infinite

        ApngFrame currentFrame = null;
        ByteArrayOutputStream currentFrameData = null;

        while (buffer.remaining() >= 8) {
            int length = buffer.getInt();
            byte[] typeBytes = new byte[4];
            buffer.get(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);

            if (buffer.remaining() < length + 4) {
                throw new IOException("Truncated PNG chunk: " + type);
            }

            byte[] data = new byte[length];
            buffer.get(data);
            int crc = buffer.getInt(); // Skip CRC check for now

            if (type.equals("IHDR")) {
                ihdrData = data;
            } else if (type.equals("acTL")) {
                if (data.length >= 8) {
                    ByteBuffer db = ByteBuffer.wrap(data);
                    numFrames = db.getInt();
                    numPlays = db.getInt();
                }
            } else if (type.equals("fcTL")) {
                // If there was a previous frame being accumulated, finalize it
                if (currentFrame != null && currentFrameData != null) {
                    currentFrame.imageData = currentFrameData.toByteArray();
                    apngFrames.add(currentFrame);
                }

                if (data.length >= 26) {
                    ByteBuffer db = ByteBuffer.wrap(data);
                    db.getInt(); // sequence_number
                    currentFrame = new ApngFrame();
                    currentFrame.w = db.getInt();
                    currentFrame.h = db.getInt();
                    currentFrame.x = db.getInt();
                    currentFrame.y = db.getInt();
                    currentFrame.delayNum = db.getShort() & 0xFFFF;
                    currentFrame.delayDen = db.getShort() & 0xFFFF;
                    currentFrame.disposalOp = db.get() & 0xFF;
                    currentFrame.blendOp = db.get() & 0xFF;
                    
                    currentFrameData = new ByteArrayOutputStream();
                }
            } else if (type.equals("IDAT")) {
                // If it's a static PNG (no fcTL yet), we treat the standard IDAT as frame 0
                if (currentFrame == null) {
                    currentFrame = new ApngFrame();
                    if (ihdrData != null) {
                        ByteBuffer ihdrBuf = ByteBuffer.wrap(ihdrData);
                        currentFrame.w = ihdrBuf.getInt();
                        currentFrame.h = ihdrBuf.getInt();
                    }
                    currentFrame.x = 0;
                    currentFrame.y = 0;
                    currentFrame.delayNum = 10; // default delay
                    currentFrame.delayDen = 100;
                    currentFrame.disposalOp = 0;
                    currentFrame.blendOp = 0;
                    currentFrameData = new ByteArrayOutputStream();
                }
                currentFrameData.write(data);
            } else if (type.equals("fdAT")) {
                if (currentFrameData != null && data.length >= 4) {
                    // Skip 4 bytes of sequence number in fdAT
                    currentFrameData.write(data, 4, data.length - 4);
                }
            } else if (!type.equals("IEND")) {
                metadataChunks.add(new RawChunk(type, data));
            }
        }

        // Finalize last frame
        if (currentFrame != null && currentFrameData != null) {
            currentFrame.imageData = currentFrameData.toByteArray();
            apngFrames.add(currentFrame);
        }

        if (ihdrData == null) {
            throw new IOException("Missing IHDR chunk");
        }

        if (apngFrames.isEmpty()) {
            throw new IOException("No frames found in APNG");
        }

        ByteBuffer ihdrBuf = ByteBuffer.wrap(ihdrData);
        int canvasWidth = ihdrBuf.getInt();
        int canvasHeight = ihdrBuf.getInt();

        AnimationCanvas canvas = new AnimationCanvas(canvasWidth, canvasHeight);
        List<AnimationFrame> frames = new ArrayList<>();

        for (ApngFrame frame : apngFrames) {
            // Reconstruct single frame PNG
            byte[] singlePng = reconstructPng(ihdrData, metadataChunks, frame);
            
            BufferedImage image;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(singlePng)) {
                image = ImageIO.read(bais);
            }
            if (image == null) {
                throw new IOException("Failed to decode APNG frame");
            }

            int[] pixels = new int[frame.w * frame.h];
            image.getRGB(0, 0, frame.w, frame.h, pixels, 0, frame.w);

            // APNG blend_op: 
            // 0 (SOURCE) -> 1 (OVERWRITE)
            // 1 (OVER) -> 0 (BLEND)
            int blend = (frame.blendOp == 0) ? 1 : 0;

            // APNG disposal_op:
            // 0 (NONE) -> 0 (NONE)
            // 1 (BACKGROUND) -> 1 (BACKGROUND)
            // 2 (PREVIOUS) -> 2 (PREVIOUS)
            int disposal = frame.disposalOp;

            canvas.drawFrame(pixels, frame.x, frame.y, frame.w, frame.h, blend, disposal);

            int delayDen = frame.delayDen == 0 ? 100 : frame.delayDen;
            int delayMs = (frame.delayNum * 1000) / delayDen;
            if (delayMs < 10) {
                delayMs = 100;
            }

            frames.add(new AnimationFrame(canvas.getCanvasCopy(), delayMs));

            canvas.postDraw(frame.x, frame.y, frame.w, frame.h, disposal);
        }

        return new DecodedAnimation(canvasWidth, canvasHeight, numPlays, frames);
    }

    private static byte[] reconstructPng(byte[] originalIhdr, List<RawChunk> metadataChunks, ApngFrame frame) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PNG_SIGNATURE);

        // Modify IHDR with frame's width and height
        byte[] ihdrData = Arrays.copyOf(originalIhdr, originalIhdr.length);
        ByteBuffer.wrap(ihdrData).putInt(0, frame.w).putInt(4, frame.h);

        writeChunk(out, "IHDR", ihdrData);

        for (RawChunk chunk : metadataChunks) {
            writeChunk(out, chunk.type, chunk.data);
        }

        writeChunk(out, "IDAT", frame.imageData);
        writeChunk(out, "IEND", new byte[0]);

        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        int len = data.length;
        byte[] lenBytes = new byte[4];
        lenBytes[0] = (byte) ((len >>> 24) & 0xFF);
        lenBytes[1] = (byte) ((len >>> 16) & 0xFF);
        lenBytes[2] = (byte) ((len >>> 8) & 0xFF);
        lenBytes[3] = (byte) (len & 0xFF);
        out.write(lenBytes);

        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);

        if (len > 0) {
            out.write(data);
        }

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        if (len > 0) {
            crc.update(data);
        }
        long crcVal = crc.getValue();
        byte[] crcBytes = new byte[4];
        crcBytes[0] = (byte) ((crcVal >>> 24) & 0xFF);
        crcBytes[1] = (byte) ((crcVal >>> 16) & 0xFF);
        crcBytes[2] = (byte) ((crcVal >>> 8) & 0xFF);
        crcBytes[3] = (byte) (crcVal & 0xFF);
        out.write(crcBytes);
    }
}
