package org.axl.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

/** Which names are visible at a position of a file, and where they are declared. */
final class Symbols {

    enum Kind { FUNCTION, VALUE, IMPORTED_FUNCTION, IMPORTED_VALUE, TYPE, PARAMETER, LOCAL, VARIABLE }

    /** type is null when it is not written in the source; function is set for functions and their locals. */
    record Symbol(String name, Kind kind, Pos pos, TypeRef type, Decl.Function function, Import imported) {}

    private Symbols() {}

    /** Visible symbols at pos, inner declarations last, so that later entries win a name lookup. */
    static List<Symbol> visibleAt(SourceFile file, Pos pos) {
        List<Symbol> result = new ArrayList<>();
        for (Import imp : file.imports()) {
            Kind kind = switch (imp.kind()) {
                case TYPE -> Kind.TYPE;
                case FN -> Kind.IMPORTED_FUNCTION;
                case VAL -> Kind.IMPORTED_VALUE;
            };
            result.add(new Symbol(imp.simpleName(), kind, imp.pos(), null, null, imp));
        }
        for (Decl decl : file.decls()) {
            switch (decl) {
                case Decl.Function fn -> result.add(new Symbol(fn.name(), Kind.FUNCTION, fn.pos(), fn.returnType(), fn, null));
                case Decl.Value value -> result.add(new Symbol(value.name(), Kind.VALUE, value.pos(), value.type(), null, null));
            }
        }
        Decl.Function fn = enclosingFunction(file, pos);
        if (fn != null) {
            for (Expr.Param p : fn.params()) {
                result.add(new Symbol(p.name(), Kind.PARAMETER, p.pos(), p.type(), fn, null));
            }
            if (fn.body() != null) {
                statements(fn.body().statements(), pos, fn, result);
            } else {
                expr(fn.exprBody(), pos, fn, result);
            }
        }
        return result;
    }

    static Symbol lookup(SourceFile file, Pos pos, String name) {
        Symbol found = null;
        for (Symbol symbol : visibleAt(file, pos)) {
            if (symbol.name().equals(name)) {
                found = symbol;
            }
        }
        return found;
    }

    /** Functions of a file by name; for overloads the first one. */
    static Map<String, Decl> topLevel(SourceFile file) {
        Map<String, Decl> result = new LinkedHashMap<>();
        file.decls().forEach(d -> result.putIfAbsent(d.name(), d));
        return result;
    }

    /** A function covers its declaration up to the next declaration. */
    static Decl.Function enclosingFunction(SourceFile file, Pos pos) {
        List<Decl> decls = file.decls();
        for (int i = 0; i < decls.size(); i++) {
            Pos start = decls.get(i).pos();
            Pos end = i + 1 < decls.size() ? decls.get(i + 1).pos() : new Pos(Integer.MAX_VALUE, 0);
            if (decls.get(i) instanceof Decl.Function fn && before(start, pos) && before(pos, end)) {
                return fn;
            }
        }
        return null;
    }

    private static void statements(List<Stmt> statements, Pos pos, Decl.Function fn, List<Symbol> out) {
        for (Stmt stmt : statements) {
            if (!before(stmt.pos(), pos)) {
                return;
            }
            switch (stmt) {
                case Stmt.Local local -> {
                    expr(local.init(), pos, fn, out);
                    out.add(new Symbol(local.name(), local.mutable() ? Kind.VARIABLE : Kind.LOCAL, local.pos(),
                            local.type(), fn, null));
                }
                case Stmt.If ifStmt -> {
                    expr(ifStmt.condition(), pos, fn, out);
                    block(ifStmt.then(), pos, fn, out);
                    if (ifStmt.elseBranch() != null) {
                        statements(List.of(ifStmt.elseBranch()), pos, fn, out);
                    }
                }
                case Stmt.While loop -> {
                    expr(loop.condition(), pos, fn, out);
                    block(loop.body(), pos, fn, out);
                }
                case Stmt.Block block -> block(block, pos, fn, out);
                case Stmt.Assign assign -> expr(assign.value(), pos, fn, out);
                case Stmt.Return ret -> {
                    if (ret.value() != null) {
                        expr(ret.value(), pos, fn, out);
                    }
                }
                case Stmt.ExprStmt e -> expr(e.expr(), pos, fn, out);
            }
        }
    }

    private static void block(Stmt.Block block, Pos pos, Decl.Function fn, List<Symbol> out) {
        if (before(block.pos(), pos) && before(pos, block.end())) {
            statements(block.statements(), pos, fn, out);
        }
    }

    /** Lambda parameters and locals are visible only inside the lambda. */
    private static void expr(Expr expr, Pos pos, Decl.Function fn, List<Symbol> out) {
        switch (expr) {
            case Expr.Lambda lambda -> {
                if (before(lambda.pos(), pos) && before(pos, lambda.end())) {
                    for (Expr.Param p : lambda.params()) {
                        out.add(new Symbol(p.name(), Kind.PARAMETER, p.pos(), p.type(), fn, null));
                    }
                    if (lambda.params().isEmpty() && !lambda.arrow()) {
                        out.add(new Symbol("it", Kind.PARAMETER, lambda.pos(), null, fn, null));
                    }
                    statements(lambda.body(), pos, fn, out);
                }
            }
            case Expr.Call c -> {
                if (c.target() != null) {
                    expr(c.target(), pos, fn, out);
                }
                c.args().forEach(a -> expr(a, pos, fn, out));
            }
            case Expr.Member m -> expr(m.target(), pos, fn, out);
            case Expr.GenericNew g -> g.args().forEach(a -> expr(a, pos, fn, out));
            case Expr.MethodRef r -> expr(r.target(), pos, fn, out);
            case Expr.Unary u -> expr(u.operand(), pos, fn, out);
            case Expr.Binary b -> {
                expr(b.left(), pos, fn, out);
                expr(b.right(), pos, fn, out);
            }
            case Expr.Elvis e -> {
                expr(e.left(), pos, fn, out);
                expr(e.right(), pos, fn, out);
            }
            case Expr.Paren p -> expr(p.inner(), pos, fn, out);
            case Expr.Try t -> expr(t.inner(), pos, fn, out);
            case Expr.Attempt a -> {
                expr(a.body(), pos, fn, out);
                expr(a.handler(), pos, fn, out);
            }
            case Expr.Pipe p -> {
                expr(p.source(), pos, fn, out);
                for (Expr.Stage stage : p.stages()) {
                    stage.args().forEach(a -> expr(a, pos, fn, out));
                    if (stage.lambda() != null) {
                        expr(stage.lambda(), pos, fn, out);
                    }
                }
            }
            case Expr.Literal l -> { }
            case Expr.Name n -> { }
        }
    }

    static boolean before(Pos a, Pos b) {
        return a.line() < b.line() || a.line() == b.line() && a.column() <= b.column();
    }
}
