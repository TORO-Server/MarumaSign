package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;
import java.util.Arrays;

/**
 * EdgeBreaker で符号化された面のつながりを戻す。
 * 符号化側は面を 1 つずつたどりながら、その面と周りの関係を 5 種類の記号 (C, S, L, R, E) で書いている。
 * 記号は逆順に並んでいるので、先頭から読みながら面を足していけば、いつでも正しいつながりになっている。
 * 「まだ相手のいない辺」をスタックに積み、新しい面はスタックの一番上の辺に付ける。
 */
final class EdgebreakerDecoder {

    // 記号の読み方
    private static final int STANDARD = 0;
    private static final int PREDICTIVE = 1;
    private static final int VALENCE = 2;

    // 記号 (ビットの並びをそのまま値にしたもの)
    private static final int C = 0;
    private static final int S = 1;
    private static final int L = 3;
    private static final int R = 5;
    private static final int E = 7;
    private static final int INVALID_SYMBOL = 9;
    private static final int[] SYMBOL_OF_ID = {C, S, L, R, E};

    private final MeshDecoding session;
    private final ByteReader in;
    private final int version;
    private final Budget budget;

    private int traversalType;
    private int numFaces;
    private int maxVertices;
    CornerTable table;
    private boolean[] isVertexHole;

    // 位置以外の属性のつながり (継ぎ目つき)
    private int numAttributeData;
    private AttributeCornerTable[] attributeTables;
    private int[] dataOwner;
    private MeshTraversal.Encoding[] dataEncoding;
    private MeshTraversal.Encoding positionEncoding;
    private int positionOwner = -1;

    // 位相の分かれ目 (穴や取っ手で、辺が離れた記号どうしにつながるところ)
    private long[] splitSource;
    private long[] splitSymbol;
    private int[] splitEdge;
    private int splitCount;

    // 記号の読み手
    private ByteReader.BitReader symbolBits;
    private ByteReader.BitReader startFaceBits;
    private Rans.BitDecoder startFaceDecoder;
    private Rans.BitDecoder[] seamDecoders;
    private Rans.BitDecoder predictionDecoder;
    private int[] valences;
    private int[][] contextSymbols;
    private int[] contextCounters;
    private int activeContext = -1;
    private int lastSymbol = -1;
    private int predictedSymbol = -1;

    EdgebreakerDecoder(MeshDecoding session) {
        this.session = session;
        this.in = session.in;
        this.version = session.version;
        this.budget = session.budget;
    }

