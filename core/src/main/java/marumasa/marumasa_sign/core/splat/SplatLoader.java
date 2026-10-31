package marumasa.marumasa_sign.core.splat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * ガウシアンスプラッティングのファイル (.ply .spz .sog .splat .ksplat) を読み込む。
 */
public final class SplatLoader {

    // 読み込むガウス分布の数の上限。これより多いファイルは、目立つものから順にこの数だけ残す
    public static final int MAX_SPLATS = 150000;

    // zip にしていない .sog の meta.json の大きさの上限
    public static final int MAX_SOG_META_SIZE = SogReader.MAX_META_BYTES;

    // 背景の遠い分布を切り落とす、中心からの距離の中央値に対する倍率
    static final float DEFAULT_CROP_FACTOR = 4.0f;

    // 以下の 2 つは SplatCheck が検査のあいだだけ変える。Mod の中では変えない
    static float cropFactor = DEFAULT_CROP_FACTOR;
    // 切り落とし (外れ値の除去) を行うかどうか。数値の比較では無効にする
    static boolean cropEnabled = true;

    private SplatLoader() {
    }

    /**
     * 先頭のバイト列と名前から、ガウシアンスプラッティングのファイルらしいかどうかを判定する。
     *
     * @param head   ファイルの先頭
     * @param length head のうち有効な長さ
     * @param name   ファイル名か URL (拡張子の判定に使う)。わからなければ null
     */
    public static boolean isSplat(byte[] head, int length, String name) {
        if (head == null || length <= 0) return false;
        final int limit = Math.min(length, head.length);
        final String ext = extension(name);
        if (PlyReader.isPly(head, limit)) return true;
        if (SpzReader.isSpz(head, limit)) return true;
        if (SogReader.isSog(head, limit, ext)) return true;
        if (KsplatReader.isKsplat(head, limit, ext)) return true;
        if (".splat".equals(ext) && limit >= 32) {
            final ByteBuffer buf = ByteBuffer.wrap(head, 0, limit).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 6; i++) {
                if (!Float.isFinite(buf.getFloat(i * 4))) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * ファイルの内容を読み込む。ガウス分布が MAX_SPLATS より多ければ、目立つものから順に残す。
     * name の末尾に「#coord=RDF」のような指定があれば、ファイルの座標の向きをそれとみなす (SplatCoordinates)。
     *
     * @param content ファイルの内容
     * @param name    ファイル名か URL (拡張子の判定と、#coord= の指定に使う)。わからなければ null
     */
    public static SplatCloud load(byte[] content, String name) throws IOException {
        if (content == null || content.length == 0) throw new IOException("Empty splat data");
        final String ext = extension(name);
        try {
            if (PlyReader.isPly(content, content.length) || ".ply".equals(ext)) {
                return validate(orient(PlyReader.load(content), name));
            }
            if (SpzReader.isSpz(content, content.length) || ".spz".equals(ext)) {
                return validate(orientSpz(SpzReader.load(content), content, name));
            }
            if (SogReader.isSog(content, content.length, ext) || ".sog".equals(ext)) {
                return validate(orient(SogReader.load(content), name));
            }
            if (".ksplat".equals(ext)) {
                return validate(orient(KsplatReader.load(content), name));
            }
            if (".splat".equals(ext)) {
                return validate(orient(loadSplat(content), name));
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid splat data: " + e, e);
        }
        throw new IOException("Not a Gaussian splatting file");
    }

    /**
     * zip にしていない .sog (meta.json と、それが参照する WebP のファイル) を読み込む。
     *
     * @param meta  meta.json の内容
     * @param files meta.json からの相対の名前でファイルを読むもの
     * @param name  meta.json の URL (#coord= の指定に使う)。わからなければ null
     */
    public static SplatCloud loadSogFiles(byte[] meta, SogFiles files, String name) throws IOException {
        try {
            return validate(orient(SogReader.loadFiles(meta, files), name));
        } catch (RuntimeException e) {
            throw new IOException("Invalid splat data: " + e, e);
        }
    }

    /** zip にしていない .sog の WebP のファイルを読むもの */
    public interface SogFiles {
        /**
         * meta.json からの相対の名前 name のファイルを、maxBytes バイトまで読む。超えたら IOException
         */
        byte[] load(String name, int maxBytes) throws IOException;
    }

    /** 内容が、zip にしていない .sog の meta.json らしいかどうか (JSON として読み、参照するファイルの書き方まで確かめる) */
    public static boolean isSogMeta(byte[] content) {
        return SogReader.metaFiles(content) != null;
    }

    // .spz 以外の形式は、読むときに 3DGS の撮影の座標 (RDF) から直してある。#coord= で別の向きと指定されたら、その向きから直し直す
    private static SplatCloud orient(SplatCloud cloud, String name) {
        final String coordinate = SplatCoordinates.fromUrl(name);
        if (coordinate != null && !coordinate.equals("RDF")) {
            SplatCoordinates.convert(cloud, "RUB", "RDF");
            SplatCoordinates.convert(cloud, coordinate, "RUB");
        }
        return cloud;
    }

    // .spz はファイルの座標のまま読む。#coord= の指定、拡張での宣言、中身からの推定、仕様の RUB の順に決める
    private static SplatCloud orientSpz(SplatCloud cloud, byte[] content, String name) {
        String coordinate = SplatCoordinates.fromUrl(name);
        if (coordinate == null) coordinate = SpzReader.declaredCoordinate(content);
        if (coordinate == null) coordinate = SplatCoordinates.guessUp(cloud);
        if (coordinate == null) coordinate = "RUB";
        SplatCoordinates.convert(cloud, coordinate, "RUB");
        return cloud;
    }

    /**
     * 読み込んだ結果を確かめる。位置と大きさが有限でない分布があれば IOException にする。
     * 形式によっては、有限の値を組み合わせた計算 (範囲の最小と最大の補間など) で無限大になることがあり、
     * そのまま描くと並べ替えや大きさの計算 (restBounds) が壊れる。
     */
    static SplatCloud validate(SplatCloud cloud) throws IOException {
        final int count = cloud.count;
        if (count < 0 || count > MAX_SPLATS || cloud.positions.length < count * 3 || cloud.scales.length < count * 3
                || cloud.rotations.length < count * 4 || cloud.opacities.length < count || cloud.colors.length < count * 3) {
            throw new IOException("Invalid splat data");
        }
        for (int i = 0; i < count * 3; i++) {
            if (!Float.isFinite(cloud.positions[i]) || !Float.isFinite(cloud.scales[i])) {
                throw new IOException("Non-finite splat position or scale");
            }
        }
        return cloud;
    }

    // 名前の拡張子を、小文字にして "." 付きで返す。URL のクエリやフラグメントは無視する。なければ ""
    static String extension(String name) {
        if (name == null) return "";
        int end = name.length();
        final int query = name.indexOf('?');
        if (query >= 0) end = query;
        final int fragment = name.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        final String path = name.substring(0, end);
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        final int dot = path.lastIndexOf('.');
        if (dot <= slash) return "";
        return path.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * 目立つ度合いの大きいものから max 個を選び、元の順に並べた添字を返す。
     * 全体が max 個以下なら、すべての添字を返す。
     */
    static int[] selectTop(float[] importance, int count, int max) {
        return select(importance, null, count, max, false);
    }

    /**
     * 目立つ度合いと位置から、残す分布の添字を昇順で返す。
     * 背景の遠い分布を切り落とし、残ったものから目立つ順に最大 max 個を選ぶ。
     */
    static int[] select(float[] importance, float[] positions, int count, int max) {
        return select(importance, positions, count, max, cropEnabled);
    }

    static int[] select(float[] importance, float[] positions, int count, int max, boolean crop) {
        if (count <= 0 || max <= 0) return new int[0];

        int[] candidates = null;
        int candCount = count;

        if (crop && positions != null && count > 1) {
            // 1. 各軸の中央値から中心を求める (省メモリのため 1 つの作業配列を使い回す)
            final float[] coords = new float[count];

            for (int i = 0; i < count; i++) coords[i] = positions[i * 3];
            Arrays.sort(coords);
            final float medX = coords[count / 2];

            for (int i = 0; i < count; i++) coords[i] = positions[i * 3 + 1];
            Arrays.sort(coords);
            final float medY = coords[count / 2];

            for (int i = 0; i < count; i++) coords[i] = positions[i * 3 + 2];
            Arrays.sort(coords);
            final float medZ = coords[count / 2];

            // 2. 各分布の中心からの距離を計算し、その中央値 dMed を求める
            final float[] dists = new float[count];
            for (int i = 0; i < count; i++) {
                final float dx = positions[i * 3] - medX;
                final float dy = positions[i * 3 + 1] - medY;
                final float dz = positions[i * 3 + 2] - medZ;
                final float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dists[i] = d;
                coords[i] = d;
            }
            Arrays.sort(coords);
            final float dMed = coords[count / 2];

            // 3. 中心から CROP_FACTOR * dMed より遠い分布を切り落とす
            final float maxDist = cropFactor * dMed;
            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (dists[i] <= maxDist) kept++;
            }

            // 安全性チェック: 残る分布が全体の半分未満になる場合は切り落とさない
            if (kept >= count - count / 2 && kept < count) {
                candidates = new int[kept];
                int n = 0;
                for (int i = 0; i < count; i++) {
                    if (dists[i] <= maxDist) candidates[n++] = i;
                }
                candCount = kept;
            }
        }

        if (candCount <= max) {
            if (candidates != null) return candidates;
            final int[] all = new int[count];
            for (int i = 0; i < count; i++) all[i] = i;
            return all;
        }

        final float[] candImp = new float[candCount];
        if (candidates != null) {
            for (int i = 0; i < candCount; i++) candImp[i] = importance[candidates[i]];
        } else {
            System.arraycopy(importance, 0, candImp, 0, count);
        }
        Arrays.sort(candImp);
        final float threshold = candImp[candCount - max];

        final int[] selected = new int[max];
        int n = 0;
        if (candidates != null) {
            for (int i = 0; i < candCount && n < max; i++) {
                final int idx = candidates[i];
                if (importance[idx] > threshold) selected[n++] = idx;
            }
            for (int i = 0; i < candCount && n < max; i++) {
                final int idx = candidates[i];
                if (importance[idx] == threshold) selected[n++] = idx;
            }
        } else {
            for (int i = 0; i < count && n < max; i++) {
                if (importance[i] > threshold) selected[n++] = i;
            }
            for (int i = 0; i < count && n < max; i++) {
                if (importance[i] == threshold) selected[n++] = i;
            }
        }
        Arrays.sort(selected);
        return selected;
    }

    // 目立つ度合い。不透明度と、分布の広がり (おおよその表面積) の平方根の積。
    // 表面積そのものを使うと、巨大な背景の分布ばかりが選ばれる
    static float importance(float opacity, float sx, float sy, float sz) {
        final float area = sx * sy + sy * sz + sz * sx;
        // NaN も 0 にする (並べ替えたときに、選ぶ数が足りなくならないように)
        if (!(area > 0f) || !(opacity > 0f)) return 0f;
        return opacity * (float) Math.sqrt(area);
    }

    /**
     * +Y が下で正面が -Z の座標系 (多くの .ply の座標系) から、+Y が上で正面が +Z の座標系に直す。
     * X 軸のまわりに半回転させる。positions と rotations と shCoefficients をその場で書き換える。
     */
    static void flipYZ(int count, float[] positions, float[] rotations, int shDegree, float[] sh) {
        for (int i = 0; i < count; i++) {
            positions[i * 3 + 1] = -positions[i * 3 + 1];
            positions[i * 3 + 2] = -positions[i * 3 + 2];
            // (1, 0, 0, 0) を左から掛ける
            final int o = i * 4;
            final float x = rotations[o], y = rotations[o + 1], z = rotations[o + 2], w = rotations[o + 3];
            rotations[o] = w;
            rotations[o + 1] = -z;
            rotations[o + 2] = y;
            rotations[o + 3] = -x;
        }
        if (shDegree <= 0) return;
        // 半回転で符号が変わる基底 (y と z について奇数次の項)
        final float[] sign = {
                -1, -1, 1,
                -1, 1, 1, -1, 1,
                -1, 1, -1, -1, 1, -1, 1
        };
        final int rest = SplatCloud.restCoefficients(shDegree);
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < rest; k++) {
                if (sign[k] > 0) continue;
                final int o = (i * rest + k) * 3;
                sh[o] = -sh[o];
                sh[o + 1] = -sh[o + 1];
                sh[o + 2] = -sh[o + 2];
            }
        }
    }

    // 1 つにつき 32 バイト: 位置 (float x3)、大きさ (float x3)、色と不透明度 (u8 x4)、回転 (u8 x4、w x y z の順)
    private static SplatCloud loadSplat(byte[] content) throws IOException {
        if (content.length % 32 != 0 || content.length == 0) throw new IOException("Invalid .splat size");
        final int total = content.length / 32;
        final ByteBuffer buffer = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

        final float[] rawPositions = new float[total * 3];
        final float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int o = i * 32;
            final float px = buffer.getFloat(o);
            final float py = buffer.getFloat(o + 4);
            final float pz = buffer.getFloat(o + 8);
            final float sx = Math.abs(buffer.getFloat(o + 12));
            final float sy = Math.abs(buffer.getFloat(o + 16));
            final float sz = Math.abs(buffer.getFloat(o + 20));
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)
                    || !Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Invalid .splat values");
            }
            rawPositions[i * 3] = px;
            rawPositions[i * 3 + 1] = py;
            rawPositions[i * 3 + 2] = pz;
            importance[i] = importance((content[o + 27] & 0xFF) / 255f, sx, sy, sz);
        }
        final int[] selected = select(importance, rawPositions, total, MAX_SPLATS);
        final int count = selected.length;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        for (int n = 0; n < count; n++) {
            final int idx = selected[n];
            final int o = idx * 32;
            for (int c = 0; c < 3; c++) {
                positions[n * 3 + c] = rawPositions[idx * 3 + c];
                scales[n * 3 + c] = Math.abs(buffer.getFloat(o + 12 + c * 4));
                colors[n * 3 + c] = (content[o + 24 + c] & 0xFF) / 255f;
            }
            opacities[n] = (content[o + 27] & 0xFF) / 255f;
            final float w = ((content[o + 28] & 0xFF) - 128) / 128f;
            final float x = ((content[o + 29] & 0xFF) - 128) / 128f;
            final float y = ((content[o + 30] & 0xFF) - 128) / 128f;
            final float z = ((content[o + 31] & 0xFF) - 128) / 128f;
            final float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (length > 0) {
                rotations[n * 4] = x / length;
                rotations[n * 4 + 1] = y / length;
                rotations[n * 4 + 2] = z / length;
                rotations[n * 4 + 3] = w / length;
            } else {
                rotations[n * 4 + 3] = 1;
            }
            for (int c = 0; c < 3; c++) {
                if (!Float.isFinite(positions[n * 3 + c]) || !Float.isFinite(scales[n * 3 + c])) {
                    throw new IOException("Invalid .splat values");
                }
            }
        }
        flipYZ(count, positions, rotations, 0, new float[0]);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, 0, new float[0]);
    }
}
