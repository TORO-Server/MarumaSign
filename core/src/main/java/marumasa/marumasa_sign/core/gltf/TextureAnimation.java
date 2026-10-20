package marumasa.marumasa_sign.core.gltf;

/**
 * 時間で絵が変わるテクスチャ (構造物の水や溶岩など)。
 */
public interface TextureAnimation {

    // GltfModel のテクスチャの番号
    int textureIndex();

    /**
     * その時刻の絵 (ARGB。テクスチャと同じ大きさ) を返す。前の呼び出しから変わっていなければ null。
     *
     * @param tick 表示を始めてからの時間 (1/20 秒単位)
     */
    int[] update(long tick);
}
