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
            case Expr.Literal expr$1 -> {
                return literal(expr$1);
            }
            case Expr.Paren expr$2 -> {
                return of(expr$2.inner());
            }
            case Expr.Unary expr$3 -> {
                return (expr$3.op().equals("!") ? simple("Boolean", expr$3.pos()) : of(expr$3.operand()));
            }
            case Expr.Binary expr$4 -> {
                return binary(expr$4);
            }
            case Expr.Elvis expr$5 -> {
                return elvis(expr$5);
            }
            case Expr.Is expr$6 -> {
                return simple("Boolean", expr$6.pos());
            }
            case Expr.Name expr$7 -> {
                return topLevelValue(expr$7.name());
            }
            case Expr.Call expr$8 -> {
                return (expr$8.target() == null ? call(expr$8) : null);
            }
            case Expr.GenericNew expr$9 -> {
                return (expr$9.typeArgs().isEmpty() ? null : new TypeRef(expr$9.pos(), expr$9.type(), expr$9.typeArgs(), false));
            }
            case Expr.NotNull expr$10 -> {
                return nonNull(of(expr$10.target()));
            }
            case Expr.Conditional expr$11 -> {
                return common(of(expr$11.then()), of(expr$11.otherwise()));
            }
            case Expr.Try expr$12 -> {
                return okType(of(expr$12.inner()));
            }
            case Expr.ClassLiteral expr$13 -> {
                return classOf(expr$13);
            }
            case Expr.Attempt expr$14 -> {
                return (throwsInstead(expr$14) ? Nulls.box(of(expr$14.body())) : resultOf(of(expr$14.body()), of(expr$14.handler()), expr$14.pos()));
            }
            default -> {
                return null;
            }
        }
    }
    private @Nullable TypeRef classOf(Expr.ClassLiteral e) {
        final var name = Expr.pathName(e.target());
        return (name == null ? null : new TypeRef(e.pos(), "Class", List.of(simple(name, e.pos())), false));
    }
    private @Nullable TypeRef okType(@Nullable TypeRef result) {
        if (result == null || !(result.name().equals("Result") || result.name().equals("axl.lang.Result")) || result.args().size() != 2) {
            return null;
        }
        return result.args().getFirst();
    }
    private @Nullable TypeRef common(@Nullable TypeRef a, @Nullable TypeRef b) {
        if (a == null || b == null || !a.name().equals(b.name()) || !a.args().isEmpty() || !b.args().isEmpty()) {
            return null;
        }
        return new TypeRef(a.pos(), a.name(), a.args(), a.nullable() || b.nullable());
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
            if (decl instanceof Decl.Function decl$1 && decl$1.name().equals(c.name()) && decl$1.params().size() == c.args().size()) {
                candidates.add(decl$1);
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
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name)) {
                return value(decl$1);
            }
        }
        return null;
    }
    private static final List<String> numbers = List.of("Int", "Long", "Double");
    private static final List<String> comparisons = List.of("==", "!=", "<", "<=", ">", ">=", "&&", "||");
    public static void inferAll(SourceFile file, Map<String, TypeRef> known) {
        final var inference = new TypeInference(file, known);
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.type() == null) {
                final var type = inference.value(decl$1);
                if (type != null) {
                    known.put(decl$1.name(), type);
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
    private static @Nullable TypeRef resultOf(@Nullable TypeRef value, @Nullable TypeRef error, Pos pos) {
        if (value == null || error == null) {
            return null;
        }
        return new TypeRef(pos, "Result", List.of(value, error), false);
    }
    private static boolean throwsInstead(Expr.Attempt a) {
        return isThrow(a.handler());
    }
    private static boolean isThrow(Expr e) {
        return e instanceof Expr.Throw e$1 || (e instanceof Expr.Paren e$2 && isThrow(e$2.inner()));
    }
}
