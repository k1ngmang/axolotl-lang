package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;

@NullMarked public final class ItFinder {
    private ItFinder() {}
    public static boolean usesIt(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local $s1 -> {
                return usesIt($s1.init());
            }
            case Stmt.Assign $s2 -> {
                return usesIt($s2.target()) || usesIt($s2.value());
            }
            case Stmt.Return $s3 -> {
                return $s3.value() != null && usesIt(java.util.Objects.requireNonNull($s3.value()));
            }
            case Stmt.If $s4 -> {
                return usesIt($s4.condition()) || usesIt($s4.then()) || ($s4.elseBranch() != null && usesIt(java.util.Objects.requireNonNull($s4.elseBranch())));
            }
            case Stmt.While $s5 -> {
                return usesIt($s5.condition()) || usesIt($s5.body());
            }
            case Stmt.For $s6 -> {
                return usesIt($s6.iterable()) || usesIt($s6.body());
            }
            case Stmt.ExprStmt $s7 -> {
                return usesIt($s7.expr());
            }
            case Stmt.Block $s8 -> {
                return $s8.statements().stream().anyMatch(it -> usesIt(it));
            }
        }
    }
    public static boolean usesIt(Expr expr) {
        switch (expr) {
            case Expr.Literal $s1 -> {
                return false;
            }
            case Expr.Name $s2 -> {
                return $s2.name().equals("it");
            }
            case Expr.Member $s3 -> {
                return usesIt($s3.target());
            }
            case Expr.Call $s4 -> {
                return ($s4.target() != null && usesIt(java.util.Objects.requireNonNull($s4.target()))) || $s4.args().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.GenericNew $s5 -> {
                return $s5.args().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.MethodRef $s6 -> {
                return usesIt($s6.target());
            }
            case Expr.NotNull $s7 -> {
                return usesIt($s7.target());
            }
            case Expr.Unary $s8 -> {
                return usesIt($s8.operand());
            }
            case Expr.Binary $s9 -> {
                return usesIt($s9.left()) || usesIt($s9.right());
            }
            case Expr.Elvis $s10 -> {
                return usesIt($s10.left()) || usesIt($s10.right());
            }
            case Expr.Is $s11 -> {
                return usesIt($s11.target());
            }
            case Expr.Conditional $s12 -> {
                return usesIt($s12.condition()) || usesIt($s12.then()) || usesIt($s12.otherwise());
            }
            case Expr.Throw $s13 -> {
                return usesIt($s13.value());
            }
            case Expr.When $s14 -> {
                return usesIt($s14.subject()) || $s14.arms().stream().anyMatch(arm -> arm.body().stream().anyMatch(it -> usesIt(it)));
            }
            case Expr.Paren $s15 -> {
                return usesIt($s15.inner());
            }
            case Expr.Try $s16 -> {
                return usesIt($s16.inner());
            }
            case Expr.Attempt $s17 -> {
                return usesIt($s17.body()) || (!"it".equals($s17.errorName()) && usesIt($s17.handler()));
            }
            case Expr.Lambda $s18 -> {
                return $s18.arrow() && $s18.params().stream().noneMatch(it -> it.name().equals("it")) && $s18.body().stream().anyMatch(it -> usesIt(it));
            }
            case Expr.Pipe $s19 -> {
                return usesIt($s19.source()) || $s19.stages().stream().anyMatch(stage ->
                        stage.args().stream().anyMatch(it -> usesIt(it)) || (stage.lambda() != null && usesIt(java.util.Objects.requireNonNull(stage.lambda()))));
            }
        }
    }
}
