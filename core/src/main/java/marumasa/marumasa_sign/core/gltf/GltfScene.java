package marumasa.marumasa_sign.core.gltf;

import java.util.ArrayList;
import java.util.List;

/**
 * 読み込んだモデルを、ある時刻の姿勢で三角形の列にする。
 * アニメーション、スキニング、モーフィングと陰影の計算はすべてここで行い、
 * 結果は「テクスチャごとの頂点と色」になる。三角形は 3 つ目の頂点を重ねた四角形として出す。
 * 1 つのスレッドから使うこと。
 */
public final class GltfScene {

    // 頂点 1 つあたりの要素数: x, y, z, u, v
    public static final int STRIDE = 5;

    // 同じテクスチャで描く面のまとまり
    public static final class Group {
        public final int texture;
        // 半透明かどうか。半透明のものは後ろに並ぶ
        public final boolean blend;
        int vertexCount;

        Group(int texture, boolean blend) {
            this.texture = texture;
            this.blend = blend;
        }

        public int vertexCount() {
            return vertexCount;
        }
    }


    private final GltfModel model;
    private final Group[] groups;
    // メッシュごと・面ごとの、groups の添字
    private final int[][] groupIndex;

    private final int nodeCount;
    private final float[] translation;
    private final float[] rotation;
    private final float[] scale;
    private final float[][] weights;
    private final float[] local;
    private final float[] global;
    private float posedTime = Float.NaN;
    private float[] restBounds;

    // 材質の動的プロパティ
    private final float[] matColors;
    private final float[] matEmissive;
    private final float[] matMetallic;
    private final float[] matRoughness;
    private final float[] matTexOffset;
    private final float[] matTexScale;
    private final float[] matTexRotation;

    // ライトの動的プロパティとシーンライト
    private final float[] lightColors;
    private final float[] lightIntensities;
    private final GltfLight.LightInstance[] sceneLights;
    private final int[] sceneLightNodes;
    private final int[] sceneLightIndices;
    private final GltfLight.Context lightContext = new GltfLight.Context();

    // 計算用の領域
    private final float[] matrix = new float[16];
    private final float[] matrix2 = new float[16];
    private final float[] normal = new float[9];
    private final float[] sample = new float[4];
    private float[] jointMatrices = new float[0];
    private float[] modelJointMatrices = new float[0];
    private float[] jointNormals = new float[0];
    private float[] positions = new float[0];
    private float[] modelPositions = new float[0];
    private float[] dynamicUv = new float[0];
    private int[] front = new int[0];
    private int[] back = new int[0];
    private final float[] lit = new float[3];
    private final int[] cursor;
    // 描画する座標の法線を、モデルの座標に戻すための行列 (配置の行列の左上 3x3 の転置)
    private final float[] toModel = new float[9];

