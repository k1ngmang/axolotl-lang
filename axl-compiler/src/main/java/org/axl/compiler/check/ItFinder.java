package org.axl.compiler.check;

import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;

/** Looks for a reference to `it` that is not bound by a nested lambda parameter. */
final class ItFinder {

    boolean in(Stmt stmt) {
        return switch (stmt) {
            case Stmt.Local l -> in(l.init());
            case Stmt.Assign a -> in(a.target()) || in(a.value());
            case Stmt.Return r -> r.value() != null && in(r.value());
            case Stmt.If i -> in(i.condition()) || in(i.then()) || i.elseBranch() != null && in(i.elseBranch());
            case Stmt.While w -> in(w.condition()) || in(w.body());
            case Stmt.ExprStmt e -> in(e.expr());
            case Stmt.Block b -> b.statements().stream().anyMatch(this::in);
        };
    }

    boolean in(Expr expr) {
        return switch (expr) {
            case Expr.Literal l -> false;
            case Expr.Name n -> n.name().equals("it");
            case Expr.Member m -> in(m.target());
            case Expr.Call c -> c.target() != null && in(c.target()) || c.args().stream().anyMatch(this::in);
            case Expr.GenericNew g -> g.args().stream().anyMatch(this::in);
            case Expr.MethodRef r -> in(r.target());
            case Expr.Unary u -> in(u.operand());
            case Expr.Binary b -> in(b.left()) || in(b.right());
            case Expr.Elvis e -> in(e.left()) || in(e.right());
            case Expr.Paren p -> in(p.inner());
            case Expr.Try t -> in(t.inner());
            case Expr.Attempt a -> in(a.body()) || !"it".equals(a.errorName()) && in(a.handler());
            case Expr.Lambda l -> l.params().stream().noneMatch(p -> p.name().equals("it"))
                    && l.body().stream().anyMatch(this::in);
        };
    }
}
