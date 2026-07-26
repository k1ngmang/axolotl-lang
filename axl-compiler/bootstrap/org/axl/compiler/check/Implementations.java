package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class Implementations {
    private Implementations() {}
    public record Mismatch(Pos pos, String message) {}
    public record Given(Pos pos, @Nullable TypeRef type) {}
    public record Implemented(Decl.Interface iface, List<TypeRef> args) {}
    public static List<Mismatch> check(SourceFile file) {
        final var problems = new ArrayList<Mismatch>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.DataClass decl$1) {
                implementer(file, decl$1, fieldsOf(decl$1.fields()), decl$1.supertypes(), problems);
            } else if (decl instanceof Decl.ClassDecl decl$2) {
                implementer(file, decl$2, propertiesOf(decl$2), decl$2.supertypes(), problems);
            }
        }
        return problems;
    }
    private static void implementer(SourceFile file, Decl decl, Map<String, Given> given, List<TypeRef> supertypes,
            List<Mismatch> problems) {
        final var seen = new HashSet<String>();
        for (final var implemented : interfaces(file, List.of(), supertypes, new HashSet<String>())) {
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
                } else if (decl instanceof Decl.ClassDecl decl$1) {
                    implementation(decl$1, method, implemented, problems);
                } else {
                    problems.add(new Mismatch(decl.namePos(), "`" + decl.name() + "` is a data class, so it has no methods, but the " +
                            "interface `" +
                            iface.name() +
                            "` requires `" +
                            signature(method) +
                            "`; make `" +
                            decl.name() +
                            "` a class, or write `" +
                            method.name() +
                            "` as a function of the file that takes the `" +
                            iface.name() +
                            "`"));
                }
            }
        }
    }
    private static void implementation(Decl.ClassDecl c, Decl.Function method, Implemented implemented, List<Mismatch> problems) {
        final var iface = implemented.iface();
        final var own = Nulls.first(c.methods().stream().filter(m -> m.name().equals(method.name()) && m.params().size() == method.params().size()).iterator());
        if (own == null) {
            problems.add(new Mismatch(c.namePos(), "`" + c.name() + "` does not give `" + signature(method) + "`, which the interface `" +
                    iface.name() +
                    "` requires; add the method"));
            return;
        }
        if (own.isPrivate()) {
            problems.add(new Mismatch(own.namePos(), "`" + own.name() + "` implements a method of the interface `" + iface.name() +
                    "`, so it cannot be private"));
        }
        final var declaredReturn = method.returnType();
        final @Nullable TypeRef required = (declaredReturn == null ? null : substitute(declaredReturn, iface.typeParams(), implemented.args()));
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
    private static String signature(Decl.Function method) {
        final var params = method.params().stream().map(Nulls.nonNull(p -> p.name() + ": " + text(Nulls.sure(p.type())))).toList();
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
        final var values = new ArrayList<Expr.Param>();
        for (final var implemented : interfaces(file, siblings, supertypes, new HashSet<String>())) {
            values.addAll(implemented.iface().members());
        }
        return values;
    }
    private static TypeRef substitute(TypeRef type, List<Decl.TypeParam> params, List<TypeRef> args) {
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
        final var substituted = type.args().stream().map(Nulls.nonNull(a -> substitute(a, params, args))).toList();
        return new TypeRef(type.pos(), type.name(), substituted, type.nullable());
    }
    public static List<String> required(SourceFile file, List<SourceFile> siblings, List<TypeRef> supertypes) {
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
                final var parents = decl.supertypes().stream().map(Nulls.nonNull(p -> substitute(p, decl.typeParams(), type.args()))).toList();
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
        final var args = type.args().stream().map(Nulls.nonNull(a -> text(a))).toList();
        final var written = (args.isEmpty() ? type.name() : type.name() + "<" + String.join(", ", args) + ">");
        return (type.nullable() ? written + "?" : written);
    }
}
