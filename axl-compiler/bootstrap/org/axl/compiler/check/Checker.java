package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class Checker {
    public final SourceFile file;
    public final List<SourceFile> siblings;
    public final JavaClasses classes;
    public final ArrayList<AxlDiagnostic> diagnostics;
    public final LinkedHashMap<Pos, String> unassigned;
    public final Set<Expr.Lambda> implicitIt;
    public final Set<Expr.Name> mutableRefs;
    public final Set<Expr.Name> stableNames;
    public final Set<Expr.Name> staticNames;
    public final Set<Expr.Name> fieldNames;
    public final HashSet<String> typeNames;
    public final IdentityHashMap<Expr.When, Boolean> whenResults;
    public int valueWhenDepth;
    public int loopDepth;
    public int expressionLambdas;
    public int conditionalDepth;
    public final Set<Expr.Throw> allowedThrows;
    public final IdentityHashMap<Expr.Stage, TypeRef> mapResultTypes;
    public Scope scope;
    public Decl.@Nullable Function current;
    public Set<String> openTypeParams;
    public List<String> enclosingTypeParams;
    public @Nullable Stmt lastInLambda;
    public final HashMap<Pos, String> createdTypes;
    public int lambdaDepth;
    public int safeTailDepth;
    public int attemptDepth;
    public boolean integerAllowed;
    public boolean valueStage;
    public Decl.@Nullable ClassDecl currentClass;
    public List<Decl.Function> currentMethods;
    public @Nullable TypeRef declaredType;
    public Checker(SourceFile file, List<SourceFile> siblings, JavaClasses classes) {
        this.file = file;
        this.siblings = siblings;
        this.classes = classes;
        this.diagnostics = new ArrayList<AxlDiagnostic>();
        this.unassigned = new LinkedHashMap<Pos, String>();
        this.implicitIt = Collections.newSetFromMap(new IdentityHashMap<Expr.Lambda, Boolean>());
        this.mutableRefs = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.stableNames = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.staticNames = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.fieldNames = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.typeNames = new HashSet<String>();
        this.whenResults = new IdentityHashMap<Expr.When, Boolean>();
        this.valueWhenDepth = -1;
        this.loopDepth = 0;
        this.expressionLambdas = 0;
        this.conditionalDepth = 0;
        this.allowedThrows = Collections.newSetFromMap(new IdentityHashMap<Expr.Throw, Boolean>());
        this.mapResultTypes = new IdentityHashMap<Expr.Stage, TypeRef>();
        this.scope = new Scope(null, true);
        this.current = null;
        this.openTypeParams = new HashSet<String>();
        this.enclosingTypeParams = List.of();
        this.lastInLambda = null;
        this.createdTypes = new HashMap<Pos, String>();
        this.lambdaDepth = 0;
        this.safeTailDepth = 0;
        this.attemptDepth = 0;
        this.integerAllowed = false;
        this.valueStage = false;
        this.currentClass = null;
        this.currentMethods = List.of();
        this.declaredType = null;
    }
    public void run() {
        checkFileName();
        checkPackage();
        checkImports();
        declareTopLevel();
        for (final var decl : file.decls()) {
            switch (decl) {
                case Decl.Value decl$1 -> {
                    topLevelValue(decl$1);
                }
                case Decl.Function decl$2 -> {
                    checkMainSignature(decl$2);
                    function(decl$2);
                }
                case Decl.ClassDecl decl$3 -> {
                    classDecl(decl$3);
                }
                case Decl.EnumDecl decl$4 -> {
                    enumConstants(decl$4);
                }
                case Decl.DataClass decl$5 -> {
                    members(decl$5.fields(), "field");
                    dataClassMethods(decl$5);
                }
                case Decl.Interface decl$6 -> {
                    members(decl$6.members(), "member");
                    interfaceMethods(decl$6);
                }
            }
        }
        checkInitOrder();
        for (final var entry : unassigned.entrySet()) {
            diagnostics.add(AxlDiagnostic.warning(file.path(), entry.getKey().line(), entry.getKey().column(), ErrorCode.UNNEEDED,
                    "`" + entry.getValue() + "` is never assigned again; declare it with `val`"));
        }
        checkFileTypeMembers();
        for (final var mismatch : Implementations.check(file, siblings)) {
            error(mismatch.pos(), ErrorCode.INTERFACE_MEMBER, mismatch.message());
        }
        for (final var mismatch : Implementations.foreignSealed(file, siblings)) {
            error(mismatch.pos(), ErrorCode.SYNTAX, mismatch.message());
        }
    }
    private @Nullable String classNameLike(String name) {
        final var sb = new StringBuilder();
        for (final var part : Pattern.compile("[^A-Za-z0-9]+").splitAsStream(name).toList()) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        var digits = 0;
        while (digits < sb.length() && Character.isDigit(sb.charAt(digits))) {
            digits += 1;
        }
        final var result = sb.substring(digits) + sb.substring(0, digits);
        return (result.isEmpty() || !Character.isLetter(result.charAt(0)) || RESERVED.contains(result) || CLASS_RESERVED.contains(result) ? null : result);
    }
    private void checkMainSignature(Decl.Function f) {
        final var result = f.returnType();
        if (f.name().equals("main") && f.params().isEmpty() && !f.isPrivate() && result != null) {
            diagnostics.add(AxlDiagnostic.warning(file.path(), f.namePos().line(), f.namePos().column(), ErrorCode.SYNTAX,
                    "a `main` with a result type does not start the program; remove `-> " + result.name() + "`"));
        }
    }
    private void checkFileName() {
        final var name = file.className();
        if (!isJavaIdentifier(name) || RESERVED.contains(name) || CLASS_RESERVED.contains(name)) {
            final var suggestion = classNameLike(name);
            final var hint = (suggestion == null ? "" : "; rename it to `" + suggestion + ".axl`");
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name + "` is not a valid class name" + hint);
        } else if (PACKAGE_ROOTS.contains(name)) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name + "` would hide the `" +
                    name +
                    "` package that the generated code uses; rename the file");
        } else if (GENERATED_CODE_CLASSES.contains(name)) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name +
                    "` would hide the class `" +
                    name +
                    "` that the generated code uses; rename the file");
        }
    }
    private void checkPackage() {
        final var packageName = file.packageName();
        if (packageName == null) {
            error(new Pos(1, 1), ErrorCode.MISSING_PACKAGE,
                    "missing `package` declaration: every .axl file starts with `package name`");
            return;
        }
        for (final var segment : Pattern.compile("\\.").splitAsStream(packageName).toList()) {
            if (RESERVED.contains(segment)) {
                error(Nulls.or(file.packagePos(), () -> new Pos(1, 1)), ErrorCode.RESERVED_NAME, "`" + segment +
                        "` is a reserved word in Java and cannot be part of a package name");
            }
        }
    }
    private void checkImports() {
        for (final var imp : file.imports()) {
            if (imp.kind() != Import.Kind.TYPE) {
                scope.names.putIfAbsent(imp.simpleName(), new Symbol(Kind.IMPORTED, imp.pos(), null));
            } else if (hidesGeneratedClass(imp)) {
                error(imp.pos(), ErrorCode.RESERVED_NAME, "`" + imp.simpleName() + "` would hide the class `" +
                        imp.simpleName() +
                        "` that the generated code uses; write `" +
                        imp.name() +
                        "` in full instead");
            }
        }
    }
    private boolean hidesGeneratedClass(Import imp) {
        if (!GENERATED_CODE_CLASSES.contains(imp.simpleName())) {
            return false;
        }
        final var packageName = imp.name().substring(0, Math.max(imp.name().lastIndexOf("."), 0));
        return !GENERATED_CODE_PACKAGES.contains(packageName);
    }
    private void declareTopLevel() {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1) {
                final var existing = scope.names.get(decl$1.name());
                if (existing != null) {
                    error(decl$1.namePos(), ErrorCode.DUPLICATE_NAME,
                            "`" + decl$1.name() + "` is already declared at line " + existing.pos().line());
                }
                scope.names.put(decl$1.name(), new Symbol(Kind.TOP_LEVEL, decl$1.pos(), decl$1.type()));
                writtenType(decl$1.type(), false);
            } else if (isTypeDecl(decl)) {
                checkTypeName(decl);
            } else {
                scope.names.putIfAbsent(decl.name(), new Symbol(Kind.TOP_LEVEL, decl.pos(), null));
            }
            checkReserved(decl.namePos(), decl.name());
            annotations(decl.annotations());
            typeParams(decl);
            if (decl instanceof Decl.Function decl$2) {
                checkFunctionName(decl$2);
            }
            if (decl instanceof Decl.Value decl$3) {
                checkValueName(decl$3);
            }
        }
    }
    private void checkValueName(Decl.Value value) {
        if (value.isPrivate() && fileIsInterface()) {
            error(value.namePos(), ErrorCode.SYNTAX, "a value in the file of an interface cannot be private");
        }
        if (value.name().equals("args") && hasMain()) {
            error(value.namePos(), ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` " +
                    "method, which would read it instead of this value; choose another name");
        }
    }
    private boolean hasMain() {
        return Nulls.stream(file.decls()).anyMatch(d -> d instanceof Decl.Function d$1 && isMain(d$1));
    }
    private void checkFileTypeMembers() {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.ClassDecl decl$1 && decl$1.name().equals(file.className())) {
                classFileMembers(decl$1);
            } else if (decl instanceof Decl.DataClass decl$2 && decl$2.name().equals(file.className())) {
                recordFileMembers(decl$2);
            } else if (decl instanceof Decl.Interface decl$3 && decl$3.name().equals(file.className())) {
                interfaceFileMembers(decl$3);
            }
        }
    }
    private void interfaceFileMembers(Decl.Interface iface) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1 && hasSignatureLike(iface, decl$1)) {
                error(decl$1.namePos(), ErrorCode.DUPLICATE_NAME, "`" + iface.name() + "`, the type of this file, requires a method `" +
                        decl$1.name() +
                        "` with as many parameters, so a function of the file cannot have its name; rename one");
            }
        }
    }
    private boolean hasSignatureLike(Decl.Interface iface, Decl.Function f) {
        return (Nulls.stream(iface.methods()).anyMatch(m -> m.name().equals(f.name()) && m.params().size() == f.params().size())) ||
                (f.params().isEmpty() && (Nulls.stream(iface.members()).anyMatch(m -> m.name().equals(f.name()))));
    }
    private void classFileMembers(Decl.ClassDecl c) {
        final var properties = new HashSet<String>();
        for (final var p : c.parameters()) {
            properties.add(p.name());
        }
        for (final var p : c.properties()) {
            properties.add(p.name());
        }
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && properties.contains(decl$1.name())) {
                error(decl$1.namePos(), ErrorCode.DUPLICATE_NAME, "`" + decl$1.name() + "` is a property of `" + c.name() +
                        "`, the type of this file, so a value of the file cannot have its name; rename one");
            } else if (decl instanceof Decl.Function decl$2 && hasMethodLike(c, decl$2)) {
                error(decl$2.namePos(), ErrorCode.DUPLICATE_NAME, "`" + c.name() + "`, the type of this file, has a method `" +
                        decl$2.name() +
                        "` with as many parameters, so a function of the file cannot have its name; rename one");
            }
        }
    }
    private boolean hasMethodLike(Decl.ClassDecl c, Decl.Function f) {
        return Nulls.stream(c.methods()).anyMatch(m -> m.name().equals(f.name()) && m.params().size() == f.params().size());
    }
    private void recordFileMembers(Decl.DataClass data) {
        for (final var decl : file.decls()) {
            final var accessor = decl instanceof Decl.Function decl$1 && decl$1.params().isEmpty() && hasField(data, decl$1.name());
            if (accessor) {
                error(decl.namePos(), ErrorCode.DUPLICATE_NAME, "`" + decl.name() + "()` reads the field `" + decl.name() +
                        "` of `" +
                        data.name() +
                        "`, the type of this file; give the function another name");
            } else if (decl instanceof Decl.Function decl$2 && (Nulls.stream(data.methods()).anyMatch(m -> m.name().equals(decl$2.name()) && m.params().size() == decl$2.params().size()))) {
                error(decl$2.namePos(), ErrorCode.DUPLICATE_NAME, "`" + data.name() + "`, the type of this file, has a method `" +
                        decl$2.name() +
                        "` with as many parameters, so a function of the file cannot have its name; rename one");
            }
        }
    }
    private boolean hasField(Decl.DataClass data, String name) {
        return Nulls.stream(data.fields()).anyMatch(f -> f.name().equals(name));
    }
    private boolean hasTopLevelValue(String name) {
        return Nulls.stream(file.decls()).anyMatch(d -> d instanceof Decl.Value d$1 && d$1.name().equals(name));
    }
    private void checkInitOrder() {
        for (final var problem : InitOrder.check(file)) {
            final var value = problem.value();
            final var position = (problem.later().equals(value.name()) ? "by itself" : "later");
            error(value.namePos(), ErrorCode.INIT_ORDER, "`" + value.name() + "` is computed from `" + problem.later() +
                    "`, which is declared " +
                    position +
                    "; top-level values are set in order, so declare `" +
                    problem.later() +
                    "` first");
        }
        for (final var problem : InitOrder.checkProperties(file)) {
            propertyProblem(problem.property(), problem.later());
        }
    }
    private void propertyProblem(Decl.Property property, String later) {
        if (later.equals(property.name()) && hasTopLevelValue(property.name())) {
            error(property.namePos(), ErrorCode.INIT_ORDER, "`" + property.name() + "` reads itself: in a class the " +
                    "name means the property; write `" +
                    file.className() +
                    "." +
                    property.name() +
                    "` for the value of the file");
            return;
        }
        if (later.equals("this")) {
            error(property.namePos(), ErrorCode.INIT_ORDER, "`" + property.name() + "` passes `this` on before the " +
                    "properties declared after it are set, so the code it reaches may read them as null; declare `" +
                    property.name() +
                    "` last, or compute it in a function");
            return;
        }
        final var position = (later.equals(property.name()) ? "by itself" : "later");
        error(property.namePos(), ErrorCode.INIT_ORDER, "`" + property.name() + "` is computed from `" + later +
                "`, which is declared " +
                position +
                "; properties are set in order, so declare `" +
                later +
                "` first");
    }
    private void classDecl(Decl.ClassDecl c) {
        currentClass = c;
        currentMethods = c.methods();
        final var outerOpen = openTypeParams;
        openTypeParams = openTypeParams(c.typeParams());
        constructors(c);
        scope = new Scope(scope, false);
        for (final var p : c.parameters()) {
            declare(p.namePos(), p.name(), fieldKind(p), p.type());
            annotations(p.annotations());
        }
        final var superArgs = c.superArgs();
        if (superArgs != null) {
            final var parent = c.supertypes().getFirst();
            if (Nulls.stream(file.decls()).anyMatch(d -> d instanceof Decl.Interface d$1 && d$1.name().equals(parent.name()))) {
                error(parent.pos(), ErrorCode.SYNTAX, "an interface takes no `()`: `: " + parent.name() + "`");
            }
            exprs(superArgs);
        }
        for (final var p : c.properties()) {
            annotations(p.annotations());
            final var type = propertyType(p);
            if (type == null) {
                final var keyword = (p.mutable() ? "var " : "val ");
                error(p.pos(), ErrorCode.TYPE_REQUIRED, "a property needs a type: `" + keyword + p.name() + ": Type = ...`");
            }
            declare(p.namePos(), p.name(), fieldKind(p), type);
        }
        for (final var p : c.properties()) {
            final var init = p.init();
            if (init != null) {
                expr(init);
            }
        }
        integerAllowed = Nulls.stream(c.supertypes()).anyMatch(t -> !declaresType(t.name()));
        enclosingTypeParams = Nulls.stream(c.typeParams()).map(Nulls.nonNull(p -> p.name())).toList();
        for (final var m : c.methods()) {
            checkReserved(m.namePos(), m.name());
            typeParams(m);
            function(m);
        }
        enclosingTypeParams = List.of();
        integerAllowed = false;
        pop();
        openTypeParams = outerOpen;
        currentClass = null;
        currentMethods = List.of();
    }
    private void constructors(Decl.ClassDecl c) {
        final var taken = new HashMap<String, Integer>();
        final var primary = new ArrayList<@Nullable TypeRef>();
        for (final var p : c.parameters()) {
            primary.add(p.type());
        }
        taken.put(erasure(primary, c), 0);
        annotations(c.constructorAnnotations());
        for (final var k : c.constructors()) {
            annotations(k.annotations());
            scope = new Scope(scope, false);
            for (final var p : k.params()) {
                declare(p.pos(), p.name(), Kind.PARAM, p.type());
                annotations(p.annotations());
            }
            if (k.args().size() != c.parameters().size()) {
                final var names = Nulls.stream(c.parameters()).map(Nulls.nonNull(p -> "`" + p.name() + "`")).toList();
                final var listed = (names.isEmpty() ? "none" : String.join(", ", names));
                error(k.thisPos(), ErrorCode.SYNTAX, "`this(...)` passes a value to each parameter of `" + c.name() + "` (" +
                        listed +
                        "): " +
                        c.parameters().size() +
                        " values, not " +
                        k.args().size());
            }
            exprs(k.args());
            pop();
            final var types = new ArrayList<@Nullable TypeRef>();
            for (final var p : k.params()) {
                types.add(p.type());
            }
            final var earlier = taken.putIfAbsent(erasure(types, c), k.namePos().line());
            if (earlier != null && earlier == 0 && types.isEmpty()) {
                error(k.namePos(), ErrorCode.DUPLICATE_NAME, "`" + c.name() + "` already has a constructor without " +
                        "parameters; to make it private or annotate it, write it after the class name: `class " +
                        c.name() +
                        " " +
                        headerText(k) +
                        "constructor()`");
            } else if (earlier != null) {
                final var other = (earlier == 0 ? "the one the class's parameters make" : "the one at line " + earlier);
                error(k.namePos(), ErrorCode.DUPLICATE_NAME, "Java cannot tell this constructor from " + other + ": the " +
                        "parameter types are the same once `?` and type arguments are left out; give it other parameters, or " +
                        "create the object in a function of the file");
            }
        }
    }
    private String headerText(Decl.Constructor k) {
        final var sb = new StringBuilder();
        for (final var a : k.annotations()) {
            sb.append("@").append(a.name()).append((a.args().isEmpty() ? " " : "(...) "));
        }
        return sb.toString() + ((k.isPrivate() ? "private " : ""));
    }
    private String erasure(List<@Nullable TypeRef> types, Decl.ClassDecl c) {
        final var erased = new ArrayList<String>();
        for (final var type : types) {
            erased.add((type == null ? "?" : erasedName(type, c)));
        }
        return String.join(",", erased);
    }
    private String erasedName(TypeRef type, Decl.ClassDecl c) {
        for (final var p : c.typeParams()) {
            final var bound = p.bound();
            if (p.name().equals(type.name())) {
                return (bound == null ? "Object" : erasedName(bound, c));
            }
        }
        final var simple = type.name().substring(type.name().lastIndexOf(".") + 1);
        final var boxed = BOXES.get(simple);
        return (boxed != null && type.nullable() ? boxed : simple);
    }
    private Set<String> openTypeParams(List<Decl.TypeParam> params) {
        final var result = new HashSet<String>(openTypeParams);
        for (final var p : params) {
            final var bound = p.bound();
            if (bound == null || bound.nullable()) {
                result.add(p.name());
            } else {
                result.remove(p.name());
            }
        }
        return result;
    }
    private boolean isOpenTypeParam(TypeRef type) {
        return type.args().isEmpty() && openTypeParams.contains(type.name());
    }
    private void dataClassMethods(Decl.DataClass data) {
        if (data.methods().isEmpty()) {
            return;
        }
        scope = new Scope(scope, false);
        for (final var f : data.fields()) {
            declare(f.pos(), f.name(), Kind.FIELD, f.type());
        }
        final var outerMethods = currentMethods;
        final var outerOpen = openTypeParams;
        openTypeParams = openTypeParams(data.typeParams());
        currentMethods = data.methods();
        integerAllowed = Nulls.stream(data.supertypes()).anyMatch(t -> !declaresType(t.name()));
        enclosingTypeParams = Nulls.stream(data.typeParams()).map(Nulls.nonNull(p -> p.name())).toList();
        for (final var m : data.methods()) {
            checkReserved(m.namePos(), m.name());
            if (m.params().isEmpty() && (Nulls.stream(data.fields()).anyMatch(f -> f.name().equals(m.name())))) {
                error(m.namePos(), ErrorCode.DUPLICATE_NAME, "`" + m.name() + "()` already reads the field `" + m.name() +
                        "` of `" +
                        data.name() +
                        "`; give the method another name");
            }
            typeParams(m);
            function(m);
        }
        enclosingTypeParams = List.of();
        integerAllowed = false;
        currentMethods = outerMethods;
        openTypeParams = outerOpen;
        pop();
    }
    private boolean declaresType(String name) {
        return Nulls.stream(file.decls()).anyMatch(d -> isTypeDecl(d) && d.name().equals(name));
    }
    private @Nullable TypeRef propertyType(Decl.Property p) {
        final var written = p.type();
        if (written != null) {
            return written;
        }
        return TypeInference.propertyTypes(file, siblings, currentClass).get(p.name());
    }
    private void typeParams(Decl decl) {
        final List<Decl.TypeParam> params = switch (decl) {
            case Decl.DataClass decl$1 -> decl$1.typeParams();
            case Decl.ClassDecl decl$2 -> decl$2.typeParams();
            case Decl.Interface decl$3 -> decl$3.typeParams();
            case Decl.Function decl$4 -> decl$4.typeParams();
            default -> List.of();
        };
        final var seen = new HashSet<String>();
        for (final var p : params) {
            final var name = p.name();
            if (!Character.isUpperCase(name.charAt(0))) {
                error(p.pos(), ErrorCode.SYNTAX, "a type parameter is a type, so its name starts with a capital letter, as `" +
                        Character.toUpperCase(name.charAt(0)) +
                        name.substring(1) +
                        "`");
            } else if (SHADOWED_TYPES.contains(name) || typeNames.contains(name) || JAVA_PRIMITIVES.containsKey(name)) {
                error(p.pos(), ErrorCode.RESERVED_NAME, "a type parameter named `" + name + "` would hide the type `" + name +
                        "`; choose another name, such as `T`");
            } else if (decl instanceof Decl.Function decl$5 && enclosingTypeParams.contains(name)) {
                error(p.pos(), ErrorCode.DUPLICATE_NAME, "the type parameter `" + name + "` hides the `" + name +
                        "` of the type around the method, which the method can use as it is; for a type of its own choose another name, such as `R`");
            } else if (!seen.add(name)) {
                error(p.pos(), ErrorCode.DUPLICATE_NAME, "the type parameter `" + name + "` is declared twice");
            }
            writtenType(p.bound(), false);
        }
    }
    private Kind fieldKind(Decl.Property p) {
        return (p.mutable() ? Kind.MUTABLE_FIELD : Kind.FIELD);
    }
    private void pop() {
        scope = java.util.Objects.requireNonNull(scope.parent);
    }
    private boolean fileIsInterface() {
        return Nulls.stream(file.decls()).anyMatch(d -> d instanceof Decl.Interface d$1 && d$1.name().equals(file.className()));
    }
    private void checkTypeName(Decl decl) {
        final var name = decl.name();
        if (!Character.isUpperCase(name.charAt(0))) {
            error(decl.namePos(), ErrorCode.SYNTAX, "the name of a type starts with a capital letter, as `" +
                    Character.toUpperCase(name.charAt(0)) +
                    name.substring(1) +
                    "`: `Name(...)` creates an object");
        } else if (name.equals(file.className()) && decl.isPrivate()) {
            error(decl.namePos(), ErrorCode.SYNTAX, "`" + name + "` is the type of this file and cannot be private");
        } else if (SHADOWED_TYPES.contains(name)) {
            error(decl.namePos(), ErrorCode.RESERVED_NAME, "a type named `" + name + "` would hide the `" + name +
                    "` that every Axolotl file uses; choose another name");
        } else if (!typeNames.add(name)) {
            error(decl.namePos(), ErrorCode.DUPLICATE_NAME, "the type `" + name + "` is already declared in this file");
        }
    }
    private void members(List<Expr.Param> list, String what) {
        final var seen = new HashSet<String>();
        for (final var member : list) {
            annotations(member.annotations());
            if (!seen.add(member.name())) {
                error(member.pos(), ErrorCode.DUPLICATE_NAME, "the " + what + " `" + member.name() + "` is declared twice");
            } else if (OBJECT_METHOD_NAMES.contains(member.name())) {
                error(member.pos(), ErrorCode.RESERVED_NAME, "`" + member.name() + "` is a method of every Java object " +
                        "and cannot be the name of a " +
                        what +
                        "; choose another name");
            } else {
                checkReserved(member.pos(), member.name());
            }
            writtenType(member.type(), false);
        }
    }
    private void interfaceMethods(Decl.Interface iface) {
        final var values = Nulls.stream(iface.members()).map(Nulls.nonNull(m -> m.name())).toList();
        final var seen = new HashSet<String>();
        enclosingTypeParams = Nulls.stream(iface.typeParams()).map(Nulls.nonNull(p -> p.name())).toList();
        for (final var m : iface.methods()) {
            annotations(m.annotations());
            typeParams(m);
            if (m.params().isEmpty() && values.contains(m.name())) {
                error(m.namePos(), ErrorCode.DUPLICATE_NAME, "`" + m.name() + "` is already a value of `" + iface.name() +
                        "`, and a value is read as `" +
                        m.name() +
                        "()`; remove one");
            } else if (!seen.add(m.name() + "/" + m.params().size())) {
                error(m.namePos(), ErrorCode.DUPLICATE_NAME, "the method `" + m.name() + "` of `" + iface.name() +
                        "` is declared twice with as many parameters");
            } else {
                checkReserved(m.namePos(), m.name());
            }
            writtenType(m.returnType(), true);
            scope = new Scope(scope, false);
            for (final var p : m.params()) {
                declare(p.pos(), p.name(), Kind.PARAM, p.type());
                annotations(p.annotations());
            }
            pop();
        }
    }
    private void enumConstants(Decl.EnumDecl e) {
        members(e.fields(), "field");
        final var seen = new HashSet<String>();
        var i = 0;
        while (i < e.constants().size()) {
            final var constant = e.constants().get(i);
            final var arguments = e.arguments().get(i);
            if (!seen.add(constant)) {
                error(e.namePos(), ErrorCode.DUPLICATE_NAME, "the constant `" + constant + "` of `" + e.name() + "` is declared twice");
            } else {
                checkReserved(e.namePos(), constant);
            }
            if (arguments.size() != e.fields().size()) {
                final var names = Nulls.stream(e.fields()).map(Nulls.nonNull(f -> f.name())).toList();
                error(e.namePos(), ErrorCode.SYNTAX, (e.fields().isEmpty() ?
                        "`" + constant + "` takes no arguments: `" + e.name() + "` declares no fields; declare them after the name: `enum " +
                        e.name() +
                        "(val code: Int)`" :
                        "`" + constant + "` gives a value for each field of `" + e.name() + "`: `" + constant + "(" + String.join(", ", names) + ")`"));
            }
            exprs(arguments);
            i += 1;
        }
    }
    private void topLevelValue(Decl.Value value) {
        final var init = value.init();
        if (init instanceof Expr.Try init$1) {
            error(init$1.pos(), ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, TRY_NEEDS_RESULT);
            expr(init$1.inner());
        } else {
            expr(init);
        }
    }
    private void function(Decl.Function f) {
        current = f;
        writtenType(f.returnType(), true);
        final var outerOpen = openTypeParams;
        openTypeParams = openTypeParams(f.typeParams());
        scope = new Scope(scope, false);
        for (final var p : f.params()) {
            declare(p.pos(), p.name(), Kind.PARAM, p.type());
            annotations(p.annotations());
        }
        final var exprBody = f.exprBody();
        final var body = f.body();
        if (exprBody != null) {
            expressionBody(f, exprBody);
        } else if (body != null) {
            statements(body.statements());
        }
        pop();
        openTypeParams = outerOpen;
        current = null;
    }
    private void expressionBody(Decl.Function f, Expr body) {
        final var inner = unparenthesized(body);
        if (inner instanceof Expr.When inner$1) {
            whenResults.put(inner$1, f.returnType() != null);
        }
        if (body instanceof Expr.Try body$2) {
            if (!returnsResult(f)) {
                statementTry(body$2);
            } else {
                error(body$2.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
                expr(body$2.inner());
            }
            return;
        }
        if (f.returnType() == null && !doesSomething(body)) {
            error(body.pos(), ErrorCode.SYNTAX, "a function without a result type runs its body as a " +
                    "statement, where this expression does nothing; to return it, add the type: `-> Type`");
        }
        expr(body);
    }
    private void statements(List<Stmt> list) {
        scope = new Scope(scope, false);
        for (final var stmt : list) {
            statement(stmt);
        }
        pop();
    }
    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local stmt$1 -> {
                local(stmt$1);
            }
            case Stmt.Assign stmt$2 -> {
                assign(stmt$2);
            }
            case Stmt.Return stmt$3 -> {
                returnStatement(stmt$3);
            }
            case Stmt.Jump stmt$4 -> {
                jump(stmt$4);
            }
            case Stmt.AttemptExit stmt$5 -> {
                attemptExit(stmt$5);
            }
            case Stmt.Use stmt$6 -> {
                use(stmt$6);
            }
            case Stmt.If stmt$7 -> {
                expr(stmt$7.condition());
                statements(stmt$7.then().statements());
                final var elseBranch = stmt$7.elseBranch();
                if (elseBranch != null) {
                    statement(elseBranch);
                }
            }
            case Stmt.While stmt$8 -> {
                expr(stmt$8.condition());
                loopDepth = loopDepth + 1;
                statements(stmt$8.body().statements());
                loopDepth = loopDepth - 1;
            }
            case Stmt.For stmt$9 -> {
                expr(stmt$9.iterable());
                scope = new Scope(scope, false);
                if (!stmt$9.name().equals("_")) {
                    declare(stmt$9.namePos(), stmt$9.name(), Kind.LOOP_VARIABLE, null);
                }
                loopDepth = loopDepth + 1;
                statements(stmt$9.body().statements());
                loopDepth = loopDepth - 1;
                pop();
            }
            case Stmt.Count stmt$10 -> {
                countLoop(stmt$10);
            }
            case Stmt.ExprStmt stmt$11 -> {
                expressionStatement(stmt$11);
            }
            case Stmt.Block stmt$12 -> {
                statements(stmt$12.statements());
            }
        }
    }
    private void countLoop(Stmt.Count stmt) {
        final var parts = new ArrayList<Expr>(List.of(stmt.from(), stmt.to()));
        final var step = stmt.step();
        if (step != null) {
            parts.add(step);
        }
        @Nullable String type = null;
        var allKnown = true;
        for (final var part : parts) {
            expr(part);
            final var partType = countPartType(part);
            if (partType == null) {
                allKnown = false;
            } else if (!partType.equals("Int") && !partType.equals("Long")) {
                error(part.pos(), ErrorCode.SYNTAX, "a counting `for` counts with `Int` or `Long` values; this is a `" + partType + "`");
                allKnown = false;
            } else if (type == null || partType.equals("Long")) {
                type = partType;
            }
        }
        final var stepValue = (step == null ? null : Expr.integerValue(step));
        if (step != null && stepValue != null && stepValue.signum() <= 0) {
            final var fix = (stmt.kind().equals("downTo") ? ", also with `downTo`: " : "; to count down, write `downTo`: ");
            error(step.pos(), ErrorCode.SYNTAX, "the step of a counting `for` is a positive number" + fix + "`for (i in 10 downTo 0 step 2)`");
        }
        final var loopType = (type != null && (allKnown || type.equals("Long")) ? new TypeRef(stmt.namePos(), type, List.of(), false) : null);
        scope = new Scope(scope, false);
        if (!stmt.name().equals("_")) {
            declare(stmt.namePos(), stmt.name(), Kind.LOOP_VARIABLE, loopType);
        }
        loopDepth = loopDepth + 1;
        statements(stmt.body().statements());
        loopDepth = loopDepth - 1;
        pop();
    }
    private @Nullable String countPartType(Expr e) {
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.Literal inner$1 && inner$1.text().startsWith("'")) {
            return "Char";
        }
        final var known = new HashMap<String, TypeRef>();
        @Nullable Scope s = scope;
        while (s != null) {
            for (final var entry : s.names.entrySet()) {
                final var written = entry.getValue().type();
                if (written != null) {
                    known.putIfAbsent(entry.getKey(), written);
                }
            }
            s = s.parent;
        }
        return Nulls.map(new TypeInference(file, known, siblings).of(inner), $n1 -> $n1.name());
    }
    private void local(Stmt.Local stmt) {
        final var init = unparenthesized(stmt.init());
        if ((init instanceof Expr.Lambda init$1 || isNull(init) || buildsResult(init)) && stmt.type() == null) {
            final var keyword = (stmt.mutable() ? "var" : "val");
            final var what = (init instanceof Expr.Lambda init$2 ? "a lambda" : (isNull(init) ? "`null`" : "branches that build an `Ok` or an `Err`"));
            error(stmt.pos(), ErrorCode.TYPE_REQUIRED, "`" + keyword + " " + stmt.name() +
                    "` needs a type when it is initialized with " +
                    what +
                    ": `" +
                    keyword +
                    " " +
                    stmt.name() +
                    ": Type = ...`");
        }
        declaredType = stmt.type();
        final var value = stmt.init();
        if (value instanceof Expr.Try value$3) {
            statementTry(value$3);
        } else {
            expr(value);
        }
        declaredType = null;
        final var type = stmt.type();
        if (type != null && !type.nullable() && isNull(value)) {
            error(value.pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable(stmt.name(), type));
        }
        final var kind = (stmt.mutable() ? Kind.MUTABLE_LOCAL : Kind.LOCAL);
        declare(stmt.namePos(), stmt.name(), kind, type);
        if (type == null && init instanceof Expr.Call init$4 && init$4.target() == null && typeOfBuild(init$4.name()) != null) {
            createdTypes.put(stmt.namePos(), init$4.name());
        }
        if (stmt.mutable()) {
            unassigned.put(stmt.namePos(), stmt.name());
        }
    }
    private void assign(Stmt.Assign stmt) {
        final var target = stmt.target();
        if (target instanceof Expr.Name target$1) {
            final var symbol = scope.lookup(target$1.name());
            final var notNull = declaredNotNull(symbol);
            if (symbol != null) {
                unassigned.remove(symbol.pos());
            }
            if (symbol != null && symbol.kind() != Kind.MUTABLE_LOCAL && symbol.kind() != Kind.MUTABLE_FIELD) {
                error(stmt.pos(), ErrorCode.ASSIGN_TO_VAL, assignmentProblem(target$1.name(), symbol));
            } else if (notNull != null && isNull(stmt.value())) {
                error(stmt.value().pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable(target$1.name(), notNull));
            }
        }
        expr(target);
        expr(stmt.value());
    }
    private @Nullable TypeRef declaredNotNull(@Nullable Symbol symbol) {
        if (symbol == null) {
            return null;
        }
        final var type = symbol.type();
        if (type != null && !type.nullable() && !isOpenTypeParam(type)) {
            return type;
        }
        return null;
    }
    private void use(Stmt.Use stmt) {
        declaredType = stmt.type();
        expr(stmt.resource());
        declaredType = null;
        scope = new Scope(scope, false);
        declare(stmt.namePos(), stmt.name(), Kind.LOCAL, stmt.type());
        statements(stmt.body().statements());
        pop();
        final var handler = stmt.handler();
        if (handler != null) {
            scope = new Scope(scope, false);
            final var errorName = stmt.errorName();
            final var errorPos = stmt.errorNamePos();
            if (errorName != null && errorPos != null) {
                declare(errorPos, errorName, Kind.PARAM, caught(stmt.errorType()));
            }
            final var inner = unparenthesized(handler);
            if (inner instanceof Expr.Throw inner$1) {
                allowedThrows.add(inner$1);
            } else if (!(inner instanceof Expr.Call inner$2) && !(inner instanceof Expr.GenericNew inner$3)) {
                error(handler.pos(), ErrorCode.SYNTAX, "the `else` of `use` needs a handler that does something: " +
                        "a call such as `println(e)`, or `throw IllegalStateException(e)`; to turn the failure into a value, throw there " +
                        "and handle it where the function is called: `attempt f() else e -> ...`");
            }
            expr(handler);
            pop();
        }
    }
    private void jump(Stmt.Jump stmt) {
        final var word = (stmt.isBreak() ? "break" : "continue");
        if (loopDepth == 0) {
            error(stmt.pos(), ErrorCode.SYNTAX, "`" + word + "` works only inside a `while` or `for` loop" +
                    ((lambdaDepth > 0 ? ", not in a lambda; a lambda ends with `return`" : ", and there is none around it")));
        } else if (valueWhenDepth == lambdaDepth) {
            error(stmt.pos(), ErrorCode.SYNTAX, "`" + word + "` cannot leave a `when` that gives a value; " +
                    "write the `when` as a statement");
        }
    }
    private void returnStatement(Stmt.Return stmt) {
        if (valueWhenDepth == lambdaDepth) {
            error(stmt.pos(), ErrorCode.SYNTAX, "`return` cannot leave a `when` that gives a value; " +
                    "write the `when` as a statement or as `return when ...`");
        }
        final var value = stmt.value();
        if (value == null) {
            return;
        }
        final var inner = unparenthesized(value);
        if (inner instanceof Expr.When inner$1) {
            whenResults.put(inner$1, true);
        }
        if (!(value instanceof Expr.Try value$2)) {
            expr(value);
        } else if (lambdaDepth > 0 || !returnsResult(current)) {
            statementTry(value$2);
        } else {
            error(value$2.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
            expr(value$2.inner());
        }
    }
    private void expressionStatement(Stmt.ExprStmt stmt) {
        final var e = stmt.expr();
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.When inner$1) {
            whenResults.put(inner$1, false);
        }
        if (e instanceof Expr.Throw e$2) {
            allowedThrows.add(e$2);
        }
        if (inner instanceof Expr.Attempt inner$3) {
            attemptStatementShape(inner$3);
        }
        if (e instanceof Expr.Lambda e$4) {
            error(e$4.pos(), ErrorCode.SYNTAX, "a lambda on its own does nothing; to run these statements, remove the braces");
        } else if (inner instanceof Expr.Conditional inner$5 && !stmt.equals(lastInLambda)) {
            error(e.pos(), ErrorCode.SYNTAX, "an `if` statement needs braces: `if (c) { ... } else { ... }`");
        } else if (!doesSomething(e)) {
            final var hint = (stmt.equals(lastInLambda) ? "to return it from the lambda, write `return` before it" : "use its value, for example in a `val`, or remove it");
            error(e.pos(), ErrorCode.SYNTAX, "this expression does nothing on its own; " + hint);
        }
        if (inner instanceof Expr.Call inner$6 && !stmt.equals(lastInLambda) && dropsResult(inner$6)) {
            final var receiver = inner$6.target();
            final var shown = ((receiver instanceof Expr.Name receiver$7 ? receiver$7.name() + "." : "")) + inner$6.name() + "(...)";
            final var at = Nulls.or(Nulls.map(receiver, $n1 -> $n1.pos()), () -> e.pos());
            diagnostics.add(AxlDiagnostic.warning(file.path(), at.line(), at.column(), ErrorCode.UNNEEDED,
                    "the `Result` of `" + shown + "` is dropped; handle the `Err` (`try`, `if (r is Err)`) or keep it with `val unused = ...`"));
        }
        if (e instanceof Expr.Try e$8) {
            statementTry(e$8);
        } else {
            expr(e);
        }
    }
    private boolean dropsResult(Expr.Call c) {
        final var called = calledFunctions(c);
        return !called.isEmpty() && (Nulls.stream(called).allMatch(f -> returnsResult(f)));
    }
    private List<Decl.Function> calledFunctions(Expr.Call c) {
        final var target = c.target();
        if (c.safe()) {
            return List.of();
        }
        if (target == null) {
            final var own = functionsNamed(currentMethods, c);
            if (!own.isEmpty()) {
                return own;
            }
            final var topLevel = functionsNamed(functionsIn(file.decls()), c);
            if (!topLevel.isEmpty()) {
                return topLevel;
            }
            final var imported = Nulls.stream(file.imports()).filter(i -> i.kind() == Import.Kind.FN && i.simpleName().equals(c.name())).toList();
            if (imported.isEmpty()) {
                return List.of();
            }
            final var path = imported.getFirst().name();
            return functionsOfFile(path.substring(0, path.lastIndexOf('.')), c);
        }
        if (!(target instanceof Expr.Name target$1)) {
            return List.of();
        }
        final var symbol = scope.lookup(target$1.name());
        if (symbol == null) {
            return (Character.isUpperCase(target$1.name().charAt(0)) ? functionsOfFile(target$1.name(), c) : List.of());
        }
        final var written = symbol.type();
        final var typeName = (written != null && written.args().isEmpty() ? written.name() : createdTypes.get(symbol.pos()));
        final var decl = (typeName == null ? null : typeOfBuild(typeName));
        switch (decl) {
            case Decl.ClassDecl decl$2 -> {
                return functionsNamed(decl$2.methods(), c);
            }
            case Decl.DataClass decl$3 -> {
                return functionsNamed(decl$3.methods(), c);
            }
            case Decl.Interface decl$4 -> {
                return functionsNamed(decl$4.methods(), c);
            }
            case null, default -> {
                return List.of();
            }
        }
    }
    private List<Decl.Function> functionsOfFile(String name, Expr.Call c) {
        final var dot = name.lastIndexOf('.');
        final var packageName = (dot < 0 ? file.packageName() : name.substring(0, dot));
        final var className = name.substring(dot + 1);
        for (final var sibling : siblings) {
            final var theirs = sibling.packageName();
            final var same = (packageName == null ? Boolean.valueOf(theirs == null) : packageName.equals(theirs));
            if (same && sibling.className().equals(className)) {
                return functionsNamed(functionsIn(sibling.decls()), c).stream().filter(f -> !f.isPrivate()).toList();
            }
        }
        return List.of();
    }
    private @Nullable Decl typeOfBuild(String name) {
        for (final var decl : file.decls()) {
            if (decl.name().equals(name) && isTypeDecl(decl)) {
                return decl;
            }
        }
        for (final var sibling : siblings) {
            final var own = file.packageName();
            final var theirs = sibling.packageName();
            if ((own == null ? Boolean.valueOf(theirs == null) : own.equals(theirs))) {
                for (final var decl : sibling.decls()) {
                    if (decl.name().equals(name) && isTypeDecl(decl)) {
                        return decl;
                    }
                }
            }
        }
        return null;
    }
    private void attemptStatementShape(Expr.Attempt a) {
        attemptBodyShape(a.body());
        final var handler = unparenthesized(a.handler());
        if (!(handler instanceof Expr.Call handler$1) && !(handler instanceof Expr.GenericNew handler$2) && !(handler instanceof Expr.Throw handler$3)) {
            error(a.handler().pos(), ErrorCode.SYNTAX, "`attempt` on a line of its own needs a handler that does something: " +
                    "a call such as `println(e)`, or `throw IllegalStateException(e)`");
        }
    }
    private void attemptBodyShape(Expr body) {
        final var call = unparenthesized(body);
        if (!(call instanceof Expr.Call call$1) && !(call instanceof Expr.GenericNew call$2)) {
            error(body.pos(), ErrorCode.SYNTAX, "`attempt` on a line of its own runs a call: " +
                    "`attempt conn.commit() else e -> ...`; to use a value, keep the result in a `val`");
        }
    }
    private void attemptExit(Stmt.AttemptExit stmt) {
        attemptBodyShape(stmt.body());
        lambdaDepth = lambdaDepth + 1;
        attemptDepth = attemptDepth + 1;
        expr(stmt.body());
        attemptDepth = attemptDepth - 1;
        lambdaDepth = lambdaDepth - 1;
        scope = new Scope(scope, false);
        final var errorName = stmt.errorName();
        final var errorPos = stmt.errorNamePos();
        if (errorName != null && errorPos != null) {
            declare(errorPos, errorName, Kind.PARAM, caught(stmt.errorType()));
        }
        statement(stmt.exit());
        pop();
    }
    private @Nullable TypeRef caught(@Nullable TypeRef type) {
        if (type == null) {
            return null;
        }
        if (type.nullable()) {
            error(type.pos(), ErrorCode.SYNTAX, "a caught exception is never null; remove the `?`: `" + type.name() + "`");
        } else if (!type.args().isEmpty()) {
            error(type.pos(), ErrorCode.SYNTAX, "an exception class takes no type arguments; write `" + type.name() + "`");
        }
        return type;
    }
    private String assignmentProblem(String name, Symbol symbol) {
        final var kind = symbol.kind();
        if (kind == Kind.PARAM) {
            return "cannot assign to the parameter `" + name + "`; copy it into a `var` first: `var current = " + name + "`";
        }
        if (kind == Kind.LOOP_VARIABLE) {
            return "cannot assign to the loop variable `" + name + "`; copy it into a `var` first: `var current = " + name + "`";
        }
        if (kind == Kind.TOP_LEVEL) {
            return topLevelProblem(name);
        }
        if (kind == Kind.FIELD) {
            return "cannot assign to the `val` property `" + name + "`; declare it with `var`";
        }
        return "cannot assign to `val` `" + name + "`; declare it with `var`";
    }
    private String topLevelProblem(String name) {
        final var isFunction = Nulls.stream(file.decls()).anyMatch(d -> d instanceof Decl.Function d$1 && d$1.name().equals(name));
        if (isFunction) {
            return "`" + name + "` is a function, not a variable";
        }
        return "cannot assign to the top-level value `" + name + "`; top-level values cannot change";
    }
    private void statementTry(Expr.Try t) {
        checkTryPlace(t.pos(), "`try`");
        expr(t.inner());
    }
    private void checkTryPlace(Pos pos, String what) {
        final var isTry = what.equals("`try`");
        if (conditionalDepth > 0) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what + " cannot be used in an `if` that gives a value; " +
                    "write an `if` statement");
        } else if (valueWhenDepth == lambdaDepth) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what + " cannot be used in a `when` that gives a value, since it " +
                    "would return from the function in the middle of the `when`; write the `when` as a statement " +
                    "or as `return when ...`");
        } else if (lambdaDepth > 0) {
            final var message = (isTry ? TRY_IN_LAMBDA :
                    what + " cannot be used in a lambda: it would return from the lambda, not the function");
            error(pos, ErrorCode.TRY_IN_EXPRESSION, message);
        } else if (safeTailDepth > 0) {
            final var message = (isTry ? TRY_AFTER_SAFE_CALL :
                    what + " after `?.` runs only for a non-null value; move it into a separate `val`");
            error(pos, ErrorCode.TRY_IN_EXPRESSION, message);
        } else if (returnsNullableResult()) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " needs a function that returns `Result` without `?`; " +
                    "this one may return null instead");
        } else if (!returnsResult(current)) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " can only be used in a function that returns `Result`");
        }
    }
    private boolean returnsNullableResult() {
        final var f = current;
        if (f == null) {
            return false;
        }
        final var type = f.returnType();
        return type != null && type.nullable() && isResult(type);
    }
    private void expr(Expr e) {
        switch (e) {
            case Expr.Literal e$1 -> {
            }
            case Expr.Name e$2 -> {
                name(e$2);
            }
            case Expr.Member e$3 -> {
                if (e$3.safe()) {
                    warnNeverNull(e$3.target(), "write `.` instead of `?.`");
                }
                expr(e$3.target());
            }
            case Expr.Call e$4 -> {
                call(e$4);
            }
            case Expr.GenericNew e$5 -> {
                exprs(e$5.args());
            }
            case Expr.MethodRef e$6 -> {
                expr(e$6.target());
            }
            case Expr.ClassLiteral e$7 -> {
                classLiteral(e$7);
            }
            case Expr.NotNull e$8 -> {
                if (isNull(e$8.target())) {
                    diagnostics.add(AxlDiagnostic.warning(file.path(), e$8.pos().line(), e$8.pos().column(), ErrorCode.UNNEEDED,
                            "`null!!` always fails; throw an exception that says what went wrong instead"));
                }
                warnNeverNull(e$8.target(), "remove `!!`");
                expr(e$8.target());
            }
            case Expr.Unary e$9 -> {
                expr(e$9.operand());
            }
            case Expr.Binary e$10 -> {
                nullComparison(e$10);
                expr(e$10.left());
                expr(e$10.right());
            }
            case Expr.Elvis e$11 -> {
                warnNeverNull(e$11.left(), "remove `?:` and its fallback");
                expr(e$11.left());
                final var fallback = unparenthesized(e$11.right());
                if (fallback instanceof Expr.Throw fallback$12) {
                    allowedThrows.add(fallback$12);
                }
                expr(e$11.right());
            }
            case Expr.Is e$13 -> {
                expr(e$13.target());
            }
            case Expr.Conditional e$14 -> {
                expr(e$14.condition());
                checkBranchTypes(e$14.pos(), "`if`", List.of(e$14.then(), e$14.otherwise()));
                checkAllNull(e$14.pos(), "`if`", List.of(e$14.then(), e$14.otherwise()));
                conditionalDepth = conditionalDepth + 1;
                expr(e$14.then());
                expr(e$14.otherwise());
                conditionalDepth = conditionalDepth - 1;
            }
            case Expr.Throw e$15 -> {
                if (!allowedThrows.contains(e$15)) {
                    error(e$15.pos(), ErrorCode.SYNTAX, "`throw` can stand as a statement, as a branch of a `when` or as " +
                            "the handler of an `attempt`, not inside an expression");
                }
                expr(e$15.value());
            }
            case Expr.When e$16 -> {
                whenExpr(e$16);
            }
            case Expr.Paren e$17 -> {
                expr(e$17.inner());
            }
            case Expr.Try e$18 -> {
                checkTryPlace(e$18.pos(), "`try`");
                if (buildsResult(e$18.inner())) {
                    error(e$18.pos(), ErrorCode.TYPE_REQUIRED, "`try` needs the type of these `Ok` and `Err` branches: " +
                            "compute them in a `val` with a type first, such as `val r: Result<Int, String> = ...`");
                }
                expr(e$18.inner());
            }
            case Expr.Attempt e$19 -> {
                attemptExpr(e$19);
            }
            case Expr.Pipe e$20 -> {
                pipeExpr(e$20);
            }
            case Expr.Lambda e$21 -> {
                lambdaExpr(e$21, false);
            }
        }
    }
    private void exprs(List<Expr> list) {
        for (final var item : list) {
            expr(item);
        }
    }
    private void name(Expr.Name e) {
        final var symbol = scope.lookup(e.name());
        if (symbol == null) {
            staticNames.add(e);
            return;
        }
        if (symbol.kind() == Kind.MUTABLE_LOCAL) {
            mutableRefs.add(e);
        }
        if (symbol.kind() == Kind.FIELD || symbol.kind() == Kind.MUTABLE_FIELD) {
            fieldNames.add(e);
        }
        if (symbol.kind() != Kind.IMPORTED && symbol.kind() != Kind.MUTABLE_FIELD) {
            stableNames.add(e);
        }
    }
    private void call(Expr.Call e) {
        final var target = e.target();
        if (target != null && e.safe()) {
            warnNeverNull(target, "write `.` instead of `?.`");
        }
        if (target != null) {
            expr(target);
        } else if (localLookup(e.name()) != null && !functionExists(e.name())) {
            error(e.pos(), ErrorCode.JAVAC, "`" + e.name() + "` is a value, not a function; " +
                    "call its method, such as `" +
                    e.name() +
                    ".apply(...)` or `" +
                    e.name() +
                    ".get()`");
        }
        final var safeTail = e.safe() || (target != null && hasSafeStep(target));
        if (safeTail) {
            safeTailDepth = safeTailDepth + 1;
        }
        exprs(e.args());
        if (safeTail) {
            safeTailDepth = safeTailDepth - 1;
        }
    }
    private boolean functionExists(String name) {
        if ((Nulls.stream(currentMethods).anyMatch(f -> f.name().equals(name))) ||
                (functionsIn(file.decls()).stream().anyMatch(f -> f.name().equals(name))) ||
                (Nulls.stream(file.imports()).anyMatch(i -> i.kind() == Import.Kind.FN && i.simpleName().equals(name)))) {
            return true;
        }
        final var c = currentClass;
        return c != null && (Nulls.stream(c.supertypes()).anyMatch(t -> inheritsMethod(t, name)));
    }
    private boolean inheritsMethod(TypeRef supertype, String name) {
        final var library = Libraries.classNamed(file, classes, supertype.name());
        return library != null && !classes.signatures(library.name(), name).isEmpty();
    }
    private void attemptExpr(Expr.Attempt e) {
        lambdaDepth = lambdaDepth + 1;
        attemptDepth = attemptDepth + 1;
        expr(e.body());
        scope = new Scope(scope, false);
        final var errorName = e.errorName();
        final var errorPos = e.errorNamePos();
        if (errorName != null && errorPos != null) {
            declare(errorPos, errorName, Kind.PARAM, caught(e.errorType()));
        }
        final var handler = unparenthesized(e.handler());
        if (handler instanceof Expr.Throw handler$1) {
            allowedThrows.add(handler$1);
        }
        expr(e.handler());
        pop();
        attemptDepth = attemptDepth - 1;
        lambdaDepth = lambdaDepth - 1;
    }
    private void whenExpr(Expr.When w) {
        final var result = whenResults.get(w);
        final var gives = result == null;
        final var returns = result != null && result;
        checkWhenPlace(w, gives);
        expr(w.subject());
        scope = new Scope(scope, false);
        final var bindingName = w.bindingName();
        final var bindingPos = w.bindingPos();
        if (bindingName != null && bindingPos != null) {
            declare(bindingPos, bindingName, Kind.LOCAL, null);
        }
        checkArms(w, gives || returns);
        if (gives || returns) {
            final var values = armValues(w);
            checkBranchTypes(w.pos(), "`when`", values);
            if (values.size() == w.arms().size()) {
                checkAllNull(w.pos(), "`when`", values);
            }
        }
        final var outerDepth = valueWhenDepth;
        if (gives) {
            valueWhenDepth = lambdaDepth;
        }
        for (final var arm : w.arms()) {
            checkArm(arm, gives, returns);
        }
        valueWhenDepth = outerDepth;
        pop();
    }
    private void checkBranchTypes(Pos pos, String construct, List<Expr> branches) {
        final var types = new LinkedHashSet<String>();
        for (final var b : branches) {
            final var type = TypeInference.typeOf(file, b);
            if (type != null && !type.nullable() && BASIC_TYPES.contains(type.name())) {
                types.add((NUMBER_TYPES.contains(type.name()) ? "number" : type.name()));
            }
        }
        if (types.size() > 1) {
            final var names = String.join("` and `", types.stream().map(Nulls.nonNull(t -> (t.equals("number") ? "Int" : t))).toList());
            error(pos, ErrorCode.SYNTAX, "the branches of this " + construct + " give values of different types, `" +
                    names +
                    "`; give every branch the same type");
        }
    }
    private void checkAllNull(Pos pos, String construct, List<Expr> branches) {
        if (branches.size() > 1 && (branches.stream().allMatch(b -> isNull(b)))) {
            error(pos, ErrorCode.SYNTAX, "every branch of this " + construct + " gives `null`; write `null` instead");
        }
    }
    private List<Expr> armValues(Expr.When w) {
        final var values = new ArrayList<Expr>();
        for (final var arm : w.arms()) {
            final var body = arm.body();
            final var last = (body.size() == 1 ? body.getFirst() : null);
            if (last instanceof Stmt.ExprStmt last$1) {
                values.add(last$1.expr());
            }
        }
        return values;
    }
    private void checkWhenPlace(Expr.When w, boolean gives) {
        if (!gives) {
            return;
        }
        if (current == null) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value must be inside a function");
        } else if (conditionalDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside an `if` that gives a value");
        } else if (expressionLambdas > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside a lambda of one expression; " +
                    "give the lambda statements and write `return when ...`");
        } else if (attemptDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside `attempt`; " +
                    "compute it in a `val` first");
        } else if (safeTailDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be an argument after `?.`; " +
                    "compute it in a `val` first");
        }
    }
    private void checkArm(Expr.Arm arm, boolean gives, boolean returns) {
        if ((gives || returns) && endsWithIfOfValues(arm.body())) {
            final var use = (returns ? "is returned" : "gives a value");
            final var alternative = (returns ? ", or `return` in each of its branches" : "");
            error(arm.body().getLast().pos(), ErrorCode.SYNTAX, "this `if` ends a branch of a `when` that " + use + ", so the " +
                    "branch gives no value; write the `if` as a value, `if (c) a else b` without braces" +
                    alternative);
            armWithIfOfValues(arm.body());
            return;
        }
        final List<Stmt> body = (returns ? Whens.returning(arm) : arm.body());
        if (gives && !endsWithValue(arm.body()) && !endsWithJump(arm.body())) {
            error(arm.pos(), ErrorCode.SYNTAX, "each branch of a `when` that gives a value ends with the value");
        }
        if (gives) {
            valueArm(body);
        } else {
            statements(body);
        }
    }
    private void armWithIfOfValues(List<Stmt> body) {
        scope = new Scope(scope, false);
        for (final var stmt : body.subList(0, body.size() - 1)) {
            statement(stmt);
        }
        valueIf(body.getLast());
        pop();
    }
    private void valueIf(Stmt stmt) {
        if (stmt instanceof Stmt.If stmt$1) {
            expr(stmt$1.condition());
            valueArm(stmt$1.then().statements());
            final var elseBranch = stmt$1.elseBranch();
            if (elseBranch != null) {
                valueIf(elseBranch);
            }
        } else if (stmt instanceof Stmt.Block stmt$2) {
            valueArm(stmt$2.statements());
        }
    }
    private boolean endsWithValue(List<Stmt> body) {
        return !body.isEmpty() && body.getLast() instanceof Stmt.ExprStmt;
    }
    private boolean endsWithJump(List<Stmt> body) {
        return !body.isEmpty() && (body.getLast() instanceof Stmt.Jump || body.getLast() instanceof Stmt.Return);
    }
    private boolean endsWithIfOfValues(List<Stmt> body) {
        if (body.isEmpty()) {
            return false;
        }
        final var last = body.getLast();
        return last instanceof Stmt.If last$1 && branchesGiveValues(last$1);
    }
    private boolean branchesGiveValues(Stmt.If ifStmt) {
        final var elseBranch = ifStmt.elseBranch();
        if (elseBranch == null || !endsWithPlainValue(ifStmt.then().statements())) {
            return false;
        }
        if (elseBranch instanceof Stmt.If elseBranch$1) {
            return branchesGiveValues(elseBranch$1);
        }
        return elseBranch instanceof Stmt.Block elseBranch$2 && endsWithPlainValue(elseBranch$2.statements());
    }
    private boolean endsWithPlainValue(List<Stmt> body) {
        if (body.isEmpty()) {
            return false;
        }
        final var last = body.getLast();
        return last instanceof Stmt.ExprStmt last$1 && !(last$1.expr() instanceof Expr.Throw);
    }
    private void valueArm(List<Stmt> body) {
        scope = new Scope(scope, false);
        var i = 0;
        while (i < body.size()) {
            final var stmt = body.get(i);
            if (i == body.size() - 1 && stmt instanceof Stmt.ExprStmt stmt$1) {
                final var value = stmt$1.expr();
                if (value instanceof Expr.Throw value$2) {
                    allowedThrows.add(value$2);
                }
                expr(value);
            } else {
                statement(stmt);
            }
            i += 1;
        }
        pop();
    }
    private void checkArms(Expr.When w, boolean needsElse) {
        if (w.arms().isEmpty()) {
            error(w.pos(), ErrorCode.SYNTAX, "this `when` has no branches; add one for each case, or an `else ->` branch");
            return;
        }
        var sawElse = false;
        var types = false;
        var constants = false;
        for (final var arm : w.arms()) {
            if (sawElse) {
                error(arm.pos(), ErrorCode.SYNTAX, "`else` must be the last branch of a `when`");
            }
            if (arm.conditions().isEmpty()) {
                sawElse = true;
            }
            for (final var condition : arm.conditions()) {
                if (condition.type() != null) {
                    types = true;
                } else if (!isNullConstant(condition)) {
                    constants = true;
                }
            }
        }
        if (types && constants) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` tests either types with `is` or constants, not both");
        } else if (constants && needsElse && !sawElse && !coversEnum(w) && !Exhaustiveness.coversLibraryEnum(w, file, classes)) {
            final var missing = Nulls.or(Exhaustiveness.missingConstants(w, file, siblings), () -> Exhaustiveness.missingLibraryConstants(w, file, classes));
            error(w.pos(), ErrorCode.SYNTAX, (missing == null ? elseMessage(w) :
                    "this `when` does not name " + missing + "; add a branch for each, or an `else ->` branch"));
        } else if (types && !sawElse) {
            final var problem = Nulls.or(Exhaustiveness.typesProblem(w, file, siblings), () -> Exhaustiveness.libraryTypesProblem(w, file, siblings, classes));
            if (problem != null) {
                error(w.pos(), ErrorCode.SYNTAX, problem);
            }
        }
    }
    private String elseMessage(Expr.When w) {
        final var booleans = Nulls.stream(w.arms()).allMatch(a -> Nulls.stream(a.conditions()).allMatch(c -> isBooleanConstant(c)));
        return (booleans ? "a `when` over `true` and `false` that gives a value still needs an `else` branch; use `if (c) a else b`" :
                "a `when` over constants that gives a value needs an `else` branch");
    }
    private boolean isBooleanConstant(Expr.Cond c) {
        final var text = Nulls.map(c.constant(), $n1 -> $n1.text());
        return "true".equals(text) || "false".equals(text);
    }
    private boolean coversEnum(Expr.When w) {
        final var named = new HashSet<String>();
        for (final var arm : w.arms()) {
            for (final var condition : arm.conditions()) {
                final var constant = condition.constant();
                if (constant != null && !isNullConstant(condition)) {
                    named.add(constant.text());
                }
            }
        }
        for (final var decl : Exhaustiveness.declsOf(file, siblings)) {
            if (decl instanceof Decl.EnumDecl decl$1 && !decl$1.constants().isEmpty() && namesAll(decl$1, named)) {
                return true;
            }
        }
        return false;
    }
    private boolean namesAll(Decl.EnumDecl e, Set<String> named) {
        final var allowed = new HashSet<String>();
        for (final var c : e.constants()) {
            allowed.add(c);
            allowed.add(e.name() + "." + c);
        }
        final var covered = Nulls.stream(e.constants()).allMatch(c -> named.contains(e.name() + "." + c) || named.contains(c));
        return covered && allowed.containsAll(named);
    }
    private void pipeExpr(Expr.Pipe p) {
        final var declared = declaredType;
        declaredType = null;
        expr(p.source());
        final var stages = p.stages();
        var i = 0;
        while (i < stages.size()) {
            final var stage = stages.get(i);
            final var isLast = i == stages.size() - 1;
            final var lambda = stage.lambda();
            checkStage(stage, isLast);
            if (stage.name().equals("mapResult") && lambda != null) {
                mapResult(stage, lambda, (isLast ? declared : null));
            }
            if (stage.name().equals("map") && lambda != null) {
                mapToResults(stage, lambda, (isLast ? declared : null));
            }
            exprs(stage.args());
            if (lambda != null) {
                final var outerStage = valueStage;
                valueStage = !stage.name().equals("forEach") && !stage.name().equals("peek");
                lambdaExpr(lambda, !stage.name().equals("sorted"));
                valueStage = outerStage;
            }
            i += 1;
        }
    }
    private void checkStage(Expr.Stage stage, boolean isLast) {
        final var name = stage.name();
        final var hasLambda = stage.lambda() != null;
        final var noArguments = STAGES_WITHOUT_ARGUMENTS.contains(name);
        final var known = STAGES_WITH_LAMBDA.contains(name) || STAGES_WITH_ARGUMENT.contains(name) ||
                noArguments ||
                name.equals("sorted");
        if (!known) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "unknown pipe stage `" + name + "`; use filter, map, mapResult, peek, " +
                    "sorted, distinct, limit, skip, forEach, anyMatch, allMatch, noneMatch, count or first; for a sum, a maximum, " +
                    "text or a reduction use the stream: `xs.stream().mapToInt({ n -> n }).sum()`, `.max(..)`, " +
                    "`.collect(Collectors.joining(\",\"))`, `.reduce(..)`, `.flatMap(..)`");
        } else if (STAGES_WITH_LAMBDA.contains(name) && (!hasLambda || !stage.args().isEmpty())) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes a lambda: `|> " + name + " { ... }`");
        } else if (STAGES_WITH_ARGUMENT.contains(name) && (hasLambda || stage.args().size() != 1)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes one argument: `|> " + name + "(n)`");
        } else if (name.equals("sorted") && (stage.args().size() > 1 || (hasLambda && !stage.args().isEmpty()))) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`sorted` takes a comparator, `sorted(byName)`, or a lambda, `sorted { a, b -> ... }`");
        } else if ((noArguments && !stage.args().isEmpty()) || (noArguments && hasLambda)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes no arguments");
        } else if (TERMINAL_STAGES.contains(name) && !isLast) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` ends a pipe and must be its last stage");
        }
    }
    private void mapToResults(Expr.Stage stage, Expr.Lambda lambda, @Nullable TypeRef declared) {
        final var body = lambda.body();
        final var last = (body.size() == 1 ? body.getFirst() : null);
        if (!(last instanceof Stmt.ExprStmt last$1) || (!buildsResult(last$1.expr()) && !isResultCall(last$1.expr()))) {
            final var mapped = (last instanceof Stmt.ExprStmt last$2 && declared == null ? MapResultTypes.mappedResult(stage, file, currentMethods) : null);
            if (mapped != null) {
                mapResultTypes.put(stage, mapped);
            }
            return;
        }
        if (declared != null && declared.args().size() == 1) {
            mapResultTypes.put(stage, declared.args().getFirst());
        } else {
            error(stage.pos(), ErrorCode.TYPE_REQUIRED, "a `map` whose lambda builds an `Ok` or an `Err` needs the " +
                    "element type: declare the result as `val name: List<Result<T, E>> = ...`");
        }
    }
    private void mapResult(Expr.Stage stage, Expr.Lambda lambda, @Nullable TypeRef declared) {
        checkTryPlace(stage.pos(), "`mapResult`");
        final var body = lambda.body();
        if (body.size() != 1 || !(body.getFirst() instanceof Stmt.ExprStmt)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "the lambda of `mapResult` must be a single expression");
            return;
        }
        final var element = MapResultTypes.elementType(stage, file, currentMethods, declared);
        if (element == null) {
            error(stage.pos(), ErrorCode.TYPE_REQUIRED, "`mapResult` needs the element type: call a function of this file " +
                    "that returns `Result<T, E>`, or declare the result as `val name: List<T> = ...`");
        } else {
            mapResultTypes.put(stage, element);
        }
    }
    private void lambdaExpr(Expr.Lambda l, boolean takesElement) {
        scope = new Scope(scope, false);
        lambdaDepth = lambdaDepth + 1;
        final var outerLoops = loopDepth;
        loopDepth = 0;
        for (final var p : l.params()) {
            declare(p.pos(), p.name(), Kind.PARAM, p.type());
        }
        declareIt(l, takesElement);
        final var body = l.body();
        final var single = onlyExpression(body);
        final var lone = (single == null ? null : unparenthesized(single));
        if (lone instanceof Expr.Attempt lone$1 && isAttemptStatement(lone$1, valueStage)) {
            attemptStatementShape(lone$1);
            statements(body);
        } else if (single != null) {
            expressionLambdas = expressionLambdas + 1;
            if (single instanceof Expr.Throw single$2) {
                allowedThrows.add(single$2);
            }
            expr(single);
            expressionLambdas = expressionLambdas - 1;
        } else {
            final var outerLast = lastInLambda;
            final var outerExpressionLambdas = expressionLambdas;
            final var outerAttempts = attemptDepth;
            final var outerSafeTails = safeTailDepth;
            expressionLambdas = 0;
            attemptDepth = 0;
            safeTailDepth = 0;
            lastInLambda = lastOf(body);
            statements(body);
            lastInLambda = outerLast;
            expressionLambdas = outerExpressionLambdas;
            attemptDepth = outerAttempts;
            safeTailDepth = outerSafeTails;
        }
        loopDepth = outerLoops;
        lambdaDepth = lambdaDepth - 1;
        pop();
    }
    private void declareIt(Expr.Lambda l, boolean takesElement) {
        final var outerIt = localLookup("it");
        if (l.arrow()) {
            return;
        }
        if (takesElement && outerIt != null) {
            error(l.pos(), ErrorCode.SHADOWING, "this lambda's `it` would shadow the `it` declared at line " +
                    outerIt.pos().line() +
                    "; name the parameter: `{ x -> ... }`");
        } else if (outerIt == null && (takesElement || mentionsIt(l.body()))) {
            implicitIt.add(l);
            scope.names.put("it", new Symbol(Kind.PARAM, l.pos(), null));
        }
    }
    private @Nullable Expr onlyExpression(List<Stmt> body) {
        if (body.size() != 1) {
            return null;
        }
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt first$1 && !(first$1.expr() instanceof Expr.Try)) {
            return first$1.expr();
        }
        return null;
    }
    private @Nullable Stmt lastOf(List<Stmt> body) {
        if (body.isEmpty()) {
            return null;
        }
        return body.getLast();
    }
    private @Nullable Symbol localLookup(String name) {
        @Nullable Scope s = scope;
        while (s != null && !s.fileScope) {
            final var symbol = s.names.get(name);
            if (symbol != null && symbol.kind() != Kind.FIELD && symbol.kind() != Kind.MUTABLE_FIELD) {
                return symbol;
            }
            s = s.parent;
        }
        return null;
    }
    private void declare(Pos pos, String name, Kind kind, @Nullable TypeRef type) {
        checkReserved(pos, name);
        writtenType(type, false);
        @Nullable Scope s = scope;
        while (s != null && !s.fileScope) {
            final var existing = s.names.get(name);
            final var property = existing != null && s != scope && (existing.kind() == Kind.FIELD || existing.kind() == Kind.MUTABLE_FIELD);
            if (existing == null || property) {
                s = s.parent;
            } else {
                if (s == scope) {
                    error(pos, ErrorCode.DUPLICATE_NAME, "`" + name + "` is already declared at line " + existing.pos().line());
                } else {
                    error(pos, ErrorCode.SHADOWING, "`" + name + "` shadows the `" + name + "` declared at line " +
                            existing.pos().line() +
                            "; choose another name");
                }
                s = null;
            }
        }
        scope.names.put(name, new Symbol(kind, pos, type));
    }
    private void checkFunctionName(Decl.Function f) {
        final var signature = f.name() + "/" + f.params().size();
        if (f.name().equals("Ok") || f.name().equals("Err")) {
            error(f.namePos(), ErrorCode.RESERVED_NAME, "`" + f.name() + "(...)` makes a `Result`, so a function of " +
                    "this name would never be called; choose another name");
        } else if (OBJECT_METHODS.contains(signature)) {
            error(f.namePos(), ErrorCode.RESERVED_NAME, "every Java object has a method `" + f.name() +
                    "` with as many parameters, which a function cannot replace; choose another name");
        }
    }
    private void checkReserved(Pos pos, String name) {
        if (RESERVED.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` cannot be used as a name: Java, which the code becomes, reserves it");
        } else if (PACKAGE_ROOTS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the `" + name +
                    "` package that the generated code uses; choose another name");
        } else if (GENERATED_CODE_RECEIVERS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the class `" + name +
                    "` that the generated code uses; choose another name");
        } else if (name.equals("args") && inMain()) {
            error(pos, ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` method; choose another name");
        }
    }
    private boolean inMain() {
        final var f = current;
        return f != null && isMain(f);
    }
    private void warnNeverNull(Expr target, String fix) {
        if (!(target instanceof Expr.Name target$1)) {
            return;
        }
        final var symbol = scope.lookup(target$1.name());
        final var written = declaredNotNull(symbol);
        if (symbol != null && written != null && symbol.kind() != Kind.IMPORTED) {
            diagnostics.add(AxlDiagnostic.warning(file.path(), target$1.pos().line(), target$1.pos().column(), ErrorCode.UNNEEDED,
                    "`" + target$1.name() + "` has the type `" + written.name() + "`, which is never null; " + fix));
        }
    }
    private void annotations(List<Decl.Annotation> list) {
        for (final var a : list) {
            final var simple = a.name().substring(a.name().lastIndexOf('.') + 1);
            if (NULLNESS_ANNOTATIONS.contains(simple)) {
                error(a.pos(), ErrorCode.SYNTAX, "nullness is part of the type: write `T?` for a value that may be null " +
                        "and `T` for one that may not, instead of `@" +
                        simple +
                        "`");
            }
            for (final var arg : a.args()) {
                annotationArg(arg);
            }
        }
    }
    private void annotationArg(Decl.AnnotationArg arg) {
        final var value = arg.value();
        if (value != null && !isConstant(value)) {
            error(value.pos(), ErrorCode.SYNTAX, "an annotation takes constants: a literal, a name such as `Color.RED`, " +
                    "`String.class`, or `{ a, b }` for several");
        }
        for (final var element : arg.elements()) {
            annotationArg(element);
        }
    }
    private void classLiteral(Expr.ClassLiteral e) {
        final var path = Expr.pathName(e.target());
        final var target = e.target();
        final var value = path != null && !path.contains(".") && Character.isLowerCase(path.charAt(0));
        if (path == null || value) {
            error(e.pos(), ErrorCode.SYNTAX, "`.class` goes after the name of a type, as in `String.class`; for the class " +
                    "of a value call `getClass()`");
        } else if (target instanceof Expr.Name target$1) {
            staticNames.add(target$1);
        }
    }
    private void nullComparison(Expr.Binary e) {
        if (!e.op().equals("==") && !e.op().equals("!=")) {
            return;
        }
        final var always = (e.op().equals("==") ? "false" : "true");
        if (isNull(e.right())) {
            warnNeverNull(e.left(), "this comparison is always " + always);
        } else if (isNull(e.left())) {
            warnNeverNull(e.right(), "this comparison is always " + always);
        }
    }
    private void writtenType(@Nullable TypeRef type, boolean isResult) {
        if (type == null) {
            return;
        }
        final var name = type.name();
        final var own = JAVA_PRIMITIVES.get(name);
        if (own != null) {
            error(type.pos(), ErrorCode.SYNTAX, "`" + name + "` is Java's name for this type; write `" + own + "`");
        } else if (isResult && name.equals("void")) {
            error(type.pos(), ErrorCode.SYNTAX, "a function that returns nothing has no result type; remove `-> " + name + "`");
        } else if (name.equals("void")) {
            error(type.pos(), ErrorCode.SYNTAX, "`void` is not the type of a value; only Java functions return it");
        } else if (name.equals("Integer") && !integerAllowed) {
            diagnostics.add(AxlDiagnostic.warning(file.path(), type.pos().line(), type.pos().column(), ErrorCode.SYNTAX,
                    "write `Int` instead of `Integer`; they are the same type, and `Int?` allows null"));
        }
    }
    private void error(Pos pos, ErrorCode code, String message) {
        diagnostics.add(AxlDiagnostic.error(file.path(), pos.line(), pos.column(), code, message));
    }
    public static final String TRY_IN_LAMBDA =
            "`try` cannot be used in a lambda or `attempt`: it would not return from the function; move it into a `val` first, " +
            "or use `|> mapResult` for a collection";
    public static final String TRY_AFTER_SAFE_CALL =
            "`try` after `?.` runs only for a non-null value; move `try` into a separate `val`";
    public static final String RETURN_TRY =
            "`return try e` makes no sense: `try` unwraps the value, but the function returns `Result`; write `return e`";
    public static final String TRY_NEEDS_RESULT = "`try` can only be used in a function that returns `Result`";
    private static final Set<String> STAGES_WITH_LAMBDA = Set.of("filter", "map", "peek", "mapResult", "forEach",
            "anyMatch", "allMatch", "noneMatch");
    private static final Set<String> STAGES_WITH_ARGUMENT = Set.of("limit", "skip");
    private static final Set<String> STAGES_WITHOUT_ARGUMENTS = Set.of("distinct", "count", "first");
    public static final Set<String> TERMINAL_STAGES = Set.of("forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "enum", "extends", "final", "finally", "float", "for",
            "goto", "implements", "instanceof", "int", "interface", "long", "native", "new", "protected",
            "public", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "void", "volatile", "_",
            "var", "yield", "sealed", "true", "false", "null",
            "package", "import", "if", "else", "while", "return", "try");
    private static final Set<String> CLASS_RESERVED = Set.of("record", "permits");
    private static final Set<String> GENERATED_CODE_CLASSES = Set.of("Nulls", "Result", "Ok", "Err", "System", "Nullable",
            "NullMarked", "String", "Integer", "Long", "Double", "Boolean", "Object", "Exception");
    private static final Set<String> OBJECT_METHODS = Set.of("toString/0", "hashCode/0", "equals/1", "getClass/0",
            "notify/0", "notifyAll/0", "wait/0", "wait/1", "wait/2", "clone/0", "finalize/0");
    private static final Set<String> OBJECT_METHOD_NAMES = Set.of("toString", "hashCode", "getClass", "clone", "finalize",
            "notify", "notifyAll", "wait");
    private static final Map<String, String> BOXES = Map.of("Int", "Integer", "Long", "java.lang.Long", "Double", "java.lang.Double",
            "Boolean", "java.lang.Boolean");
    private static final Map<String, String> JAVA_PRIMITIVES = Map.of("int", "Int", "long", "Long", "boolean", "Boolean",
            "double", "Double", "char", "Character", "byte", "Byte", "short", "Short", "float", "Float");
    private static final Set<String> NULLNESS_ANNOTATIONS = Set.of("Nullable", "NonNull", "NotNull", "Nonnull", "NullMarked", "NullUnmarked");
    private static final Set<String> GENERATED_CODE_PACKAGES = Set.of("axl.lang", "java.lang", "org.jspecify.annotations");
    private static final Set<String> SHADOWED_TYPES = Set.of("Ok", "Err", "Int", "Long", "Double", "Boolean", "Char", "String",
            "Object", "Integer", "Character", "Number", "Byte", "Short", "Float", "Void", "Math", "Exception",
            "RuntimeException", "Throwable", "Error", "Iterable", "Comparable", "Runnable", "CharSequence", "StringBuilder",
            "Override", "Record", "Enum", "Class", "Thread", "NullMarked", "Nullable", "ByteArray", "CharArray");
    private static final Set<String> GENERATED_CODE_RECEIVERS = Set.of("System", "Nulls", "Result");
    private static final Set<String> PACKAGE_ROOTS = Set.of("java", "axl");
    private static final Set<String> BASIC_TYPES = Set.of("Int", "Long", "Double", "Boolean", "String", "Char");
    private static final Set<String> NUMBER_TYPES = Set.of("Int", "Long", "Double");
    public enum Kind { LOCAL, MUTABLE_LOCAL, LOOP_VARIABLE, PARAM, TOP_LEVEL, IMPORTED, FIELD, MUTABLE_FIELD }
    public record Symbol(Kind kind, Pos pos, @Nullable TypeRef type) {}
    public static final class Scope {
        public final @Nullable Scope parent;
        public final boolean fileScope;
        public final HashMap<String, Symbol> names;
        public Scope(@Nullable Scope parent, boolean fileScope) {
            this.parent = parent;
            this.fileScope = fileScope;
            this.names = new HashMap<String, Symbol>();
        }
        public @Nullable Symbol lookup(String name) {
            @Nullable Scope s = this;
            while (s != null) {
                final var symbol = s.names.get(name);
                if (symbol != null) {
                    return symbol;
                }
                s = s.parent;
            }
            return null;
        }
    }
    public static Analysis check(SourceFile file, List<SourceFile> siblings, JavaClasses classes, List<AxlDiagnostic> diagnostics) {
        final var checker = new Checker(file, siblings, classes);
        checker.run();
        diagnostics.addAll(checker.diagnostics);
        return new Analysis(checker.mutableRefs, checker.implicitIt, checker.stableNames, checker.staticNames,
                checker.fieldNames, checker.mapResultTypes);
    }
    private static boolean isNullConstant(Expr.Cond c) {
        final var constant = c.constant();
        return constant != null && constant.text().equals("null");
    }
    public static boolean isAttemptStatement(Expr e, boolean valueStage) {
        final var inner = unparenthesized(e);
        if (valueStage || !(inner instanceof Expr.Attempt inner$1)) {
            return false;
        }
        final var handler = unparenthesized(inner$1.handler());
        return handler instanceof Expr.Call handler$2 || handler instanceof Expr.GenericNew handler$3 || handler instanceof Expr.Throw handler$4;
    }
    private static boolean buildsResult(Expr e) {
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.Conditional inner$1) {
            return isResultCall(inner$1.then()) || isResultCall(inner$1.otherwise()) || buildsResult(inner$1.then()) ||
                    buildsResult(inner$1.otherwise());
        }
        if (inner instanceof Expr.When inner$2) {
            return Nulls.stream(inner$2.arms()).anyMatch(a -> armBuildsResult(a));
        }
        return false;
    }
    private static boolean armBuildsResult(Expr.Arm arm) {
        final var last = (arm.body().isEmpty() ? null : arm.body().getLast());
        return last instanceof Stmt.ExprStmt last$1 && (isResultCall(last$1.expr()) || buildsResult(last$1.expr()));
    }
    private static boolean isResultCall(Expr e) {
        final var inner = unparenthesized(e);
        return inner instanceof Expr.Call inner$1 && inner$1.target() == null && (inner$1.name().equals("Ok") || inner$1.name().equals("Err"));
    }
    private static boolean isTypeDecl(Decl decl) {
        return decl instanceof Decl.DataClass decl$1 || decl instanceof Decl.Interface decl$2 || decl instanceof Decl.ClassDecl decl$3 || decl instanceof Decl.EnumDecl decl$4;
    }
    private static boolean isMain(Decl.Function f) {
        return f.name().equals("main") && f.params().isEmpty() && f.returnType() == null && !f.isPrivate();
    }
    private static boolean isConstant(Expr e) {
        switch (e) {
            case Expr.Literal e$1 -> {
                return true;
            }
            case Expr.Name e$2 -> {
                return true;
            }
            case Expr.Member e$3 -> {
                return !e$3.safe() && isConstant(e$3.target());
            }
            case Expr.ClassLiteral e$4 -> {
                return true;
            }
            case Expr.Unary e$5 -> {
                return isConstant(e$5.operand());
            }
            case Expr.Binary e$6 -> {
                return isConstant(e$6.left()) && isConstant(e$6.right());
            }
            case Expr.Paren e$7 -> {
                return isConstant(e$7.inner());
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean isNull(Expr e) {
        final var inner = unparenthesized(e);
        return inner instanceof Expr.Literal inner$1 && inner$1.text().equals("null");
    }
    private static String notNullable(String name, TypeRef type) {
        return "`" + name + "` is declared as `" + type.name() + "`, which does not allow null; declare it as `" + type.name() + "?`";
    }
    private static Expr unparenthesized(Expr e) {
        return (e instanceof Expr.Paren e$1 ? unparenthesized(e$1.inner()) : e);
    }
    private static boolean doesSomething(Expr e) {
        switch (e) {
            case Expr.Paren e$1 -> {
                return doesSomething(e$1.inner());
            }
            case Expr.Binary e$2 -> {
                return (e$2.op().equals("&&") || e$2.op().equals("||")) && doesSomething(e$2.right());
            }
            case Expr.Elvis e$3 -> {
                return doesSomething(e$3.right());
            }
            case Expr.Member e$4 -> {
                return true;
            }
            case Expr.Call e$5 -> {
                return true;
            }
            case Expr.GenericNew e$6 -> {
                return true;
            }
            case Expr.Try e$7 -> {
                return true;
            }
            case Expr.Pipe e$8 -> {
                return true;
            }
            case Expr.Throw e$9 -> {
                return true;
            }
            case Expr.When e$10 -> {
                return true;
            }
            case Expr.Attempt e$11 -> {
                return true;
            }
            case Expr.NotNull e$12 -> {
                return true;
            }
            case Expr.Lambda e$13 -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }
    private static List<Decl.Function> functionsNamed(List<Decl.Function> functions, Expr.Call c) {
        return functions.stream().filter(f -> f.name().equals(c.name()) && f.params().size() == c.args().size()).toList();
    }
    private static List<Decl.Function> functionsIn(List<Decl> decls) {
        final var result = new ArrayList<Decl.Function>();
        for (final var decl : decls) {
            if (decl instanceof Decl.Function decl$1) {
                result.add(decl$1);
            }
        }
        return result;
    }
    private static boolean returnsResult(Decl.@Nullable Function f) {
        if (f == null) {
            return false;
        }
        final var type = f.returnType();
        return type != null && !type.nullable() && isResult(type);
    }
    private static boolean isResult(TypeRef type) {
        return type.name().equals("Result") || type.name().equals("axl.lang.Result");
    }
    private static boolean hasSafeStep(Expr e) {
        switch (e) {
            case Expr.Member e$1 -> {
                return e$1.safe() || hasSafeStep(e$1.target());
            }
            case Expr.Call e$2 -> {
                final var target = e$2.target();
                return e$2.safe() || (target != null && hasSafeStep(target));
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean mentionsIt(List<Stmt> list) {
        for (final var stmt : list) {
            if (ItFinder.usesIt(stmt)) {
                return true;
            }
        }
        return false;
    }
    private static boolean isJavaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        var i = 0;
        while (i < name.length()) {
            if (!Character.isJavaIdentifierPart(name.charAt(i)) || name.charAt(i) == 36) {
                return false;
            }
            i += 1;
        }
        return true;
    }
}
