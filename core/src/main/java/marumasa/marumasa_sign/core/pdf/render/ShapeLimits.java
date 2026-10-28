package marumasa.marumasa_sign.core.pdf.render;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;

/**
 * 画面からとても遠くまで広がる道を、Java2D に渡す前に、画面のまわりの範囲で切り取る。
 * <p>
 * Java 8 の Java2D (Pisces) は、曲線を長さに応じて細かい線に分けてから描くので、座標が極端な道
 * (壊れた PDF や、細工した PDF にある 1e9 のような値) では、1 つの道に何十秒もかかる (Java 9 からの Marlin は先に切り取る)。
 * そうした道だけを、こちらで折れ線にして範囲で切り取る。範囲の中の見た目は変わらない。
 * <ul>
 *   <li>塗る道: 多角形として範囲で切り取る (Sutherland–Hodgman)。範囲の中の各点の巻き数は変わらないので、
 *   非ゼロ・偶奇のどちらの規則でも、範囲の中の塗りは同じになる</li>
 *   <li>線を引く道: 線分を範囲で切り取り、範囲の外に出たところで道を分ける (範囲の外の継ぎ目は見えない)</li>
 * </ul>
 */
final class ShapeLimits {

    // 曲線を折れ線にするときの細かさ (画素) と、1 つの道の折れ線の点の数の上限
    private static final double FLATNESS = 0.25;
    private static final int MAX_POINTS = 2_000_000;

    private ShapeLimits() {
    }

