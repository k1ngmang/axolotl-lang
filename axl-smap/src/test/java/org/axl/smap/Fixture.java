package org.axl.smap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                val text = "4" + "2"
                println(parse(text))
                println(sum("1", "2"))
                println(parse("x"))
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
}
