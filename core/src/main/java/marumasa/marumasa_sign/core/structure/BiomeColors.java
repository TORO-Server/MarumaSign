package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * バイオームごとの草・葉・水の色 (Minecraft の Biome と GrassColor / FoliageColor と同じ計算)。
 * バイオームの性質は、リソースと同じ jar の data/minecraft/worldgen/biome から読む。
 */
final class BiomeColors {

    static final String DEFAULT_BIOME = "minecraft:plains";
    static final int DEFAULT_WATER = 0xFF3F76E4;

    // 1 つのバイオームの色
    static final class Colors {
        final int grass, foliage, dryFoliage, water;

        Colors(int grass, int foliage, int dryFoliage, int water) {
            this.grass = grass;
            this.foliage = foliage;
            this.dryFoliage = dryFoliage;
            this.water = water;
        }
    }

    private final GameAssets assets;
    private final int[] grassMap, foliageMap, dryFoliageMap;
    private final Map<String, Colors> cache = new ConcurrentHashMap<String, Colors>();

    BiomeColors(GameAssets assets) throws IOException {
        this.assets = assets;
        grassMap = colormap("grass");
        foliageMap = colormap("foliage");
        dryFoliageMap = colormap("dry_foliage");
    }

    private int[] colormap(String name) throws IOException {
        final byte[] bytes = assets.read("assets/minecraft/textures/colormap/" + name + ".png");
        if (bytes == null) return new int[0];
        final BufferedImage image = ImageBytes.read(bytes);
        return image == null ? new int[0] : ArgbPixels.read(image);
    }

    private static int lookup(int[] map, double temperature, double downfall, int fallback) {
        downfall *= temperature;
        final int x = (int) ((1.0 - temperature) * 255.0);
        final int y = (int) ((1.0 - downfall) * 255.0);
        final int index = y << 8 | x;
        return index < 0 || index >= map.length ? fallback : map[index];
    }

    // 温度と雨の量から草の色 (GrassColor.get)
    int grassAt(double temperature, double downfall) {
        return lookup(grassMap, clamp(temperature), clamp(downfall), 0xFFFF00FF);
    }

    Colors colors(String biome) {
        final String key = biome == null ? DEFAULT_BIOME : biome;
        Colors colors = cache.get(key);
        if (colors == null) {
            colors = load(key);
            if (colors == null) colors = key.equals(DEFAULT_BIOME) ? new Colors(0xFF91BD59, 0xFF77AB2F, 0xFF8D6B4B, DEFAULT_WATER) : colors(DEFAULT_BIOME);
            cache.put(key, colors);
        }
        return colors;
    }

    private Colors load(String biome) {
        try {
            final byte[] bytes = assets.read(GameAssets.path(biome, "worldgen/biome", ".json").replaceFirst("^assets/", "data/"));
            if (bytes == null) return null;
            final Map<String, Object> json = Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            final double temperature = clamp(Json.number(json, "temperature", 0.8f));
            final double downfall = clamp(Json.number(json, "downfall", 0.4f));
            final Map<String, Object> effects = Json.object(json, "effects");
            int grass = color(effects.get("grass_color"), lookup(grassMap, temperature, downfall, 0xFFFF00FF));
            final String modifier = Json.string(effects, "grass_color_modifier", "none");
            if (modifier.equals("dark_forest")) grass = 0xFF000000 | ((grass & 0xFEFEFE) + 0x28340A >> 1);
            // 沼の草の色は場所ごとの雑音で 2 色に分かれる。多い方の色にする
            else if (modifier.equals("swamp")) grass = 0xFF6A7039;
            final int foliage = color(effects.get("foliage_color"), lookup(foliageMap, temperature, downfall, 0xFF48B518));
            final int dryFoliage = color(effects.get("dry_foliage_color"), lookup(dryFoliageMap, temperature, downfall, 0xFF8D6B4B));
            final int water = color(effects.get("water_color"), DEFAULT_WATER);
            return new Colors(grass, foliage, dryFoliage, water);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    // 色は数値か "#rrggbb" の文字列で書かれている
    private static int color(Object value, int fallback) {
        if (value instanceof Double) return 0xFF000000 | (int) Math.round((Double) value);
        if (value instanceof String) {
            final String text = (String) value;
            try {
                return 0xFF000000 | Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