    /**
     * 塗る道 (ページの空間) を、画素の空間で canvas のまわり margin までの範囲に収める。収まっていれば、そのまま返す。
     */
    static Shape forFill(Shape pageShape, Canvas canvas, double margin) {
        final Rectangle2D range = range(canvas, margin);
        if (inside(pageShape, canvas.base, range)) return pageShape;
        final Path2D.Double device = new Path2D.Double(windingRule(pageShape));
        final Polygon points = new Polygon();
        final PathIterator it = pageShape.getPathIterator(canvas.base, FLATNESS);
        final double[] c = new double[6];
        int total = 0;
        while (!it.isDone()) {
            final int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                clipPolygon(points, range, device);
                points.clear();
                points.add(c[0], c[1]);
            } else if (type == PathIterator.SEG_LINETO) {
                points.add(c[0], c[1]);
            } else if (type == PathIterator.SEG_CLOSE) {
                clipPolygon(points, range, device);
                points.clear();
            }
            if (++total > MAX_POINTS) return new Path2D.Double();
            it.next();
        }
        clipPolygon(points, range, device);
        return toPage(device, canvas.base);
    }

    /**
     * 線を引く道 (ページの空間) を、画素の空間で canvas のまわり margin までの範囲に収める。収まっていれば、そのまま返す。
     */
    static Shape forStroke(Shape pageShape, Canvas canvas, double margin) {
        final Rectangle2D range = range(canvas, margin);
        if (inside(pageShape, canvas.base, range)) return pageShape;
        final Path2D.Double device = new Path2D.Double();
        final PathIterator it = pageShape.getPathIterator(canvas.base, FLATNESS);
        final double[] c = new double[6];
        double startX = 0, startY = 0, x = 0, y = 0;
        boolean open = false;
        int total = 0;
        final double[] segment = new double[4];
        while (!it.isDone()) {
            final int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = c[0];
                startY = y = c[1];
                open = false;
            } else if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE) {
                final double nx = type == PathIterator.SEG_CLOSE ? startX : c[0];
                final double ny = type == PathIterator.SEG_CLOSE ? startY : c[1];
                segment[0] = x;
                segment[1] = y;
                segment[2] = nx;
                segment[3] = ny;
                if (clipSegment(segment, range)) {
                    // 前の線分の終わりからつながっていれば、道を続ける
                    if (!open || segment[0] != x || segment[1] != y) device.moveTo(segment[0], segment[1]);
                    device.lineTo(segment[2], segment[3]);
                    open = segment[2] == nx && segment[3] == ny;
                } else {
                    open = false;
                }
                x = nx;
                y = ny;
            }
            if (++total > MAX_POINTS) return new Path2D.Double();
            it.next();
        }
        return toPage(device, canvas.base);
    }

    private static Rectangle2D range(Canvas canvas, double margin) {
        return new Rectangle2D.Double(-margin, -margin, canvas.width + 2 * margin, canvas.height + 2 * margin);
    }

    // 道の外接矩形 (画素の空間) が範囲に収まっているか
    private static boolean inside(Shape pageShape, AffineTransform base, Rectangle2D range) {
        final Rectangle2D bounds = pageShape.getBounds2D();
        final double[] corners = {bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMinY(),
                bounds.getMinX(), bounds.getMaxY(), bounds.getMaxX(), bounds.getMaxY()};
        base.transform(corners, 0, corners, 0, 4);
        for (int i = 0; i < 8; i += 2) {
            final double px = corners[i], py = corners[i + 1];
            // NaN も外とみなす
            if (!(px >= range.getMinX() && px <= range.getMaxX() && py >= range.getMinY() && py <= range.getMaxY())) return false;
        }
        return true;
    }

    private static int windingRule(Shape shape) {
        return shape.getPathIterator(null).getWindingRule();
    }

    private static Shape toPage(Path2D.Double device, AffineTransform base) {
        try {
            device.transform(base.createInverse());
        } catch (NoninvertibleTransformException e) {
            return new Path2D.Double();
        }
        return device;
    }

    // ---------- 多角形の切り取り (Sutherland–Hodgman) ----------

    private static final class Polygon {
        double[] xy = new double[64];
        int size;

        void add(double x, double y) {
            // 無限や NaN の点は使わない (切り取りの計算が NaN になるので)
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) return;
            if (size * 2 + 2 > xy.length) xy = java.util.Arrays.copyOf(xy, xy.length * 2);
            xy[size * 2] = x;
            xy[size * 2 + 1] = y;
            size++;
        }

        void clear() {
            size = 0;
        }
    }

    private static void clipPolygon(Polygon polygon, Rectangle2D range, Path2D.Double out) {
        if (polygon.size < 3) return;
        Polygon current = polygon;
        for (int edge = 0; edge < 4 && current.size > 0; edge++) {
            final Polygon next = new Polygon();
            for (int i = 0; i < current.size; i++) {
                final double ax = current.xy[i * 2], ay = current.xy[i * 2 + 1];
                final int j = (i + 1) % current.size;
                final double bx = current.xy[j * 2], by = current.xy[j * 2 + 1];
                final boolean aIn = in(ax, ay, edge, range), bIn = in(bx, by, edge, range);
                if (aIn) next.add(ax, ay);
                if (aIn != bIn) {
                    final double t = crossing(ax, ay, bx, by, edge, range);
                    next.add(ax + (bx - ax) * t, ay + (by - ay) * t);
                }
            }
            current = next;
        }
        if (current.size < 3) return;
        out.moveTo(current.xy[0], current.xy[1]);
        for (int i = 1; i < current.size; i++) out.lineTo(current.xy[i * 2], current.xy[i * 2 + 1]);
        out.closePath();
    }

    // edge: 0 左、1 右、2 上、3 下
    private static boolean in(double x, double y, int edge, Rectangle2D r) {
        switch (edge) {
            case 0:
                return x >= r.getMinX();
            case 1:
                return x <= r.getMaxX();
            case 2:
                return y >= r.getMinY();
            default:
                return y <= r.getMaxY();
        }
    }

    private static double crossing(double ax, double ay, double bx, double by, int edge, Rectangle2D r) {
        final double t;
        switch (edge) {
            case 0:
                t = (r.getMinX() - ax) / (bx - ax);
                break;
            case 1:
                t = (r.getMaxX() - ax) / (bx - ax);
                break;
            case 2:
                t = (r.getMinY() - ay) / (by - ay);
                break;
            default:
                t = (r.getMaxY() - ay) / (by - ay);
                break;
        }
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    // ---------- 線分の切り取り (Liang–Barsky) ----------

    // segment (x0, y0, x1, y1) を範囲で切り取る。範囲に入らなければ false
    private static boolean clipSegment(double[] s, Rectangle2D r) {
        for (double v : s) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        }
        final double x0 = s[0], y0 = s[1], dx = s[2] - x0, dy = s[3] - y0;
        double t0 = 0, t1 = 1;
        final double[] p = {-dx, dx, -dy, dy};
        final double[] q = {x0 - r.getMinX(), r.getMaxX() - x0, y0 - r.getMinY(), r.getMaxY() - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (!(q[i] >= 0)) return false;
            } else {
                final double t = q[i] / p[i];
                if (p[i] < 0) {
                    if (t > t1) return false;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return false;
                    if (t < t1) t1 = t;
                }
            }
        }
        if (!(t0 <= t1)) return false;
        if (t1 < 1) {
            s[2] = x0 + dx * t1;
            s[3] = y0 + dy * t1;
        }
        if (t0 > 0) {
            s[0] = x0 + dx * t0;
            s[1] = y0 + dy * t0;
        }
        return true;
    }
}
