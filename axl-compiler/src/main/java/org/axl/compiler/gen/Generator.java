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
import org.axl.compiler.check.TypeInference;

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
    // Inside a block lambda nested in a lambda of `?:`, `?.` or `attempt`, whose captured vars are copies.
    private boolean inCopyingLambda;
    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private Emitter em = new Emitter();

    // Per function: counters of generated names and the copies of captured `var`s.
    private int tempCount;
    private int nullCount;
    private int exceptionCount;
    private int copyCount;
    private int listCount;
    private Map<String, String> copies = Map.of();
    // The Java result type of the function whose body is being emitted; null inside a lambda.
    private String returnType;

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
        this.returnType = fn.returnType() == null ? null : nullableType(fn.returnType());
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
        return fn.name().equals("main") && fn.params().isEmpty() && fn.returnType() == null && !fn.isPrivate();
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
                    String value = init instanceof Expr.Try t ? unwrapTry(line, t)
                            : value(line, init, local.type() == null ? null : nullableType(local.type()));
                    String modifiers = local.mutable() ? "" : "final ";
                    String type = local.type() == null ? "var" : type(local.type());
                    em.line(line, modifiers + type + " " + local.name() + " = " + value + ";");
                });
            }
            case Stmt.Assign assign -> withCopies(assign.pos().line(), assign.value(), () -> {
                int line = assign.pos().line();
                boolean valueLifts = Lifts.needed(assign.value());
                String target = assignTarget(line, assign.target(), valueLifts);
                String old = null;
                if (valueLifts && !assign.op().equals("=") && !isLocal(assign.target())) {
                    // `a.f += try g()` reads `a.f` before `g` runs, which may change it.
                    old = "$t" + ++tempCount;
                    em.line(line, "final var " + old + " = " + target + ";");
                }
                String value = value(line, assign.value());
                if (old != null) {
                    em.line(line, target + " = " + old + ";");
                }
                em.line(line, target + " " + assign.op() + " " + value + ";");
            });
            case Stmt.Return ret -> {
                if (ret.value() == null) {
                    em.line(ret.pos().line(), "return;");
                } else {
                    withCopies(ret.pos().line(), ret.value(), () ->
                            em.line(ret.pos().line(), "return " + value(ret.pos().line(), ret.value(), returnType) + ";"));
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
                    withCopies(line, e, () -> liftedStatement(line, e, lifted(line, e)));
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
        if (prefix.isEmpty() && captured.isEmpty() && ifStmt.elseBranch() == null
                && ifStmt.condition() instanceof Expr.Binary b && b.op().equals("&&") && Lifts.needed(b.right())) {
            // Nested ifs keep what the left side checks, such as `s != null`, known in the right side and the body.
            em.line(line, "if (" + lifted(line, b.left()).code() + ") {");
            em.indent();
            Stmt.If inner = new Stmt.If(ifStmt.pos(), b.right(), ifStmt.then(), null);
            emitIf(inner, "");
            em.line(blockEnd(inner), "}");
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
                    em.line(line, "if (" + negated(lifted(line, loop.condition()).code()) + ") break;"));
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
    private void liftedStatement(int line, Expr statement, Lifted rest) {
        String code = rest.code();
        while (code.startsWith("(") && code.endsWith(")") && closingParen(code) == code.length() - 1) {
            code = code.substring(1, code.length() - 1);
        }
        if (code.matches("[\\w$]+")) {
            return;
        }
        Expr e = statement;
        while (e instanceof Expr.Paren p) {
            e = p.inner();
        }
        // A call stays a statement: it may return nothing, which a temporary cannot hold.
        boolean call = (e instanceof Expr.Call || e instanceof Expr.GenericNew || e instanceof Expr.Try) && code.endsWith(")");
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

    private String value(int line, Expr expr) {
        return value(line, expr, null);
    }

    /**
     * The value of a statement; with inlineElvis a root `?:` becomes a temporary and a conditional.
     *
     * @param javaType the type the value is stored as, if the code says; a root `?:` whose right side
     *                 needs statements gets its temporary declared with it
     */
    private String value(int line, Expr expr, String javaType) {
        if (expr instanceof Expr.Elvis elvis && Lifts.needed(elvis.right())) {
            return elvisChain(line, elvis, javaType).code();
        }
        if (Lifts.needed(expr)) {
            return lifted(line, expr).code();
        }
        if (!options.inlineElvis() || !(expr instanceof Expr.Elvis elvis) || mixesBoxedNumbers(elvis)) {
            return expr(expr);
        }
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + expr(elvis.left()) + ";");
        // Objects.nonNull also compiles when the left side is a primitive.
        return "java.util.Objects.nonNull(" + temp + ") ? " + temp + " : " + expr(elvis.right());
    }

    /**
     * `Int? ?: Long?`: Java unboxes both sides of such a conditional, and fails on a null right side.
     * Nulls.or keeps them boxed.
     */
    private boolean mixesBoxedNumbers(Expr.Elvis elvis) {
        TypeRef left = TypeInference.typeOf(file, elvis.left());
        TypeRef right = TypeInference.typeOf(file, elvis.right());
        return left != null && right != null && right.nullable() && !left.name().equals(right.name())
                && PRIMITIVES.containsKey(left.name()) && PRIMITIVES.containsKey(right.name());
    }

    /**
     * A number on the right is passed as is, so that Java widens it to the type on the left. After
     * `?.` only when the left side is a temporary: Java could not infer the type of its Nulls.map.
     */
    private String elvis(Expr.Elvis elvis, String left) {
        Expr right = elvis.right();
        boolean number = Lifts.isNumber(right) && (!Chains.hasSafeStep(elvis.left()) || left.matches("\\$t\\d+"));
        String fallback = number ? expr(right) : "() -> " + expr(right, true);
        return joined("Nulls.or(" + left, ", ", fallback, startLine(elvis.left()), startLine(right)) + ")";
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
            if (saved.containsKey(name)) {
                // Already a copy made around an enclosing lambda.
                continue;
            }
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
            case Expr.Literal l -> literal(l.text());
            case Expr.Name n -> (inLambda || inCopyingLambda) && analysis.mutableRefs().contains(n)
                    ? copies.getOrDefault(n.name(), n.name()) : n.name();
            case Expr.Member m -> chain(Chains.flatten(m), inLambda);
            case Expr.Call c when c.target() != null -> chain(Chains.flatten(c), inLambda);
            case Expr.Call c -> call(c, args(c.args(), inLambda, c.pos().line()));
            case Expr.GenericNew g -> genericNew(g, args(g.args(), inLambda, g.pos().line()));
            case Expr.MethodRef r -> expr(r.target(), inLambda) + "::" + r.name();
            case Expr.NotNull n -> "java.util.Objects.requireNonNull(" + expr(n.target(), inLambda) + ")";
            case Expr.Unary u -> unary(u.op(), expr(u.operand(), inLambda));
            case Expr.Binary b -> joined(expr(b.left(), inLambda), " " + b.op() + " ", expr(b.right(), inLambda),
                    startLine(b.left()), startLine(b.right()));
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
        return chainFrom(expr(chain.base(), inLambda), chain.steps(), 0, inLambda, startLine(chain.base()));
    }

    private String chainFrom(String prefix, List<Chains.Step> steps, int index, boolean inLambda, int baseLine) {
        if (index == steps.size()) {
            return prefix;
        }
        Chains.Step step = steps.get(index);
        if (step.safe()) {
            String param = "$n" + ++nullCount;
            return "Nulls.map(" + prefix + ", " + param + " -> "
                    + chainFrom(param + stepOnLine(step, true, steps, index, baseLine), steps, index + 1, true, baseLine) + ")";
        }
        return chainFrom(prefix + stepOnLine(step, inLambda, steps, index, baseLine), steps, index + 1, inLambda, baseLine);
    }

    /** `.name(args)`, on a new Java line when the step starts a new .axl line. */
    private String stepOnLine(Chains.Step step, boolean inLambda, List<Chains.Step> steps, int index, int baseLine) {
        String text = stepText(step, inLambda);
        int previous = index == 0 ? baseLine : steps.get(index - 1).line();
        return step.line() > previous ? Emitter.continued(step.line(), text) : text;
    }

    private String stepText(Chains.Step step, boolean inLambda) {
        return "." + step.name() + (step.args() == null ? "" : "(" + args(step.args(), inLambda, step.line()) + ")");
    }

    private String args(List<Expr> args, boolean inLambda, int line) {
        List<String> codes = args.stream().map(a -> argument(a, inLambda)).toList();
        return joinedArgs(args, codes, line);
    }

    /** Where a `?.` argument cannot be moved into a temporary, Nulls.typed gives it its type. */
    private String argument(Expr arg, boolean inLambda) {
        return switch (arg) {
            case Expr.Paren p -> "(" + argument(p.inner(), inLambda) + ")";
            case Expr.NotNull n -> "java.util.Objects.requireNonNull(" + argument(n.target(), inLambda) + ")";
            default -> Chains.hasSafeStep(arg) ? "Nulls.typed(" + expr(arg, inLambda) + ").value()" : expr(arg, inLambda);
        };
    }

    /** Arguments separated by commas; one that starts a new .axl line starts a new Java line. */
    private static String joinedArgs(List<Expr> args, List<String> codes, int line) {
        StringBuilder sb = new StringBuilder();
        int previous = line;
        for (int i = 0; i < codes.size(); i++) {
            int argLine = startLine(args.get(i));
            String code = codes.get(i);
            if (argLine > previous) {
                sb.append(i > 0 ? "," : "").append(Emitter.continued(argLine, code));
            } else {
                sb.append(i > 0 ? ", " : "").append(code);
            }
            previous = argLine;
        }
        return sb.toString();
    }

    /** head, separator and tail, with the tail on a new Java line when it starts a new .axl line. */
    private static String joined(String head, String separator, String tail, int headLine, int tailLine) {
        return tailLine > headLine ? head + separator.stripTrailing() + Emitter.continued(tailLine, tail) : head + separator + tail;
    }

    /** The .axl line an expression starts on; a chain starts with its base. */
    private static int startLine(Expr e) {
        return switch (e) {
            case Expr.Member m -> startLine(m.target());
            case Expr.Call c when c.target() != null -> startLine(c.target());
            case Expr.Binary b -> startLine(b.left());
            case Expr.Elvis el -> startLine(el.left());
            case Expr.Pipe p -> startLine(p.source());
            case Expr.MethodRef r -> startLine(r.target());
            case Expr.NotNull n -> startLine(n.target());
            default -> e.pos().line();
        };
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
        boolean outerCopying = inCopyingLambda;
        String outerReturnType = returnType;
        returnType = null;
        inCopyingLambda = inLambda || inCopyingLambda;
        em = new Emitter();
        em.indent();
        body.forEach(this::statement);
        String block = em.embedded();
        em = outer;
        copies = outerCopies;
        inCopyingLambda = outerCopying;
        returnType = outerReturnType;
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
                yield new Lifted(joined(ops.get(0).code(), " " + b.op() + " ", ops.get(1).code(),
                        startLine(b.left()), startLine(b.right())), false);
            }
            case Expr.Elvis el when Lifts.needed(el.right()) -> elvisChain(line, el, null);
            case Expr.Elvis el when Lifts.numberAfterSafeCall(el) && !Lifts.needed(el.left()) -> {
                String temp = "$t" + ++tempCount;
                em.line(line, "final var " + temp + " = " + expr(el.left()) + ";");
                yield new Lifted(elvis(el, temp), false);
            }
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

    /** A local, which no call can change; top-level values cannot be shadowed, so a name of one is not. */
    private boolean isLocal(Expr target) {
        return target instanceof Expr.Name n && file.decls().stream()
                .noneMatch(d -> d instanceof Decl.Value v && v.name().equals(n.name()));
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
            Expr e = exprs.get(i);
            Lifted operand = i < last && takesTypeFromTarget(e) ? withArgumentsFirst(line, e) : lifted(line, e);
            if (i < last && !operand.pure() || Chains.hasSafeStep(e)) {
                String temp = "$t" + ++tempCount;
                em.line(line, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            }
            result.add(operand);
        }
        return result;
    }

    /**
     * `Ok(x)`, `ArrayList<>()` and `List.of(...)` get their type arguments from where they are passed;
     * in a temporary they would get `Object`.
     */
    private boolean takesTypeFromTarget(Expr e) {
        return switch (e) {
            case Expr.Call c when c.target() == null -> c.name().equals("Ok") || c.name().equals("Err");
            case Expr.Call c -> c.target() instanceof Expr.Name n && analysis.staticNames().contains(n)
                    && List.of("of", "copyOf", "emptyList", "emptySet", "emptyMap").contains(c.name());
            case Expr.GenericNew g -> g.typeArgs().isEmpty();
            default -> false;
        };
    }

    /** Evaluates the arguments into temporaries and keeps the call itself inline, where Java infers its type. */
    private Lifted withArgumentsFirst(int line, Expr e) {
        List<Expr> args = switch (e) {
            case Expr.Call c -> c.args();
            case Expr.GenericNew g -> g.args();
            default -> throw new IllegalStateException();
        };
        List<String> codes = new ArrayList<>();
        for (Expr arg : args) {
            Lifted operand = lifted(line, arg);
            if (!operand.pure()) {
                String temp = "$t" + ++tempCount;
                em.line(line, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            }
            codes.add(operand.code());
        }
        String joined = String.join(", ", codes);
        return new Lifted(switch (e) {
            case Expr.Call c when c.target() == null -> call(c, joined);
            case Expr.Call c -> expr(c.target()) + "." + c.name() + "(" + joined + ")";
            case Expr.GenericNew g -> genericNew(g, joined);
            default -> throw new IllegalStateException();
        }, true);
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
        boolean and = b.op().equals("&&");
        // The left side stays in the condition, so that a null check in it covers the right side.
        em.line(line, "boolean " + temp + " = " + !and + ";");
        em.line(line, "if (" + (and ? left : negated(left)) + ") {");
        em.indent();
        em.line(line, temp + " = " + lifted(line, b.right()).code() + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }

    /** `a ?: try b` evaluates `b` and its `try` only when `a` is null. */
    private Lifted elvisChain(int line, Expr.Elvis elvis, String javaType) {
        String left = lifted(line, elvis.left()).code();
        String temp = "$t" + ++tempCount;
        em.line(line, (javaType == null ? "var" : javaType) + " " + temp + " = " + left + ";");
        // Objects.isNull also compiles when the left side is a primitive.
        em.line(line, "if (java.util.Objects.isNull(" + temp + ")) {");
        em.indent();
        em.line(line, temp + " = " + lifted(line, elvis.right()).code() + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }

    /** The receiver and the arguments before the first `?.` are evaluated eagerly; the rest only for non-null. */
    private Lifted chainLifted(int line, Chains.Chain chain) {
        int firstSafe = firstSafe(chain.steps());
        return new Lifted(chainFrom(liftedPrefix(line, chain, firstSafe), chain.steps(), firstSafe, false,
                startLine(chain.base())), false);
    }

    private static int firstSafe(List<Chains.Step> steps) {
        int index = 0;
        while (index < steps.size() && !steps.get(index).safe()) {
            index++;
        }
        return index;
    }

    private static boolean isTypeName(String name) {
        return Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase(java.util.Locale.ROOT));
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
                // java.util.List and Thread.State are paths to a class; System.out and Color.RED are values.
                typeOrPackage = typeOrPackage && (Character.isLowerCase(segment.charAt(0)) || isTypeName(step.name()));
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
        int previousLine = startLine(sourceExpr);
        for (Expr.Stage stage : stages) {
            String lambda = stage.lambda() == null ? "" : lambda(stage.lambda(), inLambda);
            if (stage.pos().line() > previousLine) {
                sb.append(Emitter.continued(stage.pos().line(), ""));
                previousLine = stage.pos().line();
            }
            switch (stage.name()) {
                case "limit", "skip" -> sb.append('.').append(stage.name()).append('(').append(arg.apply(stage.args().getFirst())).append(')');
                case "distinct" -> sb.append(".distinct()");
                case "count" -> sb.append(".count()");
                case "first" -> sb.insert(0, "Nulls.first(").append(".iterator())");
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

    /**
     * javac turns unicode escapes into their characters before it reads anything else, so an escaped
     * quote would end the string there. In a string or character literal it becomes an ordinary escape.
     */
    private static String literal(String text) {
        if (!text.contains("\\u") || !(text.startsWith("\"") || text.startsWith("'"))) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 5 < text.length() && text.charAt(i + 1) == 'u') {
                int end = i + 2;
                while (end < text.length() && text.charAt(end) == 'u') {
                    end++;
                }
                char decoded = (char) Integer.parseInt(text.substring(end, end + 4), 16);
                sb.append(escaped(decoded));
                i = end + 3;
            } else if (c == '\\') {
                sb.append(c).append(text.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escaped(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\'' -> "\\'";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> c < 0x20 ? String.format("\\%03o", (int) c) : c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }

    private static String negated(String condition) {
        return condition.matches("[\\w$]+") ? "!" + condition : "!(" + condition + ")";
    }

    /** `- -x` must not become Java's `--x`. */
    private static String unary(String op, String operand) {
        boolean merges = !operand.isEmpty() && "+-".indexOf(operand.charAt(0)) >= 0 && !op.equals("!");
        return merges ? op + " " + operand : op + operand;
    }

    /** The type as a temporary that may hold null before it gets its value. */
    private static String nullableType(TypeRef type) {
        return type(new TypeRef(type.pos(), type.name(), type.args(), true), true);
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
