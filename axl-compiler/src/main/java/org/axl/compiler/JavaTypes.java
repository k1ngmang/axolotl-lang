package org.axl.compiler;

import java.util.Map;

/**
 * Asks a Java compiler for the types it gives `var` locals. The translator uses it for top-level
 * values whose type depends on library code; build tools pass an implementation with their classpath.
 */
@FunctionalInterface
public interface JavaTypes {

    /** Name of the method that holds the probed values in a probe source. */
    String PROBE_METHOD = "$infer";

    String NULLABLE = "@Nullable ";

    /** Starts the entry of a local whose type is unknown; the rest is the compiler's reason. */
    String ERROR = "error: ";

    /**
     * @param qualifiedClassName the class the source declares
     * @param javaSource         Java source with a static method {@link #PROBE_METHOD} declaring `var` locals
     * @return Java source text of each local's type, starting with {@link #NULLABLE} when the value may be
     *         null; for a local whose type is unknown, {@link #ERROR} and the reason, or nothing
     */
    Map<String, String> localTypes(String qualifiedClassName, String javaSource);
}
