package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーの頭のスキン。ブロックエンティティに書かれたプロフィールから、Mojang のサーバーのスキンを読む。
 * スキンが分からなければ、ゲームの既定のスキン (Steve) にする。
 */
final class PlayerSkins {

    private static final BoundedCache<String, Sprite> CACHE = new BoundedCache<String, Sprite>(128);

    // 1 つの構造物で、スキンをサーバーに問い合わせる数の上限
    private static final int MAX_LOOKUPS = 16;

    private final GameAssets assets;
    private int lookups;

    PlayerSkins(GameAssets assets) {
        this.assets = assets;
    }

    Sprite skin(Map<String, Object> entity) {
        final String[] profile = profile(entity);
        final String key = profile[0] + "|" + profile[1] + "|" + profile[2];
        Sprite sprite = CACHE.get(key);
        if (sprite != null) return sprite;
        if (lookups >= MAX_LOOKUPS) return Sprite.get(assets, "minecraft:entity/player/wide/steve");
        lookups++;
        sprite = load(profile);
        if (sprite == null) sprite = Sprite.get(assets, "minecraft:entity/player/wide/steve");
        CACHE.put(key, sprite);
        return sprite;
    }

    // {スキンの URL, UUID (区切りなし), 名前}。分からないものは空
    private static String[] profile(Map<String, Object> entity) {
        String textures = "", id = "", name = "";
        Object value = entity.containsKey("profile") ? entity.get("profile") : entity.get("SkullOwner");
        if (value == null && entity.get("Owner") instanceof Map) value = entity.get("Owner");
        if (value instanceof String) {
            name = (String) value;
        } else if (value instanceof Map) {
            final Map<String, Object> map = Nbt.compound(value);
            name = Nbt.string(map, "name", Nbt.string(map, "Name", ""));
            id = uuid(map.containsKey("id") ? map.get("id") : map.get("Id"));
            // 1.20.5 から: properties: [{name: "textures", value: "..."}]
            for (Object item : Nbt.list(map, "properties")) {
                final Map<String, Object> property = Nbt.compound(item);
                if ("textures".equals(Nbt.string(property, "name", ""))) textures = Nbt.string(property, "value", "");
            }
            // それより前: Properties: {textures: [{Value: "..."}]}
            for (Object item : Nbt.list(Nbt.compound(map, "Properties"), "textures")) {
                final String text = Nbt.string(Nbt.compound(item), "Value", "");
                if (!text.isEmpty()) textures = text;
            }
        }
        return new String[]{skinUrl(textures), id, name};
    }

    // UUID は int 4 つか、文字列で書かれている
    private static String uuid(Object value) {
        if (value instanceof String) return ((String) value).replace("-", "").toLowerCase(java.util.Locale.ROOT);
        final int[] parts = Nbt.ints(value);
        if (parts == null || parts.length != 4) return "";
        return String.format("%08x%08x%08x%08x", parts[0], parts[1], parts[2], parts[3]);
    }

    // textures の値 (base64 の JSON) からスキンの URL を取り出す
    private static String skinUrl(String textures) {
        if (textures.isEmpty()) return "";
        try {
            final String json = new String(java.util.Base64.getMimeDecoder().decode(textures), StandardCharsets.UTF_8);
            final Map<String, Object> root = Json.object(Json.parse(json));
            final String url = Json.string(Json.object(Json.object(root, "textures"), "SKIN"), "url", "");
            // Mojang のスキンのサーバー以外は読まない
            if (url.startsWith("http://textures.minecraft.net/") || url.startsWith("https://textures.minecraft.net/")) {
                return url.replace("http://", "https://");
            }
        } catch (Exception e) {
            // 読めなければスキンなし
        }
        return "";
    }

    private Sprite load(String[] profile) {
        try {
            String url = profile[0];
            String id = profile[1];
            if (url.isEmpty() && id.isEmpty() && !profile[2].isEmpty() && profile[2].matches("[A-Za-z0-9_]{1,16}")) {
                final Map<String, Object> user = Json.object(Json.parse(new String(Http.download(
                        "https://api.mojang.com/users/profiles/minecraft/" + profile[2]), StandardCharsets.UTF_8)));
                id = Json.string(user, "id", "");
            }
            if (url.isEmpty() && id.matches("[0-9a-f]{32}")) {
                final Map<String, Object> session = Json.object(Json.parse(new String(Http.download(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + id), StandardCharsets.UTF_8)));
                final List<Object> properties = Json.array(session, "properties");
                for (Object item : properties) {
                    final Map<String, Object> property = Json.object(item);
                    if ("textures".equals(Json.string(property, "name", ""))) url = skinUrl(Json.string(property, "value", ""));
                }
            }
            if (url.isEmpty()) return null;
            final BufferedImage image = ImageBytes.read(Http.download(url));
            if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) return null;
            final int[] pixels = ArgbPixels.read(image);
            final int[] full = new int[64 * 64];
            System.arraycopy(pixels, 0, full, 0, Math.min(pixels.length, full.length));
            return Sprite.of("skin:" + url, 64, 64, full);
        } catch (Exception e) {
            return null;
        }
    }
}
