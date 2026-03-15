package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;

@NullMarked public final class InitOrder {
    public final SourceFile file;
    public final HashSet<String> visited;
    public InitOrder(SourceFile file) {
        this.file = file;
        this.visited = new HashSet<String>();
    }
    public List<Problem> problems() {
        final var values = new ArrayList<Decl.Value>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1) {
                values.add($s1);
            }
        }
        final var problems = new ArrayList<Problem>();
        var i = 0;
        while (i < values.size()) {
            final var read = new HashSet<String>();
            visited.clear();
            names(values.get(i).init(), Set.of(), read);
            final var later = firstRead(values.subList(i, values.size()), read);
            if (later != null) {
                problems.add(new Problem(values.get(i), later));
            }
            i += 1;
        }
        return problems;
    }
    private @Nullable String firstRead(List<Decl.Value> values, Set<String> read) {
        for (final var value : values) {
            if (read.contains(value.name())) {
                return value.name();
            }
        }
        return null;
    }
    private void names(Expr expr, Set<String> hidden, Set<String> out) {
        switch (expr) {
            case Expr.Name $s1 -> {
                read($s1.name(), hidden, out);
            }
            case Expr.Call $s2 -> {
                callNames($s2, hidden, out);
            }
            case Expr.Member $s3 -> {
                if (isThisClass($s3.target())) {
                    read($s3.name(), Set.of(), out);
                } else {
                    names($s3.target(), hidden, out);
                }
            }
            case Expr.GenericNew $s4 -> {
                arguments($s4.args(), hidden, out);
            }
            case Expr.MethodRef $s5 -> {
                names($s5.target(), hidden, out);
            }
            case Expr.NotNull $s6 -> {
                names($s6.target(), hidden, out);
            }
            case Expr.Unary $s7 -> {
                names($s7.operand(), hidden, out);
            }
            case Expr.Binary $s8 -> {
                names($s8.left(), hidden, out);
                names($s8.right(), hidden, out);
            }
            case Expr.Elvis $s9 -> {
                names($s9.left(), hidden, out);
                names($s9.right(), hidden, out);
            }
            case Expr.Is $s10 -> {
                names($s10.target(), hidden, out);
            }
            case Expr.Conditional $s11 -> {
                names($s11.condition(), hidden, out);
                names($s11.then(), hidden, out);
                names($s11.otherwise(), hidden, out);
            }
            case Expr.Throw $s12 -> {
                names($s12.value(), hidden, out);
            }
            case Expr.When $s13 -> {
                whenNames($s13, hidden, out);
            }
            case Expr.Paren $s14 -> {
                names($s14.inner(), hidden, out);
            }
            case Expr.Try $s15 -> {
                names($s15.inner(), hidden, out);
            }
            case Expr.Attempt $s16 -> {
                names($s16.body(), hidden, out);
                final var errorName = $s16.errorName();
                names($s16.handler(), (errorName == null ? hidden : with(hidden, Set.of(errorName))), out);
            }
            case Expr.Pipe $s17 -> {
                pipeNames($s17, hidden, out);
            }
            case Expr.Lambda $s18 -> {
            }
            case Expr.Literal $s19 -> {
            }
        }
    }
    private void callNames(Expr.Call call, Set<String> hidden, Set<String> out) {
        final var target = call.target();
        if (target == null) {
            if (!hidden.contains(call.name())) {
                function(call.name(), out);
            }
        } else if (isThisClass(target)) {
            function(call.name(), out);
        } else {
            names(target, hidden, out);
        }
        arguments(call.args(), hidden, out);
    }
    private void whenNames(Expr.When w, Set<String> hidden, Set<String> out) {
        names(w.subject(), hidden, out);
        final var binding = w.bindingName();
        final var inArms = (binding == null ? hidden : with(hidden, Set.of(binding)));
        for (final var arm : w.arms()) {
            statements(arm.body(), inArms, out);
        }
    }
    private void pipeNames(Expr.Pipe pipe, Set<String> hidden, Set<String> out) {
        names(pipe.source(), hidden, out);
        for (final var stage : pipe.stages()) {
            for (final var arg : stage.args()) {
                names(arg, hidden, out);
            }
            final var lambda = stage.lambda();
            if (lambda != null) {
                lambdaBody(lambda, hidden, out);
            }
        }
    }
    private void arguments(List<Expr> args, Set<String> hidden, Set<String> out) {
        for (final var arg : args) {
            final var inner = unparenthesized(arg);
            if (inner instanceof Expr.Lambda $s1) {
                lambdaBody($s1, hidden, out);
            } else if (inner instanceof Expr.MethodRef $s2 && isThisClass($s2.target())) {
                function($s2.name(), out);
            } else {
                names(arg, hidden, out);
            }
        }
    }
    private void read(String name, Set<String> hidden, Set<String> out) {
        if (hidden.contains(name)) {
            return;
        }
        out.add(name);
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1 && $s1.name().equals(name) && visited.add("val " + name)) {
                final var init = unparenthesized($s1.init());
                if (init instanceof Expr.Lambda $s2) {
                    lambdaBody($s2, Set.of(), out);
                } else if (init instanceof Expr.MethodRef $s3 && isThisClass($s3.target())) {
                    function($s3.name(), out);
                }
            }
        }
    }
    private void lambdaBody(Expr.Lambda lambda, Set<String> hidden, Set<String> out) {
        final var scope = new HashSet<String>(hidden);
        for (final var param : lambda.params()) {
            scope.add(param.name());
        }
        if (lambda.params().isEmpty()) {
            scope.add("it");
        }
        statements(lambda.body(), scope, out);
    }
    private void function(String name, Set<String> out) {
        if (!visited.add("fn " + name)) {
            return;
        }
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function $s1 && $s1.name().equals(name)) {
                final var scope = new HashSet<String>();
                for (final var param : $s1.params()) {
                    scope.add(param.name());
                }
                final var exprBody = $s1.exprBody();
                if (exprBody != null) {
                    names(exprBody, scope, out);
                } else {
                    statements(java.util.Objects.requireNonNull($s1.body()).statements(), scope, out);
                }
            }
        }
    }
    private boolean isThisClass(Expr target) {
        final var text = dotted(target);
        if (text == null) {
            return false;
        }
        final var packageName = java.util.Objects.requireNonNull(file.packageName());
        final var qualified = (packageName.isEmpty() ? file.className() : packageName + "." + file.className());
        return text.equals(file.className()) || text.equals(qualified);
    }
    private void statements(List<Stmt> statements, Set<String> hidden, Set<String> out) {
        final var scope = new HashSet<String>(hidden);
        for (final var stmt : statements) {
            statement(stmt, scope, out);
            if (stmt instanceof Stmt.Local $s1) {
                scope.add($s1.name());
            }
        }
    }
    private void statement(Stmt stmt, Set<String> hidden, Set<String> out) {
        switch (stmt) {
            case Stmt.Local $s1 -> {
                final var init = unparenthesized($s1.init());
                if (init instanceof Expr.Lambda $s2) {
                    lambdaBody($s2, hidden, out);
                } else {
                    names($s1.init(), hidden, out);
                }
            }
            case Stmt.Assign $s3 -> {
                names($s3.target(), hidden, out);
                names($s3.value(), hidden, out);
            }
            case Stmt.Return $s4 -> {
                final var value = $s4.value();
                if (value != null) {
                    names(value, hidden, out);
                }
            }
            case Stmt.If $s5 -> {
                names($s5.condition(), hidden, out);
                statement($s5.then(), hidden, out);
                final var otherwise = $s5.elseBranch();
                if (otherwise != null) {
                    statement(otherwise, hidden, out);
                }
            }
            case Stmt.While $s6 -> {
                names($s6.condition(), hidden, out);
                statement($s6.body(), hidden, out);
            }
            case Stmt.For $s7 -> {
                names($s7.iterable(), hidden, out);
                statement($s7.body(), with(hidden, Set.of($s7.name())), out);
            }
            case Stmt.ExprStmt $s8 -> {
                names($s8.expr(), hidden, out);
            }
            case Stmt.Block $s9 -> {
                statements($s9.statements(), hidden, out);
            }
        }
    }
    public record Problem(Decl.Value value, String later) {}
    public static List<Problem> check(SourceFile file) {
        return new InitOrder(file).problems();
    }
    private static Expr unparenthesized(Expr expr) {
        return (expr instanceof Expr.Paren $s1 ? $s1.inner() : expr);
    }
    private static @Nullable String dotted(Expr expr) {
        if (expr instanceof Expr.Name $s1) {
            return $s1.name();
        }
        if (expr instanceof Expr.Member $s2 && !$s2.safe()) {
            final var target = dotted($s2.target());
            return (target == null ? null : target + "." + $s2.name());
        }
        return null;
    }
    private static Set<String> with(Set<String> names, Set<String> more) {
        final var result = new HashSet<String>(names);
        result.addAll(more);
        return result;
    }
}
