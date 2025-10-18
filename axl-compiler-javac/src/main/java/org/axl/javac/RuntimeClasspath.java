package org.axl.javac;

import axl.lang.Result;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Locates the axl-runtime and JSpecify jars (or class directories) this tool was started with. */
public final class RuntimeClasspath {

    private RuntimeClasspath() {}

    public static List<Path> entries() {
        return List.of(locationOf(Result.class), locationOf(Nullable.class));
    }

    static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate " + type.getName(), e);
        }
    }
}
