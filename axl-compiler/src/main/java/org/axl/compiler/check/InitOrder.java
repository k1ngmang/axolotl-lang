package org.axl.compiler.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;

/**
 * Top-level values are static fields set in declaration order, so a value that reads a later one,
 * directly or through functions of the file, sees null or zero.
 */
final class InitOrder {

    /** A value and a later value it reads while it is initialized. */
    record Problem(Decl.Value value, String later) {}

    private final SourceFile file;
    // Functions and values already followed while one initializer is checked; a cycle reads nothing new.
    private final Set<String> visited = new HashSet<>();

    private InitOrder(SourceFile file) {
        this.file = file;
    }

    static List<Problem> check(SourceFile file) {
        return new InitOrder(file).problems();
    }

    private List<Problem> problems() {
        List<Decl.Value> values = file.decls().stream().filter(Decl.Value.class::isInstance).map(Decl.Value.class::cast).toList();
        List<Problem> problems = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Set<String> read = new HashSet<>();
            visited.clear();
            names(values.get(i).init(), Set.of(), read);
            for (Decl.Value later : values.subList(i, values.size())) {
                if (read.contains(later.name())) {
                    problems.add(new Problem(values.get(i), later.name()));
                    break;
                }
            }
        }
        return problems;
    }

    /**
     * Names an expression reads when it runs, following calls into functions of the file.
     *
     * @param hidden parameters and locals in scope, which are not the top-level values of the same name
     */
    private void names(Expr expr, Set<String> hidden, Set<String> out) {
        switch (expr) {
            case Expr.Name n -> read(n.name(), hidden, out);
            case Expr.Call c -> {
                if (c.target() == null && !hidden.contains(c.name()) || c.target() != null && isThisClass(c.target())) {
                    function(c.name(), out);
                } else if (c.target() != null) {
                    names(c.target(), hidden, out);
                }
                arguments(c.args(), hidden, out);
            }
            case Expr.Member m -> {
                if (isThisClass(m.target())) {
                    read(m.name(), Set.of(), out);
                } else {
                    names(m.target(), hidden, out);
                }
            }
            case Expr.GenericNew g -> arguments(g.args(), hidden, out);
            case Expr.MethodRef r -> names(r.target(), hidden, out);
            case Expr.NotNull n -> names(n.target(), hidden, out);
            case Expr.Unary u -> names(u.operand(), hidden, out);
            case Expr.Binary b -> {
                names(b.left(), hidden, out);
                names(b.right(), hidden, out);
            }
            case Expr.Elvis e -> {
                names(e.left(), hidden, out);
                names(e.right(), hidden, out);
            }
            case Expr.Paren p -> names(p.inner(), hidden, out);
            case Expr.Try t -> names(t.inner(), hidden, out);
            case Expr.Attempt a -> {
                names(a.body(), hidden, out);
                names(a.handler(), a.errorName() == null ? hidden : with(hidden, Set.of(a.errorName())), out);
            }
            case Expr.Pipe p -> {
                names(p.source(), hidden, out);
                for (Expr.Stage stage : p.stages()) {
                    stage.args().forEach(a -> names(a, hidden, out));
                    if (stage.lambda() != null) {
                        lambdaBody(stage.lambda(), hidden, out);
                    }
                }
            }
            // A lambda that is not passed to a call runs later, if at all.
            case Expr.Lambda l -> { }
            case Expr.Literal l -> { }
        }
    }

    /** A lambda or function reference passed to a call is likely called by it. */
    private void arguments(List<Expr> args, Set<String> hidden, Set<String> out) {
        for (Expr arg : args) {
            Expr inner = arg instanceof Expr.Paren p ? p.inner() : arg;
            if (inner instanceof Expr.Lambda lambda) {
                lambdaBody(lambda, hidden, out);
            } else if (inner instanceof Expr.MethodRef ref && isThisClass(ref.target())) {
                function(ref.name(), out);
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
        // Reading a value that holds a lambda is usually done to call it.
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value && value.name().equals(name) && visited.add("val " + name)) {
                Expr init = value.init() instanceof Expr.Paren p ? p.inner() : value.init();
                if (init instanceof Expr.Lambda lambda) {
                    lambdaBody(lambda, Set.of(), out);
                } else if (init instanceof Expr.MethodRef ref && isThisClass(ref.target())) {
                    function(ref.name(), out);
                }
            }
        }
    }

    private void lambdaBody(Expr.Lambda lambda, Set<String> hidden, Set<String> out) {
        Set<String> scope = new HashSet<>(hidden);
        lambda.params().forEach(p -> scope.add(p.name()));
        if (lambda.params().isEmpty()) {
            scope.add("it");
        }
        statements(lambda.body(), scope, out);
    }

    private void function(String name, Set<String> out) {
        if (!visited.add("fn " + name)) {
            return;
        }
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Function fn && fn.name().equals(name)) {
                Set<String> scope = new HashSet<>();
                fn.params().forEach(p -> scope.add(p.name()));
                if (fn.exprBody() != null) {
                    names(fn.exprBody(), scope, out);
                } else {
                    statements(fn.body().statements(), scope, out);
                }
            }
        }
    }

    /** `Main.names` and `app.Main.names` name a value of this file too. */
    private boolean isThisClass(Expr target) {
        String qualified = file.packageName().isEmpty() ? file.className() : file.packageName() + "." + file.className();
        String text = dotted(target);
        return text != null && (text.equals(file.className()) || text.equals(qualified));
    }

    private static String dotted(Expr expr) {
        if (expr instanceof Expr.Name n) {
            return n.name();
        }
        if (expr instanceof Expr.Member m && !m.safe()) {
            String target = dotted(m.target());
            return target == null ? null : target + "." + m.name();
        }
        return null;
    }

    /** A local hides the value of the same name from its declaration to the end of its block. */
    private void statements(List<Stmt> statements, Set<String> hidden, Set<String> out) {
        Set<String> scope = new HashSet<>(hidden);
        for (Stmt stmt : statements) {
            statement(stmt, scope, out);
            if (stmt instanceof Stmt.Local local) {
                scope.add(local.name());
            }
        }
    }

    private void statement(Stmt stmt, Set<String> hidden, Set<String> out) {
        switch (stmt) {
            case Stmt.Local l -> {
                Expr init = l.init() instanceof Expr.Paren p ? p.inner() : l.init();
                // A lambda kept in a local is there to be called.
                if (init instanceof Expr.Lambda lambda) {
                    lambdaBody(lambda, hidden, out);
                } else {
                    names(l.init(), hidden, out);
                }
            }
            case Stmt.Assign a -> {
                names(a.target(), hidden, out);
                names(a.value(), hidden, out);
            }
            case Stmt.Return r -> {
                if (r.value() != null) {
                    names(r.value(), hidden, out);
                }
            }
            case Stmt.If i -> {
                names(i.condition(), hidden, out);
                statement(i.then(), hidden, out);
                if (i.elseBranch() != null) {
                    statement(i.elseBranch(), hidden, out);
                }
            }
            case Stmt.While w -> {
                names(w.condition(), hidden, out);
                statement(w.body(), hidden, out);
            }
            case Stmt.For f -> {
                names(f.iterable(), hidden, out);
                statement(f.body(), with(hidden, Set.of(f.name())), out);
            }
            case Stmt.ExprStmt e -> names(e.expr(), hidden, out);
            case Stmt.Block b -> statements(b.statements(), hidden, out);
        }
    }

    private static Set<String> with(Set<String> names, Set<String> more) {
        Set<String> result = new HashSet<>(names);
        result.addAll(more);
        return result;
    }
}
