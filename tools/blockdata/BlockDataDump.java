import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 構造物のファイルの表示に使う、ブロックの性質の表を書き出す (開発用。配布する jar には入らない)。
 * Minecraft のコードを動かして、ブロックの状態ごとの面の隠れ方などを調べる。
 * 使い方は tools/blockdata/README.md を参照。
 *
 * 出力 (gzip で圧縮したテキスト):
 *   M 番号 64 桁の 16 進数    面の形 (16x16 のます目。0 は空、1 はすべて)
 *   B 名前 種類 ずらし方 横の最大 縦の最大 [性質名=値,値,...]...   ブロック。種類は面の省き方 (skipRendering) の種類
 *   D 既定の状態の番号
 *   S 下 上 北 南 西 東 の面の形, 状態の印, 光, 液体   状態 (性質の値の組み合わせの順。最後の性質が最も速く変わる)
 *     状態の印: 1 衝突の形が立方体, 2 光を通す, 4 不透明な立方体として描く, 8 描かない, 16 << 方向 その面が丈夫, 1024 固体 (isSolid)
 */
public final class BlockDataDump {

    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Map<String, Integer> masks = new LinkedHashMap<>();
        masks.put("empty", 0);
        masks.put("full", 1);
        final List<String> lines = new ArrayList<>();

        final Method maxHorizontal = BlockBehaviour.class.getDeclaredMethod("getMaxHorizontalOffset");
        final Method maxVertical = BlockBehaviour.class.getDeclaredMethod("getMaxVerticalOffset");
        maxHorizontal.setAccessible(true);
        maxVertical.setAccessible(true);

        for (Block block : BuiltInRegistries.BLOCK) {
            final String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            final List<Property<?>> properties = new ArrayList<>(block.getStateDefinition().getProperties());
            final BlockState defaultState = block.defaultBlockState();

            String skip = "-";
            if (block instanceof HalfTransparentBlock || block instanceof PowderSnowBlock) skip = "same";
            else if (block instanceof IronBarsBlock) skip = "bars";
            else if (block instanceof LiquidBlock) skip = "liquid";
            else if (block instanceof MangroveRootsBlock) skip = "roots";

            String offset = "-";
            if (defaultState.hasOffsetFunction()) {
                offset = "xz";
                for (int i = 0; i < 256; i++) {
                    final Vec3 v = defaultState.getOffset(new BlockPos(i * 7, 0, i * 13));
                    if (v.y != 0) offset = "xyz";
                }
            }

            final StringBuilder header = new StringBuilder("B ").append(name).append(' ').append(skip).append(' ').append(offset)
                    .append(' ').append((Float) maxHorizontal.invoke(block)).append(' ').append((Float) maxVertical.invoke(block));
            for (Property<?> property : properties) {
                header.append(' ').append(property.getName()).append('=');
                boolean first = true;
                for (Object value : property.getPossibleValues()) {
                    if (!first) header.append(',');
                    first = false;
                    header.append(valueName(property, value));
                }
            }
            lines.add(header.toString());

            // 性質の値の組み合わせを順に並べる
            final int[] sizes = new int[properties.size()];
            int total = 1;
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = properties.get(i).getPossibleValues().size();
                total *= sizes[i];
            }
            int defaultIndex = 0;
            for (int i = 0; i < sizes.length; i++) {
                defaultIndex = defaultIndex * sizes[i] + indexOf(properties.get(i), defaultState.getValue(properties.get(i)));
            }
            lines.add("D " + defaultIndex);

            for (int index = 0; index < total; index++) {
                BlockState state = defaultState;
                int rest = index;
                for (int i = sizes.length - 1; i >= 0; i--) {
                    state = with(state, properties.get(i), rest % sizes[i]);
                    rest /= sizes[i];
                }
                final StringBuilder line = new StringBuilder("S");
                for (Direction direction : DIRECTIONS) {
                    line.append(' ').append(mask(masks, state.getFaceOcclusionShape(direction), direction));
                }
                int flags = 0;
                if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) flags |= 1;
                if (state.isLightPermeable()) flags |= 2;
                if (state.isSolidRender()) flags |= 4;
                if (state.getRenderShape() == RenderShape.INVISIBLE) flags |= 8;
                for (int d = 0; d < 6; d++) {
                    if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, DIRECTIONS[d])) flags |= 16 << d;
                }
                if (state.isSolid()) flags |= 1024;
                line.append(' ').append(flags).append(' ').append(state.getLightEmission());
                final FluidState fluid = state.getFluidState();
                if (fluid.isEmpty()) {
                    line.append(" -");
                } else {
                    final String type = fluid.getType().isSame(Fluids.WATER) ? "w" : fluid.getType().isSame(Fluids.LAVA) ? "l" : "?";
                    final boolean falling = fluid.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
                            && fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
                    line.append(' ').append(type).append(fluid.getAmount()).append(fluid.isSource() ? "s" : "").append(falling ? "f" : "");
                }
                lines.add(line.toString());
            }
        }

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(Paths.get(args[0]))), StandardCharsets.UTF_8))) {
            out.print("# MarumaSign block data (" + SharedConstants.getCurrentVersion().name() + ")\n");
            for (Map.Entry<String, Integer> entry : masks.entrySet()) {
                if (entry.getValue() > 1) out.print("M " + entry.getValue() + " " + entry.getKey() + "\n");
            }
            for (String line : lines) out.print(line + "\n");
        }
        System.out.println("blocks written: " + lines.size() + " lines, " + masks.size() + " masks");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property property, Object value) {
        return property.getName((Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int indexOf(Property property, Object value) {
        return new ArrayList<>(property.getPossibleValues()).indexOf(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, Property property, int valueIndex) {
        final Object value = new ArrayList<>(property.getPossibleValues()).get(valueIndex);
        return (BlockState) state.setValue(property, (Comparable) value);
    }

    // 面の形を 16x16 のます目にして、番号を返す
    private static int mask(Map<String, Integer> masks, VoxelShape shape, Direction direction) {
        if (shape.isEmpty()) return 0;
        final long[] bits = new long[4];
        for (AABB box : shape.toAabbs()) {
            for (int b = 0; b < 16; b++) {
                for (int a = 0; a < 16; a++) {
                    final double ca = (a + 0.5) / 16, cb = (b + 0.5) / 16;
                    final boolean inside;
                    switch (direction.getAxis()) {
                        case X:
                            inside = box.minZ <= ca && ca <= box.maxZ && box.minY <= cb && cb <= box.maxY;
                            break;
                        case Y:
                            inside = box.minX <= ca && ca <= box.maxX && box.minZ <= cb && cb <= box.maxZ;
                            break;
                        default:
                            inside = box.minX <= ca && ca <= box.maxX && box.minY <= cb && cb <= box.maxY;
                    }
                    if (inside) {
                        final int bit = b * 16 + a;
                        bits[bit >> 6] |= 1L << (bit & 63);
                    }
                }
            }
        }
        if (bits[0] == -1L && bits[1] == -1L && bits[2] == -1L && bits[3] == -1L) return 1;
        if (bits[0] == 0 && bits[1] == 0 && bits[2] == 0 && bits[3] == 0) return 0;
        final String key = String.format("%016x%016x%016x%016x", bits[3], bits[2], bits[1], bits[0]);
        Integer id = masks.get(key);
        if (id == null) {
            id = masks.size();
            masks.put(key, id);
        }
        return id;
    }
}
