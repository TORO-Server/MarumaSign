package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.gltf.GltfModel;
import marumasa.marumasa_sign.core.gltf.GltfScene;

import java.io.IOException;

/**
 * 構造物のファイル (.schem .litematic .nbt .mcstructure .schematic) を読み込み、3D モデルにする。
 */
public final class StructureLoader {

    // ファイルの大きさの上限
    public static final int MAX_SIZE = 64 * 1024 * 1024;
    // 描く四角形の数の上限。超える構造物は、2 x 2 x 2 ブロックを 1 つにまとめて粗くする
    static final int MAX_QUADS = 150000;

    private static volatile BlockModels models;
    private static volatile BiomeColors biomes;

    private StructureLoader() {
    }

    public static boolean isStructure(byte[] head, int length) {
        return StructureReader.looksLikeStructure(head, length);
    }

    // 読み込み結果
    public static final class Result {
        public final GltfScene scene;
        public final Structure structure;
        public final int quads;

        Result(GltfScene scene, Structure structure, int quads) {
            this.scene = scene;
            this.structure = structure;
            this.quads = quads;
        }
    }

    public static GltfScene load(byte[] content) throws IOException {
        return loadDetailed(content).scene;
    }

    public static Result loadDetailed(byte[] content) throws IOException {
        if (content.length > MAX_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SIZE + " bytes.");
        Structure structure = StructureReader.read(content);
        StructureFixer.fix(structure);
        final GameAssets assets = GameAssets.get();
        final BlockModels blockModels = models(assets);
        final BiomeColors biomeColors = biomes(assets);
        while (true) {
            final StructureMesher mesher = new StructureMesher(structure, BlockData.get(), blockModels, biomeColors, new SpecialModels(blockModels));
            mesher.build();
            final StructureMesher.Output output = mesher.output();
            if (output.quadCount > MAX_QUADS && structure.sizeX * structure.sizeY * structure.sizeZ > 1) {
                MarumaSign.LOGGER.info("Structure has " + output.quadCount + " faces; drawing it with fewer blocks");
                structure = structure.halve();
                continue;
            }
            if (output.quadCount == 0) throw new IOException("Structure has no visible blocks");
            final Atlas atlas = Atlas.of(output.sprites);
            final GltfModel model = atlas.build(output);
            final GltfScene scene = new GltfScene(model);
            // 大きさの計算には時間がかかることがあるので、描画するスレッドに渡す前にここで済ませておく
            scene.restBounds();
            return new Result(scene, structure, output.quadCount);
        }
    }

    private static BlockModels models(GameAssets assets) {
        BlockModels result = models;
        if (result == null) {
            synchronized (StructureLoader.class) {
                if (models == null) models = new BlockModels(assets);
                result = models;
            }
        }
        return result;
    }

    // 読み込み済みのバイオームの色 (まだなければ null)
    static BiomeColors biomeColors() {
        return biomes;
    }

    private static BiomeColors biomes(GameAssets assets) throws IOException {
        BiomeColors result = biomes;
        if (result == null) {
            synchronized (StructureLoader.class) {
                if (biomes == null) biomes = new BiomeColors(assets);
                result = biomes;
            }
        }
        return result;
    }
}
