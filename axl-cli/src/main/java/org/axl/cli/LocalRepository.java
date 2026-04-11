package org.axl.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** The local Maven repository, chosen as Maven chooses it. */
final class LocalRepository {

    private static final String PROPERTY = "maven.repo.local";
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(env\\.)?([^}]+)}");

    private LocalRepository() {}

    static Path find() {
        return find(Path.of(System.getProperty("user.home")), System.getProperty(PROPERTY), System.getenv("MAVEN_OPTS"));
    }

    /** -Dmaven.repo.local, also in MAVEN_OPTS, then the localRepository of ~/.m2/settings.xml. */
    static Path find(Path userHome, @Nullable String property, @Nullable String mavenOpts) {
        String configured = property != null ? property : fromMavenOpts(mavenOpts);
        if (configured == null) {
            configured = fromSettings(userHome.resolve(".m2").resolve("settings.xml"), userHome);
        }
        if (configured == null || configured.isBlank()) {
            return userHome.resolve(".m2").resolve("repository");
        }
        return Path.of(configured);
    }

    private static @Nullable String fromMavenOpts(@Nullable String mavenOpts) {
        if (mavenOpts == null) {
            return null;
        }
        String prefix = "-D" + PROPERTY + "=";
        for (String option : mavenOpts.trim().split("\\s+")) {
            if (option.startsWith(prefix)) {
                return option.substring(prefix.length());
            }
        }
        return null;
    }

    private static @Nullable String fromSettings(Path settings, Path userHome) {
        if (!Files.isRegularFile(settings)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Without a handler the parser prints its own copy of the error.
            builder.setErrorHandler(new DefaultHandler());
            Element root = builder.parse(settings.toFile()).getDocumentElement();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeName().equals("localRepository")) {
                    return expand(child.getTextContent().trim(), userHome);
                }
            }
            return null;
        } catch (IOException | ParserConfigurationException | SAXException e) {
            // Maven itself stops on a broken settings.xml and says why; here the default is good enough.
            System.err.println("axl: warning: cannot read " + settings + ": " + e.getMessage());
            return null;
        }
    }

    /** ${user.home} and ${env.NAME}, the variables a settings.xml uses for its paths. */
    private static String expand(String value, Path userHome) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(2);
            String replacement = matcher.group(1) != null ? System.getenv(name)
                    : name.equals("user.home") ? userHome.toString() : System.getProperty(name);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        return matcher.appendTail(result).toString();
    }
}
