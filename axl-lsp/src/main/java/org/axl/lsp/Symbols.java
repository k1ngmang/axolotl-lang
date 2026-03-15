package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

/** Which names are visible at a position of a file, and where they are declared. */
final class Symbols {

    enum Kind { FUNCTION, VALUE, IMPORTED_FUNCTION, IMPORTED_VALUE, TYPE, DECLARED_TYPE, PARAMETER, LOCAL, VARIABLE }

    /**
     * type is null when it is not written in the source; function is set for functions and their
     * locals, and null for the lambda parameters of a top-level value.
     */
    record Symbol(String name, Kind kind, Pos pos, TypeRef type, Decl.Function function, Import imported) {}

    private Symbols() {}

    /** Visible symbols at pos, inner declarations last, so that later entries win a name lookup. */
    static List<Symbol> visibleAt(SourceFile file, Pos pos) {
        List<Symbol> result = new ArrayList<>();
        for (Import imp : file.imports()) {
            Kind kind = switch (imp.kind()) {
                case TYPE -> Kind.TYPE;
                case FN -> Kind.IMPORTED_FUNCTION;
                case VAL -> Kind.IMPORTED_VALUE;
            };
            result.add(new Symbol(imp.simpleName(), kind, imp.pos(), null, null, imp));
        }
        for (Decl decl : file.decls()) {
            switch (decl) {
                case Decl.Function fn -> result.add(new Symbol(fn.name(), Kind.FUNCTION, fn.namePos(), fn.returnType(), fn, null));
                case Decl.Value value -> result.add(new Symbol(value.name(), Kind.VALUE, value.namePos(), value.type(), null, null));
                case Decl.ClassDecl classDecl -> { }
                case Decl.DataClass data -> result.add(new Symbol(data.name(), Kind.DECLARED_TYPE, data.namePos(), null, null, null));
                case Decl.Interface iface -> result.add(new Symbol(iface.name(), Kind.DECLARED_TYPE, iface.namePos(), null, null, null));
            }
        }
        Decl enclosing = enclosingDecl(file, pos);
        if (enclosing instanceof Decl.Function fn) {
            for (Expr.Param p : fn.params()) {
                result.add(new Symbol(p.name(), Kind.PARAMETER, p.pos(), p.type(), fn, null));
            }
            if (fn.body() != null) {
                statements(fn.body().statements(), pos, fn, result);
            } else {
                expr(fn.exprBody(), pos, fn, result);
            }
        } else if (enclosing instanceof Decl.Value value) {
            // Lambda parameters in the initializer.
            expr(value.init(), pos, null, result);
        }
        return result;
    }

    static Symbol lookup(SourceFile file, Pos pos, String name) {
        return lookup(file, pos, name, () -> null);
    }

    /**
     * The symbol a name at pos refers to: the one it declares, or of overloaded functions the one
     * that fits the arguments best. argumentTypes: see {@link Document#argumentTypes}; only asked
     * for when there are overloads.
     */
    static Symbol lookup(SourceFile file, Pos pos, String name, Supplier<List<String>> argumentTypes) {
        List<Symbol> found = visibleAt(file, pos).stream().filter(s -> s.name().equals(name)).toList();
        if (found.isEmpty()) {
            return null;
        }
        Symbol declared = found.stream().filter(s -> s.pos().equals(pos)).findFirst().orElse(null);
        if (declared != null) {
            return declared;
        }
        Symbol last = found.getLast();
        List<Decl.Function> functions = found.stream().filter(s -> s.kind() == Kind.FUNCTION).map(Symbol::function).toList();
        if (last.kind() != Kind.FUNCTION || functions.size() < 2) {
            return last;
        }
        Decl.Function best = bestOverload(functions, argumentTypes.get());
        return found.stream().filter(s -> s.function() == best).findFirst().orElse(last);
    }

    /** A top-level declaration by name; of overloaded functions the one that fits the arguments best. */
    static Decl topLevel(SourceFile file, String name, Supplier<List<String>> argumentTypes) {
        List<Decl> found = file.decls().stream().filter(d -> d.name().equals(name)).toList();
        List<Decl.Function> functions = found.stream().filter(d -> d instanceof Decl.Function)
                .map(d -> (Decl.Function) d).toList();
        if (functions.size() > 1) {
            return bestOverload(functions, argumentTypes.get());
        }
        return found.isEmpty() ? null : found.getFirst();
    }

