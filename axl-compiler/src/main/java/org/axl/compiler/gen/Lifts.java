package org.axl.compiler.gen;

import org.axl.compiler.ast.Expr;

/**
 * Finds expressions that must be split into statements: a `try` or a `mapResult` stage returns from
 * the function, which an expression cannot do. Lambdas never contain them; the checker rejects that.
 * A `?.` chain passed to a call is split off too: as an argument, Java cannot infer the type of its
 * `Nulls.map` and overloads such as `println(String)` and `println(char[])` become ambiguous. So is
 * the left side of `a?.b ?: 0`, for the same reason with the number overloads of Nulls.or.
 */
final class Lifts {

    private Lifts() {}

    /** Operands that become statements of their own. */
    static boolean hoisted(Expr expr) {
        return needed(expr) || Chains.hasSafeStep(expr);
    }

    static boolean numberAfterSafeCall(Expr.Elvis elvis) {
        return Chains.hasSafeStep(elvis.left()) && isNumber(elvis.right());
    }

    static boolean isNumber(Expr expr) {
        return expr instanceof Expr.Literal l && Character.isDigit(l.text().charAt(0))
                || expr instanceof Expr.Unary u && u.op().equals("-") && u.operand() instanceof Expr.Literal;
    }

    static boolean needed(Expr expr) {
        return switch (expr) {
            case Expr.Try t -> true;
            case Expr.Pipe p -> needed(p.source()) || p.stages().stream().anyMatch(s -> s.name().equals("mapResult")
                    || s.args().stream().anyMatch(Lifts::needed));
            case Expr.Member m -> needed(m.target());
            case Expr.Call c -> c.target() != null && needed(c.target()) || c.args().stream().anyMatch(Lifts::hoisted);
            case Expr.GenericNew g -> g.args().stream().anyMatch(Lifts::hoisted);
            case Expr.MethodRef r -> needed(r.target());
            case Expr.NotNull n -> needed(n.target());
            case Expr.Unary u -> needed(u.operand());
            case Expr.Binary b -> needed(b.left()) || needed(b.right());
            case Expr.Elvis e -> needed(e.left()) || needed(e.right()) || numberAfterSafeCall(e);
            case Expr.Is i -> needed(i.target());
            // A switch with statements in its arms cannot be written inside an expression.
            case Expr.When w -> true;
            case Expr.Paren p -> needed(p.inner());
            case Expr.Literal l -> false;
            case Expr.Name n -> false;
            case Expr.Attempt a -> false;
            case Expr.Lambda l -> false;
        };
    }
}
