package marumasa.marumasa_sign.core.structure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数に上限のある、最後に使ったのが古いものから消すキャッシュ。
 * 構造物のファイルの中身 (旗の模様、看板の文字、プレイヤーの名前など) で決まるものを、いくらでも溜め込まないように使う。
 */
final class BoundedCache<K, V> {

    private final Map<K, V> map;

    BoundedCache(final int capacity) {
        this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    synchronized V get(K key) {
        return map.get(key);
    }

    synchronized void put(K key, V value) {
        map.put(key, value);
    }
}