    /** Of overloads that fit equally well, the first declared one. */
    private static Decl.Function bestOverload(List<Decl.Function> functions, List<String> argumentTypes) {
        Decl.Function best = functions.getFirst();
        if (argumentTypes == null) {
            return best;
        }
        int bestScore = Integer.MIN_VALUE;
        for (Decl.Function fn : functions) {
            int score = fit(fn, argumentTypes);
            if (score > bestScore) {
                best = fn;
                bestScore = score;
            }
        }
        return best;
    }

    /** Higher for a better fit: the number of arguments first, then the types of the arguments that are known. */
    private static int fit(Decl.Function fn, List<String> argumentTypes) {
        if (fn.params().size() != argumentTypes.size()) {
            return Integer.MIN_VALUE + 1;
        }
        int score = 0;
        for (int i = 0; i < argumentTypes.size(); i++) {
            String argument = argumentTypes.get(i);
            TypeRef parameter = fn.params().get(i).type();
            if (argument == null || parameter == null) {
                score += 1;
            } else if (argument.equals(parameter.name())) {
                score += 2;
            } else if (argument.equals("Int") && List.of("Long", "Double").contains(parameter.name())) {
                score += 1;
            }
        }
        return score;
    }

    /** A declaration covers its text up to the next declaration. */
    private static Decl enclosingDecl(SourceFile file, Pos pos) {
        List<Decl> decls = file.decls();
        for (int i = 0; i < decls.size(); i++) {
            Pos start = decls.get(i).pos();
            Pos end = i + 1 < decls.size() ? decls.get(i + 1).pos() : new Pos(Integer.MAX_VALUE, 0);
            if (before(start, pos) && before(pos, end)) {
                return decls.get(i);
            }
        }
        return null;
    }

    private static void statements(List<Stmt> statements, Pos pos, Decl.Function fn, List<Symbol> out) {
        for (int i = 0; i < statements.size(); i++) {
            Stmt stmt = statements.get(i);
            if (!before(stmt.pos(), pos)) {
                return;
            }
            int start = out.size();
            switch (stmt) {
                case Stmt.Local local -> {
                    expr(local.init(), pos, fn, out);
                    out.add(new Symbol(local.name(), local.mutable() ? Kind.VARIABLE : Kind.LOCAL, local.namePos(),
                            local.type(), fn, null));
                }
                case Stmt.If ifStmt -> {
                    expr(ifStmt.condition(), pos, fn, out);
                    if (ifStmt.condition() instanceof Expr.Is is && !is.negated() && is.target() instanceof Expr.Name name
                            && inside(ifStmt.then(), pos)) {
                        narrow(name.name(), is.type(), out);
                    }
                    block(ifStmt.then(), pos, fn, out);
                    if (ifStmt.elseBranch() != null) {
                        statements(List.of(ifStmt.elseBranch()), pos, fn, out);
                    }
                }
                case Stmt.While loop -> {
                    expr(loop.condition(), pos, fn, out);
                    block(loop.body(), pos, fn, out);
                }
                case Stmt.For loop -> {
                    expr(loop.iterable(), pos, fn, out);
                    if (before(loop.body().pos(), pos) && before(pos, loop.body().end())) {
                        out.add(new Symbol(loop.name(), Kind.LOCAL, loop.namePos(), null, fn, null));
                    }
                    block(loop.body(), pos, fn, out);
                }
                case Stmt.Block block -> block(block, pos, fn, out);
                case Stmt.Assign assign -> expr(assign.value(), pos, fn, out);
                case Stmt.Return ret -> {
                    if (ret.value() != null) {
                        expr(ret.value(), pos, fn, out);
                    }
                }
                case Stmt.ExprStmt e -> expr(e.expr(), pos, fn, out);
            }
            if (i + 1 < statements.size() && before(statements.get(i + 1).pos(), pos)) {
                // An `attempt` error name is visible only in its own statement.
                out.subList(start, out.size()).removeIf(s -> s.kind() == Kind.PARAMETER);
            }
        }
    }

    private static void block(Stmt.Block block, Pos pos, Decl.Function fn, List<Symbol> out) {
        if (inside(block, pos)) {
            statements(block.statements(), pos, fn, out);
        }
    }

    private static boolean inside(Stmt.Block block, Pos pos) {
        return before(block.pos(), pos) && before(pos, block.end());
    }

