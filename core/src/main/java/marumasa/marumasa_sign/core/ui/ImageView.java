package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 画像を枠の中に収まる大きさで表示する。
 */
public class ImageView extends Widget {

    private BufferedImage image;
    private String placeholder = "";

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        repaint();
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        Theme.paintField(g, x, y, w, h, true);
        final BufferedImage image = this.image;
        if (image == null) {
            Theme.drawTextCentered(g, Theme.ellipsize(g, placeholder, w - 8), x, y, w, h, Theme.TEXT_DISABLED);
            return;
        }
        final int areaW = w - 8;
        final int areaH = h - 8;
        final double ratio = Math.min((double) areaW / image.getWidth(), (double) areaH / image.getHeight());
        final int drawW = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        final int drawH = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        final Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, x + (w - drawW) / 2, y + (h - drawH) / 2, drawW, drawH, null);
        if (hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
    }
}
