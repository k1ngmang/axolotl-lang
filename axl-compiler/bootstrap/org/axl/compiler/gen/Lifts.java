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
        final var digit = expr instanceof Expr.Literal expr$1 && Character.isDigit(expr$1.text().charAt(0));
        final var negative = expr instanceof Expr.Unary expr$2 && expr$2.op().equals("-") && expr$2.operand() instanceof Expr.Literal;
        return digit || negative;
    }
    public static boolean needed(Expr expr) {
        switch (expr) {
            case Expr.Try expr$1 -> {
                return true;
            }
            case Expr.Pipe expr$2 -> {
                return needed(expr$2.source()) || expr$2.stages().stream().anyMatch(stage ->
                        stage.name().equals("mapResult") || stage.args().stream().anyMatch(it -> needed(it)));
            }
            case Expr.Member expr$3 -> {
                return needed(expr$3.target());
            }
            case Expr.Call expr$4 -> {
                return neededInCall(expr$4);
            }
            case Expr.GenericNew expr$5 -> {
                return expr$5.args().stream().anyMatch(it -> hoisted(it));
            }
            case Expr.MethodRef expr$6 -> {
                return needed(expr$6.target());
            }
            case Expr.NotNull expr$7 -> {
                return needed(expr$7.target());
            }
            case Expr.Unary expr$8 -> {
                return needed(expr$8.operand());
            }
            case Expr.Binary expr$9 -> {
                return needed(expr$9.left()) || needed(expr$9.right());
            }
            case Expr.Elvis expr$10 -> {
                return needed(expr$10.left()) || needed(expr$10.right()) || numberAfterSafeCall(expr$10);
            }
            case Expr.Is expr$11 -> {
                return needed(expr$11.target());
            }
            case Expr.Conditional expr$12 -> {
                return needed(expr$12.condition()) || needed(expr$12.then()) || needed(expr$12.otherwise());
            }
            case Expr.Throw expr$13 -> {
                return needed(expr$13.value());
            }
            case Expr.When expr$14 -> {
                return true;
            }
            case Expr.Paren expr$15 -> {
                return needed(expr$15.inner());
            }
            case Expr.Literal expr$16 -> {
                return false;
            }
            case Expr.Name expr$17 -> {
                return false;
            }
            case Expr.Attempt expr$18 -> {
                return false;
            }
            case Expr.Lambda expr$19 -> {
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
