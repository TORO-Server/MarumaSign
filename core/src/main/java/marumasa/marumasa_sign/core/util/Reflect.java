package marumasa.marumasa_sign.core.util;

import java.lang.reflect.Field;

/**
 * 名前に頼らないリフレクション。
 * フィールド名は Minecraft のバージョンや実行環境によって変わるため、型で探す。
 */
public final class Reflect {

    private Reflect() {
    }

    // owner で宣言されているフィールドのうち、型が type に代入できる最初のものを返す
    public static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("No field of type " + type.getName() + " in " + owner.getName());
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
