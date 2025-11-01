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

    /**
     * @param qualifiedClassName the class the source declares
     * @param javaSource         Java source with a static method {@link #PROBE_METHOD} declaring `var` locals
     * @return Java source text of each local's type, starting with {@link #NULLABLE} when the value may be
     *         null; locals whose type is unknown are left out
     */
    Map<String, String> localTypes(String qualifiedClassName, String javaSource);
}
