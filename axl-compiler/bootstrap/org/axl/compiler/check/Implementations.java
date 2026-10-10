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
    public static List<Mismatch> check(SourceFile file, List<SourceFile> siblings) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        final var problems = new ArrayList<Mismatch>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.DataClass decl$1) {
                implementer(file, siblings, decl$1, fieldsOf(decl$1.fields()), decl$1.supertypes(), problems);
            } else if (decl instanceof Decl.ClassDecl decl$2) {
                implementer(file, siblings, decl$2, propertiesOf(decl$2), decl$2.supertypes(), problems);
            }
        }
        return problems;
    }
    private static void implementer(SourceFile file, List<SourceFile> siblings, Decl decl, Map<String, Given> given,
            List<TypeRef> supertypes, List<Mismatch> problems) {
        final var seen = new HashSet<String>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            final var iface = implemented.iface();
            for (final var member : iface.members()) {
                final var required = substitute(Nulls.sure(member.type()), iface.typeParams(), implemented.args());
                final var field = given.get(member.name());
                if (!seen.add(member.name() + "/0")) {
                } else if (field == null) {
                    problems.add(new Mismatch(decl.namePos(), "`" + decl.name() + "` does not give `val " + member.name() + ": " +
                            text(required) +
                            "`, which the interface `" +
                            iface.name() +
                            "` requires; add it to its parameters"));
                } else {
                    compare(member.name(), field, required, iface, problems);
                }
            }
            for (final var method : iface.methods()) {
                if (!seen.add(method.name() + "/" + method.params().size())) {
                } else {
                    implementation(decl, methodsOf(decl), method, implemented, problems);
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
            default -> {
                return List.of();
            }
        }
    }
    private static void implementation(Decl c, List<Decl.Function> methods, Decl.Function method, Implemented implemented,
            List<Mismatch> problems) {
        final var iface = implemented.iface();
        final var own = Nulls.first(methods.stream().filter(m -> m.name().equals(method.name()) && m.params().size() == method.params().size()).iterator());
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
            problems.add(new Mismatch(own.namePos(), "`" + own.name() + "` implements a method of the interface `" + iface.name() +
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
            case Decl.ClassDecl c$2 -> {
                final var all = new ArrayList<Decl.Property>(c$2.parameters());
                all.addAll(c$2.properties());
                final var found = Nulls.first(all.stream().filter(p -> p.name().equals(name)).iterator());
                return (found == null ? null : new Expr.Param(found.namePos(), found.name(), found.type(), List.of()));
            }
            default -> {
                return null;
            }
        }
    }
    private static String signature(Decl.Function method) {
        final var params = Nulls.stream(method.params()).map(Nulls.nonNull(p -> p.name() + ": " + text(Nulls.sure(p.type())))).toList();
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
        final var substituted = Nulls.stream(type.args()).map(Nulls.nonNull(a -> substitute(a, params, args))).toList();
        return new TypeRef(type.pos(), type.name(), substituted, type.nullable());
    }
    public record RequiredMethod(String name, List<Boolean> boxedParams, boolean boxedResult) {
        public RequiredMethod {
            Nulls.param(name, "name");
            Nulls.param(boxedParams, "boxedParams");
        }
    }
    public static List<RequiredMethod> requiredMethods(SourceFile file, List<SourceFile> siblings, JavaClasses classes, List<TypeRef> supertypes) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(classes, "classes");
        Nulls.param(supertypes, "supertypes");
        final var result = new ArrayList<RequiredMethod>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            final var iface = implemented.iface();
            final var names = Nulls.stream(iface.typeParams()).map(Nulls.nonNull(p -> p.name())).toList();
            for (final var method : iface.methods()) {
                final var params = Nulls.stream(method.params()).map(Nulls.nonNull(p -> isTypeVariable(names, p.type()))).toList();
                result.add(new RequiredMethod(method.name(), params, isTypeVariable(names, method.returnType())));
            }
        }
        for (final var type : supertypes) {
            if (!type.args().isEmpty() && interfaceNamed(file, siblings, type.name()) == null) {
                final var library = Libraries.classNamed(file, classes, type.name());
                if (library != null) {
                    for (final var shape : classes.methods(library.name())) {
                        result.add(new RequiredMethod(shape.name(), shape.typeVariables(), shape.typeVariable()));
                    }
                }
            }
        }
        return result;
    }
    private static boolean isTypeVariable(List<String> names, @Nullable TypeRef type) {
        return type != null && type.args().isEmpty() && names.contains(type.name());
    }
    public static List<String> required(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes) {
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(supertypes, "supertypes");
        final var keys = new ArrayList<String>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            final var iface = implemented.iface();
            for (final var member : iface.members()) {
                keys.add(member.name() + "/0");
            }
            for (final var method : iface.methods()) {
                keys.add(method.name() + "/" + method.params().size());
            }
        }
        return keys;
    }
    private static List<Implemented> interfaces(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes, Set<String> seen) {
        final var result = new ArrayList<Implemented>();
        for (final var type : supertypes) {
            final var decl = interfaceNamed(file, siblings, type.name());
            if (decl != null && seen.add(decl.name())) {
                result.add(new Implemented(decl, type.args()));
                final var parents = Nulls.stream(decl.supertypes()).map(Nulls.nonNull(p -> substitute(p, decl.typeParams(), type.args()))).toList();
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
        for (final var m : c.methods()) {
            if (m.params().isEmpty()) {
                given.put(m.name(), new Given(m.namePos(), m.returnType()));
            }
        }
        return given;
    }
    private static String text(TypeRef type) {
        final var args = Nulls.stream(type.args()).map(Nulls.nonNull(a -> text(a))).toList();
        final var written = (args.isEmpty() ? type.name() : type.name() + "<" + String.join(", ", args) + ">");
        return (type.nullable() ? written + "?" : written);
    }
}
