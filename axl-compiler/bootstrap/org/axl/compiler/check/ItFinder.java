package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;

@NullMarked public final class ItFinder {
    private ItFinder() {}
    public static boolean usesIt(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local stmt$1 -> {
                return usesIt(stmt$1.init());
            }
            case Stmt.Assign stmt$2 -> {
                return usesIt(stmt$2.target()) || usesIt(stmt$2.value());
            }
            case Stmt.Return stmt$3 -> {
                return stmt$3.value() != null && usesIt(Nulls.sure(stmt$3.value()));
            }
            case Stmt.Jump stmt$4 -> {
                return false;
            }
            case Stmt.Use stmt$5 -> {
                return usesIt(stmt$5.resource()) || usesIt(stmt$5.body()) || (stmt$5.handler() != null && !"it".equals(stmt$5.errorName()) && usesIt(Nulls.sure(stmt$5.handler())));
            }
            case Stmt.If stmt$6 -> {
                return usesIt(stmt$6.condition()) || usesIt(stmt$6.then()) || (stmt$6.elseBranch() != null && usesIt(Nulls.sure(stmt$6.elseBranch())));
            }
            case Stmt.While stmt$7 -> {
                return usesIt(stmt$7.condition()) || usesIt(stmt$7.body());
            }
            case Stmt.For stmt$8 -> {
                return usesIt(stmt$8.iterable()) || usesIt(stmt$8.body());
            }
            case Stmt.ExprStmt stmt$9 -> {
                return usesIt(stmt$9.expr());
            }
            case Stmt.Block stmt$10 -> {
                return stmt$10.statements().stream().anyMatch(it -> usesIt(it));
            }
        }
    }
    public static boolean usesIt(Expr expr) {
        switch (expr) {
            case Expr.Literal expr$1 -> {
                return false;
            }
            case Expr.Name expr$2 -> {
                return expr$2.name().equals("it");
            }
            case Expr.Member expr$3 -> {
                return usesIt(expr$3.target());
            }
            case Expr.Call expr$4 -> {
                return (expr$4.target() != null && usesIt(Nulls.sure(expr$4.target()))) || expr$4.args().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.GenericNew expr$5 -> {
                return expr$5.args().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.MethodRef expr$6 -> {
                return usesIt(expr$6.target());
            }
            case Expr.ClassLiteral expr$7 -> {
                return false;
            }
            case Expr.NotNull expr$8 -> {
                return usesIt(expr$8.target());
            }
            case Expr.Unary expr$9 -> {
                return usesIt(expr$9.operand());
            }
            case Expr.Binary expr$10 -> {
                return usesIt(expr$10.left()) || usesIt(expr$10.right());
            }
            case Expr.Elvis expr$11 -> {
                return usesIt(expr$11.left()) || usesIt(expr$11.right());
            }
            case Expr.Is expr$12 -> {
                return usesIt(expr$12.target());
            }
            case Expr.Conditional expr$13 -> {
                return usesIt(expr$13.condition()) || usesIt(expr$13.then()) || usesIt(expr$13.otherwise());
            }
            case Expr.Throw expr$14 -> {
                return usesIt(expr$14.value());
            }
            case Expr.When expr$15 -> {
                return usesIt(expr$15.subject()) || expr$15.arms().stream().anyMatch(arm -> arm.body().stream().anyMatch(it -> usesIt(it)));
            }
            case Expr.Paren expr$16 -> {
                return usesIt(expr$16.inner());
            }
            case Expr.Try expr$17 -> {
                return usesIt(expr$17.inner());
            }
            case Expr.Attempt expr$18 -> {
                return usesIt(expr$18.body()) || (!"it".equals(expr$18.errorName()) && usesIt(expr$18.handler()));
            }
            case Expr.Lambda expr$19 -> {
                return expr$19.arrow() && expr$19.params().stream().noneMatch(it -> it.name().equals("it")) && expr$19.body().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.Pipe expr$20 -> {
                return usesIt(expr$20.source()) || expr$20.stages().stream().anyMatch(stage ->
                        stage.args().stream().anyMatch(it -> usesIt(it)) || (stage.lambda() != null && usesIt(Nulls.sure(stage.lambda()))));
            }
        }
    }
}
