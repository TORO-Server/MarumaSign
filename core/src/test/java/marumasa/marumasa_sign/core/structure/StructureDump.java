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
        }
    }
}
