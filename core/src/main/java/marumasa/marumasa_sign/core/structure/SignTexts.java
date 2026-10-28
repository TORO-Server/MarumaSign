package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 看板の文字を絵にする。Minecraft のフォント (assets/minecraft/font) と同じ字形と文字の幅で描く。
 * jar にない文字は、ゲームと同じく Unifont で描く (Unifont はランチャーと同じ場所から読むか、ダウンロードする)。
 */
final class SignTexts {

    // 絵の大きさ (文字の座標で、看板の中心からの範囲) と、文字の座標 1 あたりのピクセル数
    static final int HALF_WIDTH = 50, HALF_HEIGHT = 26, SCALE = 2;
    // 文字ディスプレイの幅 (文字の座標) と行の数の上限
    static final int MAX_BLOCK_WIDTH = 512, MAX_BLOCK_LINES = 48;
    // 1 つの文字のデータから読む文字の数の上限
    static final int MAX_TEXT = 2048;

    // ChatFormatting の色 (§0 - §f と同じ順)
    private static final String[] COLOR_NAMES = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final int[] COLOR_VALUES = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    // DyeColor.getTextColor (StructureFixer.DYE_COLORS と同じ順)
    static final int[] DYE_TEXT = {0xFFFFFF, 0xFF681F, 0xFF00FF, 0x9AC0CD, 0xFFFF00, 0xBFFF00, 0xFF69B4, 0x808080,
            0xD3D3D3, 0x00FFFF, 0xA020F0, 0x0000FF, 0x8B4513, 0x00FF00, 0xFF0000, 0x000000};

    // Unifont (minecraft/font/unifont.zip と unifont_jp.zip。26.3 のアセットの一覧のもの)
    private static final String UNIFONT_HASH = "ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd";
    private static final long UNIFONT_SIZE = 1559654L;

    // 文字 1 つ
    private static final class Glyph {
        // ビットマップのフォントの場合: 画像と、その中の位置と大きさ
        int[] pixels;
        int imageWidth;
        int x, y, width, height;
        float scale;
        int ascent;
        // Unifont の場合: 16 行の、左から並んだビット (幅は bits)
        long[] rows;
        int bits, left, right;
        float advance;
        float boldOffset;
    }

    // 文字の並び 1 つ (同じ色と太さ)
    static final class Run {
        final String text;
        final Integer color;
        final boolean bold;

        Run(String text, Integer color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }
    }

    private static final Map<Integer, Glyph> BITMAP = new ConcurrentHashMap<Integer, Glyph>();
    private static volatile boolean bitmapLoaded;
    private static volatile Map<Integer, String> unifont;
    private static final BoundedCache<String, Sprite> CACHE = new BoundedCache<String, Sprite>(256);

    private final GameAssets assets;

    SignTexts(GameAssets assets) {
        this.assets = assets;
    }

    // ---------- フォント ----------

