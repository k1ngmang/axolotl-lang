package org.axl.gradle;

import org.gradle.api.provider.Property;

/** The `axl { }` block. */
public abstract class AxlExtension {

    /** Adds `org.axl:axl-runtime` to the `implementation` configuration of every source set. */
    public abstract Property<Boolean> getAddRuntime();

    /** Runs Error Prone and NullAway on `compileJava`. */
    public abstract Property<Boolean> getNullAway();

    /** `--release` for Java compilation. */
    public abstract Property<Integer> getJavaRelease();

    /** Expands statement-level `?:` without a lambda. */
    public abstract Property<Boolean> getInlineElvis();

    /** Adds a JSR-45 SMAP to compiled classes so that debuggers show .axl lines. */
    public abstract Property<Boolean> getSmap();

    /** Treats values from Java code without nullness annotations as nullable. */
    public abstract Property<Boolean> getStrictNull();
}
