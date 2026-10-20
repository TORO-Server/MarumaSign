package marumasa.marumasa_sign.core.gltf;

import java.util.List;

/**
 * 四角形の列から、陰影を付けないモデル (GltfModel) を作る。構造物のファイルの表示に使う。
 * 色 (面の向きによる明るさや、草の色など) は頂点ごとに計算済みのものを渡す。
 */
public final class QuadModel {

    // 同じテクスチャと透明度の扱いで描く四角形のまとまり
    public static final class Part {
        public final int texture;
        // GltfModel.ALPHA_OPAQUE / ALPHA_MASK / ALPHA_BLEND
        public final int alphaMode;
        public final boolean doubleSided;
        // 四角形ごとに 4 頂点。頂点は反時計回りに見える側が表
        public final float[] positions;
        // 頂点ごとの u, v (テクスチャの左上が 0, 0)
        public final float[] uvs;
        // 頂点ごとの色 (ARGB)
        public final int[] colors;
        public final int quadCount;

        public Part(int texture, int alphaMode, boolean doubleSided, float[] positions, float[] uvs, int[] colors, int quadCount) {
            this.texture = texture;
            this.alphaMode = alphaMode;
            this.doubleSided = doubleSided;
            this.positions = positions;
            this.uvs = uvs;
            this.colors = colors;
            this.quadCount = quadCount;
        }
    }

    private QuadModel() {
    }

    // テクスチャ (幅, 高さ, ARGB) を作る
    public static GltfModel.Texture texture(int width, int height, int[] pixels) {
        return new GltfModel.Texture(width, height, pixels);
    }

    public static GltfModel build(List<GltfModel.Texture> textures, List<Part> parts, List<TextureAnimation> animations) {
        final GltfModel model = build(textures, parts);
        model.textureAnimations = animations.toArray(new TextureAnimation[0]);
        return model;
    }

    public static GltfModel build(List<GltfModel.Texture> textures, List<Part> parts) {
        final GltfModel model = new GltfModel();
        model.textures = textures.toArray(new GltfModel.Texture[0]);
        final GltfModel.Node node = new GltfModel.Node();
        node.mesh = 0;
        model.nodes = new GltfModel.Node[]{node};
        model.order = new int[]{0};
        final GltfModel.Mesh mesh = new GltfModel.Mesh();
        mesh.primitives = new GltfModel.Primitive[parts.size()];
        model.materials = new GltfModel.Material[parts.size()];
        int triangles = 0;
        for (int p = 0; p < parts.size(); p++) {
            final Part part = parts.get(p);
            final GltfModel.Material material = new GltfModel.Material();
            material.texture = part.texture;
            material.alphaMode = part.alphaMode;
            material.doubleSided = part.doubleSided;
            material.unlit = true;
            model.materials[p] = material;

            final int vertexCount = part.quadCount * 4;
            final GltfModel.Primitive primitive = new GltfModel.Primitive();
            primitive.vertexCount = vertexCount;
            primitive.positions = java.util.Arrays.copyOf(part.positions, vertexCount * 3);
            primitive.normals = new float[vertexCount * 3];
            primitive.colors = new float[vertexCount * 4];
            primitive.indices = new int[vertexCount];
            primitive.cornerUv = java.util.Arrays.copyOf(part.uvs, vertexCount * 2);
            primitive.quads = true;
            primitive.material = p;
            for (int v = 0; v < vertexCount; v++) {
                primitive.indices[v] = v;
                final int color = part.colors[v];
                primitive.colors[v * 4] = ((color >> 16) & 0xFF) / 255f;
                primitive.colors[v * 4 + 1] = ((color >> 8) & 0xFF) / 255f;
                primitive.colors[v * 4 + 2] = (color & 0xFF) / 255f;
                primitive.colors[v * 4 + 3] = ((color >>> 24) & 0xFF) / 255f;
            }
            // 法線は陰影に使わないが、頂点ごとに色を計算する経路を通すために、面の向きを入れておく
            for (int q = 0; q < part.quadCount; q++) {
                final float[] pos = primitive.positions;
                final int o = q * 12;
                final float ux = pos[o + 3] - pos[o], uy = pos[o + 4] - pos[o + 1], uz = pos[o + 5] - pos[o + 2];
                final float vx = pos[o + 6] - pos[o], vy = pos[o + 7] - pos[o + 1], vz = pos[o + 8] - pos[o + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0) {
                    nx /= length;
                    ny /= length;
                    nz /= length;
                }
                for (int k = 0; k < 4; k++) {
                    primitive.normals[(q * 4 + k) * 3] = nx;
                    primitive.normals[(q * 4 + k) * 3 + 1] = ny;
                    primitive.normals[(q * 4 + k) * 3 + 2] = nz;
                }
            }
            mesh.primitives[p] = primitive;
            triangles += part.quadCount * 2;
        }
        model.meshes = new GltfModel.Mesh[]{mesh};
        model.triangleCount = triangles;
        return model;
    }
}
