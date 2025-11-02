package org.axl.compiler.check;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

/**
 * The type of a top-level `val` without a written type, from what the file itself says: literals,
 * operators, constructors, other values and the result types of its functions. Anything that needs
 * the classpath is left to javac (see JavaTypes).
 */
public final class TypeInference {

    private static final List<String> NUMBERS = List.of("Int", "Long", "Double");

    private final SourceFile file;
    private final Map<String, TypeRef> known;
    private final Set<String> inProgress = new HashSet<>();

    private TypeInference(SourceFile file, Map<String, TypeRef> known) {
        this.file = file;
        this.known = known;
    }

    /** Infers every untyped top-level value it can; the result maps value names to types. */
    public static void inferAll(SourceFile file, Map<String, TypeRef> known) {
        TypeInference inference = new TypeInference(file, known);
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value && value.type() == null) {
                TypeRef type = inference.value(value);
                if (type != null) {
                    known.put(value.name(), type);
                }
            }
        }
    }

    private TypeRef value(Decl.Value value) {
        if (value.type() != null) {
            return value.type();
        }
        if (known.containsKey(value.name())) {
            return known.get(value.name());
        }
        if (!inProgress.add(value.name())) {
            return null;
        }
        TypeRef type = of(value.init());
        inProgress.remove(value.name());
        return type;
    }

    private TypeRef of(Expr expr) {
        return switch (expr) {
            case Expr.Literal l -> literal(l);
            case Expr.Paren p -> of(p.inner());
            case Expr.Unary u -> u.op().equals("!") ? simple("Boolean", u.pos()) : of(u.operand());
            case Expr.Binary b -> binary(b);
            case Expr.Elvis e -> elvis(e);
            case Expr.Name n -> topLevelValue(n.name());
            case Expr.Call c when c.target() == null -> call(c);
            case Expr.GenericNew g when !g.typeArgs().isEmpty() -> new TypeRef(g.pos(), g.type(), g.typeArgs(), false);
            case Expr.NotNull n -> nonNull(of(n.target()));
            default -> null;
        };
    }

    private TypeRef literal(Expr.Literal l) {
        String text = l.text();
        if (text.startsWith("\"")) {
            return simple("String", l.pos());
        }
        if (text.equals("true") || text.equals("false")) {
            return simple("Boolean", l.pos());
        }
        if (text.equals("null") || text.startsWith("'")) {
            return null;
        }
        char last = Character.toLowerCase(text.charAt(text.length() - 1));
        if (last == 'l') {
            return simple("Long", l.pos());
        }
        boolean hex = text.startsWith("0x") || text.startsWith("0X");
        if (!hex && (text.contains(".") || text.contains("e") || text.contains("E") || last == 'd')) {
            return last == 'f' ? null : simple("Double", l.pos());
        }
        return last == 'f' && !hex ? null : simple("Int", l.pos());
    }

    private TypeRef binary(Expr.Binary b) {
        switch (b.op()) {
            case "==", "!=", "<", "<=", ">", ">=", "&&", "||" -> {
                return simple("Boolean", b.pos());
            }
            default -> { }
        }
        TypeRef left = of(b.left());
        TypeRef right = of(b.right());
        if (b.op().equals("+") && (isString(left) || isString(right))) {
            return simple("String", b.pos());
        }
        if (left == null || right == null || !NUMBERS.contains(left.name()) || !NUMBERS.contains(right.name())) {
            return null;
        }
        return NUMBERS.indexOf(left.name()) >= NUMBERS.indexOf(right.name()) ? nonNull(left) : nonNull(right);
    }

    /** `a ?: b` has the type of `b` when both sides agree; otherwise javac decides. */
    private TypeRef elvis(Expr.Elvis e) {
        TypeRef right = of(e.right());
        TypeRef left = of(e.left());
        return right != null && left != null && sameType(left, right) ? right : null;
    }

    private static boolean sameType(TypeRef a, TypeRef b) {
        if (!a.name().equals(b.name()) || a.args().size() != b.args().size()) {
            return false;
        }
        for (int i = 0; i < a.args().size(); i++) {
            if (!sameType(a.args().get(i), b.args().get(i)) || a.args().get(i).nullable() != b.args().get(i).nullable()) {
                return false;
            }
        }
        return true;
    }

    private TypeRef call(Expr.Call c) {
        List<Decl.Function> candidates = file.decls().stream()
                .filter(d -> d instanceof Decl.Function fn && fn.name().equals(c.name()) && fn.params().size() == c.args().size())
                .map(Decl.Function.class::cast).toList();
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

    private TypeRef topLevelValue(String name) {
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value && value.name().equals(name)) {
                return value(value);
            }
        }
        return null;
    }

    private static boolean isString(TypeRef type) {
        return type != null && (type.name().equals("String") || type.name().equals("java.lang.String"));
    }

    private static TypeRef nonNull(TypeRef type) {
        return type == null ? null : new TypeRef(type.pos(), type.name(), type.args(), false);
    }

    private static TypeRef simple(String name, Pos pos) {
        return new TypeRef(pos, name, List.of(), false);
    }
}
