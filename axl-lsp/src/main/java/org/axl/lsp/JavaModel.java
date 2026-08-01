package org.axl.lsp;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;
import org.axl.javac.RuntimeClasspath;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

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
    private final StandardJavaFileManager files;
    private final List<String> sourcePackages = new ArrayList<>();
    private Set<String> packages;

    private JavaModel(JavacTask task, StandardJavaFileManager files, Iterable<? extends CompilationUnitTree> units,
                      List<GeneratedUnit> generatedUnits) {
        this.files = files;
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.trees = Trees.instance(task);
        for (CompilationUnitTree unit : units) {
            if (unit.getPackageName() != null) {
                sourcePackages.add(unit.getPackageName().toString());
            }
            for (GeneratedUnit g : generatedUnits) {
                if (unit.getSourceFile().toUri().getPath().endsWith("/" + g.javaPath())) {
                    generated.put(g.qualifiedName(), unit);
                    translations.put(g.qualifiedName(), g);
                }
            }
        }
    }

    /** stubs: the module's Java sources are read from them, so that only the generated code is attributed. */
    static JavaModel build(Module module, List<GeneratedUnit> generatedUnits, JavaStubs stubs) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var ignored = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager files = compiler.getStandardFileManager(ignored, Locale.ROOT, StandardCharsets.UTF_8);
        List<JavaFileObject> sources = new ArrayList<>();
        generatedUnits.forEach(u -> sources.add(new Source(u.javaPath(), u.javaSource())));
        sources.addAll(stubs.of(JavaSources.of(module)));
        if (sources.isEmpty()) {
            // javac refuses to run without sources, and the classpath is still needed.
            sources.add(new Source("package-info.java", ""));
        }
        List<Path> classpath = new ArrayList<>(RuntimeClasspath.entries());
        classpath.addAll(module.classpath());
        List<String> options = new ArrayList<>(List.of("-proc:none", "-classpath",
                classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
        options.addAll(module.javaSourcepathOptions());
        JavacTask task = (JavacTask) compiler.getTask(null, files, ignored, options, null, sources);
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            return new JavaModel(task, files, units, generatedUnits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A type named in an .axl file: by import, declared in it, in the same package, in java.lang or axl.lang. */
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
        String fileClass = pkg.isEmpty() ? file.className() : pkg + "." + file.className();
        if (name.equals(file.className())) {
            // A file named like a type it declares is that type.
            return elements.getTypeElement(fileClass);
        }
        if (file.decls().stream().anyMatch(d -> isType(d) && d.name().equals(name))) {
            // The other data classes, classes, enums and interfaces are nested in the class of their file.
            return elements.getTypeElement(fileClass + "." + name);
        }
        for (String candidate : List.of(pkg.isEmpty() ? name : pkg + "." + name, "java.lang." + name, "axl.lang." + name)) {
            TypeElement type = elements.getTypeElement(candidate);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private static boolean isType(Decl decl) {
        return decl instanceof Decl.DataClass || decl instanceof Decl.Interface || decl instanceof Decl.ClassDecl
                || decl instanceof Decl.EnumDecl;
    }

    TypeElement resolveType(SourceFile file, TypeRef type) {
        String boxed = PRIMITIVES.get(type.name());
        return resolveType(file, boxed != null ? boxed : type.name());
    }

    /** A written type with its type arguments, such as `List<String>`; raw when they do not resolve. */
    TypeMirror typeOf(SourceFile file, TypeRef type) {
        TypeMirror variable = typeVariable(file, type);
        if (variable != null) {
            return variable;
        }
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
     * The type variable of a type parameter named where a type is written, from the generated class or
     * method that declares it, so that a `T` value has the members of its bound; the erased bound, or
     * Object, while the generated code has no such declaration. Null for a name that is no type
     * parameter there.
     */
    private TypeMirror typeVariable(SourceFile file, TypeRef type) {
        TypeParameters.Owner owner = type.args().isEmpty() ? TypeParameters.ownerOf(file, type.pos(), type.name()) : null;
        if (owner == null) {
            return null;
        }
        Parameterizable declared = generatedOwner(file, owner);
        for (TypeParameterElement parameter : declared == null ? List.<TypeParameterElement>of() : declared.getTypeParameters()) {
            if (parameter.getSimpleName().contentEquals(type.name())) {
                return parameter.asType();
            }
        }
        Decl.TypeParam parameter = TypeParameters.named(TypeParameters.of(owner.declaring()), type.name());
        TypeElement bound = parameter.bound() == null ? null : resolveType(file, parameter.bound());
        TypeElement known = bound != null ? bound : elements.getTypeElement("java.lang.Object");
        return known == null ? null : types.erasure(known.asType());
    }

    /** The generated class of a type, or the generated method of a function or a method of a type. */
    private Parameterizable generatedOwner(SourceFile file, TypeParameters.Owner owner) {
        TypeElement type = resolveType(file, owner.decl() instanceof Decl.Function ? file.className() : owner.decl().name());
        Decl.Function function = owner.method() != null ? owner.method() : owner.decl() instanceof Decl.Function fn ? fn : null;
        if (type == null || function == null) {
            return type;
        }
        return ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(m -> m.getSimpleName().contentEquals(function.name()) && m.getParameters().size() == function.params().size())
                .findFirst().orElse(null);
    }

    /**
     * The declaration javac sees for a local or lambda parameter in the generated method of a
     * function, or with function null, in the initializers of top-level values. Of several with the
     * name, the one generated from the declaration's line wins, and of several on that line, the one
     * in the same place among them, as for the `it`s of two lambdas. A `when` binding has a
     * generated name, the binding's name followed by `$e`.
     */
    private TreePath localPath(SourceFile file, String function, int paramCount, Symbols.Symbol local) {
        String qualified = file.packageName() == null || file.packageName().isEmpty()
                ? file.className() : file.packageName() + "." + file.className();
        CompilationUnitTree unit = generated.get(qualified);
        if (unit == null) {
            return null;
        }
        GeneratedUnit translation = translations.get(qualified);
        boolean main = "main".equals(function) && paramCount == 0;
        boolean binding = local.origin() == Symbols.Origin.WHEN_BINDING;
        int axlLine = local.pos().line();
        int earlier = local.kind() == Symbols.Kind.PARAMETER ? Symbols.earlierOnItsLine(file, local) : 0;
        var scanner = new TreePathScanner<Void, Void>() {
            private boolean inFunction = function == null;
            private TreePath first;
            private final List<TreePath> onLine = new ArrayList<>();

            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                inFunction = function != null && method.getName().contentEquals(function)
                        && (method.getParameters().size() == paramCount || main);
                super.visitMethod(method, unused);
                inFunction = function == null;
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                String name = variable.getName().toString();
                if (inFunction && (binding ? name.startsWith(local.name() + "$e") : name.equals(local.name()))) {
                    TreePath path = new TreePath(getCurrentPath(), variable);
                    long start = trees.getSourcePositions().getStartPosition(unit, variable);
                    if (translation.lineMap().axlLine((int) unit.getLineMap().getLineNumber(start)) == axlLine) {
                        onLine.add(path);
                    }
                    if (first == null && !binding) {
                        first = path;
                    }
                }
                return super.visitVariable(variable, unused);
            }
        };
        scanner.scan(unit, null);
        return scanner.onLine.isEmpty() ? scanner.first : scanner.onLine.get(Math.min(earlier, scanner.onLine.size() - 1));
    }

    /** The type javac gives a value, parameter or local whose type is not written; null if unknown. */
    TypeMirror typeOf(SourceFile file, Symbols.Symbol symbol) {
        if (symbol.kind() == Symbols.Kind.VALUE) {
            TypeElement owner = resolveType(file, file.className());
            return owner == null ? null : memberType(owner.asType(), symbol.name(), null);
        }
        TreePath path = localPath(file, symbol);
        return path == null ? null : trees.getTypeMirror(path);
    }

    /** Whether a local without a written type holds the result of a method whose result may be null. */
    boolean isNullable(SourceFile file, Symbols.Symbol symbol) {
        TreePath path = localPath(file, symbol);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return false;
        }
        Element called = trees.getElement(new TreePath(path, variable.getInitializer()));
        return called instanceof ExecutableElement method && isNullable(method.getReturnType());
    }

    private TreePath localPath(SourceFile file, Symbols.Symbol symbol) {
        if (!List.of(Symbols.Kind.PARAMETER, Symbols.Kind.LOCAL, Symbols.Kind.VARIABLE).contains(symbol.kind())) {
            return null;
        }
        Decl.Function fn = symbol.function();
        return fn == null ? localPath(file, null, 0, symbol) : localPath(file, fn.name(), fn.params().size(), symbol);
    }

    static boolean isNullable(TypeMirror type) {
        return type != null && type.getAnnotationMirrors().stream()
                .anyMatch(a -> a.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable"));
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

    /**
     * The fields, methods and nested classes of a type that code in a package can use, static or
     * instance ones: public ones, and those that are not private of a class of the same package.
     * Nested classes of supertypes and static members that the type's own ones of the same name
     * hide are left out, such as `Enum.EnumDesc` and `Enum.valueOf(Class, String)` for an enum.
     */
    List<Element> members(TypeElement type, boolean statics, String fromPackage) {
        List<? extends Element> all = elements.getAllMembers(type);
        Set<String> own = all.stream().filter(m -> m.getEnclosingElement().equals(type))
                .map(m -> m.getSimpleName().toString()).collect(Collectors.toSet());
        Map<String, Element> result = new LinkedHashMap<>();
        for (Element member : all) {
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            boolean inherited = !member.getEnclosingElement().equals(type);
            boolean usable = isAccessible(member, fromPackage)
                    && (member.getKind() == ElementKind.METHOD || member.getKind() == ElementKind.FIELD
                    || member.getKind() == ElementKind.ENUM_CONSTANT || member instanceof TypeElement && !inherited);
            boolean hidden = isStatic && inherited && own.contains(member.getSimpleName().toString());
            if (usable && !hidden && isStatic == statics) {
                result.putIfAbsent(signature(member), member);
            }
        }
        return new ArrayList<>(result.values());
    }

    private boolean isAccessible(Element member, String fromPackage) {
        Set<Modifier> modifiers = member.getModifiers();
        return modifiers.contains(Modifier.PUBLIC)
                || !modifiers.contains(Modifier.PRIVATE) && elements.getPackageOf(member).getQualifiedName().contentEquals(fromPackage);
    }

    /**
     * The type of a field (arguments null) or the result of the method that fits the arguments best,
     * with the receiver's type arguments filled in: `get` of a `List<String>` gives `String`.
     * arguments: see {@link Document#argumentTypes}.
     */
    TypeMirror memberType(TypeMirror receiver, String name, List<String> arguments) {
        return memberType(null, receiver, name, arguments);
    }

    /**
     * Like {@link #memberType(TypeMirror, String, List)}, and with the file whose names the
     * arguments' types are, a method's type variables are inferred from the arguments that are
     * their parameters: `List.of(p)` gives a `List<Person>`.
     */
    TypeMirror memberType(SourceFile file, TypeMirror receiver, String name, List<String> arguments) {
        TypeElement type = elementOf(receiver);
        Element member = type == null ? null : member(type, name, arguments);
        if (member == null) {
            return null;
        }
        TypeMirror memberType = asMemberOf(receiver, member);
        if (member.getKind().isField()) {
            return memberType;
        }
        TypeMirror result = ((ExecutableType) memberType).getReturnType();
        return file == null ? result : inferred(file, (ExecutableElement) member, arguments, result);
    }

    /** A result type with the method's type variables that parameters have as their type replaced by the arguments' types. */
    private TypeMirror inferred(SourceFile file, ExecutableElement method, List<String> arguments, TypeMirror result) {
        Map<Element, TypeMirror> found = new HashMap<>();
        for (int i = 0; i < arguments.size(); i++) {
            TypeMirror parameter = method.getParameters().get(i).asType();
            TypeElement argument = arguments.get(i) == null ? null : resolveType(file, PRIMITIVES.getOrDefault(arguments.get(i), arguments.get(i)));
            if (parameter instanceof TypeVariable variable && method.getTypeParameters().contains(variable.asElement()) && argument != null) {
                found.putIfAbsent(variable.asElement(), argument.asType());
            }
        }
        return substitute(result, found);
    }

    private TypeMirror substitute(TypeMirror type, Map<Element, TypeMirror> found) {
        if (type instanceof TypeVariable variable) {
            return found.getOrDefault(variable.asElement(), type);
        }
        if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty() || found.isEmpty()) {
            return type;
        }
        TypeMirror[] arguments = declared.getTypeArguments().stream().map(a -> substitute(a, found)).toArray(TypeMirror[]::new);
        return types.getDeclaredType((TypeElement) declared.asElement(), arguments);
    }

    /**
     * The field (arguments null) or of the methods with a name, declared or inherited, the one that
     * fits the arguments best; null if none. arguments: see {@link Document#argumentTypes}.
     */
    Element member(TypeElement type, String name, List<String> arguments) {
        ExecutableElement best = null;
        int bestScore = -1;
        for (Element member : elements.getAllMembers(type)) {
            if (!member.getSimpleName().contentEquals(name)) {
                continue;
            }
            if (arguments == null && member.getKind().isField()) {
                return member;
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
        return best;
    }

    /** The next segment of the names of the packages below a package, such as `util` and `io` below `java`. */
    List<String> subpackages(String pkg) {
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        return packages().stream().filter(p -> p.startsWith(prefix) && p.length() > prefix.length())
                .map(p -> p.substring(prefix.length()).split("\\.")[0]).distinct().toList();
    }

    /** The classes of a package that code in a package can use. */
    List<TypeElement> classesOf(String pkg, String fromPackage) {
        PackageElement element = elements.getPackageElement(pkg);
        return element == null ? List.of()
                : ElementFilter.typesIn(element.getEnclosedElements()).stream().filter(t -> isAccessible(t, fromPackage)).toList();
    }

    /** The packages of the JDK, the classpath and the sources; found once, when an import is completed. */
    private Set<String> packages() {
        if (packages != null) {
            return packages;
        }
        packages = new TreeSet<>(sourcePackages);
        for (ModuleElement module : elements.getAllModuleElements()) {
            ElementFilter.packagesIn(module.getEnclosedElements()).forEach(p -> packages.add(p.getQualifiedName().toString()));
        }
        try {
            for (JavaFileObject file : files.list(StandardLocation.CLASS_PATH, "", Set.of(JavaFileObject.Kind.CLASS), true)) {
                String name = files.inferBinaryName(StandardLocation.CLASS_PATH, file);
                if (name != null && name.lastIndexOf('.') > 0) {
                    packages.add(name.substring(0, name.lastIndexOf('.')));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return packages;
    }

    /** A class by its canonical name, such as `app.Tool.Inner`; null if unknown. */
    TypeElement type(String qualifiedName) {
        return elements.getTypeElement(qualifiedName);
    }

    /**
     * Where the name of a class or member of a Java source of the module is declared; null for one
     * from the classpath or from an .axl file.
     */
    Location declaration(Element element) {
        TreePath path = element == null ? null : trees.getPath(element);
        if (path == null || generated.containsValue(path.getCompilationUnit())) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        try {
            String text = unit.getSourceFile().getCharContent(true).toString();
            long offset = nameOffset(text, unit, path.getLeaf(), element.getSimpleName().toString());
            Position position = new Position((int) unit.getLineMap().getLineNumber(offset) - 1,
                    (int) unit.getLineMap().getColumnNumber(offset) - 1);
            return new Location(unit.getSourceFile().toUri().toString(), new Range(position, position));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The name comes after the modifiers and annotations, and for a method after its result type. */
    private long nameOffset(String text, CompilationUnitTree unit, Tree declaration, String name) {
        ModifiersTree modifiers = switch (declaration) {
            case ClassTree c -> c.getModifiers();
            case MethodTree m -> m.getModifiers();
            case VariableTree v -> v.getModifiers();
            default -> null;
        };
        SourcePositions positions = trees.getSourcePositions();
        long from = Math.max(positions.getStartPosition(unit, declaration), modifiers == null ? -1 : positions.getEndPosition(unit, modifiers));
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text);
        return from >= 0 && m.find((int) from) ? m.start() : Math.max(0, positions.getStartPosition(unit, declaration));
    }

    /** A public class declared in another, such as `State` in `Thread`; null if none. */
    TypeElement nestedType(TypeElement owner, String name) {
        if (owner == null) {
            return null;
        }
        return ElementFilter.typesIn(owner.getEnclosedElements()).stream()
                .filter(t -> t.getSimpleName().contentEquals(name) && t.getModifiers().contains(Modifier.PUBLIC))
                .findFirst().orElse(null);
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

    /** The type of a field or the result of a method as Axolotl writes it, `String?` for one that may be null. */
    static String typeOf(Element member) {
        return display(member instanceof ExecutableElement method ? method.getReturnType() : member.asType());
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

    /**
     * A type as Axolotl writes it, for hovers: `Int` for `int` and `Integer`, nested classes by their
     * simple name, `?` after a type that may be null; a single `?` when it is unknown.
     */
    static String display(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return "?";
        }
        String text = switch (type) {
            case DeclaredType declared -> {
                String name = declared.asElement().getSimpleName().toString();
                String arguments = declared.getTypeArguments().isEmpty() ? ""
                        : declared.getTypeArguments().stream().map(JavaModel::display).collect(Collectors.joining(", ", "<", ">"));
                yield AXL_NAMES.getOrDefault(name, name) + arguments;
            }
            case ArrayType array -> display(array.getComponentType()) + "[]";
            case WildcardType wildcard -> wildcard.getExtendsBound() != null ? display(wildcard.getExtendsBound()) : "?";
            default -> AXL_NAMES.getOrDefault(type.toString(), simple(type.toString()));
        };
        return isNullable(type) && !text.endsWith("?") ? text + "?" : text;
    }

    static String simple(String type) {
        return type.replace("java.lang.", "");
    }
}
