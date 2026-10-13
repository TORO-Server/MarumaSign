package marumasa.marumasa_sign.adapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 影 Mod (OptiFine のシェーダー) への対応。
 * シェーダーが有効なとき、ワールドの中のものは、種類ごとに決まったシェーダーのプログラムで描く必要がある。
 * プログラムを切り替えずに描くと、明るさや法線などの情報が書き込まれず、
 * 背景 (空など) の情報のまま合成されて、白っぽくにじんだ絵になってしまう。
 * そこで、看板の絵を描く前後に、ブロックエンティティを描くときと同じ切り替えを OptiFine に頼む。
 *
 * OptiFine のクラスは Minecraft 本体と違って名前が変わらないので、名前で呼び出す。
 * OptiFine が入っていなければ、何もしない。
 */
final class ShaderHooks {

    // OptiFine のシェーダーのクラス。バージョンによってパッケージが違う
    private static final String[] CLASS_NAMES = {"net.optifine.shaders.Shaders", "shadersmod.client.Shaders"};

    private static boolean searched;
    private static Method begin;
    private static Method end;
    private static Field packLoaded;
    private static Field renderingWorld;

    private ShaderHooks() {
    }

    private static void search() {
        searched = true;
        for (String name : CLASS_NAMES) {
            try {
                final Class<?> shaders = Class.forName(name);
                final Method beginMethod = shaders.getMethod("beginBlockEntities");
                final Method endMethod = shaders.getMethod("endBlockEntities");
                packLoaded = shaders.getField("shaderPackLoaded");
                renderingWorld = shaders.getField("isRenderingWorld");
                begin = beginMethod;
                end = endMethod;
                return;
            } catch (ReflectiveOperationException e) {
                // このクラスはない。次の候補を試す
            } catch (LinkageError e) {
                // 読み込めない形で入っている場合も、ないものとして扱う
            }
        }
    }

    /**
     * ブロックエンティティ用のプログラムに切り替える。切り替えた場合は true を返すので、描き終わったら end を呼ぶこと。
     */
    static boolean begin() {
        if (!searched) search();
        if (begin == null) return false;
        try {
            // シェーダーパックを使っていて、ワールドを描いている最中のときだけ切り替える
            if (!packLoaded.getBoolean(null) || !renderingWorld.getBoolean(null)) return false;
            begin.invoke(null);
            return true;
        } catch (ReflectiveOperationException e) {
            // 呼び出せないバージョンでは、以後は何もしない
            begin = null;
            return false;
        }
    }

    static void end() {
        if (end == null) return;
        try {
            end.invoke(null);
        } catch (ReflectiveOperationException e) {
            end = null;
        }
    }
}