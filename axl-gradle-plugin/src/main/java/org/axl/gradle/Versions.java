package org.axl.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Versions of the artifacts the plugin adds to a build, fixed when the plugin is built. */
final class Versions {

    private static final Properties PROPERTIES = load();

    static final String AXL = PROPERTIES.getProperty("axl");
    static final String ERROR_PRONE = PROPERTIES.getProperty("errorprone");
    static final String NULLAWAY = PROPERTIES.getProperty("nullaway");

    private Versions() {}

    private static Properties load() {
        try (InputStream in = Versions.class.getResourceAsStream("versions.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
