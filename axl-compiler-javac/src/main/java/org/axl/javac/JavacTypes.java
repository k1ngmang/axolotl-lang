package org.axl.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.axl.compiler.JavaTypes;

/** Reads the types javac gives the locals of a probe method, with a classpath and Java sources. */
public final class JavacTypes implements JavaTypes {

    private final List<Path> classpath;
    private final List<Path> sourcepath;

    public JavacTypes(List<Path> classpath, List<Path> sourcepath) {
        this.classpath = new ArrayList<>(RuntimeClasspath.entries());
        this.classpath.addAll(classpath);
        this.sourcepath = sourcepath.stream().filter(Files::isDirectory).toList();
    }

    @Override
    public Map<String, String> localTypes(String qualifiedClassName, String javaSource) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var ignored = new DiagnosticCollector<JavaFileObject>();
        var files = compiler.getStandardFileManager(ignored, Locale.ROOT, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-classpath", join(classpath)));
        if (!sourcepath.isEmpty()) {
            options.addAll(List.of("-sourcepath", join(sourcepath)));
        }
        JavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///" + qualifiedClassName.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return javaSource;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, files, ignored, options, null, List.of(source));
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Map<String, String> types = new LinkedHashMap<>();
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    private boolean inProbe;

                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        inProbe = method.getName().contentEquals(PROBE_METHOD);
                        super.visitMethod(method, unused);
                        inProbe = false;
                        return null;
                    }

                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        if (inProbe) {
                            String type = text(trees.getTypeMirror(new TreePath(getCurrentPath(), variable)));
                            if (type != null) {
                                types.put(variable.getName().toString(), type);
                            }
                        }
                        return null;
                    }
                }.scan(unit, null);
            }
            return types;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Java source text for a type, or null when it has no such text (errors, captures, intersections). */
    static String text(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.INTERSECTION) {
            return null;
        }
        String text = type.toString();
        if (text.contains("capture#") || text.contains("<any>")) {
            return null;
        }
        return text.replace("@org.jspecify.annotations.", "@").replaceAll("\\bjava\\.lang\\.(?=[@A-Z])", "");
    }

    private static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
