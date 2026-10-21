package org.axl.compiler.gen;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.check.Analysis;

@NullMarked public final class Captures {
    public final Analysis analysis;
    public final LinkedHashSet<String> names;
    public int lambdaBodies;
    public Captures(Analysis analysis) {
        Nulls.param(analysis, "analysis");
        this.analysis = analysis;
        this.names = new LinkedHashSet<String>();
        this.lambdaBodies = 0;
    }
    public void visit(Expr expr, boolean inLambda) {
        Nulls.param(expr, "expr");
        switch (expr) {
            case Expr.Literal expr$1 -> {
            }
            case Expr.Name expr$2 -> {
                if (inLambda && analysis.mutableRefs().contains(expr$2)) {
                    names.add(expr$2.name());
                }
            }
            case Expr.Member expr$3 -> {
                chain(Chains.flatten(expr$3), inLambda);
            }
            case Expr.Call expr$4 -> {
                if (expr$4.target() != null) {
                    chain(Chains.flatten(expr$4), inLambda);
                } else {
                    visitAll(expr$4.args(), inLambda);
                }
            }
            case Expr.GenericNew expr$5 -> {
                visitAll(expr$5.args(), inLambda);
            }
            case Expr.MethodRef expr$6 -> {
                visit(expr$6.target(), inLambda);
            }
            case Expr.ClassLiteral expr$7 -> {
            }
            case Expr.NotNull expr$8 -> {
                visit(expr$8.target(), inLambda);
            }
            case Expr.Unary expr$9 -> {
                visit(expr$9.operand(), inLambda);
            }
            case Expr.Binary expr$10 -> {
                visit(expr$10.left(), inLambda);
                visit(expr$10.right(), inLambda);
            }
            case Expr.Elvis expr$11 -> {
                visit(expr$11.left(), inLambda);
                visit(expr$11.right(), inLambda || !Lifts.needed(expr$11.right()));
            }
            case Expr.Is expr$12 -> {
                visit(expr$12.target(), inLambda);
            }
            case Expr.Conditional expr$13 -> {
                visit(expr$13.condition(), inLambda);
                visit(expr$13.then(), inLambda);
                visit(expr$13.otherwise(), inLambda);
            }
            case Expr.Throw expr$14 -> {
                visit(expr$14.value(), inLambda);
            }
            case Expr.When expr$15 -> {
                visit(expr$15.subject(), inLambda);
                if (lambdaBodies > 0) {
                    for (final var arm : expr$15.arms()) {
                        statements(arm.body(), inLambda);
                    }
                }
            }
            case Expr.Paren expr$16 -> {
                visit(expr$16.inner(), inLambda);
            }
            case Expr.Try expr$17 -> {
                visit(expr$17.inner(), inLambda);
            }
            case Expr.Attempt expr$18 -> {
                visit(expr$18.body(), true);
                visit(expr$18.handler(), true);
            }
            case Expr.Lambda expr$19 -> {
                final var before = new LinkedHashSet<String>(names);
                lambdaBodies += 1;
                statements(expr$19.body(), inLambda);
                lambdaBodies -= 1;
                names.retainAll(before);
            }
            case Expr.Pipe expr$20 -> {
                visit(expr$20.source(), inLambda);
                for (final var stage : expr$20.stages()) {
                    visitAll(stage.args(), inLambda);
                    final var lambda = stage.lambda();
                    if (lambda != null) {
                        visit(lambda, inLambda);
                    }
                }
            }
        }
    }
    private void visitAll(List<Expr> exprs, boolean inLambda) {
        for (final var expr : exprs) {
            visit(expr, inLambda);
        }
    }
    private void statements(List<Stmt> statements, boolean inLambda) {
        for (final var stmt : statements) {
            statement(stmt, inLambda);
        }
    }
    private void statement(Stmt stmt, boolean inLambda) {
        switch (stmt) {
            case Stmt.Local stmt$1 -> {
                visit(stmt$1.init(), inLambda);
            }
            case Stmt.Assign stmt$2 -> {
                visit(stmt$2.value(), inLambda);
            }
            case Stmt.Return stmt$3 -> {
                final var value = stmt$3.value();
                if (value != null) {
                    visit(value, inLambda);
                }
            }
            case Stmt.Jump stmt$4 -> {
            }
            case Stmt.AttemptExit stmt$5 -> {
                visit(stmt$5.body(), true);
                statement(stmt$5.exit(), inLambda);
            }
            case Stmt.Use stmt$6 -> {
                visit(stmt$6.resource(), inLambda);
                statement(stmt$6.body(), inLambda);
                final var handler = stmt$6.handler();
                if (handler != null) {
                    visit(handler, inLambda);
                }
            }
            case Stmt.If stmt$7 -> {
                visit(stmt$7.condition(), inLambda);
                statement(stmt$7.then(), inLambda);
                final var otherwise = stmt$7.elseBranch();
                if (otherwise != null) {
                    statement(otherwise, inLambda);
                }
            }
            case Stmt.While stmt$8 -> {
                visit(stmt$8.condition(), inLambda);
                statement(stmt$8.body(), inLambda);
            }
            case Stmt.For stmt$9 -> {
                visit(stmt$9.iterable(), inLambda);
                statement(stmt$9.body(), inLambda);
            }
            case Stmt.Count stmt$10 -> {
                visit(stmt$10.from(), inLambda);
                visit(stmt$10.to(), inLambda);
                final var step = stmt$10.step();
                if (step != null) {
                    visit(step, inLambda);
                }
                statement(stmt$10.body(), inLambda);
            }
            case Stmt.ExprStmt stmt$11 -> {
                visit(stmt$11.expr(), inLambda);
            }
            case Stmt.Block stmt$12 -> {
                statements(stmt$12.statements(), inLambda);
            }
        }
    }
    private void chain(Chains.Chain chain, boolean inLambda) {
        visit(chain.base(), inLambda);
        var afterSafe = inLambda;
        for (final var step : chain.steps()) {
            afterSafe = afterSafe || step.safe();
            final var args = step.args();
            if (args != null) {
                visitAll(args, afterSafe);
            }
        }
    }
    public static List<String> capturedIn(Expr expr, Analysis analysis) {
        Nulls.param(expr, "expr");
        Nulls.param(analysis, "analysis");
        return capturedIn(expr, analysis, false);
    }
    public static List<String> capturedIn(Expr expr, Analysis analysis, boolean rootElvisInlined) {
        Nulls.param(expr, "expr");
        Nulls.param(analysis, "analysis");
        final var captures = new Captures(analysis);
        if (rootElvisInlined && expr instanceof Expr.Elvis expr$1) {
            captures.visit(expr$1.left(), false);
            captures.visit(expr$1.right(), false);
        } else {
            captures.visit(expr, false);
        }
        return new ArrayList<String>(captures.names);
    }
    public static List<String> inAttemptCall(Expr body, Analysis analysis) {
        Nulls.param(body, "body");
        Nulls.param(analysis, "analysis");
        final var captures = new Captures(analysis);
        captures.visit(body, true);
        return new ArrayList<String>(captures.names);
    }
    public static List<String> inTryCatch(Expr.Attempt a, Analysis analysis) {
        Nulls.param(a, "a");
        Nulls.param(analysis, "analysis");
        final var captures = new Captures(analysis);
        captures.visit(a.body(), false);
        captures.visit(a.handler(), false);
        return new ArrayList<String>(captures.names);
    }
    public static List<String> inStatementChain(Chains.Chain chain, Analysis analysis) {
        Nulls.param(chain, "chain");
        Nulls.param(analysis, "analysis");
        final var captures = new Captures(analysis);
        captures.visit(chain.base(), false);
        for (final var step : chain.steps()) {
            final var args = step.args();
            if (args != null) {
                for (final var arg : args) {
                    captures.visit(arg, false);
                }
            }
        }
        return new ArrayList<String>(captures.names);
    }
}