    /**
     * つながりを読み、メッシュの面と点の数を決める。
     */
    void decodeConnectivity() throws IOException {
        traversalType = in.u8();
        if (traversalType != STANDARD && traversalType != PREDICTIVE && traversalType != VALENCE) {
            throw new IOException("Unsupported Draco EdgeBreaker type: " + traversalType);
        }
        if (version < ByteReader.V22) in.count32Before20(); // 足した頂点の数 (使わない)
        final long encodedVertices = in.count32Before20();
        final long faces = in.count32Before20();
        if (faces > Budget.MAX_FACES) throw new IOException("Too many Draco faces: " + faces);
        if (encodedVertices > faces * 3) throw new IOException("Too many Draco vertices");
        // 辺の数から、作れないメッシュをはじく
        if (encodedVertices * (encodedVertices - 1) / 2 < 3 * faces / 2) {
            throw new IOException("Inconsistent Draco vertex count");
        }
        numAttributeData = in.u8();
        final long symbols = in.count32Before20();
        if (faces < symbols || faces > symbols + symbols / 3) throw new IOException("Inconsistent Draco symbol count");
        final long splitSymbols = in.count32Before20();
        if (splitSymbols > symbols) throw new IOException("Too many Draco split symbols");

        numFaces = (int) faces;
        maxVertices = (int) (encodedVertices + splitSymbols);
        final long corners = 3L * numFaces;
        budget.alloc(2 * corners + 2L * maxVertices);
        budget.setStepLimit(corners);
        table = new CornerTable(numFaces, maxVertices);
        isVertexHole = new boolean[maxVertices];
        Arrays.fill(isVertexHole, true);

        // 位相の分かれ目。2.2 より前はつながりのデータの後ろに置かれている
        int splitBytes = 0;
        if (version < ByteReader.V22) {
            final long size = in.count32Before20();
            if (size == 0 || size > in.remaining()) throw new IOException("Invalid Draco connectivity size");
            final ByteReader events = new ByteReader(in.data, in.pos + (int) size, in.end, version);
            final int begin = events.pos;
            decodeSplitEvents(events);
            splitBytes = events.pos - begin;
        } else {
            decodeSplitEvents(in);
        }

        final ByteReader traversal = in.copy();
        startTraversal(traversal);
        final int numVertices = decodeTopology((int) symbols);
        in.pos = traversal.pos;
        in.skip(splitBytes);

        // 属性の継ぎ目
        attributeTables = new AttributeCornerTable[numAttributeData];
        dataOwner = new int[numAttributeData];
        dataEncoding = new MeshTraversal.Encoding[numAttributeData];
        Arrays.fill(dataOwner, -1);
        if (numAttributeData > 0) {
            budget.alloc(numAttributeData * (3 * corners + 2L * table.numVertices()));
            for (int i = 0; i < numAttributeData; i++) attributeTables[i] = new AttributeCornerTable(table);
            decodeSeams();
            for (AttributeCornerTable t : attributeTables) t.recomputeVertices(budget);
        }
        assignPoints(numVertices);
    }

    // ---------- 位相の分かれ目 ----------

    private void decodeSplitEvents(ByteReader r) throws IOException {
        final long n = r.count32Before20();
        if (n > 0) {
            if (n > numFaces) throw new IOException("Too many Draco topology splits");
            splitCount = (int) n;
            budget.alloc(3 * n);
            splitSource = new long[splitCount];
            splitSymbol = new long[splitCount];
            splitEdge = new int[splitCount];
            if (version < ByteReader.V12) {
                for (int i = 0; i < splitCount; i++) {
                    splitSymbol[i] = r.u32();
                    splitSource[i] = r.u32();
                    splitEdge[i] = r.u8() & 1;
                }
            } else {
                // 元の記号の番号は前との差、分かれ目の記号は元の記号からの差で書かれている
                long last = 0;
                for (int i = 0; i < splitCount; i++) {
                    final long source = (r.varU32() + last) & 0xffffffffL;
                    final long delta = r.varU32();
                    if (delta > source) throw new IOException("Invalid Draco topology split");
                    splitSource[i] = source;
                    splitSymbol[i] = source - delta;
                    last = (int) source;
                }
                final ByteReader.BitReader bits = r.startBits();
                for (int i = 0; i < splitCount; i++) {
                    splitEdge[i] = bits.bits(version < ByteReader.V22 ? 2 : 1) & 1;
                }
                r.endBits(bits);
            }
        }
        // 穴の記録 (古い形式だけにある。復号には使わない)
        long holes = 0;
        if (version < ByteReader.V20) {
            holes = r.u32();
        } else if (version < ByteReader.V21) {
            holes = r.varU32();
        }
        if (holes > 0) {
            if (version < ByteReader.V12) {
                r.skip(4 * holes);
            } else {
                for (long i = 0; i < holes; i++) r.varU32();
            }
        }
    }

    // ---------- 記号の読み手 ----------

    private void startTraversal(ByteReader r) throws IOException {
        if (traversalType != VALENCE || version < ByteReader.V22) {
            // 記号はビットの並びで書かれている
            final long size = version < ByteReader.V22 ? r.u64() : r.varU64();
            symbolBits = r.startBits();
            if (size < 0 || size > r.remaining()) throw new IOException("Invalid Draco symbol data size");
            r.skip(size);
        }
        // 最初の面が内側か (境界から始まったか) の印
        if (version < ByteReader.V22) {
            final long size = r.u64();
            startFaceBits = r.startBits();
            if (size < 0 || size > r.remaining()) throw new IOException("Invalid Draco start face data size");
            r.skip(size);
        } else {
            startFaceDecoder = Rans.BitDecoder.start(r);
        }
        seamDecoders = new Rans.BitDecoder[numAttributeData];
        for (int i = 0; i < numAttributeData; i++) seamDecoders[i] = Rans.BitDecoder.start(r);

        if (traversalType == PREDICTIVE) {
            final int splits = r.i32();
            if (splits < 0 || splits >= maxVertices) throw new IOException("Invalid Draco split count");
            budget.alloc(maxVertices);
            valences = new int[maxVertices];
            predictionDecoder = Rans.BitDecoder.start(r);
        } else if (traversalType == VALENCE) {
            if (version < ByteReader.V22) {
                final long splits = r.count32Before20();
                if (splits >= maxVertices) throw new IOException("Invalid Draco split count");
                if (r.i8() != 0) throw new IOException("Unsupported Draco valence mode");
            }
            budget.alloc(maxVertices);
            valences = new int[maxVertices];
            // 価数 2〜7 の 6 つの文脈ごとに、記号がまとめて書かれている
            contextSymbols = new int[6][];
            contextCounters = new int[6];
            for (int i = 0; i < 6; i++) {
                final long n = r.varU32();
                if (n > numFaces) throw new IOException("Too many Draco valence symbols");
                if (n > 0) {
                    budget.alloc(n);
                    contextSymbols[i] = new int[(int) n];
                    Rans.decodeSymbols(r, (int) n, 1, contextSymbols[i]);
                    contextCounters[i] = (int) n;
                }
            }
        }
    }

    private int standardSymbol() throws IOException {
        final int first = symbolBits.bits(1);
        if (first == C) return C;
        return first | symbolBits.bits(2) << 1;
    }

    private int nextSymbol() throws IOException {
        switch (traversalType) {
            case PREDICTIVE:
                // 予測が当たったかを 1 ビットで読み、外れたときだけ記号を読む
                if (predictedSymbol != -1 && predictionDecoder.next()) {
                    lastSymbol = predictedSymbol;
                } else {
                    lastSymbol = standardSymbol();
                }
                return lastSymbol;
            case VALENCE:
                if (activeContext != -1) {
                    final int k = --contextCounters[activeContext];
                    if (k < 0) return INVALID_SYMBOL;
                    final int id = contextSymbols[activeContext][k];
                    if (id < 0 || id > 4) return INVALID_SYMBOL;
                    lastSymbol = SYMBOL_OF_ID[id];
                } else {
                    // 最初の記号は必ず E
                    lastSymbol = version < ByteReader.V22 ? standardSymbol() : E;
                }
                return lastSymbol;
            default:
                return standardSymbol();
        }
    }

    // 新しい面ができたときに、頂点の価数を数え直して次の記号の予測や文脈を決める
    private void newActiveCorner(int corner) {
        if (traversalType == STANDARD) return;
        final int next = Connectivity.next(corner);
        final int prev = Connectivity.previous(corner);
        final int vc = table.vertex(corner);
        final int vn = table.vertex(next);
        final int vp = table.vertex(prev);
        switch (lastSymbol) {
            case C:
            case S:
                valences[vn] += 1;
                valences[vp] += 1;
                break;
            case R:
                valences[vc] += 1;
                valences[vn] += 1;
                valences[vp] += 2;
                break;
            case L:
                valences[vc] += 1;
                valences[vn] += 2;
                valences[vp] += 1;
                break;
            case E:
                valences[vc] += 2;
                valences[vn] += 2;
                valences[vp] += 2;
                break;
            default:
                break;
        }
        if (traversalType == PREDICTIVE) {
            if (lastSymbol == C || lastSymbol == R) {
                predictedSymbol = valences[vn] < 6 ? R : C;
            } else {
                predictedSymbol = -1;
            }
        } else {
            final int valence = Math.max(2, Math.min(7, valences[vn]));
            activeContext = valence - 2;
        }
    }

