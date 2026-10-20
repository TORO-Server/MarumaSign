package marumasa.marumasa_sign.core.structure;

import java.io.File;
import java.nio.file.Files;

/**
 * 構造物のファイルを読み、パレットとブロックの数を表示する (確認用)。引数: ファイル...
 */
public final class StructureDump {

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            final byte[] content = Files.readAllBytes(new File(arg).toPath());
            final Structure structure = StructureReader.read(content);
            final int[] counts = new int[structure.paletteSize()];
            for (int i = 0; i < structure.volume(); i++) counts[structure.id(i)]++;
            System.out.println(arg + ": " + structure.source + " " + structure.fullX + "x" + structure.fullY + "x" + structure.fullZ);
            for (int id = 0; id < structure.paletteSize(); id++) System.out.println("  " + id + " " + structure.state(id) + " x" + counts[id]);
            StructureFixer.fix(structure);
            System.out.println(" after fix:");
            for (int id = 0; id < structure.paletteSize(); id++) System.out.println("  " + id + " " + structure.state(id));
            // エンティティと、そのまわり (3 x 3 x 3) にあるブロックの数 (0 なら宙に浮いている)
            System.out.println(" entities:");
            for (Structure.Entity entity : structure.entities()) {
                int near = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            final int x = (int) Math.floor(entity.x) + dx, y = (int) Math.floor(entity.y) + dy, z = (int) Math.floor(entity.z) + dz;
                            if (x < 0 || y < 0 || z < 0 || x >= structure.sizeX || y >= structure.sizeY || z >= structure.sizeZ) continue;
                            if (!structure.state(structure.id(x, y, z)).name.endsWith(":air")) near++;
                        }
                    }
                }
                System.out.println(String.format("  %s %.2f %.2f %.2f near=%d", entity.id, entity.x, entity.y, entity.z, near));
            }
        }
    }
}
