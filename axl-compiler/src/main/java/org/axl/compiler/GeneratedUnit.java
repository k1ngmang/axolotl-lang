package org.axl.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Java translation of one .axl file.
 *
 * @param sourcePath path of the .axl file relative to its source root, with `/` separators
 */
public record GeneratedUnit(String sourcePath, String packageName, String className, String javaSource,
                            LineMap lineMap) {

    public String qualifiedName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /** Path of the .java file relative to the output root. */
    public String javaPath() {
        return qualifiedName().replace('.', '/') + ".java";
    }

    public String mapPath() {
        return qualifiedName().replace('.', '/') + ".axlmap";
    }

    /** Writes the .java file and its line map under outputRoot and returns the .java path. */
    public Path writeTo(Path outputRoot) throws IOException {
        Path java = outputRoot.resolve(javaPath());
        Files.createDirectories(java.getParent());
        Files.writeString(java, javaSource, StandardCharsets.UTF_8);
        Files.writeString(outputRoot.resolve(mapPath()), lineMap.toJson(), StandardCharsets.UTF_8);
        return java;
    }
}
