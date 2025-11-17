package org.axl.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.axl.compiler.JavaTypes;

/** Reads the types javac gives the locals of a probe method, with a classpath and Java sources. */
public final class JavacTypes implements JavaTypes {

    private final List<Path> classpath;
    private final List<Path> sourcepath;
    private final boolean strictNull;

    public JavacTypes(List<Path> classpath, List<Path> sourcepath) {
        this(classpath, sourcepath, false);
    }

    /** strictNull: values from Java code without nullness annotations are typed nullable. */
    public JavacTypes(List<Path> classpath, List<Path> sourcepath, boolean strictNull) {
        this.strictNull = strictNull;
        this.classpath = new ArrayList<>(RuntimeClasspath.entries());
        this.classpath.addAll(classpath);
        this.sourcepath = sourcepath.stream().filter(Files::isDirectory).toList();
    }

    @Override
    public Map<String, String> localTypes(String qualifiedClassName, String javaSource) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var errors = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(errors, Locale.ROOT, StandardCharsets.UTF_8)) {
            return localTypes(compiler, files, errors, qualifiedClassName, javaSource);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, String> localTypes(JavaCompiler compiler, StandardJavaFileManager files,
                                           DiagnosticCollector<JavaFileObject> errors, String qualifiedClassName,
                                           String javaSource) {
        List<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-XDaddTypeAnnotationsToSymbol=true",
                "-classpath", join(classpath)));
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
        JavacTask task = (JavacTask) compiler.getTask(null, files, errors, options, null, List.of(source));
        String[] lines = javaSource.split("\n", -1);
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
                            TreePath path = new TreePath(getCurrentPath(), variable);
                            String type = text(trees.getTypeMirror(path));
                            if (type != null) {
                                boolean nullable = variable.getInitializer() != null
                                        && isNullable(trees, new TreePath(path, variable.getInitializer()), strictNull);
                                types.put(variable.getName().toString(), nullable ? NULLABLE + type : type);
                            } else if (variable.getInitializer() != null) {
                                String reason = reason(trees, unit, variable.getInitializer(), errors, lines);
                                if (reason != null) {
                                    types.put(variable.getName().toString(), ERROR + reason);
                                }
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

    /** The first error javac reports inside an initializer, in Axolotl terms. */
    private static String reason(Trees trees, CompilationUnitTree unit, ExpressionTree init,
                                 DiagnosticCollector<JavaFileObject> errors, String[] lines) {
        long start = trees.getSourcePositions().getStartPosition(unit, init);
        long end = trees.getSourcePositions().getEndPosition(unit, init);
        for (Diagnostic<? extends JavaFileObject> d : errors.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR && d.getPosition() >= start && d.getPosition() <= end) {
                String message = DiagnosticTranslator.oneLine(d.getMessage(Locale.ROOT));
                return Messages.translate(message, lines, (int) d.getLineNumber()).message();
            }
        }
        return null;
    }

    /**
     * Whether an initializer may be null as far as its declarations say. javac drops type-use
     * annotations from inferred types, so this reads the declarations instead.
     */
    private static boolean isNullable(Trees trees, TreePath path, boolean strict) {
        ExpressionTree expression = (ExpressionTree) path.getLeaf();
        return switch (expression) {
            case ParenthesizedTree p -> isNullable(trees, new TreePath(path, p.getExpression()), strict);
            case ConditionalExpressionTree c -> isNullable(trees, new TreePath(path, c.getTrueExpression()), strict)
                    || isNullable(trees, new TreePath(path, c.getFalseExpression()), strict);
            case LiteralTree l -> l.getKind() == Tree.Kind.NULL_LITERAL;
            case MethodInvocationTree call -> isNullableCall(trees, path, call, strict);
            case IdentifierTree i -> declaredNullable(trees.getElement(path), strict);
            case MemberSelectTree m -> declaredNullable(trees.getElement(path), strict);
            default -> false;
        };
    }

    private static boolean isNullableCall(Trees trees, TreePath path, MethodInvocationTree call, boolean strict) {
        Element method = trees.getElement(path);
        if (method == null) {
            return false;
        }
        String name = method.getSimpleName().toString();
        String owner = method.getEnclosingElement().toString();
        // `a ?: b` is Nulls.or(a, () -> b): null only when the fallback may be.
        if (owner.equals("axl.lang.Nulls") && name.equals("or") && call.getArguments().size() == 2
                && call.getArguments().get(1) instanceof LambdaExpressionTree lambda
                && lambda.getBody() instanceof ExpressionTree body) {
            return isNullable(trees, new TreePath(new TreePath(path, lambda), body), strict);
        }
        return declaredNullable(method, strict);
    }

    private static boolean declaredNullable(Element element, boolean strict) {
        if (element == null) {
            return false;
        }
        TypeMirror type = element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
        return PlatformValues.isNullable(element, type) || strict && PlatformValues.isPlatform(element);
    }

    /** Java source text for a type, or null when it has no such text (errors, captures, intersections). */
    static String text(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.INTERSECTION) {
            return null;
        }
        String text = type.toString();
        if (text.contains("capture#") || text.contains("<any>") || text.contains("&")) {
            return null;
        }
        return text.replace("@org.jspecify.annotations.", "@").replaceAll("\\bjava\\.lang\\.(?=[@A-Z])", "")
                .replace(",", ", ");
    }

    private static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
