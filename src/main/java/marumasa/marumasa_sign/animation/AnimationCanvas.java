package marumasa.marumasa_sign.animation;

public class AnimationCanvas {
    private final int width;
    private final int height;
    private final int[] canvas;
    private final int[] prevCanvas;

    public AnimationCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.canvas = new int[width * height];
        this.prevCanvas = new int[width * height];
    }

    public void drawFrame(int[] framePixels, int fx, int fy, int fw, int fh, int blendMethod, int disposalMethod) {
        // PREVIOUS のために描画前のキャンバスを保存
        if (disposalMethod == 2) {
            System.arraycopy(canvas, 0, prevCanvas, 0, canvas.length);
        }

        // フレームのピクセルをブレンド/上書き描画
        for (int y = 0; y < fh; y++) {
            int cy = fy + y;
            if (cy < 0 || cy >= height) continue;
            for (int x = 0; x < fw; x++) {
                int cx = fx + x;
                if (cx < 0 || cx >= width) continue;

                int srcColor = framePixels[x + y * fw];
                int destIdx = cx + cy * width;

                if (blendMethod == 0) {
                    // BLEND: アルファブレンド
                    int srcA = (srcColor >>> 24) & 0xFF;
                    if (srcA == 255) {
                        canvas[destIdx] = srcColor;
                    } else if (srcA > 0) {
                        int destColor = canvas[destIdx];
                        int destA = (destColor >>> 24) & 0xFF;

                        int rA = srcA + destA * (255 - srcA) / 255;
                        if (rA > 0) {
                            int srcR = (srcColor >>> 16) & 0xFF;
                            int srcG = (srcColor >>> 8) & 0xFF;
                            int srcB = srcColor & 0xFF;

                            int destR = (destColor >>> 16) & 0xFF;
                            int destG = (destColor >>> 8) & 0xFF;
                            int destB = destColor & 0xFF;

                            int rR = (srcR * srcA + destR * destA * (255 - srcA) / 255) / rA;
                            int rG = (srcG * srcA + destG * destA * (255 - srcA) / 255) / rA;
                            int rB = (srcB * srcA + destB * destA * (255 - srcA) / 255) / rA;

                            canvas[destIdx] = (rA << 24) | (rR << 16) | (rG << 8) | rB;
                        }
                    }
                } else {
                    // OVERWRITE: そのままコピー
                    canvas[destIdx] = srcColor;
                }
            }
        }
    }

    public void postDraw(int fx, int fy, int fw, int fh, int disposalMethod) {
        if (disposalMethod == 1) {
            // BACKGROUND: 背景クリア (描画された領域のみクリア)
            for (int y = 0; y < fh; y++) {
                int cy = fy + y;
                if (cy < 0 || cy >= height) continue;
                for (int x = 0; x < fw; x++) {
                    int cx = fx + x;
                    if (cx < 0 || cx >= width) continue;
                    canvas[cx + cy * width] = 0;
                }
            }
        } else if (disposalMethod == 2) {
            // PREVIOUS: 直前復元
            System.arraycopy(prevCanvas, 0, canvas, 0, canvas.length);
        }
    }

    public int[] getCanvasCopy() {
        int[] copy = new int[canvas.length];
        System.arraycopy(canvas, 0, copy, 0, canvas.length);
        return copy;
    }
}
