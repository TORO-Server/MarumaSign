package marumasa.marumasa_sign.util;

import java.io.IOException;
import java.util.Arrays;

public class Utils {
    public static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isGif(byte[] bytes) throws IOException {
        byte[] header = Arrays.copyOf(bytes, 6);
        String s = new String(header);
        return s.equals("GIF89a");
    }
}
