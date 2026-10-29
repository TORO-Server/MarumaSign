package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * 1 回の展開で使ってよいメモリと手間の上限。
 * ストリームに書かれた数は信用せず、大きな配列を作る前と、頂点の周りを回る処理の 1 歩ごとに数える。
 * 壊れたデータや悪意のあるデータで、メモリを使い切ったり終わらなくなったりしないようにする。
 */
final class Budget {

    // 面の数の上限。モデル全体の三角形の上限 (GltfLoader) よりずっと大きい
    static final int MAX_FACES = 1 << 20;
    // 点 (頂点) の数の上限。点は最大で角の数だけできる
    static final int MAX_POINTS = 3 * MAX_FACES;
    // 配列の要素の数の合計の上限 (int なら約 128MB)
    private static final long MEMORY_UNITS = 32L << 20;

    private long memory = MEMORY_UNITS;
    private long steps = 1L << 22;

    /**
     * units 個の要素の配列を作ってよいか確かめ、使った分を差し引く。
     */
    void alloc(long units) throws IOException {
        if (units < 0 || units > memory) throw new IOException("Draco data is too large");
        memory -= units;
    }

    /**
     * 頂点の周りを回るなどの手間の上限を、角の数から決める。
     * 正しいデータなら 1 回の処理で角の数くらいしか回らない。
     */
    void setStepLimit(long corners) {
        steps = 64L * corners + (1L << 22);
    }

    void step() throws IOException {
        if (--steps < 0) throw new IOException("Draco connectivity is inconsistent");
    }
}
