package org.axl.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.format.Formatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "fmt", description = {
        "Rewrite .axl files in the one style of Axolotl: indentation, spaces, blank lines and the order of imports.",
        "Line breaks, comments and strings stay as they are. A file with syntax errors is left unchanged."})
final class FmtCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "<path>",
            description = ".axl files and directories to search for them; `-` formats standard input to standard output.")
    List<Path> paths = new ArrayList<>();

    @Option(names = "--check", description = "Change no file: list the files that would change and exit with 1 if there are any.")
    boolean check;

    @Override
    public Integer call() throws IOException {
        Path workingDirectory = Paths.workingDirectory();
        List<Path> files = new ArrayList<>();
        for (Path path : paths) {
            if (path.toString().equals("-")) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(p -> p.toString().endsWith(".axl") && Files.isRegularFile(p)).sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                System.err.println(Main.withEncodingHint("axl: no such file or directory: " + path, path.toString()));
                return 2;
            }
        }
        boolean failed = false;
        boolean unformatted = false;
        for (Path file : files) {
            boolean stdin = file.toString().equals("-");
            String shown = stdin ? "<stdin>" : Paths.display(workingDirectory, file);
            String source;
            try {
                source = stdin ? StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(System.in.readAllBytes())).toString()
                        : Files.readString(file, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                System.err.println("axl: " + shown + " is not UTF-8 text; it is left unchanged");
                failed = true;
                continue;
            }
            Formatter.Result result = Formatter.format(shown.replace('\\', '/'), source);
            for (AxlDiagnostic error : result.errors()) {
                System.err.println(error.formatWithCode());
            }
            if (result.internalError() != null) {
                System.err.println("axl: internal error: formatting " + shown + " would change its meaning ("
                        + result.internalError() + "); it is left unchanged");
            }
            failed |= result.failed();
            boolean changes = !result.text().equals(source);
            if (check) {
                if (changes) {
                    System.out.println(shown);
                    unformatted = true;
                }
            } else if (stdin) {
                System.out.write(result.text().getBytes(StandardCharsets.UTF_8));
                System.out.flush();
            } else if (changes) {
                Files.writeString(file, result.text(), StandardCharsets.UTF_8);
            }
        }
        return failed || unformatted ? 1 : 0;
    }
}
