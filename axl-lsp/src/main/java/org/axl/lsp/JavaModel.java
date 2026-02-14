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
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.ast.Decl;
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

    private static final Map<String, String> AXL_NAMES = Map.of("int", "Int", "Integer", "Int", "long", "Long",
            "boolean", "Boolean", "double", "Double");

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
    private final Types types;
    private final Trees trees;
    private final Map<String, CompilationUnitTree> generated = new LinkedHashMap<>();
    private final Map<String, GeneratedUnit> translations = new LinkedHashMap<>();

    private JavaModel(JavacTask task, Iterable<? extends CompilationUnitTree> units, List<GeneratedUnit> generatedUnits) {
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.trees = Trees.instance(task);
        for (CompilationUnitTree unit : units) {
            for (GeneratedUnit g : generatedUnits) {
                if (unit.getSourceFile().toUri().getPath().endsWith("/" + g.javaPath())) {
                    generated.put(g.qualifiedName(), unit);
                    translations.put(g.qualifiedName(), g);
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

    /** A written type with its type arguments, such as `List<String>`; raw when they do not resolve. */
    TypeMirror typeOf(SourceFile file, TypeRef type) {
        TypeElement element = resolveType(file, type);
        if (element == null) {
            return null;
        }
        List<TypeMirror> arguments = type.args().stream().map(a -> typeOf(file, a)).toList();
        if (arguments.isEmpty() || arguments.contains(null) || arguments.size() != element.getTypeParameters().size()) {
            return types.erasure(element.asType());
        }
        return types.getDeclaredType(element, arguments.toArray(TypeMirror[]::new));
    }

    /**
     * The type javac gives a local or lambda parameter in the generated method of a function. Of
     * several with the name, the one generated from the declaration's line wins.
     */
    TypeMirror localType(SourceFile file, String function, int paramCount, String local, int axlLine) {
        String qualified = file.packageName() == null || file.packageName().isEmpty()
                ? file.className() : file.packageName() + "." + file.className();
        CompilationUnitTree unit = generated.get(qualified);
        if (unit == null) {
            return null;
        }
        GeneratedUnit translation = translations.get(qualified);
        boolean main = function.equals("main") && paramCount == 0;
        var scanner = new TreePathScanner<Void, Void>() {
            private boolean inFunction;
            private TypeMirror first;
            private TypeMirror onLine;

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
                if (inFunction && variable.getName().contentEquals(local)) {
                    TypeMirror type = trees.getTypeMirror(new TreePath(getCurrentPath(), variable));
                    long start = trees.getSourcePositions().getStartPosition(unit, variable);
                    if (onLine == null && translation.lineMap().axlLine((int) unit.getLineMap().getLineNumber(start)) == axlLine) {
                        onLine = type;
                    }
                    if (first == null) {
                        first = type;
                    }
                }
                return super.visitVariable(variable, unused);
            }
        };
        scanner.scan(unit, null);
        return scanner.onLine != null ? scanner.onLine : scanner.first;
    }

    /** The type javac gives a value, parameter or local whose type is not written; null if unknown. */
    TypeMirror typeOf(SourceFile file, Symbols.Symbol symbol) {
        if (symbol.kind() == Symbols.Kind.VALUE) {
            TypeElement owner = resolveType(file, file.className());
            return owner == null ? null : memberType(owner.asType(), symbol.name(), null);
        }
        Decl.Function fn = symbol.function();
        return fn == null ? null : localType(file, fn.name(), fn.params().size(), symbol.name(), symbol.pos().line());
    }

    /** The class of a type, of the bound of a type variable or wildcard; null for primitives. */
    static TypeElement elementOf(TypeMirror type) {
        return switch (type) {
            case DeclaredType declared -> (TypeElement) declared.asElement();
            case TypeVariable variable -> elementOf(variable.getUpperBound());
            case WildcardType wildcard -> elementOf(wildcard.getExtendsBound());
            case null, default -> null;
        };
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

    /**
     * The type of a field (arguments null) or the result of the method that fits the arguments best,
     * with the receiver's type arguments filled in: `get` of a `List<String>` gives `String`.
     * arguments: see {@link Document#argumentTypes}.
     */
    TypeMirror memberType(TypeMirror receiver, String name, List<String> arguments) {
        TypeElement type = elementOf(receiver);
        if (type == null) {
            return null;
        }
        ExecutableElement best = null;
        int bestScore = -1;
        for (Element member : elements.getAllMembers(type)) {
            if (!member.getSimpleName().contentEquals(name)) {
                continue;
            }
            if (arguments == null && member.getKind().isField()) {
                return asMemberOf(receiver, member);
            }
            if (arguments != null && member instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD
                    && method.getParameters().size() == arguments.size()) {
                int score = fit(method, arguments);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
        }
        return best == null ? null : ((ExecutableType) asMemberOf(receiver, best)).getReturnType();
    }

    private TypeMirror asMemberOf(TypeMirror receiver, Element member) {
        return receiver instanceof DeclaredType declared ? types.asMemberOf(declared, member) : member.asType();
    }

    /** Higher for a better fit: arguments of the parameter's type count most, unknown ones next. */
    private static int fit(ExecutableElement method, List<String> arguments) {
        int score = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            score += argument == null ? 1 : argument.equals(axlName(method.getParameters().get(i).asType())) ? 2 : 0;
        }
        return score;
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

    /** The name Axolotl writes a type with, without type arguments: `Int` for `int` and `Integer`; null if unknown. */
    static String axlName(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return null;
        }
        String name = type instanceof DeclaredType declared ? declared.asElement().getSimpleName().toString()
                : type.toString();
        return AXL_NAMES.getOrDefault(name, name);
    }

    /** A type as hovers show it; `?` when it is unknown. */
    static String display(TypeMirror type) {
        return type == null || type.getKind() == TypeKind.ERROR ? "?" : simple(type.toString());
    }

    static String simple(String type) {
        return type.replace("java.lang.", "");
    }
}
