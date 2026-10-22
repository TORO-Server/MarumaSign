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
    private static volatile Map<String, List<String>> bedrockByName;

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
        final Map<String, List<String>> byName = new HashMap<String, List<String>>();
        final BufferedReader reader = open("bedrock.txt.gz");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                final int space = line.indexOf(' ');
                final String key = line.substring(0, space);
                table.put(key, BlockState.parse(line.substring(space + 1)));
                final int bracket = key.indexOf('[');
                final String name = bracket < 0 ? key : key.substring(0, bracket);
                List<String> list = byName.get(name);
                if (list == null) {
                    list = new ArrayList<String>();
                    byName.put(name, list);
                }
                list.add(key);
            }
        } finally {
            reader.close();
        }
        bedrockByName = byName;
        bedrock = table;
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
        final List<String> candidates = bedrockByName.get(BlockState.namespaced(name));
        if (candidates == null) return null;
        String best = null;
        int bestScore = -1;
        for (String candidate : candidates) {
            final BlockState parsed = BlockState.parse(candidate);
            int score = 0;
            for (int i = 0; i < parsed.size(); i++) {
                final String value = states.get(parsed.key(i));
                if (value != null && value.equals(parsed.value(i))) score += 2;
                else if (value == null) score -= 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? null : bedrock.get(best);
    }
}
