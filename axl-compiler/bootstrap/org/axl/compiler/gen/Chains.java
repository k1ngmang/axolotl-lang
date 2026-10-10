package org.axl.compiler.gen;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.ast.Expr;

@NullMarked public final class Chains {
    private Chains() {}
    public record Step(String name, @Nullable List<Expr> args, boolean safe, int line) {
        public Step {
            Nulls.param(name, "name");
        }
    }
    public record Chain(Expr base, List<Step> steps) {
        public Chain {
            Nulls.param(base, "base");
            Nulls.param(steps, "steps");
        }
    }
    public static Chain flatten(Expr expr) {
        Nulls.param(expr, "expr");
        switch (expr) {
            case Expr.Member expr$1 -> {
                return extend(flatten(expr$1.target()), new Step(expr$1.name(), null, expr$1.safe(), expr$1.pos().line()));
            }
            case Expr.Call expr$2 -> {
                return ofCall(expr$2);
            }
            default -> {
                return new Chain(expr, new ArrayList<Step>());
            }
        }
    }
    private static Chain ofCall(Expr.Call call) {
        final var target = call.target();
        if (target == null) {
            return new Chain(call, new ArrayList<Step>());
        }
        return extend(flatten(target), new Step(call.name(), call.args(), call.safe(), call.pos().line()));
    }
    private static Chain extend(Chain chain, Step step) {
        final var steps = new ArrayList<Step>(chain.steps());
        steps.add(step);
        return new Chain(chain.base(), steps);
    }
    public static boolean hasSafeStep(Expr expr) {
        Nulls.param(expr, "expr");
        final var isAccess = expr instanceof Expr.Member expr$1 || (expr instanceof Expr.Call expr$2 && expr$2.target() != null);
        return isAccess && flatten(expr).steps().stream().anyMatch(it -> it.safe());
    }
}
