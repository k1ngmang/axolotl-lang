package org.axl.gradle;

import org.gradle.api.provider.Property;

/** The `axl { }` block. */
public abstract class AxlExtension {

    /** Adds `org.axl:axl-runtime` to the `implementation` configuration of every source set. */
    public abstract Property<Boolean> getAddRuntime();

    /** Runs Error Prone and NullAway on `compileJava`. */
    public abstract Property<Boolean> getNullAway();

    /**
     * Runs Error Prone and NullAway on the hand-written Java of the source sets too; by default they check
     * only the code translated from .axl files.
     */
    public abstract Property<Boolean> getCheckJava();

    /** `--release` for Java compilation. */
    public abstract Property<Integer> getJavaRelease();

    /** Expands statement-level `?:` without a lambda. */
    public abstract Property<Boolean> getInlineElvis();

    /**
     * Makes compiled classes name the .axl file and its lines in stack traces and debuggers, and adds
     * a JSR-45 SMAP. When off, the classes keep the lines of the generated .java files.
     */
    public abstract Property<Boolean> getSmap();

    /** Treats values from Java code without nullness annotations as nullable. */
    public abstract Property<Boolean> getStrictNull();
}
