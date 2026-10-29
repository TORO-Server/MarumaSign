package marumasa.marumasa_sign.core.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 構造物のブロックを四角形の列にする。
 * 面の省き方、なめらかな明るさ (環境光の遮蔽)、面の向きによる明るさ、草や葉の色、植物の位置のずれ、液体の形は、
 * Minecraft (ModelBlockRenderer / BlockModelLighter / FluidRenderer) と同じ計算にする。
 * 明るさは、すべてのブロックに空の光が当たっている状態 (昼の屋外) として計算する。
 */
final class StructureMesher {

    static final int LAYER_SOLID = 0;
    static final int LAYER_CUTOUT = 1;
    static final int LAYER_TRANSLUCENT = 2;

    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int[] DX = StructureFixer.DX, DY = StructureFixer.DY, DZ = StructureFixer.DZ;
    private static final int[] OPPOSITE = StructureFixer.OPPOSITE;
    // 面の向きによる明るさ (CardinalLighting.DEFAULT)
    static final float[] SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

    // 書き出した四角形
    static final class Output {
        final List<Sprite> sprites = new ArrayList<Sprite>();
        private final Map<Sprite, Integer> spriteIndex = new HashMap<Sprite, Integer>();
        final FloatList[] positions = {new FloatList(), new FloatList(), new FloatList()};
        final FloatList[] uvs = {new FloatList(), new FloatList(), new FloatList()};
        final IntList[] colors = {new IntList(), new IntList(), new IntList()};
        final IntList[] spriteOf = {new IntList(), new IntList(), new IntList()};
        final boolean[] doubleSided = new boolean[3];
        int quadCount;

        int sprite(Sprite sprite) {
            Integer index = spriteIndex.get(sprite);
            if (index == null) {
                index = sprites.size();
                sprites.add(sprite);
                spriteIndex.put(sprite, index);
            }
            return index;
        }

        // 集める四角形の数の上限。超えたら、それ以上は捨てて overflow にする (読み込み側で粗くしてやり直す)
        static final int HARD_LIMIT = StructureLoader.MAX_QUADS * 4;
        boolean overflow;
        // 構造物から、これより離れた四角形は描かない (ます目の単位)。看板の中身は 1000 ブロックまで大きくできるので、それより広くとる
        static final float MARGIN = 4096;
        // 頂点を置いてよい範囲 (構造物のまわり)
        private final float[] min = {-MARGIN, -MARGIN, -MARGIN}, max = {MARGIN, MARGIN, MARGIN};

        Output(Structure structure) {
            max[0] += structure.sizeX;
            max[1] += structure.sizeY;
            max[2] += structure.sizeZ;
        }

        void add(int layer, float[] position, float[] uv, int[] color, Sprite sprite) {
            // NaN や無限大、とても遠い位置 (壊れたファイルのエンティティの位置や変換) の四角形は描かない。
            // 1 つでもあると、モデルの大きさが壊れて、構造物全体が見えなくなるため
            for (int i = 0; i < 12; i++) {
                final float value = position[i];
                if (!(value >= min[i % 3] && value <= max[i % 3])) return;
            }
            if (quadCount >= HARD_LIMIT) {
                overflow = true;
                return;
            }
            positions[layer].addAll(position, 12);
            uvs[layer].addAll(uv, 8);
            colors[layer].addAll(color, 4);
            spriteOf[layer].add(sprite(sprite));
            quadCount++;
        }
    }

    private final Structure structure;
    private final BlockData data;
    private final BlockModels models;
    private final BiomeColors biomes;
    private final SpecialModels special;
    private final Output output;

    // パレットの番号ごとの値
    private final int[] stateIndex;
    private final BlockData.Block[] blocks;
    private final BlockModels.StateModel[] stateModels;
    private final int[] fluid;
    private final int[] flags;
    private final int[] emission;
    private final int[] tintType;
    private final boolean[] specialBlock;
    // 面を描くかどうかの結果を覚えておく、決まった大きさの表 (番号の組み合わせで場所を決め、ぶつかったら上書きする)。
    // 組み合わせの数だけ覚えると、いろいろなブロックを混ぜて並べた大きな構造物で、メモリを使い切るため
    private static final int FACE_CACHE_BITS = 14;
    private final long[] faceKeys = new long[1 << FACE_CACHE_BITS];
    // 0 はまだ覚えていない、1 は描かない、2 は描く
    private final byte[] faceValues = new byte[1 << FACE_CACHE_BITS];

    // 計算用
    private final Random random = new Random();
    private final List<BlockModels.Variant> variants = new ArrayList<BlockModels.Variant>();
    private final float[] position = new float[12];
    private final float[] uv = new float[8];
    private final int[] color = new int[4];
    private final float[] faceShape = new float[12];
    private final float[] brightness = new float[4];

