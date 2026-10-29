package marumasa.marumasa_sign.core.gltf.draco;

/**
 * 三角形の角 (corner) で表したつながり。角 c は面 c / 3 に属し、
 * 同じ面の次の角・前の角と、辺をはさんだ向かいの角 (opposite) をたどれる。
 * 頂点ごとに「一番左の角」(境界があれば境界に接する角) を 1 つ持つ。
 */
abstract class Connectivity {

    static final int INVALID = -1;

    abstract int vertex(int corner);

    abstract int opposite(int corner);

    abstract int leftMostCorner(int vertex);

    abstract int numVertices();

    abstract int numCorners();

    abstract boolean isOnBoundary(int vertex);

    final int numFaces() {
        return numCorners() / 3;
    }

    static int next(int c) {
        if (c < 0) return c;
        return c % 3 == 2 ? c - 2 : c + 1;
    }

    static int previous(int c) {
        if (c < 0) return c;
        return c % 3 == 0 ? c + 2 : c - 1;
    }

    // 同じ頂点の、左隣の面の角
    final int swingLeft(int c) {
        return next(opposite(next(c)));
    }

    // 同じ頂点の、右隣の面の角
    final int swingRight(int c) {
        return previous(opposite(previous(c)));
    }

    final int leftCorner(int c) {
        if (c < 0) return INVALID;
        return opposite(previous(c));
    }

    final int rightCorner(int c) {
        if (c < 0) return INVALID;
        return opposite(next(c));
    }
}
