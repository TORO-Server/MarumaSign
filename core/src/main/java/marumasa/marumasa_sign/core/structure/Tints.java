package marumasa.marumasa_sign.core.structure;

/**
 * ブロックの面に掛ける色 (Minecraft の BlockColors.createDefault と同じ)。
 */
final class Tints {

    static final int NONE = 0;
    static final int GRASS = 1;
    // 2 段の草 (上の半分は下の半分の位置の色)
    static final int DOUBLE_GRASS = 2;
    static final int FOLIAGE = 3;
    static final int DRY_FOLIAGE = 4;
    static final int WATER = 5;
    static final int REDSTONE = 6;
    static final int STEM = 7;
    static final int SPRUCE = 8;
    static final int BIRCH = 9;
    static final int ATTACHED_STEM = 10;
    static final int LILY_PAD = 11;
    // 1 枚目は色なし、2 枚目が草の色 (花びらなど)
    static final int FLOWER_BED = 12;

    private static final int[] REDSTONE_COLORS = new int[16];

    static {
        for (int i = 0; i <= 15; i++) {
            final float power = i / 15.0f;
            final float red = power * 0.6f + (power > 0.0f ? 0.4f : 0.3f);
            final float green = Math.max(0, Math.min(1, power * power * 0.7f - 0.5f));
            final float blue = Math.max(0, Math.min(1, power * power * 0.6f - 0.7f));
            REDSTONE_COLORS[i] = 0xFF000000 | channel(red) << 16 | channel(green) << 8 | channel(blue);
        }
    }

    private static int channel(float value) {
        return (int) (value * 255.0f);
    }

    private Tints() {
    }

    static int type(String name) {
        switch (name) {
            case "minecraft:large_fern":
            case "minecraft:tall_grass":
                return DOUBLE_GRASS;
            case "minecraft:fern":
            case "minecraft:short_grass":
            case "minecraft:potted_fern":
            case "minecraft:bush":
            case "minecraft:grass_block":
            case "minecraft:sugar_cane":
                return GRASS;
            case "minecraft:pink_petals":
            case "minecraft:wildflowers":
                return FLOWER_BED;
            case "minecraft:spruce_leaves":
                return SPRUCE;
            case "minecraft:birch_leaves":
                return BIRCH;
            case "minecraft:oak_leaves":
            case "minecraft:jungle_leaves":
            case "minecraft:acacia_leaves":
            case "minecraft:dark_oak_leaves":
            case "minecraft:vine":
            case "minecraft:mangrove_leaves":
                return FOLIAGE;
            case "minecraft:leaf_litter":
                return DRY_FOLIAGE;
            case "minecraft:water_cauldron":
                return WATER;
            case "minecraft:redstone_wire":
                return REDSTONE;
            case "minecraft:attached_melon_stem":
            case "minecraft:attached_pumpkin_stem":
                return ATTACHED_STEM;
            case "minecraft:melon_stem":
            case "minecraft:pumpkin_stem":
                return STEM;
            case "minecraft:lily_pad":
                return LILY_PAD;
            default:
                return NONE;
        }
    }

    /**
     * 面に掛ける色 (ARGB)。色を付けない面は -1 (白)。
     *
     * @param tintIndex モデルの面の tintindex
     */
    static int color(int type, int tintIndex, BlockState state, StructureMesher mesher, int x, int y, int z) {
        switch (type) {
            case GRASS:
                return tintIndex == 0 ? mesher.blendedColor(x, y, z, StructureMesher.GRASS) : -1;
            case DOUBLE_GRASS:
                if (tintIndex != 0) return -1;
                return mesher.blendedColor(x, "upper".equals(state.get("half")) ? Math.max(0, y - 1) : y, z, StructureMesher.GRASS);
            case FLOWER_BED:
                return tintIndex == 1 ? mesher.blendedColor(x, y, z, StructureMesher.GRASS) : -1;
            case FOLIAGE:
                return tintIndex == 0 ? mesher.blendedColor(x, y, z, StructureMesher.FOLIAGE) : -1;
            case DRY_FOLIAGE:
                return tintIndex == 0 ? mesher.blendedColor(x, y, z, StructureMesher.DRY_FOLIAGE) : -1;
            case WATER:
                return tintIndex == 0 ? mesher.blendedColor(x, y, z, StructureMesher.WATER) : -1;
            case SPRUCE:
                return tintIndex == 0 ? 0xFF619961 : -1;
            case BIRCH:
                return tintIndex == 0 ? 0xFF80A755 : -1;
            case ATTACHED_STEM:
                return tintIndex == 0 ? 0xFFE0C71C : -1;
            case LILY_PAD:
                return tintIndex == 0 ? 0xFF208030 : -1;
            case REDSTONE: {
                if (tintIndex != 0) return -1;
                int power = 0;
                try {
                    power = Integer.parseInt(state.get("power"));
                } catch (NumberFormatException e) {
                    // 値がなければ 0
                }
                return REDSTONE_COLORS[Math.max(0, Math.min(15, power))];
            }
            case STEM: {
                if (tintIndex != 0) return -1;
                int age = 0;
                try {
                    age = Integer.parseInt(state.get("age"));
                } catch (NumberFormatException e) {
                    // 値がなければ 0
                }
                return 0xFF000000 | (age * 32) << 16 | (255 - age * 8) << 8 | age * 4;
            }
            default:
                return -1;
        }
    }
}
