package org.axl.compiler.gen;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.LineMap;
import org.axl.compiler.Options;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.check.Analysis;

/** Generates Java following the expansion rules of the specification. Expects a file without errors. */
public final class Generator {

    private static final Map<String, String[]> PRIMITIVES = Map.of(
            "Int", new String[] {"int", "Integer"},
            "Long", new String[] {"long", "Long"},
            "Boolean", new String[] {"boolean", "Boolean"},
            "Double", new String[] {"double", "Double"});

    private final SourceFile file;
    private final Analysis analysis;
    private final Options options;
    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private Emitter em = new Emitter();

    // Per function: counters of generated names and the copies of captured `var`s.
    private int tempCount;
    private int nullCount;
    private int exceptionCount;
    private int copyCount;
    private Map<String, String> copies = Map.of();

    private Generator(SourceFile file, Analysis analysis, Options options) {
        this.file = file;
        this.analysis = analysis;
        this.options = options;
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Function fn) {
                declaredFunctions.add(fn.name());
            }
        }
        for (Import imp : file.imports()) {
            if (imp.kind() == Import.Kind.FN) {
                declaredFunctions.add(imp.simpleName());
            }
        }
    }

    public static GeneratedUnit generate(String sourcePath, SourceFile file, Analysis analysis, Options options) {
        Generator generator = new Generator(file, analysis, options);
        generator.emitFile();
        return new GeneratedUnit(sourcePath, file.packageName(), file.className(), generator.em.text(),
                new LineMap(sourcePath, generator.em.lineMap()));
    }

    private void emitFile() {
        em.line(file.packagePos().line(), "package " + file.packageName() + ";");
        em.line(0, "import axl.lang.*;");
        em.line(0, "import org.jspecify.annotations.*;");
        for (Import imp : file.imports()) {
            String keyword = imp.kind() == Import.Kind.TYPE ? "import " : "import static ";
            em.line(imp.pos().line(), keyword + imp.name() + ";");
        }
        em.blank();
        em.line(0, "@NullMarked public final class " + file.className() + " {");
        em.indent();
        em.line(0, "private " + file.className() + "() {}");
        for (Decl decl : file.decls()) {
            resetCounters();
            switch (decl) {
                case Decl.Value value -> emitValue(value);
                case Decl.Function fn -> emitFunction(fn);
            }
        }
        em.dedent();
        em.line(0, "}");
    }

    private void resetCounters() {
        tempCount = 0;
        nullCount = 0;
        exceptionCount = 0;
        copyCount = 0;
        copies = Map.of();
    }

    private void emitValue(Decl.Value value) {
        em.line(value.pos().line(), visibility(value) + " static final " + type(value.type()) + " " + value.name()
                + " = " + expr(value.init()) + ";");
    }

    private void emitFunction(Decl.Function fn) {
        String params = isMain(fn) ? "String[] args"
                : fn.params().stream().map(p -> type(p.type()) + " " + p.name()).collect(Collectors.joining(", "));
        String returnType = fn.returnType() == null ? "void" : type(fn.returnType());
        em.line(fn.pos().line(), visibility(fn) + " static " + returnType + " " + fn.name() + "(" + params + ") {");
        em.indent();
        if (fn.exprBody() != null) {
            int line = fn.exprBody().pos().line();
            Stmt body = fn.returnType() == null
                    ? new Stmt.ExprStmt(fn.exprBody().pos(), fn.exprBody())
                    : new Stmt.Return(fn.exprBody().pos(), fn.exprBody());
            statement(body);
            em.dedent();
            em.line(line, "}");
        } else {
            fn.body().statements().forEach(this::statement);
            em.dedent();
            em.line(fn.body().end().line(), "}");
        }
    }

    private static boolean isMain(Decl.Function fn) {
        return fn.name().equals("main") && fn.params().isEmpty() && fn.returnType() == null;
    }

    private static String visibility(Decl decl) {
        return decl.isPrivate() ? "private" : "public";
    }

    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local local -> {
                int line = local.pos().line();
                Expr init = local.init();
                withCopies(line, init, () -> {
                    String value = init instanceof Expr.Try t ? unwrapTry(line, t) : value(line, init);
                    String modifiers = local.mutable() ? "" : "final ";
                    String type = local.type() == null ? "var" : type(local.type());
                    em.line(line, modifiers + type + " " + local.name() + " = " + value + ";");
                });
            }
            case Stmt.Assign assign -> withCopies(assign.pos().line(), assign.value(), () -> {
                String value = value(assign.pos().line(), assign.value());
                em.line(assign.pos().line(), expr(assign.target()) + " " + assign.op() + " " + value + ";");
            });
            case Stmt.Return ret -> {
                if (ret.value() == null) {
                    em.line(ret.pos().line(), "return;");
                } else {
                    withCopies(ret.pos().line(), ret.value(), () ->
                            em.line(ret.pos().line(), "return " + value(ret.pos().line(), ret.value()) + ";"));
                }
            }
            case Stmt.If ifStmt -> {
                emitIf(ifStmt, "");
                em.line(blockEnd(ifStmt), "}");
            }
            case Stmt.While loop -> emitWhile(loop);
            case Stmt.ExprStmt exprStmt -> {
                int line = exprStmt.pos().line();
                Expr e = exprStmt.expr();
                if (e instanceof Expr.Try t) {
                    withCopies(line, t.inner(), () -> unwrapTry(line, t));
                } else if (Chains.hasSafeStep(e)) {
                    Chains.Chain chain = Chains.flatten(e);
                    withCopies(line, Captures.inStatementChain(chain, analysis), () -> safeChainStatement(line, chain));
                } else {
                    withCopies(line, e, () -> em.line(line, expr(e) + ";"));
                }
            }
            case Stmt.Block block -> {
                em.line(block.pos().line(), "{");
                em.indent();
                block.statements().forEach(this::statement);
                em.dedent();
                em.line(block.end().line(), "}");
            }
        }
    }

    /**
     * Emits the `if` chain up to, but not including, the final closing brace.
     * An `else if` whose condition needs copies becomes `else { copies; if ... }`.
     */
    private void emitIf(Stmt.If ifStmt, String prefix) {
        int line = ifStmt.pos().line();
        List<String> captured = Captures.in(ifStmt.condition(), analysis);
        if (!prefix.isEmpty() && !captured.isEmpty()) {
            em.line(line, prefix + "{");
            em.indent();
            statement(ifStmt);
            em.dedent();
            return;
        }
        withCopies(line, ifStmt.condition(), () -> em.line(line, prefix + "if (" + expr(ifStmt.condition()) + ") {"));
        block(ifStmt.then());
        switch (ifStmt.elseBranch()) {
            case null -> { }
            case Stmt.If elseIf -> emitIf(elseIf, "} else ");
            case Stmt.Block elseBlock -> {
                em.line(elseBlock.pos().line(), "} else {");
                block(elseBlock);
            }
            default -> throw new IllegalStateException();
        }
    }

    private static int blockEnd(Stmt.If ifStmt) {
        return switch (ifStmt.elseBranch()) {
            case null -> ifStmt.then().end().line();
            case Stmt.If elseIf -> blockEnd(elseIf);
            case Stmt.Block block -> block.end().line();
            default -> throw new IllegalStateException();
        };
    }

    private void emitWhile(Stmt.While loop) {
        int line = loop.pos().line();
        if (Captures.in(loop.condition(), analysis).isEmpty()) {
            em.line(line, "while (" + expr(loop.condition()) + ") {");
            block(loop.body());
        } else {
            // The copies must be taken on every iteration, before the condition.
            em.line(line, "while (true) {");
            em.indent();
            withCopies(line, loop.condition(), () -> em.line(line, "if (!(" + expr(loop.condition()) + ")) break;"));
            loop.body().statements().forEach(this::statement);
            em.dedent();
        }
        em.line(loop.body().end().line(), "}");
    }

    private void block(Stmt.Block block) {
        em.indent();
        block.statements().forEach(this::statement);
        em.dedent();
    }

    /** The value of a statement; with inlineElvis a root `?:` becomes a temporary and a conditional. */
    private String value(int line, Expr expr) {
        if (!options.inlineElvis() || !(expr instanceof Expr.Elvis elvis)) {
            return expr(expr);
        }
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + expr(elvis.left()) + ";");
        return temp + " != null ? " + temp + " : " + expr(elvis.right());
    }

    /** Copies `var`s that lambdas of this statement capture into fresh finals, then emits it. */
    private void withCopies(int line, Expr expr, Runnable emit) {
        withCopies(line, Captures.in(expr, analysis, options.inlineElvis()), emit);
    }

    private void withCopies(int line, List<String> captured, Runnable emit) {
        if (captured.isEmpty()) {
            emit.run();
            return;
        }
        Map<String, String> saved = copies;
        copies = new HashMap<>(saved);
        for (String name : captured) {
            String copy = "$c" + ++copyCount;
            em.line(line, "final var " + copy + " = " + name + ";");
            copies.put(name, copy);
        }
        emit.run();
        copies = saved;
    }

    /** Emits the check and early return of `try` and returns the expression that unwraps the value. */
    private String unwrapTry(int line, Expr.Try t) {
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + expr(t.inner()) + ";");
        em.line(line, "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
        return "Result.unwrap(" + temp + ")";
    }

    /** `a?.b()` as a statement: no lambda, so void methods work too. */
    private void safeChainStatement(int line, Chains.Chain chain) {
        String current = expr(chain.base());
        List<Chains.Step> steps = chain.steps();
        int opened = 0;
        for (int i = 0; i < steps.size(); i++) {
            Chains.Step step = steps.get(i);
            if (step.safe()) {
                String temp = "$n" + ++nullCount;
                em.line(line, "final var " + temp + " = " + current + ";");
                em.line(line, "if (" + temp + " != null) {");
                em.indent();
                opened++;
                current = temp;
            }
            current += stepText(step, false);
        }
        em.line(line, current + ";");
        for (int i = 0; i < opened; i++) {
            em.dedent();
            em.line(line, "}");
        }
    }

    String expr(Expr expr) {
        return expr(expr, false);
    }

    /** inLambda is true inside the lambdas generated for `?:`, `?.` and `attempt`. */
    private String expr(Expr expr, boolean inLambda) {
        return switch (expr) {
            case Expr.Literal l -> l.text();
            case Expr.Name n -> inLambda && analysis.mutableRefs().contains(n)
                    ? copies.getOrDefault(n.name(), n.name()) : n.name();
            case Expr.Member m -> chain(Chains.flatten(m), inLambda);
            case Expr.Call c when c.target() != null -> chain(Chains.flatten(c), inLambda);
            case Expr.Call c -> call(c, inLambda);
            case Expr.GenericNew g -> "new " + g.type() + "<" + g.typeArgs().stream().map(t -> type(t, true))
                    .collect(Collectors.joining(", ")) + ">(" + args(g.args(), inLambda) + ")";
            case Expr.MethodRef r -> expr(r.target(), inLambda) + "::" + r.name();
            case Expr.Unary u -> u.op() + expr(u.operand(), inLambda);
            case Expr.Binary b -> expr(b.left(), inLambda) + " " + b.op() + " " + expr(b.right(), inLambda);
            case Expr.Elvis e -> "Nulls.or(" + expr(e.left(), inLambda) + ", () -> " + expr(e.right(), true) + ")";
            case Expr.Paren p -> "(" + expr(p.inner(), inLambda) + ")";
            case Expr.Attempt a -> {
                String error = a.errorName() != null ? a.errorName() : "$ex" + ++exceptionCount;
                yield "Result.attempt(() -> " + expr(a.body(), true) + ", " + error + " -> " + expr(a.handler(), true) + ")";
            }
            case Expr.Lambda l -> lambda(l, inLambda);
            case Expr.Try t -> throw new IllegalStateException("try at " + t.pos() + " should have been rejected");
        };
    }

    private String call(Expr.Call c, boolean inLambda) {
        String args = args(c.args(), inLambda);
        if (c.name().equals("println") && !declaredFunctions.contains("println")) {
            return "System.out.println(" + args + ")";
        }
        if (c.name().equals("Ok") || c.name().equals("Err")) {
            return "new " + c.name() + "<>(" + args + ")";
        }
        if (Character.isUpperCase(c.name().charAt(0)) && !declaredFunctions.contains(c.name())) {
            return "new " + c.name() + "(" + args + ")";
        }
        return c.name() + "(" + args + ")";
    }

    /** Each `?.` wraps the rest of the chain, so the first null skips everything after it. */
    private String chain(Chains.Chain chain, boolean inLambda) {
        return chainFrom(expr(chain.base(), inLambda), chain.steps(), 0, inLambda);
    }

    private String chainFrom(String prefix, List<Chains.Step> steps, int index, boolean inLambda) {
        if (index == steps.size()) {
            return prefix;
        }
        Chains.Step step = steps.get(index);
        if (step.safe()) {
            String param = "$n" + ++nullCount;
            return "Nulls.map(" + prefix + ", " + param + " -> "
                    + chainFrom(param + stepText(step, true), steps, index + 1, true) + ")";
        }
        return chainFrom(prefix + stepText(step, inLambda), steps, index + 1, inLambda);
    }

    private String stepText(Chains.Step step, boolean inLambda) {
        return "." + step.name() + (step.args() == null ? "" : "(" + args(step.args(), inLambda) + ")");
    }

    private String args(List<Expr> args, boolean inLambda) {
        return args.stream().map(a -> expr(a, inLambda)).collect(Collectors.joining(", "));
    }

    private String lambda(Expr.Lambda lambda, boolean inLambda) {
        String params;
        if (analysis.implicitIt().contains(lambda)) {
            params = "it";
        } else if (lambda.params().size() == 1 && lambda.params().getFirst().type() == null) {
            params = lambda.params().getFirst().name();
        } else {
            params = "(" + lambda.params().stream()
                    .map(p -> p.type() == null ? p.name() : type(p.type()) + " " + p.name())
                    .collect(Collectors.joining(", ")) + ")";
        }
        List<Stmt> body = lambda.body();
        if (body.size() == 1 && body.getFirst() instanceof Stmt.ExprStmt single) {
            return params + " -> " + expr(single.expr(), inLambda);
        }
        Emitter outer = em;
        Map<String, String> outerCopies = copies;
        em = new Emitter();
        em.indent();
        body.forEach(this::statement);
        String block = em.embedded();
        em = outer;
        copies = outerCopies;
        return params + " -> {" + block + Emitter.continuation(lambda.end().line(), "}");
    }

    private String type(TypeRef type) {
        return type(type, false);
    }

    /** Primitive names become wrappers inside generics and when nullable. */
    private String type(TypeRef type, boolean generic) {
        String[] primitive = PRIMITIVES.get(type.name());
        String name;
        if (primitive != null) {
            name = generic || type.nullable() ? primitive[1] : primitive[0];
        } else {
            name = type.name();
        }
        if (!type.args().isEmpty()) {
            name += "<" + type.args().stream().map(t -> type(t, true)).collect(Collectors.joining(", ")) + ">";
        }
        if (!type.nullable()) {
            return name;
        }
        // A type-use annotation on a qualified name goes before the simple name: java.util.@Nullable List.
        int dot = type.name().lastIndexOf('.');
        return dot < 0 ? "@Nullable " + name : name.substring(0, dot + 1) + "@Nullable " + name.substring(dot + 1);
    }
}
