package org.axl.compiler.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;

/**
 * Top-level values are static fields set in declaration order, so a value that reads a later one,
 * directly or through functions of the file, sees null or zero.
 */
final class InitOrder {

    /** A value and a later value it reads while it is initialized. */
    record Problem(Decl.Value value, String later) {}

    private final SourceFile file;

    private InitOrder(SourceFile file) {
        this.file = file;
    }

    static List<Problem> check(SourceFile file) {
        return new InitOrder(file).problems();
    }

    private List<Problem> problems() {
        List<Decl.Value> values = file.decls().stream().filter(Decl.Value.class::isInstance).map(Decl.Value.class::cast).toList();
        List<Problem> problems = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Set<String> read = new HashSet<>();
            names(values.get(i).init(), read, new HashSet<>());
            for (Decl.Value later : values.subList(i, values.size())) {
                if (read.contains(later.name())) {
                    problems.add(new Problem(values.get(i), later.name()));
                    break;
                }
            }
        }
        return problems;
    }

    /** Names an expression reads when it runs, following calls into functions of the file. */
    private void names(Expr expr, Set<String> out, Set<String> visitedFunctions) {
        switch (expr) {
            case Expr.Name n -> out.add(n.name());
            case Expr.Call c -> {
                if (c.target() == null) {
                    function(c.name(), out, visitedFunctions);
                } else {
                    names(c.target(), out, visitedFunctions);
                }
                c.args().forEach(a -> names(a, out, visitedFunctions));
            }
            case Expr.Member m -> names(m.target(), out, visitedFunctions);
            case Expr.GenericNew g -> g.args().forEach(a -> names(a, out, visitedFunctions));
            case Expr.MethodRef r -> names(r.target(), out, visitedFunctions);
            case Expr.NotNull n -> names(n.target(), out, visitedFunctions);
            case Expr.Unary u -> names(u.operand(), out, visitedFunctions);
            case Expr.Binary b -> {
                names(b.left(), out, visitedFunctions);
                names(b.right(), out, visitedFunctions);
            }
            case Expr.Elvis e -> {
                names(e.left(), out, visitedFunctions);
                names(e.right(), out, visitedFunctions);
            }
            case Expr.Paren p -> names(p.inner(), out, visitedFunctions);
            case Expr.Try t -> names(t.inner(), out, visitedFunctions);
            case Expr.Attempt a -> {
                names(a.body(), out, visitedFunctions);
                names(a.handler(), out, visitedFunctions);
            }
            case Expr.Pipe p -> {
                names(p.source(), out, visitedFunctions);
                for (Expr.Stage stage : p.stages()) {
                    stage.args().forEach(a -> names(a, out, visitedFunctions));
                    if (stage.lambda() != null) {
                        stage.lambda().body().forEach(s -> statement(s, out, visitedFunctions));
                    }
                }
            }
            // A lambda runs later, if at all.
            case Expr.Lambda l -> { }
            case Expr.Literal l -> { }
        }
    }

    private void function(String name, Set<String> out, Set<String> visitedFunctions) {
        if (!visitedFunctions.add(name)) {
            return;
        }
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Function fn && fn.name().equals(name)) {
                Set<String> read = new HashSet<>();
                if (fn.exprBody() != null) {
                    names(fn.exprBody(), read, visitedFunctions);
                } else {
                    statement(fn.body(), read, visitedFunctions);
                }
                // Parameters and locals of the function are not the top-level values of the same name.
                fn.params().forEach(p -> read.remove(p.name()));
                read.removeAll(locals(fn.body() == null ? List.of() : fn.body().statements()));
                out.addAll(read);
            }
        }
    }

    private void statement(Stmt stmt, Set<String> out, Set<String> visitedFunctions) {
        switch (stmt) {
            case Stmt.Local l -> names(l.init(), out, visitedFunctions);
            case Stmt.Assign a -> {
                names(a.target(), out, visitedFunctions);
                names(a.value(), out, visitedFunctions);
            }
            case Stmt.Return r -> {
                if (r.value() != null) {
                    names(r.value(), out, visitedFunctions);
                }
            }
            case Stmt.If i -> {
                names(i.condition(), out, visitedFunctions);
                statement(i.then(), out, visitedFunctions);
                if (i.elseBranch() != null) {
                    statement(i.elseBranch(), out, visitedFunctions);
                }
            }
            case Stmt.While w -> {
                names(w.condition(), out, visitedFunctions);
                statement(w.body(), out, visitedFunctions);
            }
            case Stmt.ExprStmt e -> names(e.expr(), out, visitedFunctions);
            case Stmt.Block b -> b.statements().forEach(s -> statement(s, out, visitedFunctions));
        }
    }

    private static Set<String> locals(List<Stmt> statements) {
        Set<String> result = new HashSet<>();
        for (Stmt stmt : statements) {
            switch (stmt) {
                case Stmt.Local l -> result.add(l.name());
                case Stmt.If i -> {
                    result.addAll(locals(i.then().statements()));
                    if (i.elseBranch() != null) {
                        result.addAll(locals(List.of(i.elseBranch())));
                    }
                }
                case Stmt.While w -> result.addAll(locals(w.body().statements()));
                case Stmt.Block b -> result.addAll(locals(b.statements()));
                default -> { }
            }
        }
        return result;
    }
}
