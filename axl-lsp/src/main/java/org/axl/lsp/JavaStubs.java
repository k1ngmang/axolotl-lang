package org.axl.lsp;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Java sources with the code of their methods and initializers blanked out, for the Java model:
 * javac then enters their declarations without attributing every method body after each edit.
 * Everything else keeps its position, so declarations are found where they are in the file. Each
 * file is read again only when it changes on disk.
 */
final class JavaStubs {

    private record Stub(FileTime modified, long size, String text) {}

    /** A stub that javac reads as the file it comes from. */
    static final class Source extends SimpleJavaFileObject {
        private final String text;

        Source(URI uri, String text) {
            super(uri, Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    private final Map<Path, Stub> stubs = new HashMap<>();

    /** The stubs of the files; a file that cannot be read is left out. */
    List<JavaFileObject> of(List<Path> files) {
        List<Path> changed = files.stream().filter(this::isOutdated).toList();
        if (!changed.isEmpty()) {
            blank(changed);
        }
        return files.stream().filter(stubs::containsKey)
                .<JavaFileObject>map(file -> new Source(file.toUri(), stubs.get(file).text())).toList();
    }

    private boolean isOutdated(Path file) {
        Stub stub = stubs.get(file);
        try {
            return stub == null || !stub.modified().equals(Files.getLastModifiedTime(file)) || stub.size() != Files.size(file);
        } catch (IOException e) {
            stubs.remove(file);
            return false;
        }
    }

    /** Parses the files together, which is quicker than one by one. */
    private void blank(List<Path> files) {
        List<JavaFileObject> sources = new ArrayList<>();
        Map<URI, Path> paths = new HashMap<>();
        for (Path file : files) {
            try {
                sources.add(new Source(file.toUri(), Files.readString(file, StandardCharsets.UTF_8)));
                paths.put(file.toUri(), file);
            } catch (IOException e) {
                stubs.remove(file);
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var ignored = new DiagnosticCollector<JavaFileObject>();
        JavacTask task = (JavacTask) compiler.getTask(null, null, ignored, List.of("-proc:none"), null, sources);
        try {
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                Path file = paths.get(unit.getSourceFile().toUri());
                String text = unit.getSourceFile().getCharContent(true).toString();
                stubs.put(file, new Stub(Files.getLastModifiedTime(file), Files.size(file), blanked(text, unit, positions)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Spaces in place of the code inside the braces of each method and initializer; line breaks stay. */
    private static String blanked(String text, CompilationUnitTree unit, SourcePositions positions) {
        char[] chars = text.toCharArray();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                blank(method.getBody());
                return null;
            }

            @Override
            public Void visitClass(ClassTree type, Void unused) {
                for (Tree member : type.getMembers()) {
                    if (member instanceof BlockTree initializer) {
                        blank(initializer);
                    } else {
                        scan(member, null);
                    }
                }
                return null;
            }

            /** A static initializer starts at its keyword, the others at their brace. */
            private void blank(BlockTree body) {
                int start = body == null ? -1 : text.indexOf('{', (int) positions.getStartPosition(unit, body));
                long end = body == null ? -1 : positions.getEndPosition(unit, body);
                for (int i = start + 1; start >= 0 && i < end - 1 && i < chars.length; i++) {
                    if (chars[i] != '\n' && chars[i] != '\r') {
                        chars[i] = ' ';
                    }
                }
            }
        }.scan(unit, null);
        return new String(chars);
    }
}
