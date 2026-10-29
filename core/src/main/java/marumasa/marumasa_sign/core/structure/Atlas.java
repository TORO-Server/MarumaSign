package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.gltf.GltfModel;
import marumasa.marumasa_sign.core.gltf.QuadModel;
import marumasa.marumasa_sign.core.gltf.TextureAnimation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 使われているテクスチャを 1 枚 (入りきらなければ数枚) の大きな画像にまとめ、モデルを作る。
 * アニメーションするテクスチャは、絵を差し替えやすいように別の画像にまとめる。
 */
final class Atlas {

    private static final int MAX_SIZE = 4096;
    // テクスチャの周りに、端の色を 1 ピクセル延ばす (隣のテクスチャがにじまないように)
    private static final int GUTTER = 1;

    // まとめた画像 1 枚
    static final class Page implements TextureAnimation {
        final int width, height;
        final int[] pixels;
        // アニメーションするテクスチャの画像かどうか
        final boolean animated;
        final List<Sprite> sprites = new ArrayList<Sprite>();
        final List<int[]> places = new ArrayList<int[]>();
        int index;
        // テクスチャごとの、最後に描いた絵 (絵の番号 * 256 + 混ぜる割合)
        private int[] shown = new int[0];

        Page(int width, int height, boolean animated) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
            this.animated = animated;
        }

        @Override
        public int textureIndex() {
            return index;
        }

        /**
         * SpriteContents.AnimatedTexture と同じく、tick ごとに絵を進める。interpolate なら次の絵と混ぜる。
         */
        @Override
        public int[] update(long tick) {
            if (shown.length != sprites.size()) {
                shown = new int[sprites.size()];
                java.util.Arrays.fill(shown, -1);
            }
            boolean changed = false;
            for (int i = 0; i < sprites.size(); i++) {
                final Sprite sprite = sprites.get(i);
                if (!sprite.isAnimated()) continue;
                // 長い時間を足しても int であふれないように long で数える
                long total = 0;
                for (int time : sprite.times) total += time;
                if (total <= 0) continue;
                long t = tick % total;
                int k = 0;
                while (t >= sprite.times[k]) {
                    t -= sprite.times[k];
                    k++;
                }
                final int frame = sprite.sequence[k];
                final int next = sprite.sequence[(k + 1) % sprite.sequence.length];
                int blend = 0;
                if (sprite.interpolate && frame != next) blend = (int) (t * 255 / sprite.times[k]);
                final int key = frame * 256 + blend;
                if (shown[i] == key) continue;
                shown[i] = key;
                changed = true;
                int[] image = sprite.frames[frame];
                if (blend > 0) {
                    // InterpolationData: ratio = 1 - 経過 / 絵の時間 で今の絵と次の絵を混ぜる
                    final double ratio = 1.0 - (double) t / sprite.times[k];
                    final int[] a = sprite.frames[frame], b = sprite.frames[next];
                    image = new int[a.length];
                    for (int p = 0; p < a.length; p++) image[p] = mix(ratio, a[p], b[p]);
                }
                draw(this, sprite, image, places.get(i)[0], places.get(i)[1]);
            }
            return changed ? pixels : null;
        }

