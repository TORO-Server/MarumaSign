package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;
import java.util.Arrays;

/**
 * 位置以外の属性 (UV など) のつながり。位置のつながりに「継ぎ目」の辺を足したもの。
 * 継ぎ目の辺では向かいの角をたどれず、そこで頂点が分かれる。
 */
final class AttributeCornerTable extends Connectivity {

    final CornerTable base;
    final boolean[] seamEdge;
    final boolean[] seamVertex;
    final int[] cornerToVertex;
    final IntList leftMost = new IntList();

    AttributeCornerTable(CornerTable base) {
        this.base = base;
        seamEdge = new boolean[base.numCorners()];
        seamVertex = new boolean[base.numVertices()];
        cornerToVertex = new int[base.numCorners()];
        Arrays.fill(cornerToVertex, INVALID);
    }

    // 角 c の向かいの辺を継ぎ目にする
    void addSeamEdge(int c) {
        seamEdge[c] = true;
        seamVertex[base.vertex(next(c))] = true;
        seamVertex[base.vertex(previous(c))] = true;
        final int opp = base.opposite(c);
        if (opp != INVALID) {
            seamEdge[opp] = true;
            seamVertex[base.vertex(next(opp))] = true;
            seamVertex[base.vertex(previous(opp))] = true;
        }
    }

    /**
     * 継ぎ目で位置の頂点を分け、属性の頂点の番号を振り直す。
     */
    void recomputeVertices(Budget budget) throws IOException {
        int count = 0;
        final int vertices = base.numVertices();
        for (int v = 0; v < vertices; v++) {
            final int c = base.leftMostCorner(v);
            if (c == INVALID) continue;
            int id = count++;
            int first = c;
            if (seamVertex[v]) {
                // 継ぎ目に接する頂点は、継ぎ目まで左へ回ったところから始める
                int act = swingLeft(first);
                while (act != INVALID) {
                    budget.step();
                    first = act;
                    act = swingLeft(act);
                    if (act == c) throw new IOException("Invalid Draco attribute seams");
                }
            }
            cornerToVertex[first] = id;
            leftMost.add(first);
            int act = base.swingRight(first);
            while (act != INVALID && act != first) {
                budget.step();
                if (seamEdge[next(act)]) {
                    // 継ぎ目を越えたら新しい頂点
                    id = count++;
                    leftMost.add(act);
                }
                cornerToVertex[act] = id;
                act = base.swingRight(act);
            }
        }
    }

    boolean isCornerOnSeam(int c) {
        return seamVertex[base.vertex(c)];
    }

    @Override
    int vertex(int corner) {
        if (corner < 0) return INVALID;
        return cornerToVertex[corner];
    }

    @Override
    int opposite(int corner) {
        if (corner < 0 || seamEdge[corner]) return INVALID;
        return base.opposite(corner);
    }

    @Override
    int leftMostCorner(int vertex) {
        if (vertex < 0) return INVALID;
        return leftMost.get(vertex);
    }

    @Override
    int numVertices() {
        return leftMost.getCount();
    }

    @Override
    int numCorners() {
        return base.numCorners();
    }

    @Override
    boolean isOnBoundary(int vertex) {
        final int c = leftMostCorner(vertex);
        if (c == INVALID) return true;
        return swingLeft(c) == INVALID;
    }
}
