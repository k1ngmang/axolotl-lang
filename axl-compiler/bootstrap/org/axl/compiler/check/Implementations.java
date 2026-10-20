package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class Implementations {
    private Implementations() {}
    public record Mismatch(Pos pos, String message) {
        public Mismatch {
            Nulls.param(pos, "pos");
            Nulls.param(message, "message");
        }
    }
    public record Given(Pos pos, @Nullable TypeRef type) {
        public Given {
            Nulls.param(pos, "pos");
        }
    }
    public record Implemented(Decl.Interface iface, List<TypeRef> args) {
        public Implemented {
            Nulls.param(iface, "iface");
            Nulls.param(args, "args");
        }
    }
    public record Inherited(Decl.Function method, @Nullable Implemented from) {
        public Inherited {
            Nulls.param(method, "method");
        }
    }
    public static List<Mismatch> check(SourceFile file, List<SourceFile> siblings) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        final var problems = new ArrayList<Mismatch>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.DataClass decl$1) {
                implementer(file, siblings, decl$1, fieldsOf(decl$1.fields()), decl$1.supertypes(), problems);
            } else if (decl instanceof Decl.ClassDecl decl$2) {
                implementer(file, siblings, decl$2, propertiesOf(decl$2), decl$2.supertypes(), problems);
            } else if (decl instanceof Decl.EnumDecl decl$3) {
                implementer(file, siblings, decl$3, withMethods(fieldsOf(decl$3.fields()), decl$3.methods()), decl$3.supertypes(), problems);
            }
        }
        return problems;
    }
    private static void implementer(SourceFile file, List<SourceFile> siblings, Decl decl, Map<String, Given> given,
            List<TypeRef> supertypes, List<Mismatch> problems) {
        final var seen = new HashSet<String>();
        final var all = interfaces(file, siblings, supertypes, new HashSet<String>());
        final var inherited = new ArrayList<Inherited>();
        for (final var i : all) {
            for (final var method : i.iface().methods()) {
                if (!method.isPrivate()) {
                    inherited.add(new Inherited(method, i));
                }
            }
        }
        for (final var implemented : all) {
            final var iface = implemented.iface();
            for (final var member : iface.members()) {
                final var required = substitute(Nulls.sure(member.type()), iface.typeParams(), implemented.args());
                final var field = given.get(member.name());
                if (!seen.add(member.name() + "/")) {
                } else if (field == null) {
                    problems.add(new Mismatch(decl.namePos(), "`" + decl.name() + "` does not give `val " + member.name() + ": " +
                            text(required) +
                            "`, which the interface `" +
                            iface.name() +
                            "` requires; add it to its " +
                            ((decl instanceof Decl.EnumDecl decl$1 ? "fields" : "parameters"))));
                } else {
                    compare(member.name(), field, required, iface, problems);
                }
            }
            for (final var method : iface.methods()) {
                final var key = method.name() + "/" + String.join(",", parameterTypes(new Inherited(method, implemented)));
                if (method.isPrivate() || !seen.add(key)) {
                } else {
                    final var hasDefault = inherited.stream().anyMatch(d -> hasBody(d.method()) && d.method().name().equals(method.name()) &&
                            sameParameters(d, new Inherited(method, implemented)));
                    implementation(decl, methodsOf(decl), method, implemented, inherited, hasDefault, problems);
                }
            }
        }
    }
    public static List<Mismatch> foreignSealed(SourceFile file, List<SourceFile> siblings) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        final var problems = new ArrayList<Mismatch>();
        for (final var decl : file.decls()) {
            for (final var type : supertypesOf(decl)) {
                final var qualified = qualifiedName(file, type.name());
                for (final var sibling : siblings) {
                    for (final var other : sibling.decls()) {
                        if (qualified != null && other instanceof Decl.Interface other$1 && other$1.isSealed() && !samePackage(file, sibling) &&
                                qualified.equals(javaName(sibling, other$1))) {
                            problems.add(new Mismatch(type.pos(), "`" + other$1.name() + "` is sealed, so only the types of its package `" +
                                    sibling.packageName() +
                                    "` can implement it; move `" +
                                    decl.name() +
                                    "` there, or implement an interface " +
                                    "that is not sealed"));
                        }
                    }
                }
            }
        }
        return problems;
    }
    private static List<TypeRef> supertypesOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.supertypes();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.supertypes();
            }
            case Decl.Interface decl$3 -> {
                return decl$3.supertypes();
            }
            case Decl.EnumDecl decl$4 -> {
                return decl$4.supertypes();
            }
            default -> {
                return List.of();
            }
        }
    }
    private static @Nullable String qualifiedName(SourceFile file, String name) {
        if (name.contains(".")) {
            return name;
        }
        for (final var imp : file.imports()) {
            if (imp.kind() == Import.Kind.TYPE && imp.simpleName().equals(name)) {
                return imp.name();
            }
        }
        return null;
    }
    private static String javaName(SourceFile file, Decl decl) {
        final var pkg = file.packageName();
        final var prefix = (pkg == null ? "" : pkg + ".");
        return (decl.name().equals(file.className()) ? prefix + decl.name() : prefix + file.className() + "." + decl.name());
    }
    private static List<Decl.Function> methodsOf(Decl decl) {
        switch (decl) {
            case Decl.ClassDecl decl$1 -> {
                return decl$1.methods();
            }
            case Decl.DataClass decl$2 -> {
                return decl$2.methods();
            }
            case Decl.EnumDecl decl$3 -> {
                return decl$3.methods();
            }
            default -> {
                return List.of();
            }
        }
    }
    private static void implementation(Decl c, List<Decl.Function> methods, Decl.Function method, Implemented implemented,
            List<Inherited> interfaceMethods, boolean hasDefault, List<Mismatch> problems) {
        final var iface = implemented.iface();
        final var named = methods.stream().filter(m -> m.name().equals(method.name()) && m.params().size() == method.params().size()).toList();
        final var matching = Nulls.first(named.stream().filter(m -> sameParameters(new Inherited(m, null), new Inherited(method, implemented))).iterator());
        final var own = (matching != null ? matching : Nulls.first(named.stream().filter(m -> interfaceMethods.stream().noneMatch(i ->
                i.method().name().equals(m.name()) && sameParameters(new Inherited(m, null), i)))
                .iterator()));
        final var declaredReturn = method.returnType();
        final @Nullable TypeRef required = (declaredReturn == null ? null : substitute(declaredReturn, iface.typeParams(), implemented.args()));
        final var property = (own == null && method.params().isEmpty() ? propertyNamed(c, method.name()) : null);
        if (own == null && property != null && c instanceof Decl.DataClass c$1) {
            final var type = property.type();
            if (required == null || (type != null && !fits(type, required))) {
                problems.add(new Mismatch(property.pos(), "`" + property.name() + "` is `" + returnText(type) + "` here, but the interface `" +
                        iface.name() +
                        "` requires `" +
                        signature(method) +
                        "`"));
            }
            return;
        }
        if (own == null && hasDefault) {
            return;
        }
        if (own == null) {
            final var where = (c instanceof Decl.DataClass c$2 ? "add it in a body after the fields: `data class " + c$2.name() + "(...) : " + iface.name() + " { fn ... }`" :
                    (property != null && required != null ? "its property `" + property.name() + "` is a field, which gives no method; " +
                    "write the interface's member as `val " +
                    property.name() +
                    ": " +
                    text(required) +
                    "`, which the property gives" :
                    "add the method"));
            problems.add(new Mismatch(c.namePos(), "`" + c.name() + "` does not give `" + signature(method) + "`, which the interface `" +
                    iface.name() +
                    "` requires; " +
                    where));
            return;
        }
        if (own.isPrivate()) {
            final var does = (hasBody(method) ? "overrides" : "implements");
            problems.add(new Mismatch(own.namePos(), "`" + own.name() + "` " + does + " a method of the interface `" + iface.name() +
                    "`, so it cannot be private"));
        }
        final var givenReturn = own.returnType();
        if ((required == null && givenReturn != null) || (required != null && (givenReturn == null || !fits(givenReturn, required)))) {
            problems.add(new Mismatch(own.namePos(), "`" + own.name() + "` returns `" + returnText(givenReturn) + "` here, but the interface `" +
                    iface.name() +
                    "` requires `" +
                    signature(method) +
                    "`"));
        }
        var i = 0;
        while (i < own.params().size()) {
            final var givenParam = own.params().get(i).type();
            final var declaredParam = method.params().get(i).type();
            final @Nullable TypeRef requiredParam = (declaredParam == null ? null : substitute(declaredParam, iface.typeParams(), implemented.args()));
            if (givenParam != null && requiredParam != null && !fits(requiredParam, givenParam)) {
                problems.add(new Mismatch(own.params().get(i).pos(), "the parameter `" + own.params().get(i).name() + "` is `" +
                        text(givenParam) +
                        "` here, but the interface `" +
                        iface.name() +
                        "` requires `" +
                        signature(method) +
                        "`"));
            }
            i += 1;
        }
    }
    private static Expr.@Nullable Param propertyNamed(Decl c, String name) {
        switch (c) {
            case Decl.DataClass c$1 -> {
                return Nulls.first(Nulls.stream(c$1.fields()).filter(f -> f.name().equals(name)).iterator());
            }
            case Decl.EnumDecl c$2 -> {
                return Nulls.first(Nulls.stream(c$2.fields()).filter(f -> f.name().equals(name)).iterator());
            }
            case Decl.ClassDecl c$3 -> {
                final var all = new ArrayList<Decl.Property>(c$3.parameters());
                all.addAll(c$3.properties());
                final var found = Nulls.first(all.stream().filter(p -> p.name().equals(name)).iterator());
                return (found == null ? null : new Expr.Param(found.namePos(), found.name(), found.type(), List.of()));
            }
            default -> {
                return null;
            }
        }
    }
    private static String signature(Decl.Function method) {
        final var params = Nulls.stream(method.params()).map(Nulls.asGiven(p -> p.name() + ": " + text(Nulls.sure(p.type())))).toList();
        final var returnType = method.returnType();
        return "fn " + method.name() + "(" + String.join(", ", params) + ")" + ((returnType == null ? "" : " -> " + text(returnType)));
    }
    private static String returnText(@Nullable TypeRef type) {
        return (type == null ? "nothing" : text(type));
    }
    private static void compare(String name, Given field, TypeRef required, Decl.Interface iface, List<Mismatch> problems) {
        final var type = field.type();
        if (type != null && !fits(type, required)) {
            problems.add(new Mismatch(field.pos(), "`" + name + "` is `" + text(type) + "` here, but the interface `" +
                    iface.name() +
                    "` requires `val " +
                    name +
                    ": " +
                    text(required) +
                    "`"));
        }
    }
    public static List<Expr.Param> requiredValues(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(supertypes, "supertypes");
        final var values = new ArrayList<Expr.Param>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            values.addAll(implemented.iface().members());
        }
        return values;
    }
    public static TypeRef substitute(TypeRef type, List<Decl.TypeParam> params, List<TypeRef> args) {
        Nulls.param(type, "type");
        Nulls.param(params, "params");
        Nulls.param(args, "args");
        if (params.isEmpty() || params.size() != args.size()) {
            return type;
        }
        var i = 0;
        while (i < params.size()) {
            if (params.get(i).name().equals(type.name()) && type.args().isEmpty()) {
                final var arg = args.get(i);
                return new TypeRef(type.pos(), arg.name(), arg.args(), arg.nullable() || type.nullable());
            }
            i += 1;
        }
        final var substituted = Nulls.stream(type.args()).map(Nulls.asGiven(a -> substitute(a, params, args))).toList();
        return new TypeRef(type.pos(), type.name(), substituted, type.nullable());
    }
    public record RequiredMethod(String name, List<Boolean> boxedParams, boolean boxedResult, @Nullable Inherited method) {
        public RequiredMethod {
            Nulls.param(name, "name");
            Nulls.param(boxedParams, "boxedParams");
        }
    }
    public static boolean isRequired(RequiredMethod r, Decl.Function f) {
        Nulls.param(r, "r");
        Nulls.param(f, "f");
        final var method = r.method();
        return r.name().equals(f.name()) && r.boxedParams().size() == f.params().size() &&
                (method == null || sameParameters(new Inherited(f, null), method));
    }
    public static List<RequiredMethod> requiredMethods(SourceFile file, List<SourceFile> siblings, JavaClasses classes, List<TypeRef> supertypes) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(classes, "classes");
        Nulls.param(supertypes, "supertypes");
        final var result = new ArrayList<RequiredMethod>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            final var iface = implemented.iface();
            final var names = Nulls.stream(iface.typeParams()).map(Nulls.asGiven(p -> p.name())).toList();
            for (final var member : iface.members()) {
                result.add(new RequiredMethod(member.name(), List.of(), isTypeVariable(names, member.type()), null));
            }
            for (final var method : iface.methods()) {
                if (!method.isPrivate()) {
                    final var params = Nulls.stream(method.params()).map(Nulls.asGiven(p -> isTypeVariable(names, p.type()))).toList();
                    result.add(new RequiredMethod(method.name(), params, isTypeVariable(names, method.returnType()), new Inherited(method, implemented)));
                }
            }
        }
        for (final var type : supertypes) {
            if (!type.args().isEmpty() && interfaceNamed(file, siblings, type.name()) == null) {
                final var library = Libraries.classNamed(file, classes, type.name());
                if (library != null) {
                    for (final var shape : classes.methods(library.name())) {
                        result.add(new RequiredMethod(shape.name(), shape.typeVariables(), shape.typeVariable(), null));
                    }
                }
            }
        }
        return result;
    }
    private static boolean isTypeVariable(List<String> names, @Nullable TypeRef type) {
        return type != null && type.args().isEmpty() && names.contains(type.name());
    }
    public static Set<String> boxedFields(SourceFile file, List<SourceFile> siblings, JavaClasses classes, Decl.DataClass dataClass) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(classes, "classes");
        Nulls.param(dataClass, "dataClass");
        final var result = new HashSet<String>();
        final var required = requiredMethods(file, siblings, classes, dataClass.supertypes());
        for (final var field : dataClass.fields()) {
            final var type = field.type();
            final var boxes = required.stream().anyMatch(r -> r.name().equals(field.name()) && r.boxedParams().isEmpty() && r.boxedResult());
            if (boxes && type != null && !type.nullable() && type.args().isEmpty() && PRIMITIVES.contains(type.name())) {
                result.add(field.name());
            }
        }
        return result;
    }
    private static final Set<String> PRIMITIVES = Set.of("Int", "Long", "Boolean", "Float", "Double");
    public static boolean boxedResult(SourceFile file, List<SourceFile> siblings, JavaClasses classes, TypeRef type, String method, int arity) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(classes, "classes");
        Nulls.param(type, "type");
        Nulls.param(method, "method");
        final var found = typeNamed(file, siblings, type.name());
        if (found == null) {
            return false;
        }
        final var decl = found.decl();
        final var supertypes = (decl instanceof Decl.Interface decl$1 ? List.of(type) : supertypesOf(decl));
        return requiredMethods(found.file(), siblings, classes, supertypes).stream()
                .anyMatch(r -> r.name().equals(method) && r.boxedParams().size() == arity && r.boxedResult());
    }
    public record Located(SourceFile file, Decl decl) {
        public Located {
            Nulls.param(file, "file");
            Nulls.param(decl, "decl");
        }
    }
    private static @Nullable Located typeNamed(SourceFile file, List<SourceFile> siblings, String name) {
        final var qualified = qualifiedName(file, name);
        if (qualified == null) {
            for (final var decl : file.decls()) {
                if (isImplementer(decl) && decl.name().equals(name)) {
                    return new Located(file, decl);
                }
            }
        }
        for (final var sibling : siblings) {
            for (final var decl : sibling.decls()) {
                if (isImplementer(decl) && ((qualified == null ? samePackage(file, sibling) && decl.name().equals(name) :
                        qualified.equals(javaName(sibling, decl))))) {
                    return new Located(sibling, decl);
                }
            }
        }
        return null;
    }
    private static boolean isImplementer(Decl decl) {
        return decl instanceof Decl.DataClass decl$1 || decl instanceof Decl.ClassDecl decl$2 || decl instanceof Decl.Interface decl$3;
    }
    public static boolean hasBody(Decl.Function f) {
        Nulls.param(f, "f");
        return f.exprBody() != null || f.body() != null;
    }
    public static List<TypeRef> defaultsThrough(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes, String name, int arity) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(supertypes, "supertypes");
        Nulls.param(name, "name");
        final var result = new ArrayList<TypeRef>();
        for (final var type : supertypes) {
            final var reached = interfaces(file, siblings, List.of(type), new HashSet<String>());
            final var found = reached.stream().anyMatch(i -> Nulls.stream(i.iface().methods()).anyMatch(m -> hasBody(m) && !m.isPrivate() &&
                    m.name().equals(name) &&
                    m.params().size() == arity));
            if (found) {
                result.add(type);
            }
        }
        return result;
    }
    public record Overridable(Set<String> values, List<Inherited> methods) {
        public Overridable {
            Nulls.param(values, "values");
            Nulls.param(methods, "methods");
        }
    }
    public static Overridable overridable(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(supertypes, "supertypes");
        final var values = new HashSet<String>();
        final var methods = new ArrayList<Inherited>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            final var iface = implemented.iface();
            for (final var member : iface.members()) {
                values.add(member.name());
            }
            for (final var method : iface.methods()) {
                if (!method.isPrivate()) {
                    methods.add(new Inherited(method, implemented));
                }
            }
        }
        return new Overridable(values, methods);
    }
    public static boolean overrides(Decl.Function f, Overridable overridable) {
        Nulls.param(f, "f");
        Nulls.param(overridable, "overridable");
        return (f.params().isEmpty() && overridable.values().contains(f.name())) ||
                (overridable.methods().stream().anyMatch(m -> m.method().name().equals(f.name()) && sameParameters(new Inherited(f, null), m)));
    }
    public static boolean sameParameters(Inherited a, Inherited b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        if (a.method().params().size() != b.method().params().size()) {
            return false;
        }
        final var aVariables = typeVariables(a);
        final var bVariables = typeVariables(b);
        var i = 0;
        while (i < a.method().params().size()) {
            final var boxed = aVariables.get(i) || bVariables.get(i);
            if (!erasedParameter(a, i, boxed).equals(erasedParameter(b, i, boxed))) {
                return false;
            }
            i += 1;
        }
        return true;
    }
    public static List<String> parameterTypes(Inherited m) {
        Nulls.param(m, "m");
        final var variables = typeVariables(m);
        final var result = new ArrayList<String>();
        var i = 0;
        while (i < m.method().params().size()) {
            result.add(erasedParameter(m, i, variables.get(i)));
            i += 1;
        }
        return result;
    }
    private static List<Boolean> typeVariables(Inherited m) {
        final var from = m.from();
        final List<String> names = (from == null ? List.of() : Nulls.stream(from.iface().typeParams()).map(Nulls.nonNull(p -> p.name())).toList());
        return Nulls.stream(m.method().params()).map(Nulls.nonNull(p -> isTypeVariable(names, p.type()))).toList();
    }
    private static String erasedParameter(Inherited m, int index, boolean boxed) {
        final var type = m.method().params().get(index).type();
        if (type == null) {
            return "?";
        }
        final var from = m.from();
        final var seen = (from == null ? type : substitute(type, from.iface().typeParams(), from.args()));
        return erasedName(seen, m.method().typeParams(), boxed);
    }
    public static String erasedName(TypeRef type, List<Decl.TypeParam> typeParams, boolean boxed) {
        Nulls.param(type, "type");
        Nulls.param(typeParams, "typeParams");
        for (final var p : typeParams) {
            if (p.name().equals(type.name()) && type.args().isEmpty()) {
                final var bound = p.bound();
                return (bound == null ? "Object" : erasedName(bound, List.of(), false));
            }
        }
        final var simple = type.name().substring(type.name().lastIndexOf(".") + 1);
        final var name = (simple.equals("Integer") ? "Int" : simple);
        return (PRIMITIVES.contains(name) && (boxed || type.nullable() || !simple.equals(name)) ? "java.lang." + name : name);
    }
    private static List<Implemented> interfaces(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes, Set<String> seen) {
        final var result = new ArrayList<Implemented>();
        for (final var type : supertypes) {
            final var decl = interfaceNamed(file, siblings, type.name());
            if (decl != null && seen.add(decl.name())) {
                result.add(new Implemented(decl, type.args()));
                final var parents = Nulls.stream(decl.supertypes()).map(Nulls.asGiven(p -> substitute(p, decl.typeParams(), type.args()))).toList();
                result.addAll(interfaces(file, siblings, parents, seen));
            }
        }
        return result;
    }
    private static Decl.@Nullable Interface interfaceNamed(SourceFile file, List<SourceFile> siblings, String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Interface decl$1 && decl$1.name().equals(name)) {
                return decl$1;
            }
        }
        Decl.@Nullable Interface found = null;
        for (final var sibling : siblings) {
            for (final var decl : sibling.decls()) {
                if (decl instanceof Decl.Interface decl$2 && decl$2.name().equals(name) && samePackage(file, sibling)) {
                    if (found != null) {
                        return null;
                    }
                    found = decl$2;
                }
            }
        }
        return found;
    }
    private static boolean samePackage(SourceFile file, SourceFile other) {
        final var own = file.packageName();
        final var theirs = other.packageName();
        return (own == null ? Boolean.valueOf(theirs == null) : own.equals(theirs));
    }
    private static boolean fits(TypeRef given, TypeRef required) {
        if (given.nullable() && !required.nullable()) {
            return false;
        }
        return given.name().equals(required.name()) && sameArgs(given.args(), required.args());
    }
    private static boolean sameArgs(List<TypeRef> given, List<TypeRef> required) {
        if (given.size() != required.size()) {
            return false;
        }
        var i = 0;
        while (i < given.size()) {
            final var a = given.get(i);
            final var b = required.get(i);
            if (a.nullable() != b.nullable() || !a.name().equals(b.name()) || !sameArgs(a.args(), b.args())) {
                return false;
            }
            i += 1;
        }
        return true;
    }
    private static Map<String, Given> fieldsOf(List<Expr.Param> fields) {
        final var given = new HashMap<String, Given>();
        for (final var f : fields) {
            given.put(f.name(), new Given(f.pos(), f.type()));
        }
        return given;
    }
    private static Map<String, Given> propertiesOf(Decl.ClassDecl c) {
        final var given = new HashMap<String, Given>();
        for (final var p : c.parameters()) {
            given.put(p.name(), new Given(p.namePos(), p.type()));
        }
        for (final var p : c.properties()) {
            given.put(p.name(), new Given(p.namePos(), p.type()));
        }
        return withMethods(given, c.methods());
    }
    private static Map<String, Given> withMethods(Map<String, Given> given, List<Decl.Function> methods) {
        for (final var m : methods) {
            if (m.params().isEmpty()) {
                given.put(m.name(), new Given(m.namePos(), m.returnType()));
            }
        }
        return given;
    }
    private static String text(TypeRef type) {
        final var args = Nulls.stream(type.args()).map(Nulls.asGiven(a -> text(a))).toList();
        final var written = (args.isEmpty() ? type.name() : type.name() + "<" + String.join(", ", args) + ">");
        return (type.nullable() ? written + "?" : written);
    }
}
