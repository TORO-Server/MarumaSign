package marumasa.marumasa_sign.core.structure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * ゲームのコードで形が決まっているもの (チェスト、旗、頭など) の形 (marumasa_sign/structure/entity_models.txt.gz)。
 * tools/blockdata/EntityModelDump.java で作る。四角形ごとに 4 頂点の x, y, z (ブロック単位), u, v (0 - 1)。
 */
final class EntityModels {

    private static volatile Map<String, List<float[]>> models;
    // 部分の位置 (ピクセル単位)。部分ごとに書き出したモデルだけにある
    private static final Map<String, float[]> PIVOTS = new HashMap<String, float[]>();

    static float[] pivot(String name) throws IOException {
        if (models == null) load();
        final float[] pivot = PIVOTS.get(name);
        return pivot == null ? new float[3] : pivot;
    }

    private EntityModels() {
    }

    static List<float[]> get(String name) throws IOException {
        if (models == null) load();
        final List<float[]> quads = models.get(name);
        if (quads == null) throw new IOException("Unknown entity model: " + name);
        return quads;
    }

    private static synchronized void load() throws IOException {
        if (models != null) return;
        final InputStream raw = EntityModels.class.getResourceAsStream("/marumasa_sign/structure/entity_models.txt.gz");
        if (raw == null) throw new IOException("Missing entity model data");
        final Map<String, List<float[]>> result = new HashMap<String, List<float[]>>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
        try {
            List<float[]> current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("E ")) {
                    current = new ArrayList<float[]>();
                    result.put(line.substring(2).trim(), current);
                } else if (line.startsWith("P ") && current != null) {
                    final String[] parts = line.substring(2).trim().split(" ");
                    String name = null;
                    for (Map.Entry<String, List<float[]>> entry : result.entrySet()) {
                        if (entry.getValue() == current) name = entry.getKey();
                    }
                    if (name != null) PIVOTS.put(name, new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])});
                } else if (line.startsWith("Q") && current != null) {
                    final String[] parts = line.substring(1).trim().split(" ");
                    final float[] quad = new float[20];
                    for (int i = 0; i < 20 && i < parts.length; i++) quad[i] = Float.parseFloat(parts[i]);
                    // 面積のない面 (厚さのない箱の側面) は描かない
                    if (!degenerate(quad)) current.add(quad);
                }
            }
        } finally {
            reader.close();
        }
        models = result;
    }

    private static boolean degenerate(float[] q) {
        final float ax = q[5] - q[0], ay = q[6] - q[1], az = q[7] - q[2];
        final float bx = q[10] - q[0], by = q[11] - q[1], bz = q[12] - q[2];
        final float cx = q[15] - q[0], cy = q[16] - q[1], cz = q[17] - q[2];
        final float n1x = ay * bz - az * by, n1y = az * bx - ax * bz, n1z = ax * by - ay * bx;
        final float n2x = by * cz - bz * cy, n2y = bz * cx - bx * cz, n2z = bx * cy - by * cx;
        return n1x * n1x + n1y * n1y + n1z * n1z < 1e-12f && n2x * n2x + n2y * n2y + n2z * n2z < 1e-12f;
    }
}
