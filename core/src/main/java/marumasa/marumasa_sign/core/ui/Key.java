package marumasa.marumasa_sign.core.ui;

/**
 * 画面で使うキー。入力ライブラリごとのキーコードをここで共通の値に変換する。
 */
public final class Key {

    public static final int UNKNOWN = 0;
    public static final int BACKSPACE = 1;
    public static final int DELETE = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    public static final int UP = 5;
    public static final int DOWN = 6;
    public static final int HOME = 7;
    public static final int END = 8;
    public static final int ENTER = 9;
    public static final int ESCAPE = 10;
    public static final int TAB = 11;
    public static final int PAGE_UP = 12;
    public static final int PAGE_DOWN = 13;
    public static final int SPACE = 14;
    public static final int A = 20;
    public static final int C = 21;
    public static final int V = 22;
    public static final int X = 23;

    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;

    private Key() {
    }

    // GLFW (Minecraft 1.13 以降) のキーコードから変換する
    public static int fromGlfw(int key) {
        switch (key) {
            case 259: return BACKSPACE;
            case 261: return DELETE;
            case 263: return LEFT;
            case 262: return RIGHT;
            case 265: return UP;
            case 264: return DOWN;
            case 268: return HOME;
            case 269: return END;
            case 257:
            case 335: return ENTER;
            case 256: return ESCAPE;
            case 258: return TAB;
            case 266: return PAGE_UP;
            case 267: return PAGE_DOWN;
            case 32: return SPACE;
            case 65: return A;
            case 67: return C;
            case 86: return V;
            case 88: return X;
            default: return UNKNOWN;
        }
    }

    // GLFW の修飾キーのビットから変換する (Super は Ctrl として扱う)
    public static int modifiersFromGlfw(int mods) {
        int result = 0;
        if ((mods & 0x1) != 0) result |= MOD_SHIFT;
        if ((mods & 0x2) != 0 || (mods & 0x8) != 0) result |= MOD_CTRL;
        return result;
    }

    // LWJGL 2 (Minecraft 1.12.2 以前) のキーコードから変換する
    public static int fromLwjgl2(int key) {
        switch (key) {
            case 14: return BACKSPACE;
            case 211: return DELETE;
            case 203: return LEFT;
            case 205: return RIGHT;
            case 200: return UP;
            case 208: return DOWN;
            case 199: return HOME;
            case 207: return END;
            case 28:
            case 156: return ENTER;
            case 1: return ESCAPE;
            case 15: return TAB;
            case 201: return PAGE_UP;
            case 209: return PAGE_DOWN;
            case 57: return SPACE;
            case 30: return A;
            case 46: return C;
            case 47: return V;
            case 45: return X;
            default: return UNKNOWN;
        }
    }

    public static int modifiers(boolean shift, boolean ctrl) {
        return (shift ? MOD_SHIFT : 0) | (ctrl ? MOD_CTRL : 0);
    }
}
