package marumasa.marumasa_sign.core.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    // instance のフィールドのうち、いま value そのものを保持しているものを返す
    public static Field fieldHolding(Object instance, Object value) {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                try {
                    if (field.get(instance) == value) return field;
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("No field holding the value in " + instance.getClass().getName());
    }

    // owner の public なメソッドのうち、引数の型が parameters と一致する最初のものを返す (戻り値の型は問わない)。
    // 近いバージョンの間で、戻り値の型だけが違うメソッドを呼ぶのに使う
    public static Method methodWithParameters(Class<?> owner, Class<?>... parameters) {
        for (Method method : owner.getMethods()) {
            if (java.util.Arrays.equals(method.getParameterTypes(), parameters)) return method;
        }
        throw new IllegalStateException("No method taking " + java.util.Arrays.toString(parameters) + " in " + owner.getName());
    }

    public static Object invoke(Method method, Object instance, Object... arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // 引数の数が arguments と同じ public なコンストラクタで作る。そのようなコンストラクタがなければ null。
    // 近いバージョンの間で、引数の数が違うコンストラクタを呼ぶのに使う
    @SuppressWarnings("unchecked")
    public static <T> T construct(Class<T> type, Object... arguments) {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterTypes().length != arguments.length) continue;
            try {
                return (T) constructor.newInstance(arguments);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    public static void set(Field field, Object instance, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * static final のフィールドを書き換える。
     * 普通のリフレクションでは書き換えられないか、Java のバージョンによって警告が出るため、
     * JDK が用意しているメモリの直接操作 (sun.misc.Unsafe) を使う。このクラスは古い Java では
     * コンパイル時に参照できないので、名前で呼び出す (JDK のクラスなので、名前は実行環境によって変わらない)。
     * 実行中のコードが値を定数として取り込む前に、できるだけ早い時点で呼ぶこと。
     */
    public static void setStaticFinal(Field field, Object value) {
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final Field holder = unsafeClass.getDeclaredField("theUnsafe");
            holder.setAccessible(true);
            final Object unsafe = holder.get(null);
            final Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
            final Object offset = unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
            unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class).invoke(unsafe, base, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
