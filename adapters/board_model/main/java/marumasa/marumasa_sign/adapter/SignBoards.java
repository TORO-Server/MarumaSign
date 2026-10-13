package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板の板を隠す。(Minecraft 26.2 以降)
 * このバージョンから、看板の板は地形と一緒にブロックのモデルとして描かれるようになり、看板のレンダラーを包むだけでは消せない。
 * そこで、看板のブロックのモデルを包み、MarumaSign の看板がある位置では何も描かないようにする。
 * ほかの看板は、元のモデルがそのまま描かれる。
 */
final class SignBoards {

    private static Field modelsField;
    private static List<BlockState> signStates;

    // 板を隠す位置。キーは、その位置を表す目印の値 (key を参照)
    private static final Map<Long, BlockPos> HIDDEN = new ConcurrentHashMap<Long, BlockPos>();

    private SignBoards() {
    }

    // 看板のブロックのモデルがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    static void install(Minecraft mc) {
        // ブロックのモデルは、ワールドに入る前にはまだ読み込まれていないことがある
        if (mc.level == null) return;
        final ModelManager manager = mc.getModelManager();
        if (manager == null) return;
        final BlockStateModelSet set = manager.getBlockStateModelSet();
        if (set == null) return;
        if (modelsField == null) modelsField = Reflect.fieldOfType(BlockStateModelSet.class, Map.class);
        final Map<BlockState, BlockStateModel> models = Reflect.get(modelsField, set);
        if (models == null) return;

        if (signStates == null) {
            final List<BlockState> states = new ArrayList<BlockState>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof StandingSignBlock || block instanceof WallSignBlock) {
                    states.addAll(block.getStateDefinition().getPossibleStates());
                }
            }
            signStates = states;
        }

        boolean wrapped = true;
        for (BlockState state : signStates) {
            final BlockStateModel model = models.get(state);
            if (model != null && !(model instanceof Model)) wrapped = false;
        }
        if (wrapped) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockState, BlockStateModel> replaced = new HashMap<BlockState, BlockStateModel>(models);
        for (BlockState state : signStates) {
            final BlockStateModel model = replaced.get(state);
            if (model != null && !(model instanceof Model)) replaced.put(state, new Model(model));
        }
        Reflect.set(modelsField, set, replaced);

        // すでに描かれている地形には反映されていないので、板を隠す位置を描き直してもらう
        if (mc.level != null) {
            for (BlockPos pos : HIDDEN.values()) redraw(mc.level, pos);
        }
    }

    /**
     * 看板の板を隠すかどうかを記録する。看板を描く準備のたびに呼ぶ。
     * 変わったときは、その位置の地形を描き直してもらう。
     */
    static void track(SignBlockEntity sign, boolean hide) {
        final BlockPos pos = sign.getBlockPos();
        final Long key = key(sign.getBlockState(), pos);
        if (hide) {
            if (HIDDEN.putIfAbsent(key, pos.immutable()) == null) redraw(sign.getLevel(), pos);
        } else if (HIDDEN.remove(key) != null) {
            redraw(sign.getLevel(), pos);
        }
    }

    /**
     * 位置を表す目印の値。
     * 地形を描く処理は、モデルに位置を渡さない代わりに、位置から決まる種で初期化した乱数を渡す。
     * その乱数が最初に返す値を目印にすれば、モデルの側でどの位置を描いているのかを見分けられる。
     */
    private static long key(BlockState state, BlockPos pos) {
        return RandomSource.createThreadLocalInstance(state.getSeed(pos)).nextLong();
    }

    private static void redraw(Level level, BlockPos pos) {
        if (!(level instanceof ClientLevel)) return;
        final BlockState state = level.getBlockState(pos);
        // 8 は、すぐに描き直すことを表す
        ((ClientLevel) level).sendBlockUpdated(pos, state, state, 8);
    }

    /**
     * 看板のブロックのモデル。板を隠す位置では何も描かず、それ以外は元のモデルに任せる。
     */
    private static final class Model implements BlockStateModel {

        private final BlockStateModel delegate;

        Model(BlockStateModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            // 隠す看板が 1 つもなければ、乱数には触らない
            if (!HIDDEN.isEmpty() && HIDDEN.containsKey(random.nextLong())) return;
            delegate.collectParts(random, parts);
        }

        @Override
        public Material.Baked particleMaterial() {
            return delegate.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return delegate.materialFlags();
        }
    }
}