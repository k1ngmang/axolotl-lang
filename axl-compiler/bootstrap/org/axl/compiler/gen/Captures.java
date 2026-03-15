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
        this.analysis = analysis;
        this.names = new LinkedHashSet<String>();
        this.lambdaBodies = 0;
    }
    public void visit(Expr expr, boolean inLambda) {
        switch (expr) {
            case Expr.Literal $s1 -> {
            }
            case Expr.Name $s2 -> {
                if (inLambda && analysis.mutableRefs().contains($s2)) {
                    names.add($s2.name());
                }
            }
            case Expr.Member $s3 -> {
                chain(Chains.flatten($s3), inLambda);
            }
            case Expr.Call $s4 -> {
                if ($s4.target() != null) {
                    chain(Chains.flatten($s4), inLambda);
                } else {
                    visitAll($s4.args(), inLambda);
                }
            }
            case Expr.GenericNew $s5 -> {
                visitAll($s5.args(), inLambda);
            }
            case Expr.MethodRef $s6 -> {
                visit($s6.target(), inLambda);
            }
            case Expr.NotNull $s7 -> {
                visit($s7.target(), inLambda);
            }
            case Expr.Unary $s8 -> {
                visit($s8.operand(), inLambda);
            }
            case Expr.Binary $s9 -> {
                visit($s9.left(), inLambda);
                visit($s9.right(), inLambda);
            }
            case Expr.Elvis $s10 -> {
                visit($s10.left(), inLambda);
                visit($s10.right(), inLambda || !Lifts.needed($s10.right()));
            }
            case Expr.Is $s11 -> {
                visit($s11.target(), inLambda);
            }
            case Expr.Conditional $s12 -> {
                visit($s12.condition(), inLambda);
                visit($s12.then(), inLambda);
                visit($s12.otherwise(), inLambda);
            }
            case Expr.Throw $s13 -> {
                visit($s13.value(), inLambda);
            }
            case Expr.When $s14 -> {
                visit($s14.subject(), inLambda);
                if (lambdaBodies > 0) {
                    for (final var arm : $s14.arms()) {
                        statements(arm.body(), inLambda);
                    }
                }
            }
            case Expr.Paren $s15 -> {
                visit($s15.inner(), inLambda);
            }
            case Expr.Try $s16 -> {
                visit($s16.inner(), inLambda);
            }
            case Expr.Attempt $s17 -> {
                visit($s17.body(), true);
                visit($s17.handler(), true);
            }
            case Expr.Lambda $s18 -> {
                final var before = new LinkedHashSet<String>(names);
                lambdaBodies += 1;
                statements($s18.body(), inLambda);
                lambdaBodies -= 1;
                names.retainAll(before);
            }
            case Expr.Pipe $s19 -> {
                visit($s19.source(), inLambda);
                for (final var stage : $s19.stages()) {
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
            case Stmt.Local $s1 -> {
                visit($s1.init(), inLambda);
            }
            case Stmt.Assign $s2 -> {
                visit($s2.value(), inLambda);
            }
            case Stmt.Return $s3 -> {
                final var value = $s3.value();
                if (value != null) {
                    visit(value, inLambda);
                }
            }
            case Stmt.If $s4 -> {
                visit($s4.condition(), inLambda);
                statement($s4.then(), inLambda);
                final var otherwise = $s4.elseBranch();
                if (otherwise != null) {
                    statement(otherwise, inLambda);
                }
            }
            case Stmt.While $s5 -> {
                visit($s5.condition(), inLambda);
                statement($s5.body(), inLambda);
            }
            case Stmt.For $s6 -> {
                visit($s6.iterable(), inLambda);
                statement($s6.body(), inLambda);
            }
            case Stmt.ExprStmt $s7 -> {
                visit($s7.expr(), inLambda);
            }
            case Stmt.Block $s8 -> {
                statements($s8.statements(), inLambda);
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
        return capturedIn(expr, analysis, false);
    }
    public static List<String> capturedIn(Expr expr, Analysis analysis, boolean rootElvisInlined) {
        final var captures = new Captures(analysis);
        if (rootElvisInlined && expr instanceof Expr.Elvis $s1) {
            captures.visit($s1.left(), false);
            captures.visit($s1.right(), false);
        } else {
            captures.visit(expr, false);
        }
        return new ArrayList<String>(captures.names);
    }
    public static List<String> inStatementChain(Chains.Chain chain, Analysis analysis) {
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
