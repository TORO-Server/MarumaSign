package marumasa.marumasa_sign.core.gltf.draco;

import java.util.ArrayList;
import java.util.List;

/**
 * 展開した三角形メッシュ。面は点の番号 3 つずつで、属性は点の番号から引く。
 */
public final class DracoMesh {

    private final IntList indices;
    private final int numPoints;
    private final List<PointAttribute> attributes;

    DracoMesh(int[] faces, int numPoints, List<PointAttribute> attributes) {
        this.indices = new IntList(faces);
        this.numPoints = numPoints;
        this.attributes = new ArrayList<>(attributes);
    }

    /**
     * 面の頂点 (点の番号) を 3 つずつ並べたもの。
     */
    public IntList getIndices() {
        return indices;
    }

    public int getNumFaces() {
        return indices.getCount() / 3;
    }

    public int getNumPoints() {
        return numPoints;
    }

    public int getNumAttributes() {
        return attributes.size();
    }

    public PointAttribute attribute(int index) {
        if (index < 0 || index >= attributes.size()) return null;
        return attributes.get(index);
    }

    /**
     * glTF の KHR_draco_mesh_compression で指定される番号 (unique id) で属性を探す。ないときは null。
     */
    public PointAttribute getAttributeByUniqueId(int uniqueId) {
        for (PointAttribute a : attributes) {
            if (a.getUniqueId() == uniqueId) return a;
        }
        return null;
    }

    /**
     * 種類 (PointAttribute.POSITION など) で最初の属性を探す。ないときは null。
     */
    public PointAttribute getNamedAttribute(int type) {
        for (PointAttribute a : attributes) {
            if (a.getAttributeType() == type) return a;
        }
        return null;
    }
}
