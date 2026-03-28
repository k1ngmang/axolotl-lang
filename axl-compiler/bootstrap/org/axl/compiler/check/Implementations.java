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
        for (final var iface : interfaces(file, supertypes, new HashSet<String>())) {
            for (final var member : iface.members()) {
                final var required = java.util.Objects.requireNonNull(member.type());
                final var field = given.get(member.name());
                if (field == null) {
                    problems.add(new Mismatch(decl.namePos(), "`" + decl.name() + "` does not give `val " + member.name() + ": " +
                            text(required) +
                            "`, which the interface `" +
                            iface.name() +
                            "` requires; add it to its parameters"));
                } else {
                    compare(member.name(), field, required, iface, problems);
                }
            }
        }
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
    public static List<Expr.Param> requiredValues(SourceFile file, List<TypeRef> supertypes) {
        final var values = new ArrayList<Expr.Param>();
        for (final var iface : interfaces(file, supertypes, new HashSet<String>())) {
            values.addAll(iface.members());
        }
        return values;
    }
    private static List<Decl.Interface> interfaces(SourceFile file, List<TypeRef> supertypes, Set<String> seen) {
        final var result = new ArrayList<Decl.Interface>();
        for (final var type : supertypes) {
            for (final var decl : file.decls()) {
                if (decl instanceof Decl.Interface decl$1 && decl$1.name().equals(type.name()) && seen.add(decl$1.name())) {
                    result.add(decl$1);
                    result.addAll(interfaces(file, decl$1.supertypes(), seen));
                }
            }
        }
        return result;
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
