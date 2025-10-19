package org.axl.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.LineMap;
import org.axl.compiler.Translator;

/** Reads back generated .java files and their line maps, for tools that did not generate them themselves. */
public final class GeneratedSources {

    private GeneratedSources() {}

    public static List<GeneratedSource> load(Path generatedDir, Path sourceDir, Function<Path, String> display)
            throws IOException {
        List<GeneratedSource> result = new ArrayList<>();
        if (!Files.isDirectory(generatedDir)) {
            return result;
        }
        List<Path> maps;
        try (Stream<Path> files = Files.walk(generatedDir)) {
            maps = files.filter(p -> p.toString().endsWith(".axlmap")).sorted().toList();
        }
        for (Path mapFile : maps) {
            LineMap lineMap = LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8));
            String name = mapFile.getFileName().toString();
            Path javaFile = mapFile.resolveSibling(name.substring(0, name.length() - ".axlmap".length()) + ".java");
            Path axlFile = sourceDir.resolve(lineMap.source());
            if (!Files.exists(javaFile) || !Files.exists(axlFile)) {
                continue;
            }
            String className = Translator.className(lineMap.source());
            String qualified = generatedDir.relativize(javaFile).toString().replace('\\', '/');
            String packageName = qualified.contains("/") ? qualified.substring(0, qualified.lastIndexOf('/')).replace('/', '.') : "";
            var unit = new GeneratedUnit(lineMap.source(), packageName, className,
                    Files.readString(javaFile, StandardCharsets.UTF_8), lineMap);
            result.add(new GeneratedSource(unit, javaFile, display.apply(axlFile),
                    Files.readString(axlFile, StandardCharsets.UTF_8).lines().toList()));
        }
        return result;
    }
}
