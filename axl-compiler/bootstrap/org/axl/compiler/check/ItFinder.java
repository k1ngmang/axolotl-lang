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
            case Stmt.If stmt$4 -> {
                return usesIt(stmt$4.condition()) || usesIt(stmt$4.then()) || (stmt$4.elseBranch() != null && usesIt(Nulls.sure(stmt$4.elseBranch())));
            }
            case Stmt.While stmt$5 -> {
                return usesIt(stmt$5.condition()) || usesIt(stmt$5.body());
            }
            case Stmt.For stmt$6 -> {
                return usesIt(stmt$6.iterable()) || usesIt(stmt$6.body());
            }
            case Stmt.ExprStmt stmt$7 -> {
                return usesIt(stmt$7.expr());
            }
            case Stmt.Block stmt$8 -> {
                return stmt$8.statements().stream().anyMatch(it -> usesIt(it));
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
            case Expr.NotNull expr$7 -> {
                return usesIt(expr$7.target());
            }
            case Expr.Unary expr$8 -> {
                return usesIt(expr$8.operand());
            }
            case Expr.Binary expr$9 -> {
                return usesIt(expr$9.left()) || usesIt(expr$9.right());
            }
            case Expr.Elvis expr$10 -> {
                return usesIt(expr$10.left()) || usesIt(expr$10.right());
            }
            case Expr.Is expr$11 -> {
                return usesIt(expr$11.target());
            }
            case Expr.Conditional expr$12 -> {
                return usesIt(expr$12.condition()) || usesIt(expr$12.then()) || usesIt(expr$12.otherwise());
            }
            case Expr.Throw expr$13 -> {
                return usesIt(expr$13.value());
            }
            case Expr.When expr$14 -> {
                return usesIt(expr$14.subject()) || expr$14.arms().stream().anyMatch(arm -> arm.body().stream().anyMatch(it -> usesIt(it)));
            }
            case Expr.Paren expr$15 -> {
                return usesIt(expr$15.inner());
            }
            case Expr.Try expr$16 -> {
                return usesIt(expr$16.inner());
            }
            case Expr.Attempt expr$17 -> {
                return usesIt(expr$17.body()) || (!"it".equals(expr$17.errorName()) && usesIt(expr$17.handler()));
            }
            case Expr.Lambda expr$18 -> {
                return expr$18.arrow() && expr$18.params().stream().noneMatch(it -> it.name().equals("it")) && expr$18.body().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.Pipe expr$19 -> {
                return usesIt(expr$19.source()) || expr$19.stages().stream().anyMatch(stage ->
                        stage.args().stream().anyMatch(it -> usesIt(it)) || (stage.lambda() != null && usesIt(Nulls.sure(stage.lambda()))));
            }
        }
    }
}
