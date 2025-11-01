package org.axl.lsp;

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
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;
import org.axl.javac.RuntimeClasspath;

/**
 * javac's view of a module: Java types from the classpath and the Java sources, and the types javac
 * infers for `val`s in the generated code. Errors in the code are ignored; attribution still runs.
 */
final class JavaModel {

    private static final Map<String, String> PRIMITIVES = Map.of(
            "Int", "java.lang.Integer", "Long", "java.lang.Long", "Boolean", "java.lang.Boolean", "Double", "java.lang.Double");

    private static final class Source extends SimpleJavaFileObject {
        private final String text;

        Source(String path, String text) {
            super(URI.create("axl-generated:///" + path), Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    private final Elements elements;
    private final Trees trees;
    private final Map<String, CompilationUnitTree> generated = new LinkedHashMap<>();

    private JavaModel(JavacTask task, Iterable<? extends CompilationUnitTree> units, List<GeneratedUnit> generatedUnits) {
        this.elements = task.getElements();
        this.trees = Trees.instance(task);
        for (CompilationUnitTree unit : units) {
            for (GeneratedUnit g : generatedUnits) {
                if (unit.getSourceFile().toUri().getPath().endsWith("/" + g.javaPath())) {
                    generated.put(g.qualifiedName(), unit);
                }
            }
        }
    }

    static JavaModel build(Module module, List<GeneratedUnit> generatedUnits) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var ignored = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager files = compiler.getStandardFileManager(ignored, Locale.ROOT, StandardCharsets.UTF_8);
        List<JavaFileObject> sources = new ArrayList<>();
        generatedUnits.forEach(u -> sources.add(new Source(u.javaPath(), u.javaSource())));
        files.getJavaFileObjectsFromPaths(javaFiles(module)).forEach(sources::add);
        if (sources.isEmpty()) {
            // javac refuses to run without sources, and the classpath is still needed.
            sources.add(new Source("package-info.java", ""));
        }
        List<Path> classpath = new ArrayList<>(RuntimeClasspath.entries());
        classpath.addAll(module.classpath());
        List<String> options = List.of("-proc:none", "-classpath",
                classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        JavacTask task = (JavacTask) compiler.getTask(null, files, ignored, options, null, sources);
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            return new JavaModel(task, units, generatedUnits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles(Module module) {
        List<Path> result = new ArrayList<>();
        for (Path root : module.javaRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(result::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    /** A type named in an .axl file: by import, in the same package, in java.lang or axl.lang. */
    TypeElement resolveType(SourceFile file, String name) {
        if (name.contains(".")) {
            return elements.getTypeElement(name);
        }
        for (Import imp : file.imports()) {
            if (imp.kind() == Import.Kind.TYPE && imp.simpleName().equals(name)) {
                return elements.getTypeElement(imp.name());
            }
        }
        String pkg = file.packageName() == null ? "" : file.packageName();
        for (String candidate : List.of(pkg.isEmpty() ? name : pkg + "." + name, "java.lang." + name, "axl.lang." + name)) {
            TypeElement type = elements.getTypeElement(candidate);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    TypeElement resolveType(SourceFile file, TypeRef type) {
        String boxed = PRIMITIVES.get(type.name());
        return resolveType(file, boxed != null ? boxed : type.name());
    }

    /** The type javac gives a local or lambda parameter in the generated method of a function. */
    TypeMirror localType(SourceFile file, String function, int paramCount, String local) {
        String qualified = file.packageName() == null || file.packageName().isEmpty()
                ? file.className() : file.packageName() + "." + file.className();
        CompilationUnitTree unit = generated.get(qualified);
        if (unit == null) {
            return null;
        }
        TypeMirror[] found = new TypeMirror[1];
        boolean main = function.equals("main") && paramCount == 0;
        new TreePathScanner<Void, Void>() {
            private boolean inFunction;

            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                boolean match = method.getName().contentEquals(function)
                        && (method.getParameters().size() == paramCount || main);
                inFunction = match;
                super.visitMethod(method, unused);
                inFunction = false;
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                if (inFunction && found[0] == null && variable.getName().contentEquals(local)) {
                    found[0] = trees.getTypeMirror(new TreePath(getCurrentPath(), variable));
                }
                return super.visitVariable(variable, unused);
            }
        }.scan(unit, null);
        return found[0];
    }

    /** The class of a type, or null for primitives and type variables. */
    static TypeElement elementOf(TypeMirror type) {
        return type != null && type.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) type).asElement() : null;
    }

    /** Public fields and methods of a type, static or instance ones. */
    List<Element> members(TypeElement type, boolean statics) {
        Map<String, Element> result = new LinkedHashMap<>();
        for (Element member : elements.getAllMembers(type)) {
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            boolean usable = member.getModifiers().contains(Modifier.PUBLIC)
                    && (member.getKind() == ElementKind.METHOD || member.getKind() == ElementKind.FIELD
                    || member.getKind() == ElementKind.ENUM_CONSTANT);
            if (usable && isStatic == statics) {
                result.putIfAbsent(signature(member), member);
            }
        }
        return new ArrayList<>(result.values());
    }

    static String signature(Element member) {
        if (member instanceof ExecutableElement method) {
            return method.getSimpleName() + method.getParameters().stream()
                    .map(p -> simple(p.asType().toString()) + " " + p.getSimpleName())
                    .collect(Collectors.joining(", ", "(", ")"));
        }
        return member.getSimpleName().toString();
    }

    static String typeOf(Element member) {
        TypeMirror type = member instanceof ExecutableElement method ? method.getReturnType() : member.asType();
        return simple(type.toString());
    }

    static String simple(String type) {
        return type.replace("java.lang.", "");
    }
}
