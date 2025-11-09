package org.axl.compiler.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.axl.compiler.ast.Expr;

/** A run of member accesses and calls such as `a?.b().c`, seen from its base outwards. */
final class Chains {

    /** args is null for a field access. */
    record Step(String name, List<Expr> args, boolean safe, int line) {}

    record Chain(Expr base, List<Step> steps) {}

    private Chains() {}

    static Chain flatten(Expr expr) {
        List<Step> steps = new ArrayList<>();
        Expr current = expr;
        while (true) {
            if (current instanceof Expr.Member m) {
                steps.add(new Step(m.name(), null, m.safe(), m.pos().line()));
                current = m.target();
            } else if (current instanceof Expr.Call c && c.target() != null) {
                steps.add(new Step(c.name(), c.args(), c.safe(), c.pos().line()));
                current = c.target();
            } else {
                break;
            }
        }
        Collections.reverse(steps);
        return new Chain(current, steps);
    }

    static boolean hasSafeStep(Expr expr) {
        return (expr instanceof Expr.Member || expr instanceof Expr.Call c && c.target() != null)
                && flatten(expr).steps().stream().anyMatch(Step::safe);
    }
}
