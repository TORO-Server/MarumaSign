package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 同じ順番で値が並んでいる属性のまとまり (Draco の attributes decoder 1 つ分)。
 */
final class AttributeGroup {

    final List<AttributeDecoder> decoders = new ArrayList<>();

    // EdgeBreaker のときのたどり方。順番に並べるだけ (sequential) のときは table が null
    Connectivity table;
    MeshTraversal.Encoding encoding;
    int traversal;

    /**
     * まとまりの属性をすべて読む。
     */
    void decode(MeshDecoding session, ByteReader in) throws IOException {
        final int numPoints = session.numPoints;
        final int[] pointIds;
        int[] map = null;
        if (table == null) {
            // 点の番号の順にそのまま並んでいる
            session.budget.alloc(numPoints);
            pointIds = new int[numPoints];
            for (int i = 0; i < numPoints; i++) pointIds[i] = i;
        } else {
            pointIds = MeshTraversal.run(table, traversal, encoding, session.faces, session.budget);
            // 点 → 値の番号の対応を、角を通して作る
            session.budget.alloc(numPoints);
            map = new int[numPoints];
            Arrays.fill(map, -1);
            final int[] faces = session.faces;
            for (int c = 0; c < faces.length; c++) {
                final int point = faces[c];
                final int vertex = table.vertex(c);
                if (vertex < 0 || vertex >= encoding.vertexToValue.length) throw new IOException("Invalid Draco corner vertex");
                final int value = encoding.vertexToValue[vertex];
                if (point < 0 || point >= numPoints || value >= pointIds.length) {
                    throw new IOException("Invalid Draco point mapping");
                }
                map[point] = value;
            }
        }
        for (AttributeDecoder d : decoders) d.attribute.setMapping(map);
        for (AttributeDecoder d : decoders) d.decodePortable(in, pointIds);
        for (AttributeDecoder d : decoders) d.decodeTransformData(in);
        for (AttributeDecoder d : decoders) d.transformToOriginal();
    }
}
