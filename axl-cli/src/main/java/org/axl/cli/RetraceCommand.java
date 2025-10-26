package org.axl.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.axl.smap.Retracer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "retrace", description = {
        "Rewrite .java lines of generated classes in a stack trace to .axl lines.",
        "Reads the trace from the file argument or from standard input."})
final class RetraceCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "File with the stack trace (default: standard input).")
    Path input;

    @Option(names = {"-m", "--map-dir"}, description = "Directory with generated .java and .axlmap files; repeatable.")
    List<Path> mapDirs = new ArrayList<>();

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}",
            description = "Class directories or jars whose classes carry an SMAP.")
    List<Path> classpath = new ArrayList<>();

    @Override
    public Integer call() throws IOException {
        String trace;
        if (input != null) {
            trace = Files.readString(input, StandardCharsets.UTF_8);
        } else {
            InputStream in = System.in;
            trace = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.print(Retracer.of(mapDirs, classpath).retrace(trace));
        return 0;
    }
}
