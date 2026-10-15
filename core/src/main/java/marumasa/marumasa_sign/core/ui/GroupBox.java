package marumasa.marumasa_sign.core.ui;

import java.awt.Graphics2D;

public class GroupBox extends Widget {

    private final String title;

    public GroupBox(String title) {
        this.title = title;
    }

    @Override
    public boolean contains(int mx, int my) {
        // 中に置いた部品への操作を邪魔しない
        return false;
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        Theme.paintGroup(g, x, y, w, h, title);
    }
}
