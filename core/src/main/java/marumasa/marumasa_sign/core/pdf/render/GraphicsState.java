package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.pdf.color.DeviceSpaces;
import marumasa.marumasa_sign.core.pdf.color.PdfColorSpace;
import marumasa.marumasa_sign.core.pdf.font.PdfFont;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;

/**
 * グラフィックス状態 (8.4)。q で積み、Q で戻す。
 */
final class GraphicsState implements Cloneable {

    // 現在の変換行列 (ユーザー空間からページの既定の空間へ)
    AffineTransform ctm = new AffineTransform();
    // クリップ (ページの既定の空間)。null なら制限なし
    Area clip;

    double lineWidth = 1;
    int lineCap;
    int lineJoin;
    double miterLimit = 10;
    double[] dashArray;
    double dashPhase;

    PdfColorSpace strokeSpace = DeviceSpaces.GRAY;
    PdfColorSpace fillSpace = DeviceSpaces.GRAY;
    float[] strokeColor = {0};
    float[] fillColor = {0};
    // パターンの色を選んだときの、パターンの名前の中身 (PdfDict / PdfStream)
    Object strokePattern;
    Object fillPattern;

    double strokeAlpha = 1;
    double fillAlpha = 1;
    String blendMode = "Normal";
    SoftMask softMask;
    boolean alphaIsShape;
    // 転送関数 (10.5): 赤、緑、青ごとの 256 段階の表。null なら変えない
    int[][] transfer;

    // 文字の状態
    double charSpacing;
    double wordSpacing;
    double horizontalScale = 1;
    double leading;
    PdfFont font;
    double fontSize = 1;
    int renderMode;
    double rise;

    @Override
    public GraphicsState clone() {
        try {
            final GraphicsState copy = (GraphicsState) super.clone();
            copy.ctm = new AffineTransform(ctm);
            // クリップは変えるときに新しく作るので、共有してよい
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
