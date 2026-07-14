package marumasa.marumasa_sign;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static marumasa.marumasa_sign.MarumaSign.MOD_ID;

public class Config {
    private static final Path path = FabricLoader.getInstance().getConfigDir().normalize().resolve(MOD_ID + ".json");

    private int MaxThreads = 5;

    // 画像の同時ロード数 設定
    public void setMaxThreads(int value) {
        if (value < 1) value = 1;
        if (value > 32) value = 32;
        MaxThreads = value;
        serialize();
    }
 
    // 画像の同時ロード数 追加
    public void addMaxThreads(int value) {
        final int tmp = getMaxThreads() + value;
        setMaxThreads(tmp);
    }
 
    // 画像の同時ロード数 取得
    public int getMaxThreads() {
        return MaxThreads;
    }

    public Config() {
        final File configFile = path.toFile();
        if (!configFile.exists()) {
            serialize();
        } else {
            deserialize();
        }
    }

    private void deserialize() {
        try {
            final JsonModel model = loadJSON();
            if (model != null) {
                int threads = model.MaxThreads();
                if (threads < 1) threads = 1;
                if (threads > 32) threads = 32;
                MaxThreads = threads;
            }
        } catch (Exception e) {
            marumasa.marumasa_sign.MarumaSign.LOGGER.error("Failed to deserialize config", e);
        }
    }
 
    private void serialize() {
        try {
            saveJSON(new JsonModel(getMaxThreads()));
        } catch (Exception e) {
            marumasa.marumasa_sign.MarumaSign.LOGGER.error("Failed to serialize config", e);
        }
    }
 
    private static final Gson gson = new Gson();
 
    private static JsonModel loadJSON() throws IOException {
        try (final BufferedReader reader = Files.newBufferedReader(Config.path)) {
            return gson.fromJson(reader, JsonModel.class);
        }
    }
 
    private static void saveJSON(JsonModel model) throws IOException {
        try (final BufferedWriter writer = Files.newBufferedWriter(Config.path)) {
            gson.toJson(model, model.getClass(), writer);
        }
    }

    private record JsonModel(int MaxThreads) {
    }
}