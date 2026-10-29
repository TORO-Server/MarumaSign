package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.gltf.GltfRuntimeCheck;
import marumasa.marumasa_sign.core.sign.SignSpec;
import marumasa.marumasa_sign.core.splat.SplatRuntimeCheck;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.NumberField;
import marumasa.marumasa_sign.core.ui.TextField;
import marumasa.marumasa_sign.core.ui.Theme;
import marumasa.marumasa_sign.core.util.Json;
import marumasa.marumasa_sign.core.util.OptionsFile;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * 読み込んだあとに動く部分 (3D モデルの姿勢、スプラットの描き方、設定、入力欄、看板の文字列) が、
 * 仕様どおりの値を出すことを確かめる。引数は要らない。失敗があれば 1 で終わる。
 */
public final class RuntimeLogicCheck {

    private static final String EMOJI = "\uD83D\uDE00";

    private static int failures;

    private RuntimeLogicCheck() {
    }

    public static void main(String[] args) throws Exception {
        failures += GltfRuntimeCheck.run();
        failures += SplatRuntimeCheck.run();
        textField();
        numberField();
        final Locale saved = Locale.getDefault();
        try {
            // 小数点がコンマの地域や、数字が違う地域でも、看板の文字列と設定の書き方は変わらない
            for (Locale locale : new Locale[]{Locale.ROOT, Locale.GERMANY, Locale.FRANCE, new Locale("ar", "SA"), new Locale("th", "TH", "TH"), new Locale("tr", "TR")}) {
                Locale.setDefault(locale);
                signSpec(locale);
            }
        } finally {
            Locale.setDefault(saved);
        }
        splitLines();
        config();
        optionsFile();
        System.out.println(failures == 0 ? "RuntimeLogicCheck: all tests passed" : "RuntimeLogicCheck: " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    // 対になっていないサロゲートがあるかどうか
    private static boolean broken(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    // 貼り付けと同じ処理 (insert) を、画面なしで呼べるようにしたもの
    private static final class Field extends TextField {
        void paste(String text) {
            insert(text);
        }

        Field keys(int... keys) {
            for (int key : keys) keyPressed(key, 0);
            return this;
        }

        Field type(String text) {
            for (int i = 0; i < text.length(); i++) charTyped(text.charAt(i));
            return this;
        }
    }

    private static Field field(String text) {
        final Field field = new Field();
        field.setText(text);
        return field;
    }

    // キャレットの移動と削除は、サロゲートペアや結合文字の途中で止まらない
    private static void textField() {
        check(field("a" + EMOJI + "b").keys(Key.LEFT, Key.LEFT).type("X").getText().equals("aX" + EMOJI + "b"), "text field: left arrow skips a whole emoji");
        check(field("a" + EMOJI).keys(Key.BACKSPACE).getText().equals("a"), "text field: backspace removes a whole emoji");
        check(field(EMOJI + "b").keys(Key.HOME, Key.DELETE).getText().equals("b"), "text field: delete removes a whole emoji");
        check(field("e\u0301x").keys(Key.LEFT, Key.BACKSPACE).getText().equals("x"), "text field: backspace removes a letter with its combining accent");
        check(field("e\u0301x").keys(Key.END, Key.LEFT, Key.LEFT).type("Z").getText().equals("Ze\u0301x"), "text field: arrows skip combining accents");
        final Field select = field(EMOJI + "b");
        select.keys(Key.HOME);
        select.keyPressed(Key.RIGHT, Key.MOD_SHIFT);
        check(select.type("Q").getText().equals("Qb"), "text field: shift + right selects a whole emoji");
        check(field("").type(EMOJI).getText().equals(EMOJI), "text field: an emoji typed as two characters");

        final Field limited = new Field();
        limited.maxLength(3);
        limited.setText("ab" + EMOJI);
        check(limited.getText().equals("ab"), "text field: setText does not keep half of an emoji at the length limit: " + Arrays.toString(limited.getText().toCharArray()));
        limited.paste(EMOJI + EMOJI);
        check(limited.getText().equals("ab"), "text field: pasting does not keep half of an emoji at the length limit");
        limited.type(EMOJI);
        check(!broken(limited.getText()), "text field: typing an emoji at the length limit");

        // とても長い文字列の貼り付けは、上限で切られ、すぐに終わる
        final char[] huge = new char[5_000_000];
        Arrays.fill(huge, 'x');
        final Field big = field("");
        final long start = System.nanoTime();
        big.paste(new String(huge));
        final long millis = (System.nanoTime() - start) / 1_000_000;
        check(big.getText().length() == 2048 && millis < 2000, "text field: huge paste is cut at the limit (" + big.getText().length() + " characters, " + millis + " ms)");

        textFieldScroll();

        // 幅に収まらない文字列を「...」で省略するときも、絵文字を半分にしない
        final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        boolean whole = true;
        for (int width = 0; width < 150; width++) whole &= !broken(Theme.ellipsize(g, "ab" + EMOJI + EMOJI + EMOJI + EMOJI + "cdefgh", width));
        g.dispose();
        check(whole, "ellipsized text keeps emoji whole");
    }

    // 入力欄を描き (フォーカスあり)、左端 (文字の始まる位置) をクリックしたときのキャレットの位置に "|" を入れた文字列を返す
    private static String clickLeftEdge(Field field) {
        final Graphics2D g = new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            field.paint(g, false, true);
            field.mousePressed(field.x + 4, field.y + 10, false);
            field.charTyped('|');
            return field.getText();
        } finally {
            g.dispose();
        }
    }

    // 長い文字列が短くなったとき (置き換えや削除)、文字が左に隠れたままにならない
    private static void textFieldScroll() {
        final StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) longText.append('w');

        final Field replaced = field(longText.toString());
        replaced.bounds(0, 0, 100, 21);
        replaced.paint(new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB).createGraphics(), false, true);
        replaced.selectAll();
        replaced.type("ab");
        final String afterReplace = clickLeftEdge(replaced);
        check(afterReplace.equals("|ab"), "text field: short text that replaced a long one is not scrolled out of view: " + afterReplace);

        final Field deleted = field(longText.toString());
        deleted.bounds(0, 0, 100, 21);
        deleted.paint(new BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB).createGraphics(), false, true);
        for (int i = 0; i < 57; i++) deleted.keys(Key.BACKSPACE);
        final String afterDelete = clickLeftEdge(deleted);
        check(afterDelete.equals("|www"), "text field: text left after backspace is not scrolled out of view: " + afterDelete);
    }

    // 小数点がコンマの地域では、コンマも小数点として受け付ける
    private static void numberField() {
        final Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            final NumberField german = new NumberField(0, -1000, 1000, 0.5f, false);
            german.selectAll();
            for (char c : "1,5".toCharArray()) german.charTyped(c);
            check(german.getValue() == 1.5f, "number field: 1,5 in a German locale is " + german.getValue());
            german.setValue(2.25f);
            check(german.getText().equals("2.25"), "number field: shows " + german.getText());

            Locale.setDefault(Locale.US);
            final NumberField english = new NumberField(0, -1000, 1000, 0.5f, false);
            english.selectAll();
            for (char c : "1,000".toCharArray()) english.charTyped(c);
            check(english.getValue() == 1000f, "number field: 1,000 in an English locale is " + english.getValue());

            final NumberField integer = new NumberField(5, 1, 32, 1, true);
            integer.selectAll();
            for (char c : "1,5".toCharArray()) integer.charTyped(c);
            check(integer.getValue() == 15f, "number field: integers ignore the comma: " + integer.getValue());
        } finally {
            Locale.setDefault(saved);
        }
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 0.0005f;
    }

    // 看板の文字列は、書いてから読み直すと同じ値になる
    private static void signSpec(Locale locale) {
        final SignSpec spec = new SignSpec("https://example.com/a|b.png?x=1", 1.5f, -0.25f, 1000f, 2f, 0.001f, 45.125f, -90f, -0.0004f);
        final String text = spec.toText();
        final SignSpec back = SignSpec.parse(text);
        check(back != null && back.url.equals("https://example.com/a%7Cb.png?x=1")
                        && close(back.x, 1.5f) && close(back.y, -0.25f) && close(back.z, 1000f) && close(back.scaleX, 2f)
                        && close(back.scaleY, 0.001f) && close(back.roll, 45.125f) && close(back.yaw, -90f) && close(back.pitch, 0f),
                "sign text round trip in " + locale + ": " + text);
        check(SignSpec.number(-0.0004f).equals("0") && SignSpec.number(0.5f).equals(".5") && SignSpec.number(-1.25f).equals("-1.25")
                && SignSpec.number(1e9f).equals("1000000000"), "short numbers in " + locale + ": " + SignSpec.number(-0.0004f));
        check(Json.write("\u0001\u00e9").equals("\"\\u0001\\u00e9\""), "JSON escapes in " + locale + ": " + Json.write("\u0001\u00e9"));
    }

    // 看板の 4 行に分けるとき、サロゲートペアを 2 行に分けない
    private static void splitLines() {
        final String[] texts = {"ab" + EMOJI + "cdefg", EMOJI + EMOJI + EMOJI + EMOJI, "a" + EMOJI, EMOJI + "abcdef" + EMOJI, "abcdefgh"};
        for (String text : texts) {
            for (int max = 1; max <= 6; max++) {
                final String[] lines = SignSpec.splitLines(text, max);
                if (lines == null) continue;
                boolean ok = lines.length == 4;
                final StringBuilder joined = new StringBuilder();
                for (String line : lines) {
                    ok &= !broken(line) && line.length() <= max;
                    joined.append(line);
                }
                ok &= joined.toString().equals(text);
                check(ok, "sign lines keep emoji whole: " + Arrays.toString(lines) + " (max " + max + ")");
            }
        }
        check(SignSpec.splitLines("abcdefgh", 2) != null && SignSpec.splitLines("abcdefghi", 2) == null, "sign lines: length limit");
    }

    private static File temporaryDirectory() throws IOException {
        final File directory = Files.createTempDirectory("marumasa-config").toFile();
        directory.deleteOnExit();
        return directory;
    }

    private static void config() throws IOException {
        final File game = temporaryDirectory();
        final Config config = new Config(game);
        final String directory = "C:\\Users\\\"x\"\\\n\u00e9\u65e5" + EMOJI;
        config.setLastDirectory(directory);
        config.setVolume(35);
        config.setMaxThreads(7);
        final File file = new File(new File(game, "config"), MarumaSign.MOD_ID + ".json");
        final Config reread = new Config(game);
        check(reread.getLastDirectory().equals(directory) && reread.getVolume() == 35 && reread.getMaxThreads() == 7,
                "config round trip of the folder, volume and threads");
        boolean valid;
        try {
            Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            valid = true;
        } catch (IOException e) {
            valid = false;
        }
        check(valid, "config file is valid JSON");
        final File[] files = file.getParentFile().listFiles();
        check(files != null && files.length == 1, "config: no temporary file is left: " + Arrays.toString(files));

        // 壊れた値があっても、ほかの項目は読む
        Files.write(file.toPath(), "{\"MaxThreads\":99999999999999999999,\"Volume\":50,\"LastDirectory\":\"D:\\\\x\"}".getBytes(StandardCharsets.UTF_8));
        final Config broken = new Config(game);
        check(broken.getMaxThreads() == Config.MAX_THREADS && broken.getVolume() == 50 && broken.getLastDirectory().equals("D:\\x"),
                "config: a huge number does not stop the other values: " + broken.getMaxThreads() + ", " + broken.getVolume() + ", " + broken.getLastDirectory());
        Files.write(file.toPath(), "{\"MaxThreads\":-3,\"Vol".getBytes(StandardCharsets.UTF_8));
        final Config cut = new Config(game);
        check(cut.getMaxThreads() == Config.MIN_THREADS && cut.getVolume() == 100, "config: a file cut in the middle");
    }

    private static void optionsFile() throws IOException {
        final File game = temporaryDirectory();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.write("version:1343\r\nlastServer:".getBytes(StandardCharsets.US_ASCII));
        // Shift_JIS で書かれたサーバーの名前 (UTF-8 としては正しくない)
        out.write(new byte[]{(byte) 0x83, (byte) 0x54, (byte) 0x81, (byte) 0x5B, (byte) 0x83, (byte) 0x6F});
        out.write("\r\nkey_MarumaSign:key.keyboard.k \r\nkey_key.chat:key.keyboard.t\r\n".getBytes(StandardCharsets.US_ASCII));
        final File file = new File(game, "options.txt");
        Files.write(file.toPath(), out.toByteArray());
        check("key.keyboard.k".equals(OptionsFile.read(game, "key_MarumaSign")), "options.txt with bytes that are not UTF-8: " + OptionsFile.read(game, "key_MarumaSign"));
        check("1343".equals(OptionsFile.read(game, "version")), "options.txt: first line after a BOM");
        check(OptionsFile.read(game, "key_missing") == null, "options.txt: missing key");
        file.delete();
    }
}