    public GltfScene(GltfModel model) {
        this.model = model;
        nodeCount = model.nodes.length;
        translation = new float[nodeCount * 3];
        rotation = new float[nodeCount * 4];
        scale = new float[nodeCount * 3];
        weights = new float[nodeCount][];
        local = new float[nodeCount * 16];
        global = new float[nodeCount * 16];

        final int matCount = model.materials.length;
        matColors = new float[matCount * 4];
        matEmissive = new float[matCount * 3];
        matMetallic = new float[matCount];
        matRoughness = new float[matCount];
        matTexOffset = new float[matCount * 2];
        matTexScale = new float[matCount * 2];
        matTexRotation = new float[matCount];
        resetMaterialProperties();

        final int lightCount = model.lights.length;
        lightColors = new float[lightCount * 3];
        lightIntensities = new float[lightCount];
        resetLightProperties();

        int placedCount = 0;
        for (int i : model.order) {
            final GltfModel.Node node = model.nodes[i];
            if (node.light >= 0 && node.light < model.lights.length) placedCount++;
        }
        sceneLights = new GltfLight.LightInstance[placedCount];
        sceneLightNodes = new int[placedCount];
        sceneLightIndices = new int[placedCount];
        int lIdx = 0;
        for (int i : model.order) {
            final GltfModel.Node node = model.nodes[i];
            if (node.light >= 0 && node.light < model.lights.length) {
                sceneLights[lIdx] = new GltfLight.LightInstance();
                sceneLightNodes[lIdx] = i;
                sceneLightIndices[lIdx] = node.light;
                lIdx++;
            }
        }
        lightContext.lights = sceneLights;
        lightContext.lightCount = sceneLights.length;
        lightContext.sphericalHarmonics = model.sphericalHarmonics;

        // 不透明なものを先に、半透明なものを後に並べる
        final List<Group> list = new ArrayList<Group>();
        groupIndex = new int[model.meshes.length][];
        for (int pass = 0; pass < 2; pass++) {
            for (int m = 0; m < model.meshes.length; m++) {
                final GltfModel.Primitive[] primitives = model.meshes[m].primitives;
                if (groupIndex[m] == null) groupIndex[m] = new int[primitives.length];
                for (int p = 0; p < primitives.length; p++) {
                    final GltfModel.Material material = model.materials[primitives[p].material];
                    final boolean blend = material.alphaMode == GltfModel.ALPHA_BLEND;
                    if (blend != (pass == 1)) continue;
                    int index = -1;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).texture == material.texture && list.get(i).blend == blend) index = i;
                    }
                    if (index < 0) {
                        index = list.size();
                        list.add(new Group(material.texture, blend));
                    }
                    groupIndex[m][p] = index;
                }
            }
        }
        groups = list.toArray(new Group[0]);
        cursor = new int[groups.length];

        for (int node : model.order) {
            final int mesh = model.nodes[node].mesh;
            if (mesh < 0) continue;
            final GltfModel.Primitive[] primitives = model.meshes[mesh].primitives;
            int targets = 0;
            for (int p = 0; p < primitives.length; p++) {
                final int quads = primitives[p].indices.length / (primitives[p].quads ? 4 : 3);
                final boolean doubleSided = model.materials[primitives[p].material].doubleSided;
                groups[groupIndex[mesh][p]].vertexCount += quads * 4 * (doubleSided ? 2 : 1);
                targets = Math.max(targets, primitives[p].morphPositions.length);
            }
            if (targets > 0) weights[node] = new float[targets];
        }
    }

    private void resetMaterialProperties() {
        for (int i = 0; i < model.materials.length; i++) {
            final GltfModel.Material m = model.materials[i];
            System.arraycopy(m.color, 0, matColors, i * 4, 4);
            System.arraycopy(m.emissive, 0, matEmissive, i * 3, 3);
            matMetallic[i] = m.metallic;
            matRoughness[i] = m.roughness;
            matTexOffset[i * 2] = m.texOffsetX;
            matTexOffset[i * 2 + 1] = m.texOffsetY;
            matTexScale[i * 2] = m.texScaleX;
            matTexScale[i * 2 + 1] = m.texScaleY;
            matTexRotation[i] = m.texRotation;
        }
    }

    private void resetLightProperties() {
        for (int i = 0; i < model.lights.length; i++) {
            final GltfModel.Light l = model.lights[i];
            System.arraycopy(l.color, 0, lightColors, i * 3, 3);
            lightIntensities[i] = l.intensity;
        }
    }

    public GltfModel model() {
        return model;
    }

    public Group[] groups() {
        return groups;
    }

    public boolean isAnimated() {
        return model.animations.length > 0;
    }

    // ---- 姿勢 ----

    /**
     * 指定した時刻 (秒) の姿勢にする。アニメーションは繰り返す。
     */
    public void pose(float time) {
        if (time == posedTime) return;
        posedTime = time;

        for (int i = 0; i < nodeCount; i++) {
            final GltfModel.Node node = model.nodes[i];
            System.arraycopy(node.translation, 0, translation, i * 3, 3);
            System.arraycopy(node.rotation, 0, rotation, i * 4, 4);
            System.arraycopy(node.scale, 0, scale, i * 3, 3);
            final float[] target = weights[i];
            if (target != null) {
                final float[] source = node.weights != null ? node.weights : model.meshes[node.mesh].weights;
                for (int k = 0; k < target.length; k++) {
                    target[k] = source != null && k < source.length ? source[k] : 0f;
                }
            }
        }
        resetMaterialProperties();
        resetLightProperties();

        if (model.animations.length > 0) {
            final GltfModel.Animation animation = model.animations[0];
            final float t = animation.duration > 0 ? time % animation.duration : 0f;
            for (GltfModel.Channel channel : animation.channels) {
                apply(channel, t);
            }
        }

        final float[] t = new float[3], r = new float[4], s = new float[3];
        for (int i : model.order) {
            final GltfModel.Node node = model.nodes[i];
            if (node.matrix != null) {
                System.arraycopy(node.matrix, 0, matrix, 0, 16);
            } else {
                System.arraycopy(translation, i * 3, t, 0, 3);
                System.arraycopy(rotation, i * 4, r, 0, 4);
                System.arraycopy(scale, i * 3, s, 0, 3);
                Mat4.compose(t, r, s, matrix);
            }
            System.arraycopy(matrix, 0, local, i * 16, 16);
            if (node.parent < 0) {
                System.arraycopy(matrix, 0, global, i * 16, 16);
            } else {
                System.arraycopy(global, node.parent * 16, matrix2, 0, 16);
                final float[] out = new float[16];
                Mat4.multiply(matrix2, matrix, out);
                System.arraycopy(out, 0, global, i * 16, 16);
            }
        }

        // 配置されたライトの姿勢と動的パラメータを更新
        for (int k = 0; k < sceneLights.length; k++) {
            final int nodeIdx = sceneLightNodes[k];
            final int lightIdx = sceneLightIndices[k];
            final GltfModel.Light light = model.lights[lightIdx];
            final GltfLight.LightInstance inst = sceneLights[k];
            inst.type = light.type;
            inst.r = lightColors[lightIdx * 3];
            inst.g = lightColors[lightIdx * 3 + 1];
            inst.b = lightColors[lightIdx * 3 + 2];
            inst.intensity = lightIntensities[lightIdx];
            inst.range = light.range;
            inst.innerCos = (float) Math.cos(light.innerConeAngle);
            inst.outerCos = (float) Math.cos(light.outerConeAngle);
            final int o = nodeIdx * 16;
            inst.px = global[o + 12];
            inst.py = global[o + 13];
            inst.pz = global[o + 14];
            float dx = -global[o + 8], dy = -global[o + 9], dz = -global[o + 10];
            final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0) {
                dx /= len; dy /= len; dz /= len;
            }
            inst.dx = dx;
            inst.dy = dy;
            inst.dz = dz;
        }
    }

    private void apply(GltfModel.Channel channel, float time) {
        final float[] times = channel.times;
        final float[] values = channel.values;
        final int components = channel.components;
        final int keys = times.length;
        final boolean cubic = channel.interpolation == GltfModel.INTERPOLATION_CUBIC;
        // 3 次スプラインは、キーごとに「入りの接線、値、出の接線」の順で並んでいる
        final int keySize = cubic ? components * 3 : components;
        final int valueOffset = cubic ? components : 0;

        final float[] out;
        final int outOffset;
        final int count;
        final boolean isNodeRotation;

        switch (channel.targetType) {
            case GltfModel.TARGET_NODE:
                if (channel.path == GltfModel.PATH_WEIGHTS) {
                    out = weights[channel.node];
                    outOffset = 0;
                    if (out == null) return;
                    count = Math.min(components, out.length);
                    isNodeRotation = false;
                } else if (channel.path == GltfModel.PATH_ROTATION) {
                    out = rotation;
                    outOffset = channel.node * 4;
                    count = components;
                    isNodeRotation = true;
                } else {
                    out = channel.path == GltfModel.PATH_TRANSLATION ? translation : scale;
                    outOffset = channel.node * 3;
                    count = components;
                    isNodeRotation = false;
                }
                break;
            case GltfModel.TARGET_MATERIAL_COLOR:
                out = matColors;
                outOffset = channel.targetIndex * 4;
                count = Math.min(components, 4);
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_EMISSIVE:
                out = matEmissive;
                outOffset = channel.targetIndex * 3;
                count = Math.min(components, 3);
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_METALLIC:
                out = matMetallic;
                outOffset = channel.targetIndex;
                count = 1;
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_ROUGHNESS:
                out = matRoughness;
                outOffset = channel.targetIndex;
                count = 1;
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_TEX_OFFSET:
                out = matTexOffset;
                outOffset = channel.targetIndex * 2;
                count = Math.min(components, 2);
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_TEX_SCALE:
                out = matTexScale;
                outOffset = channel.targetIndex * 2;
                count = Math.min(components, 2);
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_MATERIAL_TEX_ROTATION:
                out = matTexRotation;
                outOffset = channel.targetIndex;
                count = 1;
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_LIGHT_COLOR:
                out = lightColors;
                outOffset = channel.targetIndex * 3;
                count = Math.min(components, 3);
                isNodeRotation = false;
                break;
            case GltfModel.TARGET_LIGHT_INTENSITY:
                out = lightIntensities;
                outOffset = channel.targetIndex;
                count = 1;
                isNodeRotation = false;
                break;
            default:
                return;
        }

        int key;
        if (time <= times[0]) {
            key = 0;
        } else if (time >= times[keys - 1]) {
            key = keys - 1;
        } else {
            int low = 0, high = keys - 1;
            while (high - low > 1) {
                final int mid = (low + high) >>> 1;
                if (times[mid] <= time) low = mid;
                else high = mid;
            }
            key = low;
        }

        final boolean held = key == keys - 1 || time <= times[0] || channel.interpolation == GltfModel.INTERPOLATION_STEP;
        if (held) {
            System.arraycopy(values, key * keySize + valueOffset, out, outOffset, count);
            if (isNodeRotation) normalizeRotation(out, outOffset);
            return;
        }

        final float span = times[key + 1] - times[key];
        final float u = span > 0 ? (time - times[key]) / span : 0f;
        final int a = key * keySize + valueOffset;
        final int b = (key + 1) * keySize + valueOffset;
        if (cubic) {
            final float u2 = u * u, u3 = u2 * u;
            final float h00 = 2 * u3 - 3 * u2 + 1;
            final float h10 = (u3 - 2 * u2 + u) * span;
            final float h01 = -2 * u3 + 3 * u2;
            final float h11 = (u3 - u2) * span;
            for (int c = 0; c < count; c++) {
                out[outOffset + c] = h00 * values[a + c] + h10 * values[a + components + c]
                        + h01 * values[b + c] + h11 * values[b - components + c];
            }
            if (isNodeRotation) normalizeRotation(out, outOffset);
        } else if (isNodeRotation) {
            Mat4.slerp(values, a, values, b, u, sample);
            System.arraycopy(sample, 0, out, outOffset, 4);
        } else {
            for (int c = 0; c < count; c++) {
                out[outOffset + c] = values[a + c] + (values[b + c] - values[a + c]) * u;
            }
        }
    }

    private void normalizeRotation(float[] array, int offset) {
        System.arraycopy(array, offset, sample, 0, 4);
        Mat4.normalize4(sample);
        System.arraycopy(sample, 0, array, offset, 4);
    }

    // ---- 三角形の出力 ----

    /**
     * 現在の姿勢の三角形を出力する。
     *
     * @param placement モデルの座標から描画する座標への変換 (列優先の 4x4)
     * @param vertices  groups() と同じ並びの出力先。それぞれ vertexCount * STRIDE 以上の長さ
     * @param colors    頂点ごとの色 (ARGB) の出力先。それぞれ vertexCount 以上の長さ
     */
    public void emit(float[] placement, float[][] vertices, int[][] colors) {
        if (Float.isNaN(posedTime)) pose(0);
        for (int i = 0; i < cursor.length; i++) cursor[i] = 0;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                toModel[column * 3 + row] = placement[row * 4 + column];
            }
        }

        for (int nodeIndex : model.order) {
            final GltfModel.Node node = model.nodes[nodeIndex];
            if (node.mesh < 0) continue;
            final GltfModel.Mesh mesh = model.meshes[node.mesh];
            final GltfModel.Skin skin = node.skin >= 0 ? model.skins[node.skin] : null;

            // このノードの変換。陰影はモデルに固定なので、法線は配置の前の座標で求める
            System.arraycopy(global, nodeIndex * 16, matrix2, 0, 16);
            Mat4.multiply(placement, matrix2, matrix);
            boolean flip = Mat4.determinant3(matrix) < 0;
            Mat4.normalMatrix(matrix2, normal);
            if (Mat4.determinant3(matrix2) < 0) negate(normal, 0);

            if (skin != null) {
                // スキンのあるメッシュは、ノード自身の変換を使わない
                flip = Mat4.determinant3(placement) < 0;
                prepareJoints(skin, placement);
            }

            for (int p = 0; p < mesh.primitives.length; p++) {
                final GltfModel.Primitive primitive = mesh.primitives[p];
                final GltfModel.Material material = model.materials[primitive.material];
                final int group = groupIndex[node.mesh][p];
                transform(primitive, skin, weights[nodeIndex], material);
                cursor[group] = write(primitive, material, flip, vertices[group], colors[group], cursor[group]);
            }
        }
    }

    private static void negate(float[] array, int offset) {
        for (int i = 0; i < 9; i++) array[offset + i] = -array[offset + i];
    }

    private void prepareJoints(GltfModel.Skin skin, float[] placement) {
        final int count = skin.joints.length;
        if (jointMatrices.length < count * 16) {
            jointMatrices = new float[count * 16];
            modelJointMatrices = new float[count * 16];
            jointNormals = new float[count * 9];
        }
        final float[] inverseBind = new float[16];
        final float[] joint = new float[16];
        final float[] out = new float[16];
        final float[] bone = new float[16];
        final float[] boneNormal = new float[9];
        for (int j = 0; j < count; j++) {
            if (skin.joints[j] < 0) {
                System.arraycopy(Mat4.identity(), 0, bone, 0, 16);
            } else {
                System.arraycopy(global, skin.joints[j] * 16, joint, 0, 16);
                System.arraycopy(skin.inverseBind, j * 16, inverseBind, 0, 16);
                Mat4.multiply(joint, inverseBind, bone);
            }
            System.arraycopy(bone, 0, modelJointMatrices, j * 16, 16);
            Mat4.multiply(placement, bone, out);
            System.arraycopy(out, 0, jointMatrices, j * 16, 16);
            Mat4.normalMatrix(bone, boneNormal);
            // 余因子行列は裏返しの変換で向きが逆になるので、元に戻す
            if (Mat4.determinant3(bone) < 0) negate(boneNormal, 0);
            System.arraycopy(boneNormal, 0, jointNormals, j * 9, 9);
        }
    }

    // 頂点の位置と色を計算して positions, front, back に入れる
    private void transform(GltfModel.Primitive primitive, GltfModel.Skin skin, float[] morph, GltfModel.Material material) {
        final int count = primitive.vertexCount;
        if (positions.length < count * 3) {
            positions = new float[count * 3];
            modelPositions = new float[count * 3];
            front = new int[count];
            back = new int[count];
        }
        final float[] sourcePositions = primitive.positions;
        final float[] sourceNormals = primitive.normals;
        final int targets = morph == null ? 0 : Math.min(morph.length, primitive.morphPositions.length);
        final int influences = skin == null ? 0 : primitive.influences;
        final float[] m = matrix;
        final float[] n = normal;

        for (int i = 0; i < count; i++) {
            float px = sourcePositions[i * 3], py = sourcePositions[i * 3 + 1], pz = sourcePositions[i * 3 + 2];
            float nx = 0, ny = 0, nz = 0;
            if (sourceNormals != null) {
                nx = sourceNormals[i * 3];
                ny = sourceNormals[i * 3 + 1];
                nz = sourceNormals[i * 3 + 2];
            }
            for (int t = 0; t < targets; t++) {
                final float weight = morph[t];
                if (weight == 0) continue;
                final float[] deltaPositions = primitive.morphPositions[t];
                if (deltaPositions != null) {
                    px += deltaPositions[i * 3] * weight;
                    py += deltaPositions[i * 3 + 1] * weight;
                    pz += deltaPositions[i * 3 + 2] * weight;
                }
                final float[] deltaNormals = primitive.morphNormals[t];
                if (deltaNormals != null && sourceNormals != null) {
                    nx += deltaNormals[i * 3] * weight;
                    ny += deltaNormals[i * 3 + 1] * weight;
                    nz += deltaNormals[i * 3 + 2] * weight;
                }
            }

            float x, y, z, wnx, wny, wnz;
            float mx, my, mz;
            if (influences > 0) {
                x = y = z = wnx = wny = wnz = mx = my = mz = 0;
                for (int k = 0; k < influences; k++) {
                    final float weight = primitive.weights[i * influences + k];
                    if (weight == 0) continue;
                    final int joint = primitive.joints[i * influences + k];
                    if (joint < 0 || joint >= skin.joints.length) continue;
                    final int o = joint * 16;
                    final float[] jm = jointMatrices;
                    x += weight * (jm[o] * px + jm[o + 4] * py + jm[o + 8] * pz + jm[o + 12]);
                    y += weight * (jm[o + 1] * px + jm[o + 5] * py + jm[o + 9] * pz + jm[o + 13]);
                    z += weight * (jm[o + 2] * px + jm[o + 6] * py + jm[o + 10] * pz + jm[o + 14]);

                    final float[] mjm = modelJointMatrices;
                    mx += weight * (mjm[o] * px + mjm[o + 4] * py + mjm[o + 8] * pz + mjm[o + 12]);
                    my += weight * (mjm[o + 1] * px + mjm[o + 5] * py + mjm[o + 9] * pz + mjm[o + 13]);
                    mz += weight * (mjm[o + 2] * px + mjm[o + 6] * py + mjm[o + 10] * pz + mjm[o + 14]);

                    final int q = joint * 9;
                    final float[] jn = jointNormals;
                    wnx += weight * (jn[q] * nx + jn[q + 3] * ny + jn[q + 6] * nz);
                    wny += weight * (jn[q + 1] * nx + jn[q + 4] * ny + jn[q + 7] * nz);
                    wnz += weight * (jn[q + 2] * nx + jn[q + 5] * ny + jn[q + 8] * nz);
                }
            } else {
                x = m[0] * px + m[4] * py + m[8] * pz + m[12];
                y = m[1] * px + m[5] * py + m[9] * pz + m[13];
                z = m[2] * px + m[6] * py + m[10] * pz + m[14];

                final float[] m2 = matrix2;
                mx = m2[0] * px + m2[4] * py + m2[8] * pz + m2[12];
                my = m2[1] * px + m2[5] * py + m2[9] * pz + m2[13];
                mz = m2[2] * px + m2[6] * py + m2[10] * pz + m2[14];

                wnx = n[0] * nx + n[3] * ny + n[6] * nz;
                wny = n[1] * nx + n[4] * ny + n[7] * nz;
                wnz = n[2] * nx + n[5] * ny + n[8] * nz;
            }
            positions[i * 3] = x;
            positions[i * 3 + 1] = y;
            positions[i * 3 + 2] = z;
            modelPositions[i * 3] = mx;
            modelPositions[i * 3 + 1] = my;
            modelPositions[i * 3 + 2] = mz;

            if (sourceNormals != null) {
                final float length = (float) Math.sqrt(wnx * wnx + wny * wny + wnz * wnz);
                if (length > 0) {
                    wnx /= length;
                    wny /= length;
                    wnz /= length;
                }
                front[i] = color(primitive, material, i, wnx, wny, wnz, mx, my, mz);
                if (material.doubleSided) {
                    back[i] = color(primitive, material, i, -wnx, -wny, -wnz, mx, my, mz);
                }
            }
        }
    }

    // 頂点の色を計算する。n は面の外側を向いた法線 (モデルの座標)
    private int color(GltfModel.Primitive primitive, GltfModel.Material material, int vertex,
                      float nx, float ny, float nz, float px, float py, float pz) {
        final int mat = primitive.material;
        float r = matColors[mat * 4];
        float g = matColors[mat * 4 + 1];
        float b = matColors[mat * 4 + 2];
        float a = matColors[mat * 4 + 3];

        if (!material.unlit) {
            final GltfLight.Context ctx = lightContext;
            ctx.f0r = material.f0[0];
            ctx.f0g = material.f0[1];
            ctx.f0b = material.f0[2];
            ctx.clearcoat = material.clearcoat;
            ctx.clearcoatRoughness = material.clearcoatRoughness;
            ctx.cnx = nx; ctx.cny = ny; ctx.cnz = nz;
            ctx.sheenR = material.sheen[0];
            ctx.sheenG = material.sheen[1];
            ctx.sheenB = material.sheen[2];
            ctx.sheenRoughness = material.sheenRoughness;
            ctx.iridescence = material.iridescence;
            ctx.iridescenceIor = material.iridescenceIor;
            ctx.iridescenceThickness = material.iridescenceThickness;
            ctx.anisotropy = material.anisotropy;
            if (primitive.tangents != null && vertex * 4 + 3 < primitive.tangents.length) {
                float tx = primitive.tangents[vertex * 4];
                float ty = primitive.tangents[vertex * 4 + 1];
                float tz = primitive.tangents[vertex * 4 + 2];
                float sign = primitive.tangents[vertex * 4 + 3];
                float bx = (ny * tz - nz * ty) * sign;
                float by = (nz * tx - nx * tz) * sign;
                float bz = (nx * ty - ny * tx) * sign;
                ctx.tx = tx; ctx.ty = ty; ctx.tz = tz;
                ctx.bx = bx; ctx.by = by; ctx.bz = bz;
            } else {
                float ox = Math.abs(ny) > 0.99f ? 1f : 0f;
                float oy = Math.abs(ny) > 0.99f ? 0f : 1f;
                float oz = 0f;
                float tx = oy * nz - oz * ny, ty = oz * nx - ox * nz, tz = ox * ny - oy * nx;
                final float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (len > 0) {
                    tx /= len; ty /= len; tz /= len;
                }
                ctx.tx = tx; ctx.ty = ty; ctx.tz = tz;
                ctx.bx = ny * tz - nz * ty;
                ctx.by = nz * tx - nx * tz;
                ctx.bz = nx * ty - ny * tx;
            }
            ctx.diffuseTransR = material.diffuseTransmission[0];
            ctx.diffuseTransG = material.diffuseTransmission[1];
            ctx.diffuseTransB = material.diffuseTransmission[2];
            ctx.px = px; ctx.py = py; ctx.pz = pz;

            final float linR = GltfLight.toLinear(r);
            final float linG = GltfLight.toLinear(g);
            final float linB = GltfLight.toLinear(b);
            GltfLight.shade(linR, linG, linB, matMetallic[mat], matRoughness[mat],
                    nx, ny, nz, 1f, ctx, lit);
            r = GltfLight.toDisplay(lit[0]) / 255f;
            g = GltfLight.toDisplay(lit[1]) / 255f;
            b = GltfLight.toDisplay(lit[2]) / 255f;
        }

        if (primitive.colors != null) {
            r *= primitive.colors[vertex * 4];
            g *= primitive.colors[vertex * 4 + 1];
            b *= primitive.colors[vertex * 4 + 2];
            if (material.alphaMode != GltfModel.ALPHA_OPAQUE) a *= primitive.colors[vertex * 4 + 3];
        }
        r += matEmissive[mat * 3];
        g += matEmissive[mat * 3 + 1];
        b += matEmissive[mat * 3 + 2];
        return (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    // 三角形を四角形として書き出し、書き出したあとの頂点数を返す
    private int write(GltfModel.Primitive primitive, GltfModel.Material material, boolean flip,
                      float[] vertices, int[] colors, int count) {
        final int[] indices = primitive.indices;
        float[] uv = primitive.cornerUv;
        final int mat = primitive.material;
        final float offX = matTexOffset[mat * 2];
        final float offY = matTexOffset[mat * 2 + 1];
        final float scaX = matTexScale[mat * 2];
        final float scaY = matTexScale[mat * 2 + 1];
        final float rot = matTexRotation[mat];
        final boolean transformUv = primitive.rawUv != null && (offX != 0 || offY != 0 || scaX != 1 || scaY != 1 || rot != 0);
        if (transformUv) {
            if (dynamicUv.length < indices.length * 2) {
                dynamicUv = new float[indices.length * 2];
            }
            final float cos = (float) Math.cos(rot);
            final float sin = (float) Math.sin(rot);
            final float[] raw = primitive.rawUv;
            for (int i = 0; i < indices.length; i++) {
                final float u = raw[i * 2] * scaX;
                final float v = raw[i * 2 + 1] * scaY;
                dynamicUv[i * 2] = cos * u + sin * v + offX;
                dynamicUv[i * 2 + 1] = -sin * u + cos * v + offY;
            }
            uv = dynamicUv;
        }

        final boolean smooth = primitive.normals != null;
        final boolean doubleSided = material.doubleSided;

        if (primitive.quads && smooth) {
            // 四角形のまま書き出す (構造物のブロックの面など)
            for (int i = 0; i + 3 < indices.length; i += 4) {
                final int c0 = i, c1 = flip ? i + 3 : i + 1, c2 = i + 2, c3 = flip ? i + 1 : i + 3;
                count = put(vertices, colors, count, indices[c0], c0, front[indices[c0]], uv);
                count = put(vertices, colors, count, indices[c1], c1, front[indices[c1]], uv);
                count = put(vertices, colors, count, indices[c2], c2, front[indices[c2]], uv);
                count = put(vertices, colors, count, indices[c3], c3, front[indices[c3]], uv);
                if (doubleSided) {
                    count = put(vertices, colors, count, indices[c3], c3, back[indices[c3]], uv);
                    count = put(vertices, colors, count, indices[c2], c2, back[indices[c2]], uv);
                    count = put(vertices, colors, count, indices[c1], c1, back[indices[c1]], uv);
                    count = put(vertices, colors, count, indices[c0], c0, back[indices[c0]], uv);
                }
            }
            return count;
        }

        for (int i = 0; i + 2 < indices.length; i += 3) {
            // 変換で裏返っている場合は、頂点の順序を逆にして表を保つ
            final int cornerA = i, cornerB = flip ? i + 2 : i + 1, cornerC = flip ? i + 1 : i + 2;
            final int a = indices[cornerA], b = indices[cornerB], c = indices[cornerC];

            int colorA, colorB, colorC, backA = 0, backB = 0, backC = 0;
            if (smooth) {
                colorA = front[a];
                colorB = front[b];
                colorC = front[c];
                if (doubleSided) {
                    backA = back[a];
                    backB = back[b];
                    backC = back[c];
                }
            } else {
                // 法線がないモデルは、面ごとに向きを求める
                final float ax = positions[a * 3], ay = positions[a * 3 + 1], az = positions[a * 3 + 2];
                final float ux = positions[b * 3] - ax, uy = positions[b * 3 + 1] - ay, uz = positions[b * 3 + 2] - az;
                final float wx = positions[c * 3] - ax, wy = positions[c * 3 + 1] - ay, wz = positions[c * 3 + 2] - az;
                final float px = uy * wz - uz * wy, py = uz * wx - ux * wz, pz = ux * wy - uy * wx;
                // 描画する座標で求めた向きを、モデルの座標に戻す
                float nx = toModel[0] * px + toModel[3] * py + toModel[6] * pz;
                float ny = toModel[1] * px + toModel[4] * py + toModel[7] * pz;
                float nz = toModel[2] * px + toModel[5] * py + toModel[8] * pz;
                final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0) {
                    nx /= length;
                    ny /= length;
                    nz /= length;
                }
                colorA = color(primitive, material, a, nx, ny, nz, modelPositions[a * 3], modelPositions[a * 3 + 1], modelPositions[a * 3 + 2]);
                colorB = color(primitive, material, b, nx, ny, nz, modelPositions[b * 3], modelPositions[b * 3 + 1], modelPositions[b * 3 + 2]);
                colorC = color(primitive, material, c, nx, ny, nz, modelPositions[c * 3], modelPositions[c * 3 + 1], modelPositions[c * 3 + 2]);
                if (doubleSided) {
                    backA = color(primitive, material, a, -nx, -ny, -nz, modelPositions[a * 3], modelPositions[a * 3 + 1], modelPositions[a * 3 + 2]);
                    backB = color(primitive, material, b, -nx, -ny, -nz, modelPositions[b * 3], modelPositions[b * 3 + 1], modelPositions[b * 3 + 2]);
                    backC = color(primitive, material, c, -nx, -ny, -nz, modelPositions[c * 3], modelPositions[c * 3 + 1], modelPositions[c * 3 + 2]);
                }
            }

            count = put(vertices, colors, count, a, cornerA, colorA, uv);
            count = put(vertices, colors, count, b, cornerB, colorB, uv);
            count = put(vertices, colors, count, c, cornerC, colorC, uv);
            count = put(vertices, colors, count, c, cornerC, colorC, uv);
            if (doubleSided) {
                count = put(vertices, colors, count, a, cornerA, backA, uv);
                count = put(vertices, colors, count, c, cornerC, backC, uv);
                count = put(vertices, colors, count, b, cornerB, backB, uv);
                count = put(vertices, colors, count, b, cornerB, backB, uv);
            }
        }
        return count;
    }

    private int put(float[] vertices, int[] colors, int count, int vertex, int corner, int color, float[] uv) {
        final int o = count * STRIDE;
        vertices[o] = positions[vertex * 3];
        vertices[o + 1] = positions[vertex * 3 + 1];
        vertices[o + 2] = positions[vertex * 3 + 2];
        vertices[o + 3] = uv[corner * 2];
        vertices[o + 4] = uv[corner * 2 + 1];
        colors[count] = color;
        return count + 1;
    }

    /**
     * 最初の姿勢でモデルを囲む直方体 (モデルの座標)。minX, minY, minZ, maxX, maxY, maxZ の順。
     */
    public float[] restBounds() {
        if (restBounds == null) restBounds = computeRestBounds();
        return restBounds;
    }

    private float[] computeRestBounds() {
        pose(0);
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount * STRIDE];
            colors[i] = new int[groups[i].vertexCount];
        }
        emit(Mat4.identity(), vertices, colors);

        final float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < groups.length; i++) {
            for (int v = 0; v < groups[i].vertexCount; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    final float value = vertices[i][v * STRIDE + axis];
                    if (value < box[axis]) box[axis] = value;
                    if (value > box[axis + 3]) box[axis + 3] = value;
                }
            }
        }
        return box;
    }
}
