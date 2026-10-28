package marumasa.marumasa_sign.core.structure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 古い形式や統合版のブロックを、Java 版のブロックの状態に直すための表。
 * 表は minecraft-data (MIT) から tools/blockdata/gen_tables.py で作る。
 */
final class BlockTables {

    private static volatile Map<Integer, BlockState> legacy;
    private static volatile Map<String, Integer> legacyIds;
    private static volatile Map<String, BlockState> bedrock;
    // 統合版のブロックの名前ごとの、表に載っている状態
    private static volatile Map<String, Candidates> bedrockByName;

    private BlockTables() {
    }

    private static BufferedReader open(String name) throws IOException {
        final InputStream raw = BlockTables.class.getResourceAsStream("/marumasa_sign/structure/" + name);
        if (raw == null) throw new IOException("Missing table: " + name);
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
    }

    private static synchronized void loadLegacy() throws IOException {
        if (legacy != null) return;
        final Map<Integer, BlockState> states = new HashMap<Integer, BlockState>();
        final Map<String, Integer> ids = new HashMap<String, Integer>();
        final BufferedReader reader = open("legacy.txt.gz");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                final String[] parts = line.split(" ");
                if (parts[0].equals("N")) {
                    ids.put(parts[1], Integer.parseInt(parts[2]));
                    continue;
                }
                final int colon = parts[0].indexOf(':');
                final int id = Integer.parseInt(parts[0].substring(0, colon));
                final int data = Integer.parseInt(parts[0].substring(colon + 1));
                states.put(id << 4 | data, BlockState.parse(parts[1]));
            }
        } finally {
            reader.close();
        }
        legacyIds = ids;
        legacy = states;
    }

    /**
     * 1.12 までの番号とデータ値から、1.13 のブロックの状態を返す。
     * データ値に対応するものがなければ、データ値 0 のもの。番号自体を知らなければ null。
     */
    static BlockState legacy(int id, int data) throws IOException {
        if (legacy == null) loadLegacy();
        BlockState state = legacy.get(id << 4 | (data & 15));
        if (state == null) state = legacy.get(id << 4);
        return state;
    }

    // 1.12 のブロックの名前 (minecraft:stone など) から番号を返す。知らなければ -1
    static int legacyId(String name) throws IOException {
        if (legacy == null) loadLegacy();
        final Integer id = legacyIds.get(BlockState.namespaced(name));
        return id == null ? -1 : id;
    }

    private static synchronized void loadBedrock() throws IOException {
        if (bedrock != null) return;
        final Map<String, BlockState> table = new HashMap<String, BlockState>();
        final Map<String, List<BlockState[]>> lists = new HashMap<String, List<BlockState[]>>();
        final BufferedReader reader = open("bedrock.txt.gz");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                final int space = line.indexOf(' ');
                final String key = line.substring(0, space);
                final BlockState java = BlockState.parse(line.substring(space + 1));
                table.put(key, java);
                final BlockState parsed = BlockState.parse(key);
                List<BlockState[]> list = lists.get(parsed.name);
                if (list == null) {
                    list = new ArrayList<BlockState[]>();
                    lists.put(parsed.name, list);
                }
                list.add(new BlockState[]{parsed, java});
            }
        } finally {
            reader.close();
        }
        final Map<String, Candidates> byName = new HashMap<String, Candidates>();
        for (Map.Entry<String, List<BlockState[]>> entry : lists.entrySet()) byName.put(entry.getKey(), new Candidates(entry.getValue()));
        bedrockByName = byName;
        bedrock = table;
    }

    /**
     * 統合版のブロックの名前 1 つの、表に載っている状態の一覧。
     * 近いものを探すのに、状態を 1 つずつ比べると、状態の多いブロック (2000 以上) を値だけ変えてたくさん並べた壊れたファイルで
     * 時間がかかるので、性質と値ごとに、それを持つ状態の番号を引けるようにしておく。
     */
    private static final class Candidates {
        // 表の順の Java 版の状態と、それぞれの性質の数
        final BlockState[] java;
        final int[] propertyCount;
        // 性質の名前 -> それを持つ状態の番号
        final Map<String, int[]> withKey = new HashMap<String, int[]>();
        // 性質の名前 -> 値 -> その値を持つ状態の番号
        final Map<String, Map<String, int[]>> withValue = new HashMap<String, Map<String, int[]>>();

        Candidates(List<BlockState[]> list) {
            java = new BlockState[list.size()];
            propertyCount = new int[list.size()];
            final Map<String, List<Integer>> keys = new HashMap<String, List<Integer>>();
            final Map<String, Map<String, List<Integer>>> values = new HashMap<String, Map<String, List<Integer>>>();
            for (int c = 0; c < list.size(); c++) {
                final BlockState parsed = list.get(c)[0];
                java[c] = list.get(c)[1];
                propertyCount[c] = parsed.size();
                for (int i = 0; i < parsed.size(); i++) {
                    List<Integer> withKeyList = keys.get(parsed.key(i));
                    if (withKeyList == null) {
                        withKeyList = new ArrayList<Integer>();
                        keys.put(parsed.key(i), withKeyList);
                    }
                    withKeyList.add(c);
                    Map<String, List<Integer>> byValue = values.get(parsed.key(i));
                    if (byValue == null) {
                        byValue = new HashMap<String, List<Integer>>();
                        values.put(parsed.key(i), byValue);
                    }
                    List<Integer> withValueList = byValue.get(parsed.value(i));
                    if (withValueList == null) {
                        withValueList = new ArrayList<Integer>();
                        byValue.put(parsed.value(i), withValueList);
                    }
                    withValueList.add(c);
                }
            }
            for (Map.Entry<String, List<Integer>> entry : keys.entrySet()) withKey.put(entry.getKey(), toArray(entry.getValue()));
            for (Map.Entry<String, Map<String, List<Integer>>> entry : values.entrySet()) {
                final Map<String, int[]> byValue = new HashMap<String, int[]>();
                for (Map.Entry<String, List<Integer>> value : entry.getValue().entrySet()) byValue.put(value.getKey(), toArray(value.getValue()));
                withValue.put(entry.getKey(), byValue);
            }
        }

        private static int[] toArray(List<Integer> list) {
            final int[] result = new int[list.size()];
            for (int i = 0; i < result.length; i++) result[i] = list.get(i);
            return result;
        }

        /**
         * 値が最も多く一致する状態 (同じなら表で先のもの)。状態ごとの点数は、性質の値が一致すれば 2、性質がなければ -1。
         * 点数が 0 以上のものがなければ null。
         */
        BlockState nearest(Map<String, String> states) {
            // 点数 = 2 x 一致した数 - (性質の数 - 書かれていた性質の数)
            final int[] score = new int[java.length];
            for (int c = 0; c < score.length; c++) score[c] = -propertyCount[c];
            for (Map.Entry<String, String> entry : states.entrySet()) {
                final int[] present = withKey.get(entry.getKey());
                if (present == null) continue;
                for (int c : present) score[c]++;
                final int[] matching = withValue.get(entry.getKey()).get(entry.getValue());
                if (matching == null) continue;
                for (int c : matching) score[c] += 2;
            }
            int best = -1, bestScore = -1;
            for (int c = 0; c < score.length; c++) {
                if (score[c] > bestScore) {
                    bestScore = score[c];
                    best = c;
                }
            }
            return best < 0 ? null : java[best];
        }
    }

    /**
     * 統合版のブロック (名前と、名前の順に並べた状態の値) から、Java 版の状態を返す。
     * 完全に一致するものがなければ、同じ名前で値が最も多く一致するもの。名前を知らなければ null。
     */
    static BlockState bedrock(String name, Map<String, String> states) throws IOException {
        if (bedrock == null) loadBedrock();
        final String key = BlockState.of(name, states).toString();
        final BlockState exact = bedrock.get(key);
        if (exact != null) return exact;
        final Candidates candidates = bedrockByName.get(BlockState.namespaced(name));
        return candidates == null ? null : candidates.nearest(states);
    }
}
