package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub. Legacy FML (1.8-1.12) reads {@code modid}; MinecraftForge 1.13+ reads {@code value}. Setting both
 * on one class makes it load on both.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value() default "";

    String modid() default "";

    String name() default "";

    String version() default "";

    String acceptedMinecraftVersions() default "";

    boolean clientSideOnly() default false;
}
