package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.util.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * すべての言語の翻訳ファイルを確かめる。引数は要らない (core のフォルダで動かす)。失敗があれば 1 で終わる。
 * <ul>
 * <li>core の画面の翻訳 (marumasa_sign/lang/*.properties): 正しい UTF-8 で、キーが en_us と同じで重複がなく、
 * 「%d」などの書式が en_us と同じ引数を同じ型で使うこと</li>
 * <li>チャットの翻訳 (chat.properties): すべての言語に同じメッセージがあり、別名の行き先があること</li>
 * <li>コードが使うキー (Lang.get("...")) が en_us にあり、渡す引数の数が書式と合うこと</li>
 * <li>Minecraft のキー割り当ての名前 (dist の assets/marumasa_sign/lang の .json と .lang): 正しい JSON で、両方が同じ内容で、
 * core と同じ言語がそろっていること</li>
 * </ul>
 */
public final class LangCheck {

    private static final File CORE_LANG = new File("src/main/resources/marumasa_sign/lang");
    private static final File DIST_LANG = new File("../dist/src/main/resources/assets/marumasa_sign/lang");
    private static final File SOURCES = new File("src/main/java");
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(<]*\\d*(\\.\\d+)?[a-zA-Z%]");
    private static final Pattern CODE = Pattern.compile("[a-z]{2,3}(_[a-z0-9]{2,4})?|[a-z]{3,4}");
    private static final String DIST_KEY = "key.marumasa_sign.open_menu";

    private static int failures;

    private LangCheck() {
    }

    public static void main(String[] args) throws Exception {
        final Map<String, Map<String, String>> ui = uiFiles();
        final Map<String, String> english = ui.get("en_us");
        check(english != null && !english.isEmpty(), "en_us.properties exists");
        if (english == null) finish();

        for (Map.Entry<String, Map<String, String>> entry : ui.entrySet()) {
            uiLanguage(entry.getKey(), entry.getValue(), english);
        }
        final Set<String> chatLanguages = chat(ui.keySet());
        codeKeys(english);
        dist(ui.keySet());
        runtime(ui.keySet(), chatLanguages, english);
        finish();
    }

    private static void finish() {
        System.out.println(failures == 0 ? "LangCheck: all tests passed" : "LangCheck: " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.out.println("FAIL " + message);
            failures++;
        }
    }

    // UTF-8 として正しくないバイトがあれば失敗にして読む
    private static String read(File file) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
        } catch (CharacterCodingException e) {
            check(false, file + ": not valid UTF-8");
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
    }

    // Lang と同じ読み方 (Properties) で読み、行ごとにも調べる (重複したキー、= のない行、\ を含む値)
    private static Map<String, String> properties(File file) throws IOException {
        final String text = read(file);
        check(!text.startsWith("﻿"), file + ": starts with a BOM (the first key would not be found)");
        final Set<String> seen = new HashSet<String>();
        for (String line : text.split("\r?\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            final int equals = line.indexOf('=');
            check(equals > 0, file.getName() + ": line without '=': " + line);
            if (equals <= 0) continue;
            final String key = line.substring(0, equals).trim();
            check(seen.add(key), file.getName() + ": duplicate key " + key);
            // Properties は \ を特別に扱うので、翻訳に \ があると文字が消える
            check(line.indexOf('\\') < 0, file.getName() + ": backslash in " + key);
        }
        final Properties properties = new Properties();
        final InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            properties.load(reader);
        } finally {
            reader.close();
        }
        final Map<String, String> result = new LinkedHashMap<String, String>();
        for (String name : properties.stringPropertyNames()) result.put(name, properties.getProperty(name));
        return result;
    }

    private static Map<String, Map<String, String>> uiFiles() throws IOException {
        final File[] files = CORE_LANG.listFiles();
        check(files != null && files.length > 100, "language folder " + CORE_LANG + " has the translations");
        final Map<String, Map<String, String>> result = new HashMap<String, Map<String, String>>();
        if (files == null) return result;
        Arrays.sort(files);
        for (File file : files) {
            final String name = file.getName();
            if (!name.endsWith(".properties") || name.equals("chat.properties")) continue;
            final String code = name.substring(0, name.length() - ".properties".length());
            check(CODE.matcher(code).matches(), "language code " + code + " is lowercase like Minecraft's");
            result.put(code, properties(file));
        }
        return result;
    }

    // 書式の引数ごとの型 (引数の番号 → 変換の文字)。%% と %n は引数を使わない
    private static Map<Integer, Character> arguments(String value) {
        final Map<Integer, Character> result = new HashMap<Integer, Character>();
        final Matcher matcher = FORMAT.matcher(value);
        int next = 1;
        while (matcher.find()) {
            final String spec = matcher.group();
            final char conversion = Character.toLowerCase(spec.charAt(spec.length() - 1));
            if (conversion == '%' || conversion == 'n') continue;
            final int index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1).substring(0, matcher.group(1).length() - 1)) : next++;
            result.put(index, conversion);
        }
        return result;
    }

    // en_us の書式の型に合う、見分けやすい値
    private static Object[] sampleArguments(String englishValue) {
        final Map<Integer, Character> types = arguments(englishValue);
        final Object[] result = new Object[types.size()];
        for (int i = 0; i < result.length; i++) {
            final Character type = types.get(i + 1);
            result[i] = type != null && type == 'd' ? (Object) (4321 + i * 1111) : "ARG" + (i + 1);
        }
        return result;
    }

    private static void uiLanguage(String code, Map<String, String> values, Map<String, String> english) {
        final Set<String> missing = new TreeSet<String>(english.keySet());
        missing.removeAll(values.keySet());
        final Set<String> extra = new TreeSet<String>(values.keySet());
        extra.removeAll(english.keySet());
        check(missing.isEmpty(), code + ": missing keys " + missing);
        check(extra.isEmpty(), code + ": keys that en_us does not have " + extra);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            check(!value.trim().isEmpty(), code + ": empty translation for " + key);
            final String englishValue = english.get(key);
            if (englishValue == null) continue;
            check(arguments(value).equals(arguments(englishValue)),
                    code + ": " + key + " uses " + arguments(value) + " but en_us uses " + arguments(englishValue) + ": " + value);
            formats(code + ": " + key, value, sampleArguments(englishValue));
        }
    }

    // 実際に書式を当てはめて、例外にならず、すべての引数が文字列に現れること
    private static void formats(String label, String value, Object[] arguments) {
        try {
            final String formatted = String.format(Locale.ROOT, value, arguments);
            for (Object argument : arguments) {
                check(formatted.contains(String.valueOf(argument)), label + ": argument " + argument + " does not appear in \"" + formatted + "\"");
            }
        } catch (IllegalFormatException e) {
            check(false, label + ": invalid format \"" + value + "\": " + e);
        }
    }

    private static Set<String> chat(Set<String> uiLanguages) throws IOException {
        final Map<String, String> chat = properties(new File(CORE_LANG, "chat.properties"));
        final Map<String, Set<String>> byLanguage = new HashMap<String, Set<String>>();
        final Map<String, String> aliases = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : chat.entrySet()) {
            final String key = entry.getKey();
            if (key.startsWith("alias.")) {
                aliases.put(key.substring("alias.".length()), entry.getValue().trim());
                continue;
            }
            final int dot = key.indexOf('.');
            final String code = dot > 0 ? key.substring(0, dot) : "";
            final String message = dot > 0 ? key.substring(dot + 1) : key;
            check(CODE.matcher(code).matches() && message.startsWith("text."), "chat.properties: unexpected key " + key);
            Set<String> messages = byLanguage.get(code);
            if (messages == null) byLanguage.put(code, messages = new TreeSet<String>());
            messages.add(message);
            check(entry.getValue().indexOf('%') < 0, "chat.properties: chat messages are not formatted, but " + key + " has %");
        }
        final Set<String> english = byLanguage.get("en_us");
        check(english != null && !english.isEmpty(), "chat.properties has en_us messages");
        if (english == null) return byLanguage.keySet();
        for (Map.Entry<String, Set<String>> entry : byLanguage.entrySet()) {
            check(entry.getValue().equals(english), "chat.properties: " + entry.getKey() + " has " + entry.getValue() + " (en_us has " + english + ")");
        }
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            check(byLanguage.containsKey(entry.getValue()), "chat.properties: alias." + entry.getKey() + " points to " + entry.getValue() + ", which has no messages");
            check(!byLanguage.containsKey(entry.getKey()), "chat.properties: alias." + entry.getKey() + " is hidden by its own messages");
        }
        for (String code : uiLanguages) {
            check(byLanguage.containsKey(code) || aliases.containsKey(code), "chat.properties has messages for " + code);
        }
        return byLanguage.keySet();
    }

    // コードが使うキーと、渡している引数の数
    private static void codeKeys(Map<String, String> english) throws IOException {
        final List<File> files = new ArrayList<File>();
        collect(SOURCES, files);
        final Pattern call = Pattern.compile("Lang\\.get\\(\"([^\"]+)\"");
        final Pattern literal = Pattern.compile("\"((?:ui|browser)\\.[a-z0-9_.]+)\"");
        int calls = 0;
        for (File file : files) {
            final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            final Matcher matcher = call.matcher(source);
            while (matcher.find()) {
                calls++;
                final String key = matcher.group(1);
                final String value = english.get(key);
                check(value != null, file.getName() + ": Lang.get(\"" + key + "\") is not in en_us.properties");
                if (value == null) continue;
                final int count = argumentCount(source, matcher.end());
                check(count == arguments(value).size(), file.getName() + ": Lang.get(\"" + key + "\") passes " + count
                        + " argument(s) but the text uses " + arguments(value).size());
            }
            // Lang.get(条件 ? "a" : "b") のように、ほかの場所に書いたキー
            final Matcher keys = literal.matcher(source);
            while (keys.find()) {
                if (source.contains("Lang")) check(english.containsKey(keys.group(1)), file.getName() + ": key " + keys.group(1) + " is not in en_us.properties");
            }
        }
        check(calls > 20, "found the Lang.get calls in the sources (" + calls + ")");
    }

    // Lang.get("key" の直後から、閉じ括弧までのカンマの数 (括弧と文字列の中は数えない)
    private static int argumentCount(String source, int from) {
        int depth = 0, count = 0;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return count;
                depth--;
            } else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static void collect(File directory, List<File> out) {
        final File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collect(file, out);
            else if (file.getName().endsWith(".java")) out.add(file);
        }
    }

    private static void dist(Set<String> uiLanguages) throws IOException {
        final File[] files = DIST_LANG.listFiles();
        check(files != null && files.length > 0, "dist language folder " + DIST_LANG + " exists");
        if (files == null) return;
        final Set<String> json = new TreeSet<String>();
        final Set<String> lang = new TreeSet<String>();
        for (File file : files) {
            final String name = file.getName();
            if (name.endsWith(".json")) {
                final String code = name.substring(0, name.length() - 5);
                json.add(code);
                final String text = read(file);
                check(!text.startsWith("﻿"), name + ": starts with a BOM");
                String value = null;
                try {
                    final Map<String, Object> map = Json.object(Json.parse(text));
                    check(map.keySet().equals(new HashSet<String>(Arrays.asList(DIST_KEY))), name + ": keys are " + map.keySet());
                    value = Json.string(map, DIST_KEY, null);
                } catch (IOException | RuntimeException e) {
                    check(false, name + ": invalid JSON: " + e);
                }
                check(value != null && !value.trim().isEmpty(), name + ": has a name for " + DIST_KEY);
                final File legacy = new File(DIST_LANG, code + ".lang");
                if (legacy.isFile() && value != null) {
                    final Map<String, String> legacyValues = new HashMap<String, String>();
                    for (String line : read(legacy).split("\r?\n")) {
                        if (line.trim().isEmpty() || line.startsWith("#")) continue;
                        final int equals = line.indexOf('=');
                        check(equals > 0, code + ".lang: line without '=': " + line);
                        if (equals > 0) legacyValues.put(line.substring(0, equals), line.substring(equals + 1));
                    }
                    check(legacyValues.size() == 1 && value.equals(legacyValues.get(DIST_KEY)),
                            code + ".lang does not match " + name + ": " + legacyValues);
                }
            } else if (name.endsWith(".lang")) {
                lang.add(name.substring(0, name.length() - 5));
            } else {
                check(false, "unexpected file in the dist language folder: " + name);
            }
        }
        check(json.equals(lang), "every .json has a .lang (1.12 and older) and the other way round: " + symmetricDifference(json, lang));
        check(json.equals(uiLanguages), "the dist languages match the core languages: " + symmetricDifference(json, uiLanguages));
    }

    private static Set<String> symmetricDifference(Set<String> a, Set<String> b) {
        final Set<String> result = new TreeSet<String>(a);
        result.addAll(b);
        final Set<String> both = new HashSet<String>(a);
        both.retainAll(b);
        result.removeAll(both);
        return result;
    }

    // Lang で実際に引く。どの言語でも、地域の設定 (トルコ語の i など) が何であっても、例外にならず、キーそのものが出ない
    private static void runtime(Set<String> uiLanguages, Set<String> chatLanguages, Map<String, String> english) {
        final Locale saved = Locale.getDefault();
        final Set<String> codes = new TreeSet<String>(uiLanguages);
        codes.addAll(chatLanguages);
        try {
            for (Locale locale : new Locale[]{new Locale("tr", "TR"), new Locale("ar", "SA"), new Locale("th", "TH", "TH"), Locale.GERMANY}) {
                Locale.setDefault(locale);
                for (String code : codes) {
                    Lang.select(code.toUpperCase(Locale.ROOT));
                    for (Map.Entry<String, String> entry : english.entrySet()) {
                        final Object[] arguments = sampleArguments(entry.getValue());
                        try {
                            final String text = Lang.get(entry.getKey(), arguments);
                            check(!text.equals(entry.getKey()), code + ": Lang.get(" + entry.getKey() + ") returns the key");
                            for (Object argument : arguments) {
                                check(text.contains(String.valueOf(argument)), code + " in " + locale + ": " + entry.getKey() + " lost argument " + argument + ": " + text);
                            }
                        } catch (RuntimeException e) {
                            check(false, code + " in " + locale + ": Lang.get(" + entry.getKey() + ") throws " + e);
                        }
                    }
                    for (String message : new String[]{"text.write_mode", "text.write_done", "text.ffmpeg_missing"}) {
                        check(!Lang.get(message).equals(message), code + ": chat message " + message + " is missing");
                    }
                }
            }
        } finally {
            Locale.setDefault(saved);
            Lang.select("en_us");
        }
    }
}
