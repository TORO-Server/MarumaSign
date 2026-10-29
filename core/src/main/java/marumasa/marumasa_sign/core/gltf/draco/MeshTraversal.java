package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * EdgeBreaker で符号化されたメッシュの、属性の値の並び順を作る。
 * 符号化側と同じ順にメッシュの面をたどり、初めて出会った頂点の順に値が並んでいる。
 * たどり方は深さ優先と、予測に使える面の多い頂点を先にする方式の 2 つ。
 * どちらも再帰は使わず、自前のスタックでたどる。
 */
final class MeshTraversal {

    static final int DEPTH_FIRST = 0;
    static final int PREDICTION_DEGREE = 1;

    /**
     * 頂点と値の番号の対応。予測でも使う。
     */
    static final class Encoding {
        // 頂点 → 値の番号 (まだ出会っていない頂点は 0 のまま。元の実装と同じ)
        final int[] vertexToValue;
        // 値の番号 → その値を読んだときの角
        final IntList valueToCorner = new IntList();

        Encoding(int vertices) {
            vertexToValue = new int[vertices];
        }
    }

    private final Connectivity table;
    private final Encoding encoding;
    private final int[] faces;
    private final boolean[] faceVisited;
    private final boolean[] vertexVisited;
    private final IntList points = new IntList();

    private MeshTraversal(Connectivity table, Encoding encoding, int[] faces) {
        this.table = table;
        this.encoding = encoding;
        this.faces = faces;
        faceVisited = new boolean[table.numFaces()];
        vertexVisited = new boolean[table.numVertices()];
    }

    /**
     * たどって、値の順の点の番号を返す。encoding に頂点と値の対応を書き込む。
     */
    static int[] run(Connectivity table, int method, Encoding encoding, int[] faces, Budget budget) throws IOException {
        budget.alloc((long) table.numFaces() + table.numVertices() * 3L);
        final MeshTraversal t = new MeshTraversal(table, encoding, faces);
        final int numFaces = table.numFaces();
        if (method == PREDICTION_DEGREE) {
            final int[] degree = new int[table.numVertices()];
            if (degree.length > 0) {
                for (int f = 0; f < numFaces; f++) t.byPredictionDegree(3 * f, degree);
            }
        } else {
            final IntList stack = new IntList();
            for (int f = 0; f < numFaces; f++) t.depthFirst(3 * f, stack);
        }
        return t.points.toArray();
    }

    private boolean isFaceVisited(int corner) {
        if (corner < 0) return true;
        return faceVisited[corner / 3];
    }

    private void visitVertex(int vertex, int corner) {
        vertexVisited[vertex] = true;
        points.add(faces[corner]);
        encoding.valueToCorner.add(corner);
        encoding.vertexToValue[vertex] = encoding.valueToCorner.getCount() - 1;
    }

    // 深さ優先。境界でない新しい頂点では右の面へ進み続ける
    private void depthFirst(int start, IntList stack) throws IOException {
        if (isFaceVisited(start)) return;
        stack.clear();
        stack.add(start);
        final int nextVertex = table.vertex(Connectivity.next(start));
        final int prevVertex = table.vertex(Connectivity.previous(start));
        if (nextVertex < 0 || prevVertex < 0) throw new IOException("Invalid Draco corner");
        if (!vertexVisited[nextVertex]) visitVertex(nextVertex, Connectivity.next(start));
        if (!vertexVisited[prevVertex]) visitVertex(prevVertex, Connectivity.previous(start));
        while (!stack.isEmpty()) {
            int corner = stack.last();
            if (corner < 0 || faceVisited[corner / 3]) {
                stack.removeLast();
                continue;
            }
            while (true) {
                if (corner < 0) throw new IOException("Invalid Draco corner");
                faceVisited[corner / 3] = true;
                final int vertex = table.vertex(corner);
                if (vertex < 0) throw new IOException("Invalid Draco corner");
                if (!vertexVisited[vertex]) {
                    final boolean boundary = table.isOnBoundary(vertex);
                    visitVertex(vertex, corner);
                    if (!boundary) {
                        corner = table.rightCorner(corner);
                        continue;
                    }
                }
                final int right = table.rightCorner(corner);
                final int left = table.leftCorner(corner);
                final boolean rightDone = isFaceVisited(right);
                final boolean leftDone = isFaceVisited(left);
                if (rightDone && leftDone) {
                    stack.removeLast();
                    break;
                } else if (rightDone) {
                    corner = left;
                } else if (leftDone) {
                    corner = right;
                } else {
                    // 両方まだなら右を先に、左は後で
                    stack.setLast(left);
                    stack.add(right);
                    break;
                }
            }
        }
    }

    // 予測の次数を優先度にしてたどる。優先度ごとにスタックを持つ
    private final IntList[] stacks = {new IntList(), new IntList(), new IntList()};
    private int bestPriority;

    private void byPredictionDegree(int start, int[] degree) {
        stacks[0].add(start);
        bestPriority = 0;
        final int nextVertex = table.vertex(Connectivity.next(start));
        final int prevVertex = table.vertex(Connectivity.previous(start));
        if (!vertexVisited[nextVertex]) visitVertex(nextVertex, Connectivity.next(start));
        if (!vertexVisited[prevVertex]) visitVertex(prevVertex, Connectivity.previous(start));
        final int tip = table.vertex(start);
        if (!vertexVisited[tip]) visitVertex(tip, start);
        int corner;
        while ((corner = popNext()) != Connectivity.INVALID) {
            if (faceVisited[corner / 3]) continue;
            while (true) {
                faceVisited[corner / 3] = true;
                final int vertex = table.vertex(corner);
                if (!vertexVisited[vertex]) visitVertex(vertex, corner);
                final int right = table.rightCorner(corner);
                final int left = table.leftCorner(corner);
                final boolean rightDone = isFaceVisited(right);
                final boolean leftDone = isFaceVisited(left);
                if (!leftDone) {
                    final int priority = priority(left, degree);
                    if (rightDone && priority <= bestPriority) {
                        corner = left;
                        continue;
                    }
                    push(left, priority);
                }
                if (!rightDone) {
                    final int priority = priority(right, degree);
                    if (priority <= bestPriority) {
                        corner = right;
                        continue;
                    }
                    push(right, priority);
                }
                break;
            }
        }
    }

    private int popNext() {
        for (int i = bestPriority; i < stacks.length; i++) {
            if (!stacks[i].isEmpty()) {
                bestPriority = i;
                return stacks[i].removeLast();
            }
        }
        return Connectivity.INVALID;
    }

    private void push(int corner, int priority) {
        stacks[priority].add(corner);
        if (priority < bestPriority) bestPriority = priority;
    }

    // すでに出会った頂点へ向かう面が 0、予測の材料が多い頂点が 1、少ない頂点が 2
    private int priority(int corner, int[] degree) {
        final int tip = table.vertex(corner);
        if (vertexVisited[tip]) return 0;
        return ++degree[tip] > 1 ? 1 : 2;
    }
}