    StructureMesher(Structure structure, BlockData data, BlockModels models, BiomeColors biomes, SpecialModels special) {
        this.structure = structure;
        this.data = data;
        this.models = models;
        this.biomes = biomes;
        this.special = special;
        this.output = new Output(structure);
        final int count = structure.paletteSize();
        stateIndex = new int[count];
        blocks = new BlockData.Block[count];
        stateModels = new BlockModels.StateModel[count];
        fluid = new int[count];
        flags = new int[count];
        emission = new int[count];
        tintType = new int[count];
        specialBlock = new boolean[count];
        for (int id = 0; id < count; id++) {
            final BlockState state = structure.state(id);
            final BlockData.Block block = data.block(state.name);
            blocks[id] = block;
            stateIndex[id] = block == null ? -1 : data.stateIndex(block, state);
            if (block != null) {
                final int index = stateIndex[id];
                fluid[id] = data.fluid(index);
                flags[id] = data.flags(index);
                emission[id] = data.lightEmission(index);
            } else {
                // 知らないブロック (別の Mod のものなど) は、光を通す空気のようなものとして扱う
                flags[id] = BlockData.LIGHT_PERMEABLE;
            }
            tintType[id] = Tints.type(state.name);
            specialBlock[id] = special.handles(state);
            if (!state.isAir() && (flags[id] & BlockData.INVISIBLE) == 0) stateModels[id] = models.model(state);
        }
    }

    Output output() {
        return output;
    }

    // ---------- 隣のブロック ----------

