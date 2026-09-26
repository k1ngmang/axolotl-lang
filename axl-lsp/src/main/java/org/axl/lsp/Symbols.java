package org.axl.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    record Symbol(String name, Kind kind, Pos pos, TypeRef type, Decl.Function function, Import imported, Origin origin) {
        Symbol(String name, Kind kind, Pos pos, TypeRef type, Decl.Function function, Import imported) {
            this(name, kind, pos, type, function, imported, Origin.DECLARED);
        }
    }

    /** How a symbol came to be: declared, a declared one read as the type a test narrowed it to, or the binding of a `when`. */
    enum Origin { DECLARED, NARROWED, WHEN_BINDING }

    /** A name a condition tests and the type it has where the condition has the outcome asked for. */
    private record Test(String name, TypeRef type) {}

    private Symbols() {}

    /** Visible symbols at pos, inner declarations last, so that later entries win a name lookup. */
    static List<Symbol> visibleAt(SourceFile file, Pos pos) {
        List<Symbol> result = topLevelSymbols(file);
        Decl enclosing = enclosingDecl(file, pos);
        Decl.Constructor constructor = enclosing instanceof Decl.ClassDecl classDecl ? enclosingConstructor(classDecl, pos) : null;
        if (constructor != null) {
            // `this(...)` runs before the object has fields: the constructor sees its own parameters only.
            for (Expr.Param p : constructor.params()) {
                result.add(new Symbol(p.name(), Kind.PARAMETER, p.pos(), p.type(), null, null));
            }
            constructor.args().forEach(a -> expr(a, pos, null, result));
            return result;
        }
        if (enclosing instanceof Decl.ClassDecl classDecl) {
            classScope(classDecl, result);
            enclosing = enclosingMethod(classDecl.methods(), pos);
        } else if (enclosing instanceof Decl.DataClass data) {
            dataClassScope(data, result);
            enclosing = enclosingMethod(data.methods(), pos);
        }
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

    /**
     * How many other declarations of a symbol's name start before it on its line, such as the `it`
     * of an earlier lambda. Each is visible from somewhere between the start of the line and the symbol.
     */
    static int earlierOnItsLine(SourceFile file, Symbol symbol) {
        Pos at = symbol.pos();
        Set<Pos> found = new HashSet<>();
        for (int column = 1; column < at.column(); column++) {
            visibleAt(file, new Pos(at.line(), column)).stream()
                    .filter(s -> s.name().equals(symbol.name()) && s.pos().line() == at.line() && s.pos().column() < at.column())
                    .forEach(s -> found.add(s.pos()));
        }
        return found.size();
    }

    /** The imports and the top-level declarations of a file, visible everywhere in it. */
    static List<Symbol> topLevelSymbols(SourceFile file) {
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
                case Decl.ClassDecl classDecl -> result.add(new Symbol(classDecl.name(), Kind.DECLARED_TYPE, classDecl.namePos(), null, null, null));
                case Decl.EnumDecl enumeration -> result.add(new Symbol(enumeration.name(), Kind.DECLARED_TYPE, enumeration.namePos(), null, null, null));
                case Decl.DataClass data -> result.add(new Symbol(data.name(), Kind.DECLARED_TYPE, data.namePos(), null, null, null));
                case Decl.Interface iface -> result.add(new Symbol(iface.name(), Kind.DECLARED_TYPE, iface.namePos(), null, null, null));
            }
        }
        return result;
    }

    /** Inside a class, its fields are visible by name. */
    private static void classScope(Decl.ClassDecl classDecl, List<Symbol> out) {
        for (Decl.Property p : classDecl.parameters()) {
            out.add(property(p));
        }
        for (Decl.Property p : classDecl.properties()) {
            out.add(property(p));
        }
        for (Decl.Function method : classDecl.methods()) {
            out.add(new Symbol(method.name(), Kind.FUNCTION, method.namePos(), method.returnType(), method, null));
        }
    }

    private static Symbol property(Decl.Property p) {
        return new Symbol(p.name(), p.mutable() ? Kind.VARIABLE : Kind.LOCAL, p.namePos(), p.type(), null, null);
    }

    /** The methods of a data class see its fields and each other by name, as those of a class do. */
    private static void dataClassScope(Decl.DataClass data, List<Symbol> out) {
        for (Expr.Param field : data.fields()) {
            out.add(new Symbol(field.name(), Kind.LOCAL, field.pos(), field.type(), null, null));
        }
        for (Decl.Function method : data.methods()) {
            out.add(new Symbol(method.name(), Kind.FUNCTION, method.namePos(), method.returnType(), method, null));
        }
    }

    /** The constructor whose text holds a position: the last to start before it, with no other member between. */
    private static Decl.Constructor enclosingConstructor(Decl.ClassDecl classDecl, Pos pos) {
        Decl.Constructor found = null;
        for (Decl.Constructor constructor : classDecl.constructors()) {
            if (before(constructor.pos(), pos) && (found == null || before(found.pos(), constructor.pos()))) {
                found = constructor;
            }
        }
        if (found == null) {
            return null;
        }
        Pos start = found.pos();
        boolean later = classDecl.methods().stream().anyMatch(m -> before(start, m.pos()) && before(m.pos(), pos))
                || classDecl.properties().stream().anyMatch(p -> before(start, p.pos()) && before(p.pos(), pos));
        return later ? null : found;
    }

    /** A method covers its text up to the next one; the last also covers the rest of the type. */
    private static Decl enclosingMethod(List<Decl.Function> methods, Pos pos) {
        Decl.Function found = null;
        for (Decl.Function method : methods) {
            if (before(method.pos(), pos)) {
                found = method;
            }
        }
        return found;
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

    /** The name of the class whose text holds pos, or null. */
    static String enclosingClass(SourceFile file, Pos pos) {
        return enclosingDecl(file, pos) instanceof Decl.ClassDecl classDecl ? classDecl.name() : null;
    }

    /** A declaration covers its text up to the next declaration. */
    static Decl enclosingDecl(SourceFile file, Pos pos) {
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
                case Stmt.If ifStmt -> ifStatement(ifStmt, pos, fn, out);
                case Stmt.While loop -> {
                    expr(loop.condition(), pos, fn, out);
                    block(loop.body(), pos, fn, out);
                }
                case Stmt.For loop -> {
                    expr(loop.iterable(), pos, fn, out);
                    if (loop.namePos().equals(pos) || before(loop.body().pos(), pos) && before(pos, loop.body().end())) {
                        out.add(new Symbol(loop.name(), Kind.LOCAL, loop.namePos(), null, fn, null));
                    }
                    block(loop.body(), pos, fn, out);
                }
                case Stmt.Block block -> block(block, pos, fn, out);
                case Stmt.Assign assign -> expr(assign.value(), pos, fn, out);
                case Stmt.Jump jump -> { }
                case Stmt.AttemptExit attempt -> {
                    expr(attempt.body(), pos, fn, out);
                    if (attempt.errorName() != null && before(attempt.errorNamePos(), pos)) {
                        out.add(new Symbol(attempt.errorName(), Kind.PARAMETER, attempt.errorNamePos(), attempt.errorType(), fn, null));
                    }
                    statements(List.of(attempt.exit()), pos, fn, out);
                }
                case Stmt.Use use -> {
                    expr(use.resource(), pos, fn, out);
                    if (before(use.body().pos(), pos) && before(pos, use.body().end())) {
                        out.add(new Symbol(use.name(), Kind.LOCAL, use.namePos(), use.type(), fn, null));
                    }
                    block(use.body(), pos, fn, out);
                    if (use.handler() != null) {
                        if (use.errorName() != null && before(use.errorNamePos(), pos)) {
                            out.add(new Symbol(use.errorName(), Kind.PARAMETER, use.errorNamePos(), use.errorType(), fn, null));
                        }
                        expr(use.handler(), pos, fn, out);
                    }
                }
                case Stmt.Return ret -> {
                    if (ret.value() != null) {
                        expr(ret.value(), pos, fn, out);
                    }
                }
                case Stmt.ExprStmt e -> expr(e.expr(), pos, fn, out);
            }
            if (i + 1 < statements.size() && before(statements.get(i + 1).pos(), pos)) {
                // An `attempt` error name and the narrowing by a test in an expression hold only in their own statement.
                out.subList(start, out.size()).removeIf(s -> s.kind() == Kind.PARAMETER || s.origin() == Origin.NARROWED);
                if (stmt instanceof Stmt.If ifStmt && ifStmt.elseBranch() == null && leaves(ifStmt.then())) {
                    tests(ifStmt.condition(), false).forEach(t -> narrow(t.name(), t.type(), out));
                }
            }
        }
    }

    /** The branch where the condition holds sees what it tests, the other branch what it rules out. */
    private static void ifStatement(Stmt.If ifStmt, Pos pos, Decl.Function fn, List<Symbol> out) {
        expr(ifStmt.condition(), pos, fn, out);
        if (inside(ifStmt.then(), pos)) {
            tests(ifStmt.condition(), true).forEach(t -> narrow(t.name(), t.type(), out));
            block(ifStmt.then(), pos, fn, out);
        } else if (ifStmt.elseBranch() != null && before(ifStmt.elseBranch().pos(), pos)) {
            tests(ifStmt.condition(), false).forEach(t -> narrow(t.name(), t.type(), out));
            statements(List.of(ifStmt.elseBranch()), pos, fn, out);
        }
    }

    /** A block that ends with `return`, `break`, `continue` or `throw`, after which the code runs only when its condition failed. */
    private static boolean leaves(Stmt.Block block) {
        Stmt last = block.statements().isEmpty() ? null : block.statements().getLast();
        return last instanceof Stmt.Return || last instanceof Stmt.Jump || last instanceof Stmt.ExprStmt e && e.expr() instanceof Expr.Throw;
    }

    /** The names a condition tests with `is` and their types, where the condition holds or, with holds false, fails. */
    private static List<Test> tests(Expr condition, boolean holds) {
        return switch (condition) {
            case Expr.Paren p -> tests(p.inner(), holds);
            case Expr.Is is when is.negated() != holds && is.target() instanceof Expr.Name name -> List.of(new Test(name.name(), is.type()));
            case Expr.Unary u when u.op().equals("!") -> tests(u.operand(), !holds);
            case Expr.Binary b when b.op().equals(holds ? "&&" : "||") -> {
                List<Test> both = new ArrayList<>(tests(b.left(), holds));
                both.addAll(tests(b.right(), holds));
                yield both;
            }
            default -> List.of();
        };
    }

    private static void block(Stmt.Block block, Pos pos, Decl.Function fn, List<Symbol> out) {
        if (inside(block, pos)) {
            statements(block.statements(), pos, fn, out);
        }
    }

    private static boolean inside(Stmt.Block block, Pos pos) {
        return before(block.pos(), pos) && before(pos, block.end());
    }

    /**
     * Where a test `x is T` holds, `x` reads as a T: the nearest visible `x` is shadowed by one with
     * that type. A `var` may change after the test and keeps its type.
     */
    private static void narrow(String name, TypeRef type, List<Symbol> out) {
        for (int i = out.size() - 1; i >= 0; i--) {
            Symbol symbol = out.get(i);
            if (symbol.name().equals(name)) {
                if (symbol.kind() != Kind.VARIABLE) {
                    out.add(new Symbol(name, symbol.kind(), symbol.pos(), type, symbol.function(), symbol.imported(), Origin.NARROWED));
                }
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
            case Expr.ClassLiteral c -> expr(c.target(), pos, fn, out);
            case Expr.NotNull n -> expr(n.target(), pos, fn, out);
            case Expr.Unary u -> expr(u.operand(), pos, fn, out);
            case Expr.Binary b -> {
                expr(b.left(), pos, fn, out);
                boolean and = b.op().equals("&&");
                if ((and || b.op().equals("||")) && before(start(b.right()), pos)) {
                    tests(b.left(), and).forEach(t -> narrow(t.name(), t.type(), out));
                }
                expr(b.right(), pos, fn, out);
            }
            case Expr.Elvis e -> {
                expr(e.left(), pos, fn, out);
                expr(e.right(), pos, fn, out);
            }
            case Expr.Is i -> expr(i.target(), pos, fn, out);
            case Expr.Conditional c -> {
                expr(c.condition(), pos, fn, out);
                if (before(start(c.then()), pos)) {
                    tests(c.condition(), !before(start(c.otherwise()), pos)).forEach(t -> narrow(t.name(), t.type(), out));
                }
                expr(c.then(), pos, fn, out);
                expr(c.otherwise(), pos, fn, out);
            }
            case Expr.Throw t -> expr(t.value(), pos, fn, out);
            case Expr.When w -> {
                expr(w.subject(), pos, fn, out);
                if (w.bindingName() != null && before(w.bindingPos(), pos) && before(pos, w.end())) {
                    out.add(new Symbol(w.bindingName(), Kind.LOCAL, w.bindingPos(), null, fn, null, Origin.WHEN_BINDING));
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
                    out.add(new Symbol(a.errorName(), Kind.PARAMETER, a.errorNamePos(), a.errorType(), fn, null));
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

    /** Where an expression starts; operators and member accesses are positioned after their left side. */
    private static Pos start(Expr expr) {
        Expr left = switch (expr) {
            case Expr.Binary b -> b.left();
            case Expr.Member m -> m.target();
            case Expr.Call c -> c.target();
            case Expr.NotNull n -> n.target();
            case Expr.Is i -> i.target();
            case Expr.Elvis e -> e.left();
            case Expr.MethodRef r -> r.target();
            case Expr.Pipe p -> p.source();
            default -> null;
        };
        Pos leftStart = left == null ? expr.pos() : start(left);
        return before(leftStart, expr.pos()) ? leftStart : expr.pos();
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