    private void mergeValences(int dest, int source) {
        if (valences != null) valences[dest] += valences[source];
    }

    private boolean startFaceIsInterior() throws IOException {
        if (version < ByteReader.V22) return startFaceBits.bits(1) != 0;
        return startFaceDecoder.next();
    }

    // ---------- 面を足していく ----------

    // 分かれ目の記録の一番後ろが、この記号のものか
    private int splitIndexFor(int encoderSymbol) throws IOException {
        if (splitCount == 0) return -1;
        final long source = splitSource[splitCount - 1];
        if (source > encoderSymbol) throw new IOException("Invalid Draco topology split order");
        if (source != encoderSymbol) return -1;
        return --splitCount;
    }

    private int addVertex() throws IOException {
        final int v = table.addVertex();
        if (table.numVertices() > maxVertices) throw new IOException("Too many Draco vertices decoded");
        return v;
    }

    private static void requireFree(CornerTable t, int corner) throws IOException {
        if (corner < 0 || t.opposite(corner) != Connectivity.INVALID) {
            throw new IOException("Invalid Draco EdgeBreaker connectivity");
        }
    }

    /**
     * 記号を読んで面を足す。できた頂点の数 (使われなくなった頂点を詰めたあと) を返す。
     */
    private int decodeTopology(int numSymbols) throws IOException {
        final IntList active = new IntList();
        // 分かれ目の記号の番号 → そこで使う辺
        budget.alloc(numSymbols);
        final int[] splitCorners = new int[numSymbols];
        Arrays.fill(splitCorners, -1);
        final boolean removeIsolated = numAttributeData == 0;
        final IntList isolated = new IntList();
        final CornerTable t = table;
        int faceCount = 0;
        for (int symbolId = 0; symbolId < numSymbols; symbolId++) {
            final int corner = 3 * faceCount++;
            boolean checkSplit = false;
            final int symbol = nextSymbol();
            if (symbol == C) {
                // 開いた境界の 2 つの辺の間に面を作る
                if (active.isEmpty()) throw new IOException("Invalid Draco EdgeBreaker symbol C");
                final int cornerA = active.last();
                final int vertexX = t.vertex(Connectivity.next(cornerA));
                final int cornerB = Connectivity.next(t.leftMostCorner(vertexX));
                if (cornerA == cornerB) throw new IOException("Invalid Draco EdgeBreaker connectivity");
                requireFree(t, cornerA);
                requireFree(t, cornerB);
                t.setOpposites(cornerA, corner + 1);
                t.setOpposites(cornerB, corner + 2);
                final int vertexAPrev = t.vertex(Connectivity.previous(cornerA));
                final int vertexBNext = t.vertex(Connectivity.next(cornerB));
                if (vertexX == vertexAPrev || vertexX == vertexBNext) throw new IOException("Degenerate Draco face");
                t.map(corner, vertexX);
                t.map(corner + 1, vertexBNext);
                t.map(corner + 2, vertexAPrev);
                t.setLeftMostCorner(vertexAPrev, corner + 2);
                isVertexHole[vertexX] = false;
                active.setLast(corner);
            } else if (symbol == R || symbol == L) {
                // 境界の辺に面を足し、新しい頂点を 1 つ作る
                if (active.isEmpty()) throw new IOException("Invalid Draco EdgeBreaker symbol");
                final int cornerA = active.last();
                requireFree(t, cornerA);
                final int opp;
                final int cornerL;
                final int cornerR;
                if (symbol == R) {
                    opp = corner + 2;
                    cornerL = corner + 1;
                    cornerR = corner;
                } else {
                    opp = corner + 1;
                    cornerL = corner;
                    cornerR = corner + 2;
                }
                t.setOpposites(opp, cornerA);
                final int newVertex = addVertex();
                t.map(opp, newVertex);
                t.setLeftMostCorner(newVertex, opp);
                final int vertexR = t.vertex(Connectivity.previous(cornerA));
                t.map(cornerR, vertexR);
                t.setLeftMostCorner(vertexR, cornerR);
                t.map(cornerL, t.vertex(Connectivity.next(cornerA)));
                active.setLast(corner);
                checkSplit = true;
            } else if (symbol == S) {
                // スタックの上の 2 つの辺をつなぐ面を作り、2 つの頂点を 1 つにまとめる
                if (active.isEmpty()) throw new IOException("Invalid Draco EdgeBreaker symbol S");
                final int cornerB = active.removeLast();
                if (splitCorners[symbolId] != -1) active.add(splitCorners[symbolId]);
                if (active.isEmpty()) throw new IOException("Invalid Draco EdgeBreaker symbol S");
                final int cornerA = active.last();
                if (cornerA == cornerB) throw new IOException("Invalid Draco EdgeBreaker connectivity");
                requireFree(t, cornerA);
                requireFree(t, cornerB);
                t.setOpposites(cornerA, corner + 2);
                t.setOpposites(cornerB, corner + 1);
                final int vertexP = t.vertex(Connectivity.previous(cornerA));
                t.map(corner, vertexP);
                t.map(corner + 1, t.vertex(Connectivity.next(cornerA)));
                final int vertexBPrev = t.vertex(Connectivity.previous(cornerB));
                t.map(corner + 2, vertexBPrev);
                t.setLeftMostCorner(vertexBPrev, corner + 2);
                int cornerN = Connectivity.next(cornerB);
                final int vertexN = t.vertex(cornerN);
                mergeValences(vertexP, vertexN);
                t.setLeftMostCorner(vertexP, t.leftMostCorner(vertexN));
                // n の周りの角をすべて p にする
                final int first = cornerN;
                while (cornerN != Connectivity.INVALID) {
                    budget.step();
                    t.map(cornerN, vertexP);
                    cornerN = t.swingLeft(cornerN);
                    if (cornerN == first) throw new IOException("Invalid Draco EdgeBreaker connectivity");
                }
                t.setLeftMostCorner(vertexN, Connectivity.INVALID);
                if (removeIsolated) isolated.add(vertexN);
                active.setLast(corner);
            } else if (symbol == E) {
                // 新しい部分の始まり。頂点を 3 つ作る
                final int v0 = addVertex();
                final int v1 = addVertex();
                final int v2 = addVertex();
                t.map(corner, v0);
                t.map(corner + 1, v1);
                t.map(corner + 2, v2);
                t.setLeftMostCorner(v0, corner);
                t.setLeftMostCorner(v1, corner + 1);
                t.setLeftMostCorner(v2, corner + 2);
                active.add(corner);
                checkSplit = true;
            } else {
                throw new IOException("Invalid Draco EdgeBreaker symbol: " + symbol);
            }
            newActiveCorner(active.last());
            if (checkSplit) {
                // この面の残りの辺が、後で S の記号でつながる辺か
                final int encoderSymbol = numSymbols - symbolId - 1;
                int index;
                while ((index = splitIndexFor(encoderSymbol)) >= 0) {
                    final long encoderSplit = splitSymbol[index];
                    if (encoderSplit > Integer.MAX_VALUE) throw new IOException("Invalid Draco topology split");
                    final int top = active.last();
                    final int newCorner = splitEdge[index] == 1 ? Connectivity.next(top) : Connectivity.previous(top);
                    // 古い形式では番号が範囲の外のこともある。その辺はどの S でも使われない
                    if (encoderSplit < numSymbols) splitCorners[numSymbols - (int) encoderSplit - 1] = newCorner;
                }
            }
        }
        if (t.numVertices() > maxVertices) throw new IOException("Too many Draco vertices decoded");

        // 残った辺には最初の面を付ける (内側の面なら 3 辺とも閉じる)
        while (!active.isEmpty()) {
            final int corner = active.removeLast();
            if (startFaceIsInterior()) {
                if (faceCount >= t.numFaces()) throw new IOException("Too many Draco faces decoded");
                final int vertexN = t.vertex(Connectivity.next(corner));
                final int cornerB = Connectivity.next(t.leftMostCorner(vertexN));
                if (cornerB < 0) throw new IOException("Invalid Draco EdgeBreaker connectivity");
                final int vertexX = t.vertex(Connectivity.next(cornerB));
                final int cornerC = Connectivity.next(t.leftMostCorner(vertexX));
                if (cornerC < 0 || corner == cornerB || corner == cornerC || cornerB == cornerC) {
                    throw new IOException("Invalid Draco EdgeBreaker connectivity");
                }
                requireFree(t, corner);
                requireFree(t, cornerB);
                requireFree(t, cornerC);
                final int vertexP = t.vertex(Connectivity.next(cornerC));
                final int newCorner = 3 * faceCount++;
                t.setOpposites(newCorner, corner);
                t.setOpposites(newCorner + 1, cornerB);
                t.setOpposites(newCorner + 2, cornerC);
                t.map(newCorner, vertexX);
                t.map(newCorner + 1, vertexP);
                t.map(newCorner + 2, vertexN);
                for (int i = 0; i < 3; i++) isVertexHole[t.vertex(newCorner + i)] = false;
            }
        }
        if (faceCount != t.numFaces()) throw new IOException("Unexpected number of Draco faces");

        // 位置以外の属性がないときは、まとめて使われなくなった頂点を後ろの頂点と入れ替えて詰める
        int numVertices = t.numVertices();
        for (int i = 0; i < isolated.getCount(); i++) {
            final int invalid = isolated.get(i);
            int src = numVertices - 1;
            while (src >= 0 && t.leftMostCorner(src) == Connectivity.INVALID) {
                src = --numVertices - 1;
            }
            if (src < 0) throw new IOException("Invalid Draco vertices");
            if (src < invalid) continue;
            // src の周りの角をすべて invalid の番号にする
            final int start = t.leftMostCorner(src);
            int c = start;
            boolean left = true;
            while (c != Connectivity.INVALID) {
                budget.step();
                if (t.vertex(c) != src) throw new IOException("Invalid Draco vertex ring");
                t.map(c, invalid);
                if (left) {
                    c = t.swingLeft(c);
                    if (c == Connectivity.INVALID) {
                        c = t.swingRight(start);
                        left = false;
                    } else if (c == start) {
                        c = Connectivity.INVALID;
                    }
                } else {
                    c = t.swingRight(c);
                }
            }
            t.setLeftMostCorner(invalid, start);
            t.setLeftMostCorner(src, Connectivity.INVALID);
            isVertexHole[invalid] = isVertexHole[src];
            isVertexHole[src] = false;
            numVertices--;
        }
        return numVertices;
    }