    /** Where a test `x is T` holds, `x` reads as a T: the nearest visible `x` is shadowed by one with that type. */
    private static void narrow(String name, TypeRef type, List<Symbol> out) {
        for (int i = out.size() - 1; i >= 0; i--) {
            Symbol symbol = out.get(i);
            if (symbol.name().equals(name)) {
                out.add(new Symbol(name, symbol.kind(), symbol.pos(), type, symbol.function(), symbol.imported()));
                return;
            }
        }
    }

    /** Lambda parameters and locals are visible only inside the lambda. */
    private static void expr(Expr expr, Pos pos, Decl.Function fn, List<Symbol> out) {
        switch (expr) {
            case Expr.Lambda lambda -> {
                if (before(lambda.pos(), pos) && before(pos, lambda.end())) {
                    for (Expr.Param p : lambda.params()) {
                        out.add(new Symbol(p.name(), Kind.PARAMETER, p.pos(), p.type(), fn, null));
                    }
                    if (lambda.params().isEmpty() && !lambda.arrow()) {
                        out.add(new Symbol("it", Kind.PARAMETER, lambda.pos(), null, fn, null));
                    }
                    statements(lambda.body(), pos, fn, out);
                }
            }
            case Expr.Call c -> {
                if (c.target() != null) {
                    expr(c.target(), pos, fn, out);
                }
                c.args().forEach(a -> expr(a, pos, fn, out));
            }
            case Expr.Member m -> expr(m.target(), pos, fn, out);
            case Expr.GenericNew g -> g.args().forEach(a -> expr(a, pos, fn, out));
            case Expr.MethodRef r -> expr(r.target(), pos, fn, out);
            case Expr.NotNull n -> expr(n.target(), pos, fn, out);
            case Expr.Unary u -> expr(u.operand(), pos, fn, out);
            case Expr.Binary b -> {
                expr(b.left(), pos, fn, out);
                expr(b.right(), pos, fn, out);
            }
            case Expr.Elvis e -> {
                expr(e.left(), pos, fn, out);
                expr(e.right(), pos, fn, out);
            }
            case Expr.Is i -> expr(i.target(), pos, fn, out);
            case Expr.Conditional c -> {
                expr(c.condition(), pos, fn, out);
                expr(c.then(), pos, fn, out);
                expr(c.otherwise(), pos, fn, out);
            }
            case Expr.Throw t -> expr(t.value(), pos, fn, out);
            case Expr.When w -> {
                expr(w.subject(), pos, fn, out);
                if (w.bindingName() != null && before(w.bindingPos(), pos) && before(pos, w.end())) {
                    out.add(new Symbol(w.bindingName(), Kind.LOCAL, w.bindingPos(), null, fn, null));
                }
                List<Expr.Arm> arms = w.arms();
                for (int i = 0; i < arms.size(); i++) {
                    Pos armEnd = i + 1 < arms.size() ? arms.get(i + 1).pos() : w.end();
                    if (before(arms.get(i).pos(), pos) && before(pos, armEnd)) {
                        narrowToArmType(w, arms.get(i), out);
                        statements(arms.get(i).body(), pos, fn, out);
                    }
                }
            }
            case Expr.Paren p -> expr(p.inner(), pos, fn, out);
            case Expr.Try t -> expr(t.inner(), pos, fn, out);
            case Expr.Attempt a -> {
                expr(a.body(), pos, fn, out);
                if (a.errorName() != null && before(a.errorNamePos(), pos)) {
                    out.add(new Symbol(a.errorName(), Kind.PARAMETER, a.errorNamePos(), null, fn, null));
                }
                expr(a.handler(), pos, fn, out);
            }
            case Expr.Pipe p -> {
                expr(p.source(), pos, fn, out);
                for (Expr.Stage stage : p.stages()) {
                    stage.args().forEach(a -> expr(a, pos, fn, out));
                    if (stage.lambda() != null) {
                        expr(stage.lambda(), pos, fn, out);
                    }
                }
            }
            case Expr.Literal l -> { }
            case Expr.Name n -> { }
        }
    }

    /** In an arm `is T ->`, the subject reads as a T. */
    private static void narrowToArmType(Expr.When when, Expr.Arm arm, List<Symbol> out) {
        String subject = when.bindingName() != null ? when.bindingName()
                : when.subject() instanceof Expr.Name name ? name.name() : null;
        if (subject != null && arm.conditions().size() == 1 && arm.conditions().getFirst().type() != null) {
            narrow(subject, arm.conditions().getFirst().type(), out);
        }
    }

    static boolean before(Pos a, Pos b) {
        return a.line() < b.line() || a.line() == b.line() && a.column() <= b.column();
    }
}
