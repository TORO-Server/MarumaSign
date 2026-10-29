package marumasa.marumasa_sign.core.gltf.draco;

import java.util.Arrays;

/**
 * 位置のつながり。EdgeBreaker の復号で面を 1 つずつ足しながら作る。
 */
final class CornerTable extends Connectivity {

    final int[] cornerToVertex;
    final int[] opposite;
    // 頂点ごとの一番左の角。頂点を足すたびに伸びる
    final IntList vertexCorners;

    CornerTable(int numFaces, int expectedVertices) {
        cornerToVertex = new int[numFaces * 3];
        opposite = new int[numFaces * 3];
        Arrays.fill(cornerToVertex, INVALID);
        Arrays.fill(opposite, INVALID);
        vertexCorners = new IntList(Math.min(expectedVertices, 1 << 16));
    }

    @Override
    int vertex(int corner) {
        if (corner < 0) return INVALID;
        return cornerToVertex[corner];
    }

    @Override
    int opposite(int corner) {
        if (corner < 0) return INVALID;
        return opposite[corner];
    }

    @Override
    int leftMostCorner(int vertex) {
        if (vertex < 0) return INVALID;
        return vertexCorners.get(vertex);
    }

    @Override
    int numVertices() {
        return vertexCorners.getCount();
    }

    @Override
    int numCorners() {
        return cornerToVertex.length;
    }

    @Override
    boolean isOnBoundary(int vertex) {
        return swingLeft(leftMostCorner(vertex)) == INVALID;
    }

    int addVertex() {
        vertexCorners.add(INVALID);
        return vertexCorners.getCount() - 1;
    }

    void setLeftMostCorner(int vertex, int corner) {
        if (vertex >= 0) vertexCorners.set(vertex, corner);
    }

    void map(int corner, int vertex) {
        cornerToVertex[corner] = vertex;
    }

    // 2 つの角を互いに向かい合わせにする
    void setOpposites(int a, int b) {
        opposite[a] = b;
        opposite[b] = a;
    }
}