    // ---------- 属性の継ぎ目と点 ----------

    private void decodeSeams() throws IOException {
        final CornerTable t = table;
        final boolean legacy = version < ByteReader.V21;
        for (int face = 0; face < numFaces; face++) {
            for (int k = 0; k < 3; k++) {
                final int c = 3 * face + k;
                final int opp = t.opposite(c);
                if (opp == Connectivity.INVALID) {
                    // 境界の辺はいつも継ぎ目
                    for (AttributeCornerTable a : attributeTables) a.addSeamEdge(c);
                    continue;
                }
                // 新しい形式では、辺は片側の面でだけ読む
                if (!legacy && opp / 3 < face) continue;
                for (int i = 0; i < numAttributeData; i++) {
                    if (seamDecoders[i].next()) attributeTables[i].addSeamEdge(c);
                }
            }
        }
    }

    /**
     * 角ごとに点の番号を決める。どの属性も変わらない角どうしは同じ点にまとめる。
     */
    private void assignPoints(int numVertices) throws IOException {
        final CornerTable t = table;
        final int corners = t.numCorners();
        budget.alloc(corners);
        final int[] faces = new int[corners];
        if (numAttributeData == 0) {
            for (int c = 0; c < corners; c++) faces[c] = t.vertex(c);
            session.setFaces(faces, numVertices);
            return;
        }
        int count = 0;
        final int vertices = t.numVertices();
        for (int v = 0; v < vertices; v++) {
            int c = t.leftMostCorner(v);
            if (c == Connectivity.INVALID) continue;
            int first = c;
            if (!isVertexHole[v]) {
                // 内側の頂点は、どれかの属性の継ぎ目から始める
                for (AttributeCornerTable a : attributeTables) {
                    if (!a.isCornerOnSeam(c)) continue;
                    final int id = a.vertex(c);
                    int act = t.swingRight(c);
                    boolean found = false;
                    while (act != c) {
                        budget.step();
                        if (act == Connectivity.INVALID) throw new IOException("Invalid Draco vertex ring");
                        if (a.vertex(act) != id) {
                            first = act;
                            found = true;
                            break;
                        }
                        act = t.swingRight(act);
                    }
                    if (found) break;
                }
            }
            c = first;
            faces[c] = count++;
            int prev = c;
            c = t.swingRight(c);
            while (c != Connectivity.INVALID && c != first) {
                budget.step();
                boolean seam = false;
                for (AttributeCornerTable a : attributeTables) {
                    if (a.vertex(c) != a.vertex(prev)) {
                        seam = true;
                        break;
                    }
                }
                faces[c] = seam ? count++ : faces[prev];
                prev = c;
                c = t.swingRight(c);
            }
        }
        session.setFaces(faces, count);
    }