    private int idAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= structure.sizeX || y >= structure.sizeY || z >= structure.sizeZ) return 0;
        return structure.id(x, y, z);
    }

    private float shadeBrightness(int id) {
        return (flags[id] & BlockData.FULL_COLLISION) != 0 ? 0.2f : 1.0f;
    }

    private boolean lightPermeable(int id) {
        return (flags[id] & BlockData.LIGHT_PERMEABLE) != 0;
    }

    private long[] faceMask(int id, int direction) {
        return stateIndex[id] < 0 ? null : data.faceMask(stateIndex[id], direction);
    }

    private static boolean isEmpty(long[] mask) {
        return mask == null || (mask[0] == 0 && mask[1] == 0 && mask[2] == 0 && mask[3] == 0);
    }

    private static boolean isFull(long[] mask) {
        return mask != null && mask[0] == -1L && mask[1] == -1L && mask[2] == -1L && mask[3] == -1L;
    }

    // Block.shouldRenderFace
    private boolean shouldRenderFace(int id, int neighbor, int direction) {
        final long key = ((long) id << 32) | ((long) neighbor << 3) | direction;
        final int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CACHE_BITS));
        if (faceValues[slot] != 0 && faceKeys[slot] == key) return faceValues[slot] == 2;
        final boolean result = computeShouldRenderFace(id, neighbor, direction);
        faceKeys[slot] = key;
        faceValues[slot] = (byte) (result ? 2 : 1);
        return result;
    }

    private boolean computeShouldRenderFace(int id, int neighbor, int direction) {
        final long[] occluder = faceMask(neighbor, OPPOSITE[direction]);
        if (isFull(occluder)) return false;
        if (skipRendering(id, neighbor, direction)) return false;
        if (isEmpty(occluder)) return true;
        final long[] shape = faceMask(id, direction);
        if (isEmpty(shape)) return true;
        for (int i = 0; i < 4; i++) {
            if ((shape[i] & ~occluder[i]) != 0) return true;
        }
        return false;
    }

    private boolean skipRendering(int id, int neighbor, int direction) {
        final BlockData.Block block = blocks[id];
        if (block == null) return false;
        final BlockState state = structure.state(id);
        final BlockState other = structure.state(neighbor);
        switch (block.skip) {
            case BlockData.SKIP_SAME:
                return state.name.equals(other.name);
            case BlockData.SKIP_BARS: {
                final boolean bothBars = isBarsTag(state) && isBarsTag(other) && other.get(StructureFixer.NAMES[OPPOSITE[direction]]) != null;
                if (!state.name.equals(other.name) && !bothBars) return false;
                if (direction == DOWN || direction == UP) return true;
                return "true".equals(state.get(StructureFixer.NAMES[direction])) && "true".equals(other.get(StructureFixer.NAMES[OPPOSITE[direction]]));
            }
            case BlockData.SKIP_LIQUID:
                return fluidType(neighbor) == fluidType(id);
            case BlockData.SKIP_ROOTS:
                return other.name.equals("minecraft:mangrove_roots") && (direction == DOWN || direction == UP);
            default:
                return false;
        }
    }

    // タグ minecraft:bars (鉄格子と銅の格子)
    private static boolean isBarsTag(BlockState state) {
        final String path = state.path();
        return path.equals("iron_bars") || path.endsWith("copper_bars");
    }

    private int fluidType(int id) {
        return fluid[id] & 3;
    }

    // ---------- 全体 ----------

    void build() {
        special.startPass();
        for (int y = 0; y < structure.sizeY; y++) {
            for (int z = 0; z < structure.sizeZ; z++) {
                for (int x = 0; x < structure.sizeX; x++) {
                    // 多すぎる面は集めない (読み込み側で粗くしてやり直す)
                    if (output.overflow) return;
                    final int index = structure.index(x, y, z);
                    final int id = structure.id(index);
                    if (id == 0) continue;
                    if (fluid[id] != 0) fluid(x, y, z, id);
                    boolean hidden = false;
                    if (specialBlock[id]) hidden = special.emit(this, structure.state(id), structure.blockEntity(index), x, y, z);
                    final BlockModels.StateModel model = stateModels[id];
                    if (model != null && !hidden) block(x, y, z, id, model);
                }
            }
        }
        special.emitEntities(this, structure);
    }

    // ---------- ブロック ----------

    private void block(int x, int y, int z, int id, BlockModels.StateModel model) {
        variants.clear();
        final int wx = x * structure.step, wy = y * structure.step, wz = z * structure.step;
        model.collect(seed(wx, wy, wz), random, variants);
        if (variants.isEmpty()) return;

        // 植物などの、位置ごとのずれ
        float ox = 0, oy = 0, oz = 0;
        final BlockData.Block block = blocks[id];
        if (block != null && block.offsetType != BlockData.OFFSET_NONE) {
            final long s = seed(wx, 0, wz);
            final float max = block.maxHorizontalOffset;
            ox = clamp(((s & 15L) / 15.0f - 0.5f) * 0.5f, -max, max);
            oz = clamp(((s >> 8 & 15L) / 15.0f - 0.5f) * 0.5f, -max, max);
            if (block.offsetType == BlockData.OFFSET_XYZ) oy = ((s >> 4 & 15L) / 15.0f - 1.0f) * block.maxVerticalOffset;
        }

        final boolean ambientOcclusion = emission[id] == 0 && variants.get(0).ambientOcclusion;
        int cullChecked = 0, cullVisible = 0;
        final BlockState state = structure.state(id);
        for (BlockModels.Variant variant : variants) {
            for (BlockModels.Quad quad : variant.quads) {
                if (quad.cull >= 0) {
                    final int mask = 1 << quad.cull;
                    if ((cullChecked & mask) == 0) {
                        cullChecked |= mask;
                        if (shouldRenderFace(id, idAt(x + DX[quad.cull], y + DY[quad.cull], z + DZ[quad.cull]), quad.cull)) cullVisible |= mask;
                    }
                    if ((cullVisible & mask) == 0) continue;
                }
                if (ambientOcclusion) ambientOcclusion(x, y, z, id, quad);
                else java.util.Arrays.fill(brightness, SHADE[quad.shadeDirection]);
                final int tint = quad.tint >= 0 ? Tints.color(tintType[id], quad.tint, state, this, x, y, z) : -1;
                for (int v = 0; v < 4; v++) {
                    position[v * 3] = x + quad.positions[v * 3] + ox;
                    position[v * 3 + 1] = y + quad.positions[v * 3 + 1] + oy;
                    position[v * 3 + 2] = z + quad.positions[v * 3 + 2] + oz;
                    color[v] = scale(tint, brightness[v]);
                }
                System.arraycopy(quad.uvs, 0, uv, 0, 8);
                output.add(layer(quad.transparency), position, uv, color, quad.sprite);
            }
        }
    }

    static int layer(int transparency) {
        return transparency == Sprite.TRANSLUCENT ? LAYER_TRANSLUCENT : transparency == Sprite.CUTOUT ? LAYER_CUTOUT : LAYER_SOLID;
    }

    // Mth.getSeed
    static long seed(int x, int y, int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }

    // 色に明るさを掛ける (ARGB)
    static int scale(int color, float factor) {
        final int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        final int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        final int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }

    // ---------- なめらかな明るさ (BlockModelLighter.prepareQuadAmbientOcclusion) ----------

    // 面ごとの、明るさを調べる 4 つの隣の方向
    private static final int[][] CORNERS = {
            {WEST, EAST, NORTH, SOUTH},
            {EAST, WEST, NORTH, SOUTH},
            {UP, DOWN, EAST, WEST},
            {WEST, EAST, DOWN, UP},
            {UP, DOWN, NORTH, SOUTH},
            {DOWN, UP, NORTH, SOUTH},
    };

    // faceShape の添字
    private static final int S_DOWN = 0, S_UP = 1, S_NORTH = 2, S_SOUTH = 3, S_WEST = 4, S_EAST = 5;
    private static final int F_DOWN = 6, F_UP = 7, F_NORTH = 8, F_SOUTH = 9, F_WEST = 10, F_EAST = 11;

    // 面ごと・頂点ごとの重み (AdjacencyInfo.vertNWeights)
    private static final int[][][] WEIGHTS = {
            {
                    {F_WEST, S_SOUTH, F_WEST, F_SOUTH, S_WEST, F_SOUTH, S_WEST, S_SOUTH},
                    {F_WEST, S_NORTH, F_WEST, F_NORTH, S_WEST, F_NORTH, S_WEST, S_NORTH},
                    {F_EAST, S_NORTH, F_EAST, F_NORTH, S_EAST, F_NORTH, S_EAST, S_NORTH},
                    {F_EAST, S_SOUTH, F_EAST, F_SOUTH, S_EAST, F_SOUTH, S_EAST, S_SOUTH},
            },
            {
                    {S_EAST, S_SOUTH, S_EAST, F_SOUTH, F_EAST, F_SOUTH, F_EAST, S_SOUTH},
                    {S_EAST, S_NORTH, S_EAST, F_NORTH, F_EAST, F_NORTH, F_EAST, S_NORTH},
                    {S_WEST, S_NORTH, S_WEST, F_NORTH, F_WEST, F_NORTH, F_WEST, S_NORTH},
                    {S_WEST, S_SOUTH, S_WEST, F_SOUTH, F_WEST, F_SOUTH, F_WEST, S_SOUTH},
            },
            {
                    {S_UP, F_WEST, S_UP, S_WEST, F_UP, S_WEST, F_UP, F_WEST},
                    {S_UP, F_EAST, S_UP, S_EAST, F_UP, S_EAST, F_UP, F_EAST},
                    {S_DOWN, F_EAST, S_DOWN, S_EAST, F_DOWN, S_EAST, F_DOWN, F_EAST},
                    {S_DOWN, F_WEST, S_DOWN, S_WEST, F_DOWN, S_WEST, F_DOWN, F_WEST},
            },
            {
                    {S_UP, F_WEST, F_UP, F_WEST, F_UP, S_WEST, S_UP, S_WEST},
                    {S_DOWN, F_WEST, F_DOWN, F_WEST, F_DOWN, S_WEST, S_DOWN, S_WEST},
                    {S_DOWN, F_EAST, F_DOWN, F_EAST, F_DOWN, S_EAST, S_DOWN, S_EAST},
                    {S_UP, F_EAST, F_UP, F_EAST, F_UP, S_EAST, S_UP, S_EAST},
            },
            {
                    {S_UP, S_SOUTH, S_UP, F_SOUTH, F_UP, F_SOUTH, F_UP, S_SOUTH},
                    {S_UP, S_NORTH, S_UP, F_NORTH, F_UP, F_NORTH, F_UP, S_NORTH},
                    {S_DOWN, S_NORTH, S_DOWN, F_NORTH, F_DOWN, F_NORTH, F_DOWN, S_NORTH},
                    {S_DOWN, S_SOUTH, S_DOWN, F_SOUTH, F_DOWN, F_SOUTH, F_DOWN, S_SOUTH},
            },
            {
                    {F_DOWN, S_SOUTH, F_DOWN, F_SOUTH, S_DOWN, F_SOUTH, S_DOWN, S_SOUTH},
                    {F_DOWN, S_NORTH, F_DOWN, F_NORTH, S_DOWN, F_NORTH, S_DOWN, S_NORTH},
                    {F_UP, S_NORTH, F_UP, F_NORTH, S_UP, F_NORTH, S_UP, S_NORTH},
                    {F_UP, S_SOUTH, F_UP, F_SOUTH, S_UP, F_SOUTH, S_UP, S_SOUTH},
            },
    };

    // AmbientVertexRemap
    private static final int[][] REMAP = {{0, 1, 2, 3}, {2, 3, 0, 1}, {3, 0, 1, 2}, {0, 1, 2, 3}, {3, 0, 1, 2}, {1, 2, 3, 0}};

    private void ambientOcclusion(int x, int y, int z, int id, BlockModels.Quad quad) {
        final float[] p = quad.positions;
        float minX = 32, minY = 32, minZ = 32, maxX = -32, maxY = -32, maxZ = -32;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, p[i * 3]);
            minY = Math.min(minY, p[i * 3 + 1]);
            minZ = Math.min(minZ, p[i * 3 + 2]);
            maxX = Math.max(maxX, p[i * 3]);
            maxY = Math.max(maxY, p[i * 3 + 1]);
            maxZ = Math.max(maxZ, p[i * 3 + 2]);
        }
        faceShape[S_WEST] = minX;
        faceShape[S_EAST] = maxX;
        faceShape[S_DOWN] = minY;
        faceShape[S_UP] = maxY;
        faceShape[S_NORTH] = minZ;
        faceShape[S_SOUTH] = maxZ;
        faceShape[F_WEST] = 1 - minX;
        faceShape[F_EAST] = 1 - maxX;
        faceShape[F_DOWN] = 1 - minY;
        faceShape[F_UP] = 1 - maxY;
        faceShape[F_NORTH] = 1 - minZ;
        faceShape[F_SOUTH] = 1 - maxZ;
        final int direction = quad.direction;
        final boolean partial;
        final boolean cubic;
        final boolean fullBlock = (flags[id] & BlockData.FULL_COLLISION) != 0;
        switch (direction) {
            case DOWN:
            case UP:
                partial = minX >= 1.0E-4f || minZ >= 1.0E-4f || maxX <= 0.9999f || maxZ <= 0.9999f;
                cubic = minY == maxY && ((direction == DOWN ? minY < 1.0E-4f : maxY > 0.9999f) || fullBlock);
                break;
            case NORTH:
            case SOUTH:
                partial = minX >= 1.0E-4f || minY >= 1.0E-4f || maxX <= 0.9999f || maxY <= 0.9999f;
                cubic = minZ == maxZ && ((direction == NORTH ? minZ < 1.0E-4f : maxZ > 0.9999f) || fullBlock);
                break;
            default:
                partial = minY >= 1.0E-4f || minZ >= 1.0E-4f || maxY <= 0.9999f || maxZ <= 0.9999f;
                cubic = minX == maxX && ((direction == WEST ? minX < 1.0E-4f : maxX > 0.9999f) || fullBlock);
                break;
        }

        final int bx = cubic ? x + DX[direction] : x, by = cubic ? y + DY[direction] : y, bz = cubic ? z + DZ[direction] : z;
        final int[] corners = CORNERS[direction];
        final float[] shade = new float[4];
        final boolean[] permeable = new boolean[4];
        for (int i = 0; i < 4; i++) {
            final int c = corners[i];
            shade[i] = shadeBrightness(idAt(bx + DX[c], by + DY[c], bz + DZ[c]));
            permeable[i] = lightPermeable(idAt(bx + DX[c] + DX[direction], by + DY[c] + DY[direction], bz + DZ[c] + DZ[direction]));
        }
        final float corner02 = !permeable[2] && !permeable[0] ? shade[0] : cornerShade(bx, by, bz, corners[0], corners[2]);
        final float corner03 = !permeable[3] && !permeable[0] ? shade[0] : cornerShade(bx, by, bz, corners[0], corners[3]);
        final float corner12 = !permeable[2] && !permeable[1] ? shade[0] : cornerShade(bx, by, bz, corners[1], corners[2]);
        final float corner13 = !permeable[3] && !permeable[1] ? shade[0] : cornerShade(bx, by, bz, corners[1], corners[3]);
        final float center = cubic ? shadeBrightness(idAt(bx, by, bz)) : shadeBrightness(id);

        final float level1 = (shade[3] + shade[0] + corner03 + center) * 0.25f;
        final float level2 = (shade[2] + shade[0] + corner02 + center) * 0.25f;
        final float level3 = (shade[2] + shade[1] + corner12 + center) * 0.25f;
        final float level4 = (shade[3] + shade[1] + corner13 + center) * 0.25f;
        final int[] remap = REMAP[direction];
        if (partial) {
            final int[][] weights = WEIGHTS[direction];
            for (int v = 0; v < 4; v++) {
                final int[] w = weights[v];
                final float w01 = faceShape[w[0]] * faceShape[w[1]];
                final float w23 = faceShape[w[2]] * faceShape[w[3]];
                final float w45 = faceShape[w[4]] * faceShape[w[5]];
                final float w67 = faceShape[w[6]] * faceShape[w[7]];
                brightness[remap[v]] = clamp(level1 * w01 + level2 * w23 + level3 * w45 + level4 * w67, 0, 1);
            }
        } else {
            brightness[remap[0]] = level1;
            brightness[remap[1]] = level2;
            brightness[remap[2]] = level3;
            brightness[remap[3]] = level4;
        }
        final float directional = SHADE[quad.shadeDirection];
        for (int v = 0; v < 4; v++) {
            // Minecraft は明るさを 8 ビットの灰色にしてから掛ける
            brightness[v] = Math.round(brightness[v] * 255f) / 255f * directional;
        }
    }

    private float cornerShade(int bx, int by, int bz, int a, int b) {
        return shadeBrightness(idAt(bx + DX[a] + DX[b], by + DY[a] + DY[b], bz + DZ[a] + DZ[b]));
    }

    // ---------- 草や葉の色 ----------

    BiomeColors.Colors biomeAt(int x, int y, int z) {
        if (!structure.hasBiomes()) return biomes.colors(null);
        return biomes.colors(structure.biome(structure.index(x, y, z)));
    }

    // まわり 5x5 (半径 2) のバイオームの色の平均 (Minecraft の初期設定と同じ)
    int blendedColor(int x, int y, int z, int kind) {
        if (!structure.hasBiomes()) return pick(biomes.colors(null), kind);
        int r = 0, g = 0, b = 0, count = 0;
        final int radius = Math.max(0, 2 / structure.step);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                final int nx = Math.max(0, Math.min(structure.sizeX - 1, x + dx));
                final int nz = Math.max(0, Math.min(structure.sizeZ - 1, z + dz));
                final int c = pick(biomeAt(nx, y, nz), kind);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                count++;
            }
        }
        return 0xFF000000 | (r / count) << 16 | (g / count) << 8 | (b / count);
    }

    static final int GRASS = 0, FOLIAGE = 1, DRY_FOLIAGE = 2, WATER = 3;

    private static int pick(BiomeColors.Colors colors, int kind) {
        switch (kind) {
            case FOLIAGE:
                return colors.foliage;
            case DRY_FOLIAGE:
                return colors.dryFoliage;
            case WATER:
                return colors.water;
            default:
                return colors.grass;
        }
    }

    // ---------- 液体 (FluidRenderer) ----------

    private static final float MAX_FLUID_HEIGHT = 0.8888889f;

    private int fluidAmount(int id) {
        return (fluid[id] >> 2) & 15;
    }

    private boolean falling(int id) {
        return (fluid[id] & (1 << 7)) != 0;
    }

    private float ownHeight(int id) {
        return fluidType(id) == 0 ? 0 : fluidAmount(id) / 9.0f;
    }

    private float height(int type, int x, int y, int z) {
        final int id = idAt(x, y, z);
        if (fluidType(id) == type) return fluidType(idAt(x, y + 1, z)) == type ? 1.0f : ownHeight(id);
        return (flags[id] & BlockData.SOLID) == 0 ? 0.0f : -1.0f;
    }

    // 液体の面が、その方向のブロックで隠れるか (FluidRenderer.isFaceOccludedByState)
    private boolean occludedByState(int direction, float height, int id) {
        final long[] occluder = faceMask(id, OPPOSITE[direction]);
        if (isEmpty(occluder)) return false;
        if (isFull(occluder)) return direction != UP || height == 1.0f;
        if (direction == UP) return false;
        if (direction == DOWN) return isFull(occluder);
        // 横の面: 液体の高さまでのます目がすべて覆われていれば隠れる
        final int rows = Math.min(16, (int) Math.ceil(height * 16 - 1e-4));
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < 16; column++) {
                final int bit = row * 16 + column;
                if ((occluder[bit >> 6] & (1L << (bit & 63))) == 0) return false;
            }
        }
        return true;
    }

    private void fluid(int x, int y, int z, int id) {
        final int type = fluidType(id);
        if (type == 0) return;
        final int down = idAt(x, y - 1, z), up = idAt(x, y + 1, z);
        final int north = idAt(x, y, z - 1), south = idAt(x, y, z + 1), west = idAt(x - 1, y, z), east = idAt(x + 1, y, z);
        final boolean renderUp = fluidType(up) != type;
        final boolean renderDown = fluidType(down) != type && !occludedByState(UP, 1.0f, id) && !occludedByState(DOWN, MAX_FLUID_HEIGHT, down);
        final boolean renderNorth = fluidType(north) != type && !occludedByState(SOUTH, 1.0f, id);
        final boolean renderSouth = fluidType(south) != type && !occludedByState(NORTH, 1.0f, id);
        final boolean renderWest = fluidType(west) != type && !occludedByState(EAST, 1.0f, id);
        final boolean renderEast = fluidType(east) != type && !occludedByState(WEST, 1.0f, id);
        if (!renderUp && !renderDown && !renderNorth && !renderSouth && !renderWest && !renderEast) return;

        final boolean water = type == 1;
        final GameAssets assets = models.assets();
        final Sprite still = Sprite.get(assets, water ? "minecraft:block/water_still" : "minecraft:block/lava_still");
        final Sprite flowing = Sprite.get(assets, water ? "minecraft:block/water_flow" : "minecraft:block/lava_flow");
        final Sprite overlay = water ? Sprite.get(assets, "minecraft:block/water_overlay") : null;
        final int tint = water ? blendedColor(x, y, z, WATER) : 0xFFFFFFFF;
        final int layer = water ? LAYER_TRANSLUCENT : LAYER_SOLID;

        final float self = height(type, x, y, z);
        float ne, nw, se, sw;
        if (self >= 1.0f) {
            ne = nw = se = sw = 1.0f;
        } else {
            final float hn = height(type, x, y, z - 1), hs = height(type, x, y, z + 1);
            final float he = height(type, x + 1, y, z), hw = height(type, x - 1, y, z);
            ne = averageHeight(type, self, hn, he, x + 1, y, z - 1);
            nw = averageHeight(type, self, hn, hw, x - 1, y, z - 1);
            se = averageHeight(type, self, hs, he, x + 1, y, z + 1);
            sw = averageHeight(type, self, hs, hw, x - 1, y, z + 1);
        }
        final float bottom = renderDown ? 0.001f : 0.0f;

        if (renderUp && !occludedByState(UP, Math.min(Math.min(nw, sw), Math.min(se, ne)), up)) {
            nw -= 0.001f;
            sw -= 0.001f;
            se -= 0.001f;
            ne -= 0.001f;
            final double[] flow = flow(type, x, y, z, id);
            final float[] u = new float[4], v = new float[4];
            Sprite sprite;
            if (flow[0] == 0 && flow[1] == 0) {
                sprite = still;
                u[0] = 0; v[0] = 0;
                u[1] = 0; v[1] = 1;
                u[2] = 1; v[2] = 1;
                u[3] = 1; v[3] = 0;
            } else {
                sprite = flowing;
                final float angle = (float) Math.atan2(flow[1], flow[0]) - 1.5707964f;
                final float s = (float) Math.sin(angle) * 0.25f, c = (float) Math.cos(angle) * 0.25f;
                u[0] = 0.5f + (-c - s); v[0] = 0.5f + (-c + s);
                u[1] = 0.5f + (-c + s); v[1] = 0.5f + (c + s);
                u[2] = 0.5f + (c + s); v[2] = 0.5f + (c - s);
                u[3] = 0.5f + (c - s); v[3] = 0.5f + (-c - s);
            }
            final int topColor = scale(tint, SHADE[UP]);
            fluidFace(layer, sprite, topColor, backwardUpFace(type, x, y + 1, z),
                    x, y + nw, z, u[0], v[0],
                    x, y + sw, z + 1, u[1], v[1],
                    x + 1, y + se, z + 1, u[2], v[2],
                    x + 1, y + ne, z, u[3], v[3]);
        }

        if (renderDown) {
            final int belowColor = scale(tint, SHADE[DOWN]);
            fluidFace(layer, still, belowColor, false,
                    x, y + bottom, z, 0, 0,
                    x + 1, y + bottom, z, 1, 0,
                    x + 1, y + bottom, z + 1, 1, 1,
                    x, y + bottom, z + 1, 0, 1);
        }

        for (int face = NORTH; face <= EAST; face++) {
            final float h0, h1, x0, z0, x1, z1;
            final boolean render;
            final int faceId;
            switch (face) {
                case NORTH:
                    h0 = nw; h1 = ne; x0 = x; x1 = x + 1; z0 = z + 0.001f; z1 = z + 0.001f;
                    render = renderNorth; faceId = north;
                    break;
                case SOUTH:
                    h0 = se; h1 = sw; x0 = x + 1; x1 = x; z0 = z + 1 - 0.001f; z1 = z + 1 - 0.001f;
                    render = renderSouth; faceId = south;
                    break;
                case WEST:
                    h0 = sw; h1 = nw; x0 = x + 0.001f; x1 = x + 0.001f; z0 = z + 1; z1 = z;
                    render = renderWest; faceId = west;
                    break;
                default:
                    h0 = ne; h1 = se; x0 = x + 1 - 0.001f; x1 = x + 1 - 0.001f; z0 = z; z1 = z + 1;
                    render = renderEast; faceId = east;
                    break;
            }
            if (!render || occludedByState(face, Math.max(h0, h1), faceId)) continue;
            Sprite sprite = flowing;
            boolean isOverlay = false;
            if (overlay != null) {
                final BlockData.Block neighborBlock = blocks[faceId];
                if ((neighborBlock != null && neighborBlock.skip == BlockData.SKIP_SAME && !structure.state(faceId).path().equals("powder_snow"))
                        || structure.state(faceId).path().endsWith("_leaves")) {
                    sprite = overlay;
                    isOverlay = true;
                }
            }
            final float side = face == NORTH || face == SOUTH ? SHADE[NORTH] : SHADE[WEST];
            final int faceColor = scale(tint, SHADE[UP] * side);
            fluidFace(layer, sprite, faceColor, !isOverlay,
                    x0, y + h0, z0, 0, (1 - h0) * 0.5f,
                    x1, y + h1, z1, 0.5f, (1 - h1) * 0.5f,
                    x1, y + bottom, z1, 0.5f, 0.5f,
                    x0, y + bottom, z0, 0, 0.5f);
        }
    }

    private float averageHeight(int type, float self, float height2, float height1, int cx, int cy, int cz) {
        if (height1 >= 1.0f || height2 >= 1.0f) return 1.0f;
        final float[] weighted = new float[2];
        if (height1 > 0.0f || height2 > 0.0f) {
            final float corner = height(type, cx, cy, cz);
            if (corner >= 1.0f) return 1.0f;
            addWeighted(weighted, corner);
        }
        addWeighted(weighted, self);
        addWeighted(weighted, height1);
        addWeighted(weighted, height2);
        return weighted[0] / weighted[1];
    }

    private static void addWeighted(float[] weighted, float height) {
        if (height >= 0.8f) {
            weighted[0] += height * 10.0f;
            weighted[1] += 10.0f;
        } else if (height >= 0.0f) {
            weighted[0] += height;
            weighted[1]++;
        }
    }

    // FlowingFluid.getFlow (x, z)
    private double[] flow(int type, int x, int y, int z, int id) {
        double flowX = 0, flowZ = 0;
        final float own = ownHeight(id);
        for (int face = NORTH; face <= EAST; face++) {
            final int nx = x + DX[face], nz = z + DZ[face];
            final int neighbor = idAt(nx, y, nz);
            if (fluidType(neighbor) != 0 && fluidType(neighbor) != type) continue;
            float neighborHeight = ownHeight(neighbor);
            float distance = 0;
            if (neighborHeight == 0.0f) {
                final int below = idAt(nx, y - 1, nz);
                if (fluidType(below) == 0 || fluidType(below) == type) {
                    neighborHeight = ownHeight(below);
                    if (neighborHeight > 0.0f) distance = own - (neighborHeight - MAX_FLUID_HEIGHT);
                }
            } else if (neighborHeight > 0.0f) {
                distance = own - neighborHeight;
            }
            if (distance != 0) {
                flowX += DX[face] * distance;
                flowZ += DZ[face] * distance;
            }
        }
        if (falling(id)) {
            for (int face = NORTH; face <= EAST; face++) {
                final int nx = x + DX[face], nz = z + DZ[face];
                if (solidFace(type, nx, y, nz, face) || solidFace(type, nx, y + 1, nz, face)) {
                    final double length = Math.sqrt(flowX * flowX + flowZ * flowZ);
                    if (length >= 1.0E-5) {
                        flowX /= length;
                        flowZ /= length;
                    } else {
                        flowX = 0;
                        flowZ = 0;
                    }
                    break;
                }
            }
        }
        final double length = Math.sqrt(flowX * flowX + flowZ * flowZ);
        if (length < 1.0E-5) return new double[]{0, 0};
        return new double[]{flowX / length, flowZ / length};
    }

    private boolean solidFace(int type, int x, int y, int z, int direction) {
        final int id = idAt(x, y, z);
        if (fluidType(id) == type) return false;
        final String path = structure.state(id).path();
        if (path.equals("ice") || path.equals("frosted_ice")) return false;
        return stateIndex[id] >= 0 && (data.flags(stateIndex[id]) & (1 << (BlockData.STURDY_SHIFT + direction))) != 0;
    }

    // FluidState.shouldRenderBackwardUpFace: 上の 3x3 に、同じ液体でも不透明なブロックでもないものがあれば、上面の裏も描く
    private boolean backwardUpFace(int type, int x, int y, int z) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                final int id = idAt(x + ox, y, z + oz);
                if (fluidType(id) != type && (flags[id] & BlockData.SOLID_RENDER) == 0) return true;
            }
        }
        return false;
    }

    private void fluidFace(int layer, Sprite sprite, int faceColor, boolean backFace,
                           float x0, float y0, float z0, float u0, float v0,
                           float x1, float y1, float z1, float u1, float v1,
                           float x2, float y2, float z2, float u2, float v2,
                           float x3, float y3, float z3, float u3, float v3) {
        final float[] p = {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3};
        final float[] t = {u0, v0, u1, v1, u2, v2, u3, v3};
        final int[] c = {faceColor, faceColor, faceColor, faceColor};
        output.add(layer, p, t, c, sprite);
        if (backFace) {
            final float[] bp = {x0, y0, z0, x3, y3, z3, x2, y2, z2, x1, y1, z1};
            final float[] bt = {u0, v0, u3, v3, u2, v2, u1, v1};
            output.add(layer, bp, bt, c, sprite);
        }
    }

    // ---------- ほかのクラスから使う ----------

    Structure structure() {
        return structure;
    }

    BlockModels models() {
        return models;
    }

    // エンティティの明るさに使う 2 つの光の向き (Lighting.DIFFUSE_LIGHT_0 / 1)
    private static final float[] LIGHT0 = normalize(0.2f, 1.0f, -0.7f);
    private static final float[] LIGHT1 = normalize(-0.2f, 1.0f, 0.7f);

    private static float[] normalize(float x, float y, float z) {
        final float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
    }

    /**
     * 特別な形のブロック (チェストなど) の四角形を書き出す。
     * 明るさは、ゲームがエンティティを描くときと同じ計算 (2 つの光の向きと法線) にする。
     *
     * @param shade 明るさを付けるかどうか
     */
    void addQuad(int layer, float[] positions, float[] uvs, int quadColor, Sprite sprite, boolean shade) {
        float brightnessValue = 1.0f;
        if (shade) {
            final float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
            final float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
            final float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) {
                final float x = nx / length, y = ny / length, z = nz / length;
                final float d0 = Math.max(0, x * LIGHT0[0] + y * LIGHT0[1] + z * LIGHT0[2]);
                final float d1 = Math.max(0, x * LIGHT1[0] + y * LIGHT1[1] + z * LIGHT1[2]);
                brightnessValue = Math.min(1.0f, (d0 + d1) * 0.6f + 0.4f);
            }
        }
        final int c = scale(quadColor, brightnessValue);
        output.add(layer, positions, uvs, new int[]{c, c, c, c}, sprite);
    }

    // 頂点ごとの色を決めてある四角形を書き出す (看板の 3D モデルなど)
    void addColoredQuad(int layer, float[] positions, float[] uvs, int[] colors, Sprite sprite) {
        output.add(layer, positions, uvs, colors, sprite);
    }

    // ---------- 増える配列 ----------

    static final class FloatList {
        float[] values = new float[1024];
        int size;

        void addAll(float[] source, int count) {
            if (size + count > values.length) values = java.util.Arrays.copyOf(values, Math.max(values.length * 2, size + count));
            System.arraycopy(source, 0, values, size, count);
            size += count;
        }
    }

    static final class IntList {
        int[] values = new int[256];
        int size;

        void add(int value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        void addAll(int[] source, int count) {
            if (size + count > values.length) values = java.util.Arrays.copyOf(values, Math.max(values.length * 2, size + count));
            System.arraycopy(source, 0, values, size, count);
            size += count;
        }
    }
}
