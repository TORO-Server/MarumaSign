package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ブロックなどのテクスチャ 1 枚。アニメーションするテクスチャは、すべての絵と、それぞれを表示する時間 (tick) を持つ。
 */
final class Sprite {

    static final int OPAQUE = 0;
    // 完全に透明な部分がある (くり抜き)
    static final int CUTOUT = 1;
    // 半透明の部分がある
    static final int TRANSLUCENT = 2;

    private static final Map<String, Sprite> CACHE = new ConcurrentHashMap<String, Sprite>();
    // 見つからなかったテクスチャの名前 (確認用)
    static final java.util.Set<String> MISSING = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    final String id;
    final int width, height;
    // 絵ごとの ARGB (アニメーションしなければ 1 枚)
    final int[][] frames;
    // 表示する順の絵の番号と、それぞれの時間 (tick)。アニメーションしなければ null
    final int[] sequence;
    final int[] times;
    // 次の絵との間を混ぜるかどうか
    final boolean interpolate;
    final boolean missing;
    // 全体の透明度の扱い (まだ調べていなければ -1)。看板の絵 (アニメーションで 512 x 512 が数百枚) を、
    // 同じ URL の看板の数だけ調べ直さないように覚えておく
    private volatile int wholeTransparency = -1;

    private Sprite(String id, int width, int height, int[][] frames, int[] sequence, int[] times, boolean interpolate, boolean missing) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.sequence = sequence;
        this.times = times;
        this.interpolate = interpolate;
        this.missing = missing;
    }

    // 作った絵 (旗の模様を重ねたものなど) をテクスチャにする
    static Sprite of(String id, int width, int height, int[] pixels) {
        return new Sprite(id, width, height, new int[][]{pixels}, null, null, false, false);
    }

    // 作ったアニメーション (看板の GIF など)。times は tick 単位
    static Sprite animated(String id, int width, int height, int[][] frames, int[] sequence, int[] times) {
        return new Sprite(id, width, height, frames, sequence, times, false, false);
    }

    boolean isAnimated() {
        return sequence != null && sequence.length > 1;
    }

    private static final Sprite MISSING_SPRITE = missing();

    // テクスチャがないときの、紫と黒の市松模様 (Minecraft と同じ)
    static Sprite missing() {
        final int[] pixels = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = ((x < 8) == (y < 8)) ? 0xFFF800F8 : 0xFF000000;
            }
        }
        return new Sprite("minecraft:missingno", 16, 16, new int[][]{pixels}, null, null, false, true);
    }

    /**
     * テクスチャを読む (例: minecraft:block/stone)。なければ紫と黒の市松模様。
     */
    static Sprite get(GameAssets assets, String id) {
        final String key = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
        Sprite sprite = CACHE.get(key);
        if (sprite != null) return sprite;
        try {
            sprite = load(assets, key);
        } catch (IOException | RuntimeException e) {
            sprite = null;
        }
        if (sprite == null) {
            // ないテクスチャは覚えない (名前は構造物のファイルの中身で決まることがあり、いくらでも増えうる)
            if (MISSING.size() < 256) MISSING.add(key);
            return MISSING_SPRITE;
        }
        CACHE.put(key, sprite);
        return sprite;
    }

    private static Sprite load(GameAssets assets, String id) throws IOException {
        final String path = GameAssets.path(id, "textures", ".png");
        final byte[] bytes = assets.read(path);
        if (bytes == null) return null;
        final BufferedImage image = ImageBytes.read(bytes);
        if (image == null) return null;
        final int imageWidth = image.getWidth(), imageHeight = image.getHeight();
        final int[] all = ArgbPixels.read(image);

        Map<String, Object> animation = null;
        final byte[] meta = assets.read(path + ".mcmeta");
        if (meta != null) {
            final Object parsed = Json.parse(new String(meta, StandardCharsets.UTF_8));
            final Object section = Json.object(parsed).get("animation");
            if (section instanceof Map) animation = Json.object(section);
        }
        if (animation == null) {
            return new Sprite(id, imageWidth, imageHeight, new int[][]{all}, null, null, false, false);
        }

        // 絵の大きさ。指定がなければ、画像の短い辺の正方形
        final int side = Math.min(imageWidth, imageHeight);
        final int frameWidth = Json.integer(animation, "width", side);
        final int frameHeight = Json.integer(animation, "height", side);
        if (frameWidth <= 0 || frameHeight <= 0 || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
            return new Sprite(id, imageWidth, imageHeight, new int[][]{all}, null, null, false, false);
        }
        final int columns = imageWidth / frameWidth;
        final int count = columns * (imageHeight / frameHeight);
        final int[][] frames = new int[count][];
        for (int f = 0; f < count; f++) {
            final int ox = (f % columns) * frameWidth, oy = (f / columns) * frameHeight;
            final int[] pixels = new int[frameWidth * frameHeight];
            for (int y = 0; y < frameHeight; y++) {
                System.arraycopy(all, (oy + y) * imageWidth + ox, pixels, y * frameWidth, frameWidth);
            }
            frames[f] = pixels;
        }
        final int defaultTime = Math.max(1, Json.integer(animation, "frametime", 1));
        final List<int[]> order = new ArrayList<int[]>();
        final List<Object> listed = Json.array(animation, "frames");
        if (listed.isEmpty()) {
            for (int f = 0; f < count; f++) order.add(new int[]{f, defaultTime});
        } else {
            for (Object item : listed) {
                if (item instanceof Double) {
                    order.add(new int[]{(int) Math.round((Double) item), defaultTime});
                } else if (item instanceof Map) {
                    final Map<String, Object> frame = Json.object(item);
                    order.add(new int[]{Json.integer(frame, "index", 0), Math.max(1, Json.integer(frame, "time", defaultTime))});
                }
            }
        }
        final int[] sequence = new int[order.size()];
        final int[] times = new int[order.size()];
        for (int i = 0; i < sequence.length; i++) {
            final int index = order.get(i)[0];
            sequence[i] = index >= 0 && index < count ? index : 0;
            times[i] = order.get(i)[1];
        }
        return new Sprite(id, frameWidth, frameHeight, frames, sequence, times, Json.bool(animation, "interpolate", false), false);
    }

    // 最初に表示する絵
    int[] firstFrame() {
        return frames[sequence != null && sequence.length > 0 ? sequence[0] : 0];
    }

    /**
     * 範囲 (0 - 1) の透明度の扱い。アニメーションするものはすべての絵を見る (Minecraft の SpriteContents.computeTransparency と同じ)。
     */
    int transparency(float u0, float v0, float u1, float v1) {
        final boolean whole = Math.min(u0, u1) <= 0 && Math.min(v0, v1) <= 0 && Math.max(u0, u1) >= 1 && Math.max(v0, v1) >= 1;
        if (whole && wholeTransparency >= 0) return wholeTransparency;
        final int result = computeTransparency(u0, v0, u1, v1);
        if (whole) wholeTransparency = result;
        return result;
    }

    private int computeTransparency(float u0, float v0, float u1, float v1) {
        final int x0 = Math.max(0, (int) Math.floor(Math.min(u0, u1) * width));
        final int y0 = Math.max(0, (int) Math.floor(Math.min(v0, v1) * height));
        final int x1 = Math.min(width, (int) Math.ceil(Math.max(u0, u1) * width));
        final int y1 = Math.min(height, (int) Math.ceil(Math.max(v0, v1) * height));
        int result = OPAQUE;
        final int frameCount = sequence == null ? 1 : frames.length;
        for (int f = 0; f < frameCount; f++) {
            final int[] pixels = frames[f];
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    final int alpha = pixels[y * width + x] >>> 24;
                    if (alpha == 0) result = Math.max(result, CUTOUT);
                    else if (alpha < 255) return TRANSLUCENT;
                }
            }
        }
        return result;
    }
}