    // ---------- 属性のまとまり ----------

    /**
     * 属性のまとまりの見出し (どのつながりを使い、どの順にたどるか) を読む。
     */
    void readGroupHeader(AttributeGroup group, int groupIndex) throws IOException {
        final int dataId = in.i8();
        final int decoderType = in.u8();
        if (dataId >= 0) {
            if (dataId >= numAttributeData) throw new IOException("Invalid Draco attribute data id");
            if (dataOwner[dataId] >= 0) throw new IOException("Draco attribute data used twice");
            dataOwner[dataId] = groupIndex;
        } else {
            if (positionOwner >= 0) throw new IOException("Draco position data used twice");
            positionOwner = groupIndex;
        }
        int method = MeshTraversal.DEPTH_FIRST;
        if (version >= ByteReader.V12) {
            method = in.u8();
            if (method > MeshTraversal.PREDICTION_DEGREE) throw new IOException("Invalid Draco traversal method");
        }
        if (decoderType == 0) {
            // 頂点ごとの属性。位置のつながりでたどる
            group.table = table;
            group.encoding = dataId < 0 ? positionEncoding() : dataEncoding(dataId);
            group.traversal = method;
        } else {
            // 角ごとの属性。継ぎ目つきのつながりでたどる
            if (method != MeshTraversal.DEPTH_FIRST || dataId < 0) throw new IOException("Invalid Draco corner attribute");
            group.table = attributeTables[dataId];
            group.encoding = dataEncoding(dataId);
            group.traversal = MeshTraversal.DEPTH_FIRST;
        }
    }

    private MeshTraversal.Encoding positionEncoding() throws IOException {
        if (positionEncoding == null) {
            budget.alloc(table.numVertices());
            positionEncoding = new MeshTraversal.Encoding(table.numVertices());
        }
        return positionEncoding;
    }

    private MeshTraversal.Encoding dataEncoding(int id) throws IOException {
        if (dataEncoding[id] == null) {
            final int n = Math.max(attributeTables[id].numVertices(), table.numVertices());
            budget.alloc(n);
            dataEncoding[id] = new MeshTraversal.Encoding(n);
        }
        return dataEncoding[id];
    }
}
