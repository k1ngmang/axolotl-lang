package org.axl.compiler.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.JavaTypes;
import org.axl.compiler.LineMap;
import org.axl.compiler.Options;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.check.Analysis;
import org.axl.compiler.check.Checker;

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
    // Java types of top-level values without a written type, by name.
    private final Map<String, String> valueTypes;
    // Values declared as locals of a probe method instead of fields, for JavaTypes.
    private final Set<String> probed;
    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private Emitter em = new Emitter();

    // Per function: counters of generated names and the copies of captured `var`s.
    private int tempCount;
    private int nullCount;
    private int exceptionCount;
    private int copyCount;
    private int listCount;
    private Map<String, String> copies = Map.of();

    private Generator(SourceFile file, Analysis analysis, Options options, Map<String, String> valueTypes, Set<String> probed) {
        this.file = file;
        this.analysis = analysis;
        this.options = options;
        this.valueTypes = valueTypes;
        this.probed = probed;
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

    public static GeneratedUnit generate(String sourcePath, SourceFile file, Analysis analysis, Options options,
                                         Map<String, String> valueTypes) {
        Generator generator = new Generator(file, analysis, options, valueTypes, Set.of());
        generator.emitFile();
        return new GeneratedUnit(sourcePath, file.packageName(), file.className(), generator.em.text(),
                new LineMap(sourcePath, generator.em.lineMap()));
    }

    /**
     * Java source in which the values in {@code probed} are `var` locals of the method
     * {@link JavaTypes#PROBE_METHOD}, so that a compiler can tell their types.
     */
    public static String probe(SourceFile file, Analysis analysis, Options options, Map<String, String> valueTypes,
                               Set<String> probed) {
        Generator generator = new Generator(file, analysis, options, valueTypes, probed);
        generator.emitFile();
        return generator.em.text();
    }

    /** The Java form of a written type. */
    public static String javaType(TypeRef type) {
        return type(type, false);
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
                case Decl.Value value when probed.contains(value.name()) -> { }
                case Decl.Value value -> emitValue(value);
                case Decl.Function fn -> emitFunction(fn);
            }
        }
        if (!probed.isEmpty()) {
            emitProbe();
        }
        em.dedent();
        em.line(0, "}");
    }

    private void resetCounters() {
        tempCount = 0;
        nullCount = 0;
        exceptionCount = 0;
        copyCount = 0;
        listCount = 0;
        copies = Map.of();
    }

    private void emitValue(Decl.Value value) {
        String type = value.type() != null ? type(value.type()) : valueTypes.get(value.name());
        em.line(value.pos().line(), visibility(value) + " static final " + type + " " + value.name()
                + " = " + expr(value.init()) + ";");
    }

    private void emitProbe() {
        resetCounters();
        em.line(0, "private static void " + JavaTypes.PROBE_METHOD + "() {");
        em.indent();
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value && probed.contains(value.name())) {
                em.line(value.pos().line(), "var " + value.name() + " = " + expr(value.init()) + ";");
            }
        }
        em.dedent();
        em.line(0, "}");
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
                String target = assignTarget(assign.pos().line(), assign.target(), Lifts.needed(assign.value()));
                String value = value(assign.pos().line(), assign.value());
                em.line(assign.pos().line(), target + " " + assign.op() + " " + value + ";");
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
                } else if (Lifts.needed(e)) {
                    withCopies(line, e, () -> liftedStatement(line, lifted(line, e)));
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
        boolean prelude = !captured.isEmpty() || Lifts.needed(ifStmt.condition());
        if (!prefix.isEmpty() && prelude) {
            em.line(line, prefix + "{");
            em.indent();
            statement(ifStmt);
            em.dedent();
            return;
        }
        withCopies(line, ifStmt.condition(), () ->
                em.line(line, prefix + "if (" + lifted(line, ifStmt.condition()).code() + ") {"));
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
        if (Captures.in(loop.condition(), analysis).isEmpty() && !Lifts.needed(loop.condition())) {
            em.line(line, "while (" + expr(loop.condition()) + ") {");
            block(loop.body());
        } else {
            // Copies and lifted `try`s must run on every iteration, before the condition.
            em.line(line, "while (true) {");
            em.indent();
            withCopies(line, loop.condition(), () ->
                    em.line(line, "if (!(" + lifted(line, loop.condition()).code() + ")) break;"));
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

    /** What is left of a lifted statement may not be a Java statement: `$p1`, `$t1 + 1`, `(f())`. */
    private void liftedStatement(int line, Lifted rest) {
        String code = rest.code();
        while (code.startsWith("(") && code.endsWith(")") && closingParen(code) == code.length() - 1) {
            code = code.substring(1, code.length() - 1);
        }
        if (code.matches("[\\w$]+")) {
            return;
        }
        boolean call = code.endsWith(")") && !code.startsWith("(");
        em.line(line, call ? code + ";" : "final var $t" + ++tempCount + " = " + code + ";");
    }

    private static int closingParen(String code) {
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The value of a statement; with inlineElvis a root `?:` becomes a temporary and a conditional. */
    private String value(int line, Expr expr) {
        if (Lifts.needed(expr)) {
            return lifted(line, expr).code();
        }
        if (!options.inlineElvis() || !(expr instanceof Expr.Elvis elvis)) {
            return expr(expr);
        }
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + expr(elvis.left()) + ";");
        // Objects.nonNull also compiles when the left side is a primitive.
        return "java.util.Objects.nonNull(" + temp + ") ? " + temp + " : " + expr(elvis.right());
    }

    /**
     * A number on the right is passed as is, so that Java widens it to the type on the left. Not after
     * `?.`, whose Nulls.map result type Java could then not infer.
     */
    private String elvis(Expr.Elvis elvis, String left) {
        Expr right = elvis.right();
        boolean number = !Chains.hasSafeStep(elvis.left())
                && (right instanceof Expr.Literal l && Character.isDigit(l.text().charAt(0))
                || right instanceof Expr.Unary u && u.op().equals("-") && u.operand() instanceof Expr.Literal);
        return number ? "Nulls.or(" + left + ", " + expr(right) + ")" : "Nulls.or(" + left + ", () -> " + expr(right, true) + ")";
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
        String inner = lifted(line, t.inner()).code();
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + inner + ";");
        em.line(line, "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
        return "Result.unwrap(" + temp + ")";
    }

    /** `a?.b()` as a statement: no lambda, so void methods work too. */
    private void safeChainStatement(int line, Chains.Chain chain) {
        List<Chains.Step> steps = chain.steps();
        int firstSafe = firstSafe(steps);
        String current = liftedPrefix(line, chain, firstSafe);
        int opened = 0;
        for (int i = firstSafe; i < steps.size(); i++) {
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
            case Expr.Call c -> call(c, args(c.args(), inLambda));
            case Expr.GenericNew g -> genericNew(g, args(g.args(), inLambda));
            case Expr.MethodRef r -> expr(r.target(), inLambda) + "::" + r.name();
            case Expr.NotNull n -> "java.util.Objects.requireNonNull(" + expr(n.target(), inLambda) + ")";
            case Expr.Unary u -> unary(u.op(), expr(u.operand(), inLambda));
            case Expr.Binary b -> expr(b.left(), inLambda) + " " + b.op() + " " + expr(b.right(), inLambda);
            case Expr.Elvis e -> elvis(e, expr(e.left(), inLambda));
            // `(a ?: b)` needs parentheses in Axolotl, but its expansion is a method call in Java.
            case Expr.Paren p when becomesCall(p.inner()) -> expr(p.inner(), inLambda);
            case Expr.Paren p -> "(" + expr(p.inner(), inLambda) + ")";
            case Expr.Attempt a -> {
                String error = a.errorName() != null ? a.errorName() : "$ex" + ++exceptionCount;
                yield "Result.attempt(() -> " + expr(a.body(), true) + ", " + error + " -> " + expr(a.handler(), true) + ")";
            }
            case Expr.Lambda l -> lambda(l, inLambda);
            case Expr.Pipe p -> pipe(expr(p.source(), inLambda), p.source(), p.stages(), a -> expr(a, inLambda), inLambda);
            case Expr.Try t -> throw new IllegalStateException("try at " + t.pos() + " should have been lifted");
        };
    }

    private static boolean becomesCall(Expr expr) {
        return expr instanceof Expr.Elvis || expr instanceof Expr.Attempt || Chains.hasSafeStep(expr);
    }

    private String genericNew(Expr.GenericNew g, String args) {
        return "new " + g.type() + "<" + g.typeArgs().stream().map(t -> type(t, true)).collect(Collectors.joining(", "))
                + ">(" + args + ")";
    }

    private String call(Expr.Call c, String args) {
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

    /** Code of an expression whose `try`s and `mapResult`s were moved into statements before it. */
    private record Lifted(String code, boolean pure) {}

    /**
     * Emits the statements a `try` or `mapResult` needs and returns what is left of the expression.
     * Operands to the left of a lifted part are evaluated into temporaries first, so the order of
     * evaluation stays left to right.
     */
    private Lifted lifted(int line, Expr e) {
        if (!Lifts.needed(e)) {
            return new Lifted(expr(e), isPure(e));
        }
        return switch (e) {
            case Expr.Try t -> new Lifted(unwrapTry(line, t), true);
            case Expr.Binary b when (b.op().equals("&&") || b.op().equals("||")) && Lifts.needed(b.right()) ->
                    shortCircuit(line, b);
            case Expr.Binary b -> {
                List<Lifted> ops = operands(line, List.of(b.left(), b.right()));
                yield new Lifted(ops.get(0).code() + " " + b.op() + " " + ops.get(1).code(), false);
            }
            case Expr.Elvis el when Lifts.needed(el.right()) -> elvisChain(line, el);
            case Expr.Elvis el -> new Lifted(elvis(el, lifted(line, el.left()).code()), false);
            case Expr.Unary u -> new Lifted(unary(u.op(), lifted(line, u.operand()).code()), false);
            case Expr.Paren p -> {
                Lifted inner = lifted(line, p.inner());
                yield new Lifted(becomesCall(p.inner()) ? inner.code() : "(" + inner.code() + ")", inner.pure());
            }
            case Expr.Call c when c.target() == null -> new Lifted(call(c, codes(operands(line, c.args()))), false);
            case Expr.Member m -> chainLifted(line, Chains.flatten(m));
            case Expr.Call c -> chainLifted(line, Chains.flatten(c));
            case Expr.GenericNew g -> new Lifted(genericNew(g, codes(operands(line, g.args()))), false);
            case Expr.MethodRef r -> new Lifted(lifted(line, r.target()).code() + "::" + r.name(), false);
            case Expr.NotNull n -> new Lifted("java.util.Objects.requireNonNull(" + lifted(line, n.target()).code() + ")", false);
            case Expr.Pipe p -> pipeLifted(line, p);
            default -> throw new IllegalStateException("nothing to lift in " + e);
        };
    }

    /** Java evaluates the object of `a.f = v` before `v`, so before a `try` in `v`. */
    private String assignTarget(int line, Expr target, boolean valueLifts) {
        if (!(target instanceof Expr.Member m) || !valueLifts && !Lifts.needed(target)) {
            return expr(target);
        }
        Lifted receiver = lifted(line, m.target());
        String code = receiver.code();
        if (valueLifts && !receiver.pure()) {
            code = "$t" + ++tempCount;
            em.line(line, "final var " + code + " = " + receiver.code() + ";");
        }
        return code + "." + m.name();
    }

    /** Operands in evaluation order; those left of the last lifted one are kept in temporaries. */
    private List<Lifted> operands(int line, List<Expr> exprs) {
        int last = -1;
        for (int i = 0; i < exprs.size(); i++) {
            if (Lifts.hoisted(exprs.get(i))) {
                last = i;
            }
        }
        List<Lifted> result = new ArrayList<>();
        for (int i = 0; i < exprs.size(); i++) {
            Lifted operand = lifted(line, exprs.get(i));
            if (i < last && !operand.pure() || Chains.hasSafeStep(exprs.get(i))) {
                String temp = "$t" + ++tempCount;
                em.line(line, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            }
            result.add(operand);
        }
        return result;
    }

    private static String codes(List<Lifted> lifted) {
        return lifted.stream().map(Lifted::code).collect(Collectors.joining(", "));
    }

    /** Values that no call can change: literals, lambdas and the file's own names. */
    private boolean isPure(Expr e) {
        return switch (e) {
            case Expr.Literal l -> true;
            case Expr.Name n -> analysis.stableNames().contains(n) || analysis.staticNames().contains(n);
            case Expr.Lambda l -> true;
            case Expr.MethodRef r -> true;
            case Expr.Paren p -> isPure(p.inner());
            // Kept inline so that Java can still infer their type arguments from the target.
            case Expr.Call c when c.target() == null && (c.name().equals("Ok") || c.name().equals("Err")) ->
                    c.args().stream().allMatch(this::isPure);
            default -> false;
        };
    }

    /** `a && try b` evaluates `b` and its `try` only when `a` holds. */
    private Lifted shortCircuit(int line, Expr.Binary b) {
        String left = lifted(line, b.left()).code();
        String temp = "$t" + ++tempCount;
        em.line(line, "boolean " + temp + " = " + left + ";");
        em.line(line, "if (" + (b.op().equals("&&") ? temp : "!" + temp) + ") {");
        em.indent();
        em.line(line, temp + " = " + lifted(line, b.right()).code() + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }

    /** `a ?: try b` evaluates `b` and its `try` only when `a` is null. */
    private Lifted elvisChain(int line, Expr.Elvis elvis) {
        String left = lifted(line, elvis.left()).code();
        String temp = "$t" + ++tempCount;
        em.line(line, "var " + temp + " = " + left + ";");
        em.line(line, "if (" + temp + " == null) {");
        em.indent();
        em.line(line, temp + " = " + lifted(line, elvis.right()).code() + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }

    /** The receiver and the arguments before the first `?.` are evaluated eagerly; the rest only for non-null. */
    private Lifted chainLifted(int line, Chains.Chain chain) {
        int firstSafe = firstSafe(chain.steps());
        return new Lifted(chainFrom(liftedPrefix(line, chain, firstSafe), chain.steps(), firstSafe, false), false);
    }

    private static int firstSafe(List<Chains.Step> steps) {
        int index = 0;
        while (index < steps.size() && !steps.get(index).safe()) {
            index++;
        }
        return index;
    }

    /** The chain up to its first `?.`, with the statements its `try`s need. */
    private String liftedPrefix(int line, Chains.Chain chain, int firstSafe) {
        List<Chains.Step> steps = chain.steps();
        Lifted base = lifted(line, chain.base());
        String current = base.code();
        // fixed: no evaluation can change what `current` denotes; a type or package name is fixed too.
        boolean fixed = base.pure();
        boolean typeOrPackage = chain.base() instanceof Expr.Name n && analysis.staticNames().contains(n);
        String segment = typeOrPackage ? ((Expr.Name) chain.base()).name() : "";
        for (int i = 0; i < firstSafe; i++) {
            Chains.Step step = steps.get(i);
            if (step.args() == null) {
                // java.util.List is a path to a class; System.out is a value read from one.
                typeOrPackage = typeOrPackage && Character.isLowerCase(segment.charAt(0));
                fixed = typeOrPackage;
                segment = step.name();
                current += "." + step.name();
                continue;
            }
            String args;
            if (step.args().stream().anyMatch(Lifts::hoisted)) {
                // The receiver is evaluated before the arguments, so before their `try`.
                if (!fixed) {
                    String temp = "$t" + ++tempCount;
                    em.line(line, "final var " + temp + " = " + current + ";");
                    current = temp;
                }
                args = codes(operands(line, step.args()));
            } else {
                args = step.args().stream().map(this::expr).collect(Collectors.joining(", "));
            }
            current += "." + step.name() + "(" + args + ")";
            fixed = false;
            typeOrPackage = false;
        }
        return current;
    }

    /** `xs |> ... |> mapResult { f(it) } |> ...`: a loop that returns the first `Err` from the function. */
    private Lifted pipeLifted(int line, Expr.Pipe pipe) {
        List<Expr.Stage> stages = pipe.stages();
        int mapResult = 0;
        while (mapResult < stages.size() && !stages.get(mapResult).name().equals("mapResult")) {
            mapResult++;
        }
        List<Expr> eager = new ArrayList<>(List.of(pipe.source()));
        for (int i = 0; i < Math.min(mapResult, stages.size()); i++) {
            eager.addAll(stages.get(i).args());
        }
        List<Lifted> ops = operands(line, eager);
        Map<Expr, String> argCodes = new java.util.IdentityHashMap<>();
        for (int i = 1; i < eager.size(); i++) {
            argCodes.put(eager.get(i), ops.get(i).code());
        }
        if (mapResult == stages.size()) {
            return new Lifted(pipe(ops.getFirst().code(), pipe.source(), stages, argCodes::get, false), false);
        }
        String items = mapResult == 0 ? parenthesized(ops.getFirst().code(), pipe.source())
                : pipe(ops.getFirst().code(), pipe.source(), stages.subList(0, mapResult), argCodes::get, false);
        Expr.Stage stage = stages.get(mapResult);
        Expr.Lambda lambda = stage.lambda();
        String param = lambda.params().isEmpty() ? "it" : lambda.params().getFirst().name();
        Expr body = ((Stmt.ExprStmt) lambda.body().getFirst()).expr();
        String list = "$p" + ++listCount;
        String element = type(analysis.mapResultTypes().get(stage), true);
        em.line(line, "final java.util.List<" + element + "> " + list + " = new java.util.ArrayList<>();");
        em.line(line, "for (final var " + param + " : " + items + ") {");
        em.indent();
        String temp = "$t" + ++tempCount;
        em.line(lambda.pos().line(), "final var " + temp + " = " + expr(body) + ";");
        em.line(lambda.pos().line(), "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
        em.line(lambda.pos().line(), list + ".add(Result.<" + element + ">unwrap(" + temp + "));");
        em.dedent();
        em.line(line, "}");
        List<Expr.Stage> rest = stages.subList(mapResult + 1, stages.size());
        if (rest.isEmpty()) {
            return new Lifted(list, true);
        }
        return pipeLifted(line, new Expr.Pipe(pipe.pos(), new Expr.Name(pipe.pos(), list), rest));
    }

    /** Pipe stages as a Java stream; without a terminal stage the result is a List. */
    private String pipe(String source, Expr sourceExpr, List<Expr.Stage> stages,
                        java.util.function.Function<Expr, String> arg, boolean inLambda) {
        StringBuilder sb = new StringBuilder(parenthesized(source, sourceExpr)).append(".stream()");
        boolean terminal = false;
        for (Expr.Stage stage : stages) {
            String lambda = stage.lambda() == null ? "" : lambda(stage.lambda(), inLambda);
            switch (stage.name()) {
                case "limit", "skip" -> sb.append('.').append(stage.name()).append('(').append(arg.apply(stage.args().getFirst())).append(')');
                case "distinct" -> sb.append(".distinct()");
                case "count" -> sb.append(".count()");
                case "first" -> sb.append(".findFirst().orElse(null)");
                case "map" -> sb.append(".map(Nulls.nonNull(").append(lambda).append("))");
                default -> sb.append('.').append(stage.name()).append('(').append(lambda).append(')');
            }
            terminal |= Checker.TERMINAL_STAGES.contains(stage.name());
        }
        return terminal ? sb.toString() : sb.append(".toList()").toString();
    }

    private static String parenthesized(String code, Expr source) {
        boolean simple = source instanceof Expr.Name || source instanceof Expr.Call || source instanceof Expr.Member
                || source instanceof Expr.Paren || source instanceof Expr.GenericNew || source instanceof Expr.Literal;
        return simple ? code : "(" + code + ")";
    }

    /** `- -x` must not become Java's `--x`. */
    private static String unary(String op, String operand) {
        boolean merges = !operand.isEmpty() && "+-".indexOf(operand.charAt(0)) >= 0 && !op.equals("!");
        return merges ? op + " " + operand : op + operand;
    }

    private static String type(TypeRef type) {
        return type(type, false);
    }

    /** Primitive names become wrappers inside generics and when nullable. */
    private static String type(TypeRef type, boolean generic) {
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
