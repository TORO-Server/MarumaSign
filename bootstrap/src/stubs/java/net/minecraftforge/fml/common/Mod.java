package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * コンパイル用の定義。
 * Forge 1.8 - 1.12.2 は modid を、1.13 以降は value を読むため、両方を持たせている。
 * 実行時には各バージョンの本物のアノテーションが使われ、知らない要素は無視される。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value() default "";

    String modid() default "";

    String name() default "";

    String version() default "";

    boolean clientSideOnly() default false;

    String acceptableRemoteVersions() default "";
}