        private static int mix(double ratio, int a, int b) {
            int result = 0;
            for (int shift = 0; shift <= 24; shift += 8) {
                final int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
                result |= ((int) (ratio * ca + (1.0 - ratio) * cb) & 0xFF) << shift;
            }
            return result;
        }
    }

    // テクスチャ 1 枚の置き場所
    static final class Slot {
        int page;
        int x, y;
    }

    final List<Page> pages = new ArrayList<Page>();
    private final Slot[] slots;

    private Atlas(List<Sprite> sprites) {
        slots = new Slot[sprites.size()];
        final List<Integer> still = new ArrayList<Integer>();
        final List<Integer> moving = new ArrayList<Integer>();
        for (int i = 0; i < sprites.size(); i++) {
            if (sprites.get(i).isAnimated()) moving.add(i);
            else still.add(i);
        }
        pack(sprites, still, false);
        pack(sprites, moving, true);
    }

    static Atlas of(List<Sprite> sprites) {
        return new Atlas(sprites);
    }

    private void pack(final List<Sprite> sprites, List<Integer> indices, boolean animated) {
        if (indices.isEmpty()) return;
        // 背の高い順に棚に並べる
        final Integer[] order = indices.toArray(new Integer[0]);
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                final Sprite sa = sprites.get(a), sb = sprites.get(b);
                if (sa.height != sb.height) return sb.height - sa.height;
                return sb.width - sa.width;
            }
        });
        int start = 0;
        while (start < order.length) {
            long area = 0;
            for (int i = start; i < order.length; i++) {
                final Sprite s = sprites.get(order[i]);
                area += (long) (s.width + GUTTER * 2) * (s.height + GUTTER * 2);
            }
            int size = 16;
            while (size < MAX_SIZE && (long) size * size < area) size *= 2;
            int placed;
            while (true) {
                placed = tryPack(sprites, order, start, size, false, animated);
                if (placed == order.length - start || size >= MAX_SIZE) break;
                size *= 2;
            }
            if (placed == 0) {
                // 1 枚が大きすぎる。紫と黒の模様で代わりにする
                placed = 1;
            }
            tryPack(sprites, order, start, size, true, animated);
            start += placed;
        }
    }

    // 入る数を返す。commit なら実際に置く
    private int tryPack(List<Sprite> sprites, Integer[] order, int start, int size, boolean commit, boolean animated) {
        int x = 0, y = 0, shelf = 0, count = 0;
        Page page = null;
        if (commit) {
            page = new Page(size, size, animated);
            pages.add(page);
        }
        for (int i = start; i < order.length; i++) {
            final Sprite sprite = sprites.get(order[i]);
            final int w = sprite.width + GUTTER * 2, h = sprite.height + GUTTER * 2;
            if (w > size || h > size) break;
            if (x + w > size) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            if (y + h > size) break;
            if (commit) {
                final Slot slot = new Slot();
                slot.page = pages.size() - 1;
                slot.x = x + GUTTER;
                slot.y = y + GUTTER;
                slots[order[i]] = slot;
                page.sprites.add(sprite);
                page.places.add(new int[]{slot.x, slot.y});
                draw(page, sprite, sprite.firstFrame(), slot.x, slot.y);
            }
            x += w;
            shelf = Math.max(shelf, h);
            count++;
        }
        return count;
    }

    // テクスチャを画像に描き、周りに端の色を延ばす
    static void draw(Page page, Sprite sprite, int[] frame, int left, int top) {
        final int w = sprite.width, h = sprite.height;
        for (int y = -GUTTER; y < h + GUTTER; y++) {
            final int sy = Math.max(0, Math.min(h - 1, y));
            final int py = top + y;
            if (py < 0 || py >= page.height) continue;
            for (int x = -GUTTER; x < w + GUTTER; x++) {
                final int sx = Math.max(0, Math.min(w - 1, x));
                final int px = left + x;
                if (px < 0 || px >= page.width) continue;
                page.pixels[py * page.width + px] = frame[sy * w + sx];
            }
        }
    }

    /**
     * 書き出した四角形とまとめた画像から、モデルを作る。
     */
    GltfModel build(StructureMesher.Output output) {
        final List<GltfModel.Texture> textures = new ArrayList<GltfModel.Texture>();
        final List<TextureAnimation> animations = new ArrayList<TextureAnimation>();
        for (Page page : pages) {
            page.index = textures.size();
            // テクスチャは最初の絵の写しを渡す (アニメーションで書き換える画像と分ける)
            textures.add(QuadModel.texture(page.width, page.height, page.animated ? page.pixels.clone() : page.pixels));
            if (page.animated) animations.add(page);
        }
        final List<QuadModel.Part> parts = new ArrayList<QuadModel.Part>();
        final int[] alphaModes = {GltfModel.ALPHA_OPAQUE, GltfModel.ALPHA_MASK, GltfModel.ALPHA_BLEND};
        for (int layer = 0; layer < 3; layer++) {
            final StructureMesher.IntList spriteOf = output.spriteOf[layer];
            final int quads = spriteOf.size;
            if (quads == 0) continue;
            for (int p = 0; p < pages.size(); p++) {
                final Page page = pages.get(p);
                int count = 0;
                for (int q = 0; q < quads; q++) {
                    if (slots[spriteOf.values[q]].page == p) count++;
                }
                if (count == 0) continue;
                final float[] positions = new float[count * 12];
                final float[] uvs = new float[count * 8];
                final int[] colors = new int[count * 4];
                int n = 0;
                for (int q = 0; q < quads; q++) {
                    final int spriteIndex = spriteOf.values[q];
                    final Slot slot = slots[spriteIndex];
                    if (slot.page != p) continue;
                    final Sprite sprite = output.sprites.get(spriteIndex);
                    System.arraycopy(output.positions[layer].values, q * 12, positions, n * 12, 12);
                    System.arraycopy(output.colors[layer].values, q * 4, colors, n * 4, 4);
                    for (int v = 0; v < 4; v++) {
                        final float u = clamp(output.uvs[layer].values[q * 8 + v * 2]);
                        final float t = clamp(output.uvs[layer].values[q * 8 + v * 2 + 1]);
                        uvs[n * 8 + v * 2] = (slot.x + u * sprite.width) / page.width;
                        uvs[n * 8 + v * 2 + 1] = (slot.y + t * sprite.height) / page.height;
                    }
                    n++;
                }
                parts.add(new QuadModel.Part(p, alphaModes[layer], false, positions, uvs, colors, count));
            }
        }
        return QuadModel.build(textures, parts, animations);
    }

    private static float clamp(float value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }
}
