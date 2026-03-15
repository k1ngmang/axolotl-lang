package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class TypeInference {
    public final SourceFile file;
    public final Map<String, TypeRef> known;
    public final HashSet<String> inProgress;
    public TypeInference(SourceFile file, Map<String, TypeRef> known) {
        this.file = file;
        this.known = known;
        this.inProgress = new HashSet<String>();
    }
    public @Nullable TypeRef value(Decl.Value value) {
        if (value.type() != null) {
            return value.type();
        }
        if (known.containsKey(value.name())) {
            return known.get(value.name());
        }
        if (!inProgress.add(value.name())) {
            return null;
        }
        final var type = of(value.init());
        inProgress.remove(value.name());
        return type;
    }
    public @Nullable TypeRef of(Expr expr) {
        switch (expr) {
            case Expr.Literal $s1 -> {
                return literal($s1);
            }
            case Expr.Paren $s2 -> {
                return of($s2.inner());
            }
            case Expr.Unary $s3 -> {
                return ($s3.op().equals("!") ? simple("Boolean", $s3.pos()) : of($s3.operand()));
            }
            case Expr.Binary $s4 -> {
                return binary($s4);
            }
            case Expr.Elvis $s5 -> {
                return elvis($s5);
            }
            case Expr.Is $s6 -> {
                return simple("Boolean", $s6.pos());
            }
            case Expr.Name $s7 -> {
                return topLevelValue($s7.name());
            }
            case Expr.Call $s8 -> {
                return ($s8.target() == null ? call($s8) : null);
            }
            case Expr.GenericNew $s9 -> {
                return ($s9.typeArgs().isEmpty() ? null : new TypeRef($s9.pos(), $s9.type(), $s9.typeArgs(), false));
            }
            case Expr.NotNull $s10 -> {
                return nonNull(of($s10.target()));
            }
            default -> {
                return null;
            }
        }
    }
    private @Nullable TypeRef literal(Expr.Literal l) {
        final var text = l.text();
        if (text.startsWith("\"")) {
            return simple("String", l.pos());
        }
        if (text.equals("true") || text.equals("false")) {
            return simple("Boolean", l.pos());
        }
        if (text.equals("null") || text.startsWith("'")) {
            return null;
        }
        final var last = Character.toLowerCase(text.charAt(text.length() - 1));
        if (last == 'l') {
            return simple("Long", l.pos());
        }
        final var hex = text.startsWith("0x") || text.startsWith("0X");
        if (!hex && (text.contains(".") || text.contains("e") || text.contains("E") || last == 'd')) {
            return (last == 'f' ? null : simple("Double", l.pos()));
        }
        return (last == 'f' && !hex ? null : simple("Int", l.pos()));
    }
    private @Nullable TypeRef binary(Expr.Binary b) {
        if (comparisons.contains(b.op())) {
            return simple("Boolean", b.pos());
        }
        final var left = of(b.left());
        final var right = of(b.right());
        if (b.op().equals("+") && (isString(left) || isString(right))) {
            return simple("String", b.pos());
        }
        if (left == null || right == null || !numbers.contains(left.name()) || !numbers.contains(right.name())) {
            return null;
        }
        return (numbers.indexOf(left.name()) >= numbers.indexOf(right.name()) ? nonNull(left) : nonNull(right));
    }
    private @Nullable TypeRef elvis(Expr.Elvis e) {
        final var right = of(e.right());
        final var left = of(e.left());
        return (right != null && left != null && sameType(left, right) ? right : null);
    }
    private @Nullable TypeRef call(Expr.Call c) {
        final var candidates = new ArrayList<Decl.Function>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function $s1 && $s1.name().equals(c.name()) && $s1.params().size() == c.args().size()) {
                candidates.add($s1);
            }
        }
        if (candidates.size() == 1) {
            return candidates.getFirst().returnType();
        }
        if (candidates.size() > 1 || isImportedFunction(c.name())) {
            return null;
        }
        if (Character.isUpperCase(c.name().charAt(0)) && !c.name().equals("Ok") && !c.name().equals("Err")) {
            return simple(c.name(), c.pos());
        }
        return null;
    }
    private boolean isImportedFunction(String name) {
        return file.imports().stream().anyMatch(i -> i.kind() != Import.Kind.TYPE && i.simpleName().equals(name));
    }
    private @Nullable TypeRef topLevelValue(String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1 && $s1.name().equals(name)) {
                return value($s1);
            }
        }
        return null;
    }
    private static final List<String> numbers = List.of("Int", "Long", "Double");
    private static final List<String> comparisons = List.of("==", "!=", "<", "<=", ">", ">=", "&&", "||");
    public static void inferAll(SourceFile file, Map<String, TypeRef> known) {
        final var inference = new TypeInference(file, known);
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1 && $s1.type() == null) {
                final var type = inference.value($s1);
                if (type != null) {
                    known.put($s1.name(), type);
                }
            }
        }
    }
    public static @Nullable TypeRef typeOf(SourceFile file, Expr expr) {
        return new TypeInference(file, new HashMap<String, TypeRef>()).of(expr);
    }
    private static boolean sameType(TypeRef a, TypeRef b) {
        if (!a.name().equals(b.name()) || a.args().size() != b.args().size()) {
            return false;
        }
        var i = 0;
        while (i < a.args().size()) {
            final var left = a.args().get(i);
            final var right = b.args().get(i);
            if (!sameType(left, right) || left.nullable() != right.nullable()) {
                return false;
            }
            i += 1;
        }
        return true;
    }
    private static boolean isString(@Nullable TypeRef type) {
        return type != null && (type.name().equals("String") || type.name().equals("java.lang.String"));
    }
    private static @Nullable TypeRef nonNull(@Nullable TypeRef type) {
        return (type == null ? null : new TypeRef(type.pos(), type.name(), type.args(), false));
    }
    private static TypeRef simple(String name, Pos pos) {
        return new TypeRef(pos, name, new ArrayList<TypeRef>(), false);
    }
}
