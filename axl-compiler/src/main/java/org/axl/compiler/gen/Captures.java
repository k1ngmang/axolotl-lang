package org.axl.compiler.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.check.Analysis;

/**
 * Finds `var` locals referenced inside the lambdas that `?:`, `?.` and `attempt` expand to.
 * Java lambdas only capture effectively final variables, so those need a final copy.
 */
final class Captures {

    private final Analysis analysis;
    private final Set<String> names = new LinkedHashSet<>();
    // Depth of the block lambdas being looked into.
    private int lambdaBodies;

    private Captures(Analysis analysis) {
        this.analysis = analysis;
    }

    static List<String> in(Expr expr, Analysis analysis) {
        return in(expr, analysis, false);
    }

    /** rootElvisInlined: a `?:` at the root expands without a lambda. */
    static List<String> in(Expr expr, Analysis analysis, boolean rootElvisInlined) {
        Captures captures = new Captures(analysis);
        if (rootElvisInlined && expr instanceof Expr.Elvis elvis) {
            captures.visit(elvis.left(), false);
            captures.visit(elvis.right(), false);
        } else {
            captures.visit(expr, false);
        }
        return new ArrayList<>(captures.names);
    }

    /** A safe chain used as a statement expands to `if` checks, so only nested lambdas count. */
    static List<String> inStatementChain(Chains.Chain chain, Analysis analysis) {
        Captures captures = new Captures(analysis);
        captures.visit(chain.base(), false);
        for (Chains.Step step : chain.steps()) {
            if (step.args() != null) {
                step.args().forEach(a -> captures.visit(a, false));
            }
        }
        return new ArrayList<>(captures.names);
    }

    private void visit(Expr expr, boolean inLambda) {
        switch (expr) {
            case Expr.Literal l -> { }
            case Expr.Name n -> {
                if (inLambda && analysis.mutableRefs().contains(n)) {
                    names.add(n.name());
                }
            }
            case Expr.Member m -> chain(Chains.flatten(m), inLambda);
            case Expr.Call c when c.target() != null -> chain(Chains.flatten(c), inLambda);
            case Expr.Call c -> c.args().forEach(a -> visit(a, inLambda));
            case Expr.GenericNew g -> g.args().forEach(a -> visit(a, inLambda));
            case Expr.MethodRef r -> visit(r.target(), inLambda);
            case Expr.NotNull n -> visit(n.target(), inLambda);
            case Expr.Unary u -> visit(u.operand(), inLambda);
            case Expr.Binary b -> {
                visit(b.left(), inLambda);
                visit(b.right(), inLambda);
            }
            case Expr.Elvis e -> {
                visit(e.left(), inLambda);
                // A right side that needs statements becomes an `if`, not a lambda.
                visit(e.right(), inLambda || !Lifts.needed(e.right()));
            }
            case Expr.Is i -> visit(i.target(), inLambda);
            case Expr.When w -> {
                visit(w.subject(), inLambda);
                // Outside a lambda the statements of the arms are emitted, and so copied, one by one.
                if (lambdaBodies > 0) {
                    w.arms().forEach(a -> a.body().forEach(s -> statement(s, inLambda)));
                }
            }
            case Expr.Paren p -> visit(p.inner(), inLambda);
            case Expr.Try t -> visit(t.inner(), inLambda);
            case Expr.Attempt a -> {
                visit(a.body(), true);
                visit(a.handler(), true);
            }
            // The statements of a block lambda copy its own locals themselves. A `var` from outside is
            // captured by the lambda itself, which javac rejects; a copy would hide that behind a stale value.
            case Expr.Lambda l -> {
                Set<String> before = new LinkedHashSet<>(names);
                lambdaBodies++;
                l.body().forEach(s -> statement(s, inLambda));
                lambdaBodies--;
                names.retainAll(before);
            }
            case Expr.Pipe p -> {
                visit(p.source(), inLambda);
                for (Expr.Stage stage : p.stages()) {
                    stage.args().forEach(a -> visit(a, inLambda));
                    if (stage.lambda() != null) {
                        visit(stage.lambda(), inLambda);
                    }
                }
            }
        }
    }

    private void statement(Stmt stmt, boolean inLambda) {
        switch (stmt) {
            case Stmt.Local l -> visit(l.init(), inLambda);
            case Stmt.Assign a -> visit(a.value(), inLambda);
            case Stmt.Return r -> {
                if (r.value() != null) {
                    visit(r.value(), inLambda);
                }
            }
            case Stmt.If i -> {
                visit(i.condition(), inLambda);
                statement(i.then(), inLambda);
                if (i.elseBranch() != null) {
                    statement(i.elseBranch(), inLambda);
                }
            }
            case Stmt.While w -> {
                visit(w.condition(), inLambda);
                statement(w.body(), inLambda);
            }
            case Stmt.For f -> {
                visit(f.iterable(), inLambda);
                statement(f.body(), inLambda);
            }
            case Stmt.ExprStmt e -> visit(e.expr(), inLambda);
            case Stmt.Block b -> b.statements().forEach(s -> statement(s, inLambda));
        }
    }

    private void chain(Chains.Chain chain, boolean inLambda) {
        visit(chain.base(), inLambda);
        boolean afterSafe = inLambda;
        for (Chains.Step step : chain.steps()) {
            afterSafe |= step.safe();
            if (step.args() != null) {
                for (Expr arg : step.args()) {
                    visit(arg, afterSafe);
                }
            }
        }
    }
}
