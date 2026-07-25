package org.axl.smap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.axl.compiler.LineMap;
import org.axl.javac.AxlBuild;

/** Compiles a small program and adds SMAPs, as the build plugins do. */
final class Fixture {

    static final String MAIN = """
            package app

            fn parse(s: String) -> Int = Integer.parseInt(s)

            fn checked(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else "bad"

            fn sum(a: String, b: String) -> Result<Int, String> {
                return Ok(try checked(a) + try checked(b))
            }

            fn main() {
                val text = Joiner("4").join("2")
                println(parse(text))
                println(sum("1", "2"))
                println(parse("x"))
            }

            class Joiner(val head: String) {
                fn join(tail: String) -> String {
                    return head + tail
                }
            }
            """;

    final Path sources;
    final Path generated;
    final Path classes;

    Fixture(Path dir) throws IOException {
        sources = Files.createDirectories(dir.resolve("src"));
        generated = dir.resolve("gen");
        classes = dir.resolve("classes");
        Files.createDirectories(sources.resolve("app"));
        Files.writeString(sources.resolve("app/Main.axl"), MAIN);
        AxlBuild.Result result = new AxlBuild(sources, generated, classes).nullChecks(false).run();
        if (!result.success()) {
            throw new IllegalStateException(result.diagnostics().toString());
        }
    }

    int inject() throws IOException {
        return SmapInjector.inject(classes, generated);
    }

    /** Adds a SMAP the way earlier versions did, with the generated Java lines in the class. */
    void injectSmapOnly() throws IOException {
        LineMap lineMap = LineMap.fromJson(Files.readString(generated.resolve("app/Main.axlmap")));
        String smap = Smap.of(lineMap, "Main.java").format();
        for (String name : new String[] {"app/Main.class", "app/Main$Joiner.class"}) {
            Path classFile = classes.resolve(name);
            Files.write(classFile, SmapInjector.withSmap(Files.readAllBytes(classFile), smap));
        }
    }
}