    private synchronized void loadBitmaps() {
        if (bitmapLoaded) return;
        try {
            final byte[] json = assets.read("assets/minecraft/font/include/default.json");
            if (json == null) return;
            for (Object item : Json.array(Json.object(Json.parse(new String(json, StandardCharsets.UTF_8))), "providers")) {
                final Map<String, Object> provider = Json.object(item);
                if (!"bitmap".equals(Json.string(provider, "type", ""))) continue;
                final String file = Json.string(provider, "file", "");
                final byte[] png = assets.read(GameAssets.path(file, "textures", ""));
                if (png == null) continue;
                final BufferedImage image = ImageBytes.read(png);
                if (image == null) continue;
                final int[] pixels = ArgbPixels.read(image);
                final List<Object> rows = Json.array(provider, "chars");
                if (rows.isEmpty()) continue;
                final int[][] grid = new int[rows.size()][];
                int columns = 0;
                for (int r = 0; r < rows.size(); r++) {
                    grid[r] = String.valueOf(rows.get(r)).codePoints().toArray();
                    columns = Math.max(columns, grid[r].length);
                }
                final int cellWidth = image.getWidth() / columns, cellHeight = image.getHeight() / grid.length;
                final int height = Json.integer(provider, "height", 8);
                final int ascent = Json.integer(provider, "ascent", 7);
                final float scale = (float) height / cellHeight;
                for (int r = 0; r < grid.length; r++) {
                    for (int c = 0; c < grid[r].length; c++) {
                        final int codepoint = grid[r][c];
                        if (codepoint == 0 || BITMAP.containsKey(codepoint)) continue;
                        final Glyph glyph = new Glyph();
                        glyph.pixels = pixels;
                        glyph.imageWidth = image.getWidth();
                        glyph.x = c * cellWidth;
                        glyph.y = r * cellHeight;
                        glyph.width = cellWidth;
                        glyph.height = cellHeight;
                        glyph.scale = scale;
                        glyph.ascent = ascent;
                        // BitmapProvider.getActualGlyphWidth: 右から見て最初に色のある列まで
                        int actual = cellWidth - 1;
                        outer:
                        for (; actual >= 0; actual--) {
                            for (int py = 0; py < cellHeight; py++) {
                                if ((pixels[(glyph.y + py) * glyph.imageWidth + glyph.x + actual] >>> 24) != 0) break outer;
                            }
                        }
                        glyph.advance = (int) (0.5 + (actual + 1) * scale) + 1;
                        glyph.boldOffset = 1;
                        BITMAP.put(codepoint, glyph);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 読めなかったフォントは使わない
        } finally {
            bitmapLoaded = true;
        }
    }

    private static synchronized void loadUnifont() {
        if (unifont != null) return;
        final Map<Integer, String> map = new HashMap<Integer, String>();
        try {
            final byte[] zip = GameAssets.object(UNIFONT_HASH, UNIFONT_SIZE);
            final ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".hex")) continue;
                final BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null) {
                    final int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    try {
                        map.put(Integer.parseInt(line.substring(0, colon), 16), line.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        // 壊れた行は飛ばす
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unifont が使えなければ、その文字は描かない
        }
        unifont = map;
    }

    // Unifont の幅の指定 (unifont.json の size_overrides)
    private static int[] overrideFor(int codepoint) {
        final int[][] ranges = {{0x3001, 0x30FF, 0, 15}, {0x3200, 0x9FFF, 0, 15}, {0x1100, 0x11FF, 0, 15}, {0x3130, 0x318F, 0, 15},
                {0xA960, 0xA97F, 0, 15}, {0xD7B0, 0xD7FF, 0, 15}, {0xAC00, 0xD7AF, 1, 15}, {0xF900, 0xFAFF, 0, 15}, {0xFF01, 0xFF5E, 0, 15}};
        for (int[] range : ranges) {
            if (codepoint >= range[0] && codepoint <= range[1]) return new int[]{range[2], range[3]};
        }
        return null;
    }

    private Glyph glyph(int codepoint) {
        if (!bitmapLoaded) loadBitmaps();
        final Glyph bitmap = BITMAP.get(codepoint);
        if (bitmap != null) return bitmap;
        if (unifont == null) loadUnifont();
        final String hex = unifont.get(codepoint);
        if (hex == null) return null;
        final int bits = hex.length() * 4 / 16;
        if (bits <= 0 || bits > 64) return null;
        final Glyph glyph = new Glyph();
        glyph.rows = new long[16];
        glyph.bits = bits;
        final int digits = bits / 4;
        int left = bits, right = -1;
        for (int row = 0; row < 16; row++) {
            final long value = Long.parseUnsignedLong(hex.substring(row * digits, (row + 1) * digits), 16);
            glyph.rows[row] = value;
            for (int column = 0; column < bits; column++) {
                if ((value >>> (bits - 1 - column) & 1) != 0) {
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                }
            }
        }
        final int[] override = overrideFor(codepoint);
        if (override != null) {
            left = override[0];
            right = override[1];
        } else if (right < 0) {
            left = 0;
            right = -1;
        }
        glyph.left = left;
        glyph.right = right;
        glyph.advance = (right - left + 1) / 2 + 1;
        glyph.boldOffset = 0.5f;
        return glyph;
    }

    private float advance(int codepoint, boolean bold) {
        if (codepoint == ' ') return 4;
        if (codepoint == 0x200C) return 0;
        final Glyph glyph = glyph(codepoint);
        if (glyph == null) return 0;
        return glyph.advance + (bold ? glyph.boldOffset : 0);
    }

    float width(List<Run> runs) {
        float width = 0;
        for (Run run : runs) {
            for (int i = 0; i < run.text.length(); ) {
                final int codepoint = run.text.codePointAt(i);
                width += advance(codepoint, run.bold);
                i += Character.charCount(codepoint);
            }
        }
        return width;
    }

    // 最大の幅に収まるところまでで切る (Font.split の最初の行)
    List<Run> truncate(List<Run> runs, float maxWidth) {
        final List<Run> result = new ArrayList<Run>();
        float width = 0;
        for (Run run : runs) {
            final StringBuilder kept = new StringBuilder();
            for (int i = 0; i < run.text.length(); ) {
                final int codepoint = run.text.codePointAt(i);
                final float advance = advance(codepoint, run.bold);
                if (width + advance > maxWidth) {
                    if (kept.length() > 0) result.add(new Run(kept.toString(), run.color, run.bold));
                    return result;
                }
                width += advance;
                kept.appendCodePoint(codepoint);
                i += Character.charCount(codepoint);
            }
            result.add(new Run(kept.toString(), run.color, run.bold));
        }
        return result;
    }

    // ---------- 描く ----------

    /**
     * 看板の片面の文字を絵にする。文字がなければ null。
     *
     * @param lines      行ごとの文字
     * @param lineHeight 行の高さ
     * @param maxWidth   1 行の最大の幅
     * @param color      色を指定していない文字の色
     * @param outline    縁取りの色 (縁取りしなければ -1)
     */
    Sprite render(List<List<Run>> lines, int lineHeight, int maxWidth, int color, int outline) {
        final StringBuilder key = new StringBuilder("sign:").append(lineHeight).append(':').append(maxWidth).append(':')
                .append(color).append(':').append(outline);
        boolean any = false;
        for (List<Run> line : lines) {
            key.append('\n');
            for (Run run : line) {
                key.append(run.color).append(run.bold ? 'b' : 'n').append(run.text).append('\u0000');
                if (!run.text.trim().isEmpty()) any = true;
            }
        }
        if (!any) return null;
        final String id = key.toString();
        final Sprite cached = CACHE.get(id);
        if (cached != null) return cached;

        final int width = HALF_WIDTH * 2 * SCALE, height = HALF_HEIGHT * 2 * SCALE;
        final int[] text = new int[width * height];
        final int midpoint = 4 * lineHeight / 2;
        for (int i = 0; i < lines.size(); i++) {
            final List<Run> line = truncate(lines.get(i), maxWidth);
            float x = -(int) width(line) / 2;
            final float y = i * lineHeight - midpoint;
            for (Run run : line) {
                final int runColor = 0xFF000000 | (run.color != null ? run.color : color);
                for (int c = 0; c < run.text.length(); ) {
                    final int codepoint = run.text.codePointAt(c);
                    c += Character.charCount(codepoint);
                    final Glyph glyph = codepoint == ' ' ? null : glyph(codepoint);
                    if (glyph != null) {
                        draw(text, width, height, glyph, x, y, runColor);
                        if (run.bold) draw(text, width, height, glyph, x + glyph.boldOffset, y, runColor);
                    }
                    x += advance(codepoint, run.bold);
                }
            }
        }
        int[] pixels = text;
        if (outline != -1) {
            // 光る文字の縁取り: 文字のまわり 1 ます (文字の座標で) に縁の色を置き、その上に文字を重ねる
            pixels = new int[text.length];
            for (int py = 0; py < height; py++) {
                for (int px = 0; px < width; px++) {
                    if ((text[py * width + px] >>> 24) == 0) continue;
                    for (int oy = -SCALE; oy <= SCALE; oy += SCALE) {
                        for (int ox = -SCALE; ox <= SCALE; ox += SCALE) {
                            final int nx = px + ox, ny = py + oy;
                            if (nx >= 0 && ny >= 0 && nx < width && ny < height) pixels[ny * width + nx] = 0xFF000000 | outline;
                        }
                    }
                }
            }
            for (int i = 0; i < text.length; i++) {
                if ((text[i] >>> 24) != 0) pixels[i] = text[i];
            }
        }
        final Sprite sprite = Sprite.of(id, width, height, pixels);
        CACHE.put(id, sprite);
        return sprite;
    }

    // 文字の並びを、改行と最大の幅で行に分ける
    List<List<Run>> wrap(List<Run> runs, int maxWidth) {
        final List<List<Run>> lines = new ArrayList<List<Run>>();
        List<Run> line = new ArrayList<Run>();
        float width = 0;
        for (Run run : runs) {
            final StringBuilder part = new StringBuilder();
            for (int i = 0; i < run.text.length(); ) {
                final int codepoint = run.text.codePointAt(i);
                i += Character.charCount(codepoint);
                final float advance = advance(codepoint, run.bold);
                if (codepoint == '\n' || (width + advance > maxWidth && width > 0)) {
                    if (part.length() > 0) line.add(new Run(part.toString(), run.color, run.bold));
                    part.setLength(0);
                    lines.add(line);
                    line = new ArrayList<Run>();
                    width = 0;
                    if (codepoint == '\n') continue;
                }
                part.appendCodePoint(codepoint);
                width += advance;
            }
            if (part.length() > 0) line.add(new Run(part.toString(), run.color, run.bold));
        }
        lines.add(line);
        return lines;
    }

    /**
     * 文字ディスプレイの文字を、背景付きの絵にする (TextDisplayRenderer と同じ並べ方)。
     *
     * @return {絵, 幅, 高さ} (幅と高さは文字の座標。背景の分の 1 を含む)
     */
    Object[] renderBlock(List<Run> runs, int lineWidth, String alignment, int background, int opacity) {
        // 絵が大きくなりすぎないように、幅と行の数に上限を設ける (データは構造物のファイルの中身で決まる)
        List<List<Run>> lines = wrap(runs, Math.max(1, Math.min(MAX_BLOCK_WIDTH, lineWidth)));
        if (lines.size() > MAX_BLOCK_LINES) lines = lines.subList(0, MAX_BLOCK_LINES);
        int textWidth = 0;
        for (List<Run> line : lines) textWidth = Math.max(textWidth, (int) width(line));
        final int lineHeight = 10;
        final int textHeight = lines.size() * lineHeight - 1;
        // 背景は (-1, -1) から (幅, 高さ) まで
        final int boxWidth = textWidth + 1, boxHeight = textHeight + 1;
        final int width = boxWidth * SCALE, height = boxHeight * SCALE;
        final int[] pixels = new int[Math.max(1, width * height)];
        java.util.Arrays.fill(pixels, background);
        final int alpha = Math.max(0, Math.min(255, opacity));
        for (int i = 0; i < lines.size(); i++) {
            final List<Run> line = lines.get(i);
            final float lineWidthUnits = width(line);
            float x = alignment.equals("left") ? 0 : alignment.equals("right") ? textWidth - lineWidthUnits : textWidth / 2f - lineWidthUnits / 2f;
            final float y = i * lineHeight;
            for (Run run : line) {
                final int runColor = alpha << 24 | (run.color != null ? run.color : 0xFFFFFF);
                for (int c = 0; c < run.text.length(); ) {
                    final int codepoint = run.text.codePointAt(c);
                    c += Character.charCount(codepoint);
                    final Glyph glyph = codepoint == ' ' ? null : glyph(codepoint);
                    if (glyph != null) {
                        drawAt(pixels, width, height, glyph, x + 1, y + 1, runColor);
                        if (run.bold) drawAt(pixels, width, height, glyph, x + 1 + glyph.boldOffset, y + 1, runColor);
                    }
                    x += advance(codepoint, run.bold);
                }
            }
        }
        return new Object[]{Sprite.of("text_display:" + System.identityHashCode(pixels), Math.max(1, width), Math.max(1, height), pixels), boxWidth, boxHeight};
    }

    // 左上を (0, 0) とする絵に描く
    private static void drawAt(int[] target, int width, int height, Glyph glyph, float x, float y, int color) {
        draw(target, width, height, glyph, x - HALF_WIDTH, y - HALF_HEIGHT, color);
    }

    private static void draw(int[] target, int width, int height, Glyph glyph, float x, float y, int color) {
        if (glyph.rows != null) {
            // Unifont: 1 ピクセルが文字の座標で 0.5
            for (int row = 0; row < 16; row++) {
                for (int column = glyph.left; column <= glyph.right; column++) {
                    if ((glyph.rows[row] >>> (glyph.bits - 1 - column) & 1) == 0) continue;
                    fill(target, width, height, x + (column - glyph.left) * 0.5f, y + row * 0.5f, 0.5f, 0.5f, color);
                }
            }
            return;
        }
        final float top = y + 7 - glyph.ascent;
        for (int py = 0; py < glyph.height; py++) {
            for (int px = 0; px < glyph.width; px++) {
                final int pixel = glyph.pixels[(glyph.y + py) * glyph.imageWidth + glyph.x + px];
                final int alpha = pixel >>> 24;
                if (alpha == 0) continue;
                // フォントの画像は白で描かれているので、色を掛ける
                final int r = ((pixel >> 16) & 0xFF) * ((color >> 16) & 0xFF) / 255;
                final int g = ((pixel >> 8) & 0xFF) * ((color >> 8) & 0xFF) / 255;
                final int b = (pixel & 0xFF) * (color & 0xFF) / 255;
                fill(target, width, height, x + px * glyph.scale, top + py * glyph.scale, glyph.scale, glyph.scale, alpha << 24 | r << 16 | g << 8 | b);
            }
        }
    }

    // 文字の座標の範囲を塗る
    private static void fill(int[] target, int width, int height, float x, float y, float w, float h, int color) {
        final int x0 = Math.round((x + HALF_WIDTH) * SCALE), y0 = Math.round((y + HALF_HEIGHT) * SCALE);
        final int x1 = Math.round((x + w + HALF_WIDTH) * SCALE), y1 = Math.round((y + h + HALF_HEIGHT) * SCALE);
        for (int py = Math.max(0, y0); py < Math.min(height, y1); py++) {
            for (int px = Math.max(0, x0); px < Math.min(width, x1); px++) target[py * width + px] = color;
        }
    }

    // ---------- 文字のデータ ----------

    static Integer namedColor(String name) {
        if (name == null) return null;
        if (name.startsWith("#")) {
            try {
                return Integer.parseInt(name.substring(1), 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(name)) return COLOR_VALUES[i];
        }
        return null;
    }

    /**
     * 1 行分のデータ (文字そのまま、JSON の文字列、NBT のテキストコンポーネント) を文字の並びにする。
     * 文字の中の § による色と太字の指定も読む。
     */
    static List<Run> parse(Object value) {
        final List<Run> runs = new ArrayList<Run>();
        Object component = value;
        if (value instanceof String) {
            final String text = (String) value;
            final char first = text.isEmpty() ? ' ' : text.charAt(0);
            if (first == '{' || first == '[' || first == '"') {
                try {
                    component = Json.parse(text);
                } catch (IOException e) {
                    component = text;
                }
            }
        }
        // 読む文字の数の残り (MAX_TEXT から減らしていく)
        final int[] left = {MAX_TEXT};
        flatten(component, null, false, runs, left);
        return runs;
    }

    private static void flatten(Object value, Integer color, boolean bold, List<Run> runs, int[] left) {
        // 読み終えたら、残りの要素は見ない (空の文字をたくさん並べたデータで、時間がかからないように)
        if (left[0] <= 0) return;
        if (value instanceof String) {
            addLegacy((String) value, color, bold, runs, left);
        } else if (value instanceof List) {
            final List<?> list = (List<?>) value;
            // リストの最初の要素の書式は、残りの要素にも引き継がれる
            for (Object item : list) {
                if (left[0] <= 0) break;
                flatten(item, color, bold, runs, left);
            }
        } else if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final Object colorValue = map.get("color");
            final Integer own = colorValue instanceof String ? namedColor((String) colorValue) : null;
            final Object boldValue = map.get("bold");
            final boolean ownBold = boldValue instanceof Boolean ? (Boolean) boldValue
                    : boldValue instanceof Number ? ((Number) boldValue).intValue() != 0 : bold;
            final Integer used = own != null ? own : color;
            final Object text = map.containsKey("text") ? map.get("text") : map.get("");
            if (text instanceof String) addLegacy((String) text, used, ownBold, runs, left);
            else if (map.get("translate") instanceof String) addLegacy((String) map.get("translate"), used, ownBold, runs, left);
            flatten(map.get("extra"), used, ownBold, runs, left);
        } else if (value instanceof Number || value instanceof Boolean) {
            addLegacy(String.valueOf(value), color, bold, runs, left);
        }
    }

    // § の書式記号を読みながら加える。left は読む文字の数の残り (書式記号も数える)
    private static void addLegacy(String text, Integer color, boolean bold, List<Run> runs, int[] left) {
        if (left[0] <= 0) return;
        if (text.length() > left[0]) text = text.substring(0, left[0]);
        left[0] -= text.length();
        Integer current = color;
        boolean currentBold = bold;
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (builder.length() > 0) {
                    runs.add(new Run(builder.toString(), current, currentBold));
                    builder.setLength(0);
                }
                final char code = Character.toLowerCase(text.charAt(++i));
                final int index = "0123456789abcdef".indexOf(code);
                if (index >= 0) {
                    current = COLOR_VALUES[index];
                    currentBold = false;
                } else if (code == 'l') {
                    currentBold = true;
                } else if (code == 'r') {
                    current = color;
                    currentBold = bold;
                }
                continue;
            }
            builder.append(c);
        }
        if (builder.length() > 0) runs.add(new Run(builder.toString(), current, currentBold));
    }
}
