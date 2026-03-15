package org.axl.compiler.gen;
import axl.lang.*;
import org.jspecify.annotations.*;
import org.axl.compiler.ast.Expr;

@NullMarked public final class Lifts {
    private Lifts() {}
    public static boolean hoisted(Expr expr) {
        return needed(expr) || Chains.hasSafeStep(expr);
    }
    public static boolean numberAfterSafeCall(Expr.Elvis elvis) {
        return Chains.hasSafeStep(elvis.left()) && isNumber(elvis.right());
    }
    public static boolean isNumber(Expr expr) {
        final var digit = expr instanceof Expr.Literal $s1 && Character.isDigit($s1.text().charAt(0));
        final var negative = expr instanceof Expr.Unary $s2 && $s2.op().equals("-") && $s2.operand() instanceof Expr.Literal;
        return digit || negative;
    }
    public static boolean needed(Expr expr) {
        switch (expr) {
            case Expr.Try $s1 -> {
                return true;
            }
            case Expr.Pipe $s2 -> {
                return needed($s2.source()) || $s2.stages().stream().anyMatch(stage ->
                        stage.name().equals("mapResult") || stage.args().stream().anyMatch(it -> needed(it)));
            }
            case Expr.Member $s3 -> {
                return needed($s3.target());
            }
            case Expr.Call $s4 -> {
                return neededInCall($s4);
            }
            case Expr.GenericNew $s5 -> {
                return $s5.args().stream().anyMatch(it -> hoisted(it));
            }
            case Expr.MethodRef $s6 -> {
                return needed($s6.target());
            }
            case Expr.NotNull $s7 -> {
                return needed($s7.target());
            }
            case Expr.Unary $s8 -> {
                return needed($s8.operand());
            }
            case Expr.Binary $s9 -> {
                return needed($s9.left()) || needed($s9.right());
            }
            case Expr.Elvis $s10 -> {
                return needed($s10.left()) || needed($s10.right()) || numberAfterSafeCall($s10);
            }
            case Expr.Is $s11 -> {
                return needed($s11.target());
            }
            case Expr.Conditional $s12 -> {
                return needed($s12.condition()) || needed($s12.then()) || needed($s12.otherwise());
            }
            case Expr.Throw $s13 -> {
                return needed($s13.value());
            }
            case Expr.When $s14 -> {
                return true;
            }
            case Expr.Paren $s15 -> {
                return needed($s15.inner());
            }
            case Expr.Literal $s16 -> {
                return false;
            }
            case Expr.Name $s17 -> {
                return false;
            }
            case Expr.Attempt $s18 -> {
                return false;
            }
            case Expr.Lambda $s19 -> {
                return false;
            }
        }
    }
    private static boolean neededInCall(Expr.Call call) {
        final var target = call.target();
        final var inTarget = target != null && needed(target);
        return inTarget || call.args().stream().anyMatch(it -> hoisted(it));
    }
}
