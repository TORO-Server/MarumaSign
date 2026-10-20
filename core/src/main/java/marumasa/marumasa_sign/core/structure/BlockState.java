package marumasa.marumasa_sign.core.structure;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * ブロックの名前と性質の値 (例: minecraft:oak_stairs[facing=east,half=bottom])。
 * 性質は名前の順に並べて持つ。作ったあとは変更しない。
 */
public final class BlockState {

    public static final BlockState AIR = new BlockState("minecraft:air", new String[0], new String[0]);

    public final String name;
    final String[] keys;
    final String[] values;
    private final int hash;

    BlockState(String name, String[] keys, String[] values) {
        this.name = name;
        this.keys = keys;
        this.values = values;
        this.hash = name.hashCode() * 31 + Arrays.hashCode(keys) * 17 + Arrays.hashCode(values);
    }

    // 名前空間がなければ minecraft: を付ける
    static String namespaced(String name) {
        final String trimmed = name.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    public static BlockState of(String name, Map<String, String> properties) {
        final TreeMap<String, String> sorted = new TreeMap<String, String>(properties);
        return new BlockState(namespaced(name), sorted.keySet().toArray(new String[0]), sorted.values().toArray(new String[0]));
    }

    // "名前[性質=値,...]" の形の文字列から作る
    public static BlockState parse(String text) {
        final int open = text.indexOf('[');
        if (open < 0) return new BlockState(namespaced(text), new String[0], new String[0]);
        final TreeMap<String, String> properties = new TreeMap<String, String>();
        final int close = text.lastIndexOf(']');
        final String body = text.substring(open + 1, close > open ? close : text.length());
        for (String pair : body.split(",")) {
            final int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            properties.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return of(text.substring(0, open), properties);
    }

    public String get(String key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return values[i];
        }
        return null;
    }

    public int size() {
        return keys.length;
    }

    public String key(int index) {
        return keys[index];
    }

    public String value(int index) {
        return values[index];
    }

    public Map<String, String> properties() {
        final TreeMap<String, String> map = new TreeMap<String, String>();
        for (int i = 0; i < keys.length; i++) map.put(keys[i], values[i]);
        return map;
    }

    // 性質を 1 つ変えたものを返す。value が null なら性質を除く
    public BlockState with(String key, String value) {
        final Map<String, String> map = properties();
        if (value == null) map.remove(key);
        else map.put(key, value);
        return of(name, map);
    }

    public BlockState withName(String newName) {
        return new BlockState(namespaced(newName), keys, values);
    }

    // 名前空間を除いた名前
    public String path() {
        final int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    public boolean isAir() {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlockState)) return false;
        final BlockState that = (BlockState) other;
        return hash == that.hash && name.equals(that.name) && Arrays.equals(keys, that.keys) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        if (keys.length == 0) return name;
        final StringBuilder builder = new StringBuilder(name).append('[');
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(keys[i]).append('=').append(values[i]);
        }
        return builder.append(']').toString();
    }
}
