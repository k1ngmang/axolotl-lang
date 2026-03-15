package org.axl.compiler.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.check.Analysis;
import org.axl.compiler.check.Checker;
import org.axl.compiler.check.TypeInference;
import org.axl.compiler.check.Whens;

/** Generates Java following the expansion rules of the specification. Expects a file without errors. */
public final class Generator {

    private static final Map<String, String[]> PRIMITIVES = Map.of(
            "Int", new String[] {"int", "Integer"},
            "Long", new String[] {"long", "Long"},
            "Boolean", new String[] {"boolean", "Boolean"},
            "Double", new String[] {"double", "Double"});

    // Types whose values Java unboxes in a conditional, as Axolotl or Java names them.
    private static final Set<String> NUMBERS = Set.of("Int", "Long", "Double", "Float", "Short", "Byte",
            "Integer", "Character");

    // `s ?: 0` with `s: Short?`: the number is converted, so that both sides have the type on the left.
    private static final Map<String, String> NARROW_NUMBERS = Map.of("Short", "short", "Byte", "byte",
            "Character", "char", "Float", "float");

    private static final Set<String> TARGET_TYPED_FACTORIES = Set.of("of", "copyOf", "ofEntries", "entry", "empty",
            "emptyList", "emptySet", "emptyMap", "naturalOrder", "reverseOrder", "nullsFirst", "nullsLast", "identity");

    private final SourceFile file;
    private final Analysis analysis;
    private final Options options;
    // Java types of top-level values without a written type, by name.
    private final Map<String, String> valueTypes;
    // Values declared as locals of a probe method instead of fields, for JavaTypes.
    private final Set<String> probed;
    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private Emitter em = new Emitter();

    // Names known as another type here, with the pattern variable that holds them: `x is Circle` makes
    // `x` read `$s1` where the check holds, as Java scopes pattern variables.
    private Map<String, String> smart = new HashMap<>();
    private final Map<Expr.Is, String> patternVariables = new IdentityHashMap<>();
    private int patternCount;

    // Per function: counters of generated names and the copies of captured `var`s.
    private int tempCount;
    private int nullCount;
    private int exceptionCount;
    private int copyCount;
    private int listCount;
    private Map<String, String> copies = Map.of();
    // The Java result type of the function whose body is being emitted; null inside a lambda.
    private String returnType;
    // Types of the parameters and locals of the function so far; null where the code does not say.
    private final Map<String, TypeRef> localTypes = new HashMap<>();

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
                new LineMap(sourcePath, generator.em.lineNumbers().stream().mapToInt(Integer::intValue).toArray()));
    }

    /**
     * Java source in which the values in {@code probed} are `var` locals of the method
     * {@link JavaTypes#PROBE_METHOD}, so that a compiler can tell their types; its line map tells
     * where the compiler's errors are.
     */
    public static GeneratedUnit probe(SourceFile file, Analysis analysis, Options options, Map<String, String> valueTypes,
                                      Set<String> probed) {
        Generator generator = new Generator(file, analysis, options, valueTypes, probed);
        generator.emitFile();
        return new GeneratedUnit(file.className() + ".axl", file.packageName(), file.className(), generator.em.text(),
                new LineMap(file.className() + ".axl", generator.em.lineNumbers().stream().mapToInt(Integer::intValue).toArray()));
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
        Decl fileType = fileType();
        if (fileType == null) {
            em.line(0, "@NullMarked public final class " + file.className() + " {");
            em.indent();
            em.line(0, "private " + file.className() + "() {}");
        } else {
            emitFileType(fileType);
        }
        for (Decl decl : file.decls()) {
            resetCounters();
            if (decl == fileType) {
                continue;
            }
            switch (decl) {
                case Decl.Value value when probed.contains(value.name()) -> { }
                case Decl.Value value -> emitValue(value);
                case Decl.Function fn -> emitFunction(fn, true);
                case Decl.ClassDecl classDecl -> emitClass(classDecl);
                case Decl.DataClass data -> emitDataClass(data);
                case Decl.Interface iface -> emitInterface(iface);
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
        patternCount = 0;
        smart = new HashMap<>();
        copies = Map.of();
    }

    /** A data class or interface named like the file is the class of the file; the rest of the file is in it. */
    private Decl fileType() {
        return file.decls().stream().filter(d -> d.name().equals(file.className())
                && (d instanceof Decl.DataClass || d instanceof Decl.Interface || d instanceof Decl.ClassDecl))
                .findFirst().orElse(null);
    }

    private void emitFileType(Decl fileType) {
        int line = fileType.pos().line();
        switch (fileType) {
            case Decl.DataClass data -> em.line(line, "@NullMarked public record " + data.name() + "("
                    + parameters(data.fields(), line) + ")" + typeList(" implements ", data.supertypes()) + " {");
            case Decl.Interface iface -> {
                em.line(line, "@NullMarked public" + interfaceModifier(iface) + " interface " + iface.name()
                        + typeList(" extends ", iface.supertypes()) + " {");
                emitRequiredValues(iface);
                em.indent();
                return;
            }
            case Decl.ClassDecl classDecl -> {
                em.line(line, "@NullMarked public final class " + classDecl.name()
                        + typeList(" implements ", classDecl.supertypes()) + " {");
                emitClassMembers(classDecl);
                em.indent();
                return;
            }
            default -> throw new IllegalStateException();
        }
        em.indent();
    }

    private void emitClass(Decl.ClassDecl classDecl) {
        em.line(classDecl.pos().line(), visibility(classDecl) + " static final class " + classDecl.name()
                + typeList(" implements ", classDecl.supertypes()) + " {");
        emitClassMembers(classDecl);
        em.line(classDecl.pos().line(), "}");
    }

    /** Fields, the constructor and the methods, inside the class that is open. */
    private void emitClassMembers(Decl.ClassDecl classDecl) {
        em.indent();
        // Properties are set after the parameters, in the constructor, since they may read them.
        boolean inConstructor = !classDecl.parameters().isEmpty();
        for (Decl.Property p : classDecl.parameters()) {
            em.line(p.pos().line(), field(p) + ";");
        }
        for (Decl.Property p : classDecl.properties()) {
            resetCounters();
            em.line(p.pos().line(), field(p) + (inConstructor ? "" : " = " + expr(p.init())) + ";");
        }
        if (inConstructor) {
            int line = classDecl.pos().line();
            String params = classDecl.parameters().stream().map(p -> type(p.type()) + " " + p.name()).collect(Collectors.joining(", "));
            em.line(line, "public " + classDecl.name() + "(" + params + ") {");
            em.indent();
            for (Decl.Property p : classDecl.parameters()) {
                em.line(p.pos().line(), "this." + p.name() + " = " + p.name() + ";");
            }
            for (Decl.Property p : classDecl.properties()) {
                resetCounters();
                em.line(p.pos().line(), "this." + p.name() + " = " + expr(p.init()) + ";");
            }
            em.dedent();
            em.line(line, "}");
        }
        for (Decl.Function method : classDecl.methods()) {
            resetCounters();
            emitFunction(method, false);
        }
        em.dedent();
    }

    private String field(Decl.Property p) {
        return (p.isPrivate() ? "private" : "public") + (p.mutable() ? "" : " final") + " " + type(p.type()) + " " + p.name();
    }

    private void emitDataClass(Decl.DataClass data) {
        int line = data.pos().line();
        em.line(line, visibility(data) + " record " + data.name() + "(" + parameters(data.fields(), line) + ")"
                + typeList(" implements ", data.supertypes()) + " {}");
    }

    private void emitInterface(Decl.Interface iface) {
        int line = iface.pos().line();
        String head = visibility(iface) + interfaceModifier(iface) + " interface " + iface.name()
                + typeList(" extends ", iface.supertypes());
        if (iface.members().isEmpty()) {
            em.line(line, head + " {}");
            return;
        }
        em.line(line, head + " {");
        emitRequiredValues(iface);
        em.line(line, "}");
    }

    private void emitRequiredValues(Decl.Interface iface) {
        em.indent();
        for (Expr.Param member : iface.members()) {
            em.line(member.pos().line(), type(member.type()) + " " + member.name() + "();");
        }
        em.dedent();
    }

    /** Java wants an interface under a sealed one to be sealed or open itself. */
    private String interfaceModifier(Decl.Interface iface) {
        if (iface.isSealed()) {
            return " sealed";
        }
        boolean underSealed = file.decls().stream().anyMatch(d -> d instanceof Decl.Interface parent && parent.isSealed()
                && iface.supertypes().stream().anyMatch(t -> t.name().equals(parent.name())));
        return underSealed ? " non-sealed" : "";
    }

    private static String typeList(String keyword, List<TypeRef> types) {
        return types.isEmpty() ? "" : keyword + types.stream().map(Generator::type).collect(Collectors.joining(", "));
    }

    private void emitValue(Decl.Value value) {
        String type = value.type() != null ? type(value.type()) : valueTypes.get(value.name());
        int line = value.pos().line();
        em.line(line, joined(visibility(value) + " static final " + type + " " + value.name(), " = ", expr(value.init()),
                line, startLine(value.init())) + ";");
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

    private void emitFunction(Decl.Function fn, boolean isStatic) {
        String params = isStatic && isMain(fn) ? "String[] args" : parameters(fn);
        String returnType = fn.returnType() == null ? "void" : type(fn.returnType());
        em.line(fn.pos().line(), visibility(fn) + (isStatic ? " static " : " ") + returnType + " " + fn.name() + "(" + params + ") {");
        this.returnType = fn.returnType() == null ? null : nullableType(fn.returnType());
        localTypes.clear();
        fn.params().forEach(p -> localTypes.put(p.name(), p.type()));
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

    /** The condition under which a statement `c && f()`, `c || f()` or `a ?: f()` runs its right side, or null. */
    private static Expr runsWhen(Expr statement) {
        return switch (statement) {
            case Expr.Binary b when b.op().equals("&&") -> b.left();
            case Expr.Binary b when b.op().equals("||") -> new Expr.Unary(b.pos(), "!", new Expr.Paren(b.pos(), b.left()));
            case Expr.Elvis e -> new Expr.Binary(e.pos(), "==", e.left(), new Expr.Literal(e.pos(), "null"));
            default -> null;
        };
    }

    /** `(f())` as a statement is the call: Java takes no parentheses there. */
    private static Expr withoutParens(Expr e) {
        return e instanceof Expr.Paren p ? withoutParens(p.inner()) : e;
    }

    /** A parameter on a line of its own keeps that line, so that errors in its type are found there. */
    private static String parameters(Decl.Function fn) {
        return parameters(fn.params(), fn.pos().line());
    }

    private static String parameters(List<Expr.Param> params, int declarationLine) {
        StringBuilder sb = new StringBuilder();
        int previous = declarationLine;
        for (int i = 0; i < params.size(); i++) {
            Expr.Param p = params.get(i);
            String code = type(p.type()) + " " + p.name();
            if (p.pos().line() > previous) {
                sb.append(i > 0 ? "," : "").append(Emitter.continued(p.pos().line(), code));
                previous = p.pos().line();
            } else {
                sb.append(i > 0 ? ", " : "").append(code);
            }
        }
        return sb.toString();
    }

    private static boolean isMain(Decl.Function fn) {
        return fn.name().equals("main") && fn.params().isEmpty() && fn.returnType() == null && !fn.isPrivate();
    }

    private static String visibility(Decl decl) {
        return decl.isPrivate() ? "private" : "public";
    }

    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local local when withoutParens(local.init()) instanceof Expr.When w -> {
                String modifiers = local.mutable() ? "" : "final ";
                String type = local.type() == null ? "var" : type(local.type());
                emitWhen(local.pos().line(), w, modifiers + type + " " + local.name() + " = ", WhenForm.VALUE);
                localTypes.put(local.name(), local.type());
            }
            case Stmt.Local local -> {
                int line = local.pos().line();
                Expr init = local.init();
                TypeRef localType = local.type() != null ? local.type() : typeOf(init);
                withCopies(line, Captures.capturedIn(init, analysis, inlinesElvis(init)), () -> {
                    String value = init instanceof Expr.Try t ? unwrapTry(line, t)
                            : value(line, init, local.type() == null ? null : nullableType(local.type()));
                    String modifiers = local.mutable() ? "" : "final ";
                    String type = local.type() == null ? "var" : type(local.type());
                    em.line(line, modifiers + type + " " + local.name() + " = " + value + ";");
                });
                localTypes.put(local.name(), localType);
            }
            case Stmt.Assign assign -> withCopies(assign.pos().line(),
                    Captures.capturedIn(assign.value(), analysis, inlinesElvis(assign.value())), () -> {
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
            case Stmt.Return ret when ret.value() != null && withoutParens(ret.value()) instanceof Expr.When w ->
                    emitWhen(ret.pos().line(), w, "", WhenForm.RESULT);
            case Stmt.Return ret -> {
                if (ret.value() == null) {
                    em.line(ret.pos().line(), "return;");
                } else {
                    withCopies(ret.pos().line(), Captures.capturedIn(ret.value(), analysis, inlinesElvis(ret.value())), () ->
                            em.line(ret.pos().line(), "return " + value(ret.pos().line(), ret.value(), returnType) + ";"));
                }
            }
            case Stmt.If ifStmt when splitsCondition(ifStmt) -> shortCircuitIf(ifStmt, (Expr.Binary) ifStmt.condition());
            case Stmt.If ifStmt -> {
                emitIf(ifStmt, "");
                em.line(blockEnd(ifStmt), "}");
                smart = union(smart, narrowedAfter(ifStmt));
            }
            case Stmt.While loop -> emitWhile(loop);
            case Stmt.For loop -> emitFor(loop);
            case Stmt.ExprStmt exprStmt -> {
                int line = exprStmt.pos().line();
                Expr e = withoutParens(exprStmt.expr());
                Expr condition = runsWhen(e);
                if (e instanceof Expr.When w) {
                    emitWhen(line, w, "", WhenForm.STATEMENT);
                } else if (e instanceof Expr.Throw t) {
                    withCopies(line, t.value(), () -> em.line(line, "throw " + lifted(line, t.value()).code() + ";"));
                } else if (condition != null) {
                    // Java takes no `c && f()` or `a ?: f()` as a statement.
                    Expr action = e instanceof Expr.Binary b ? b.right() : ((Expr.Elvis) e).right();
                    Stmt.Block then = new Stmt.Block(action.pos(), List.of(new Stmt.ExprStmt(action.pos(), action)), action.pos());
                    statement(new Stmt.If(exprStmt.pos(), condition, then, null));
                } else if (e instanceof Expr.Try t) {
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
        List<String> captured = Captures.capturedIn(ifStmt.condition(), analysis);
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
        narrowed(whenTrue(ifStmt.condition()), () -> block(ifStmt.then()));
        Map<String, String> otherwise = whenFalse(ifStmt.condition());
        switch (ifStmt.elseBranch()) {
            case null -> { }
            case Stmt.If elseIf -> narrowed(otherwise, () -> emitIf(elseIf, "} else "));
            case Stmt.Block elseBlock -> {
                em.line(elseBlock.pos().line(), "} else {");
                narrowed(otherwise, () -> block(elseBlock));
            }
            default -> throw new IllegalStateException();
        }
    }

    private boolean splitsCondition(Stmt.If ifStmt) {
        boolean simpleAnd = ifStmt.elseBranch() == null && ifStmt.condition() instanceof Expr.Binary b && b.op().equals("&&");
        return !simpleAnd && Captures.capturedIn(ifStmt.condition(), analysis).isEmpty()
                && ifStmt.condition() instanceof Expr.Binary b && (b.op().equals("&&") || b.op().equals("||"))
                && Lifts.needed(b.right());
    }

    /**
     * `if (s != null && try ok(s)) {...} else {...}`, or `||`: nested ifs keep what the left side
     * checks known where it holds, which a boolean temporary would hide from NullAway. The branch
     * taken inside them leaves a loop that runs once, so that the other one is written only once.
     */
    private void shortCircuitIf(Stmt.If ifStmt, Expr.Binary b) {
        int line = ifStmt.pos().line();
        boolean and = b.op().equals("&&");
        Stmt.Block otherwise = switch (ifStmt.elseBranch()) {
            case null -> null;
            case Stmt.Block block -> block;
            case Stmt.If elseIf -> new Stmt.Block(elseIf.pos(), List.of(elseIf), new Pos(blockEnd(elseIf), 1));
            default -> throw new IllegalStateException();
        };
        Stmt.Block inside = and ? ifStmt.then() : otherwise;
        Stmt.Block after = and ? otherwise : ifStmt.then();
        em.line(line, "do {");
        em.indent();
        String left = lifted(line, b.left()).code();
        em.line(line, "if (" + (and ? left : negated(left)) + ") {");
        em.indent();
        String right = lifted(line, b.right()).code();
        em.line(line, "if (" + (and ? right : negated(right)) + ") {");
        if (inside != null) {
            block(inside);
        }
        if (inside == null || !exits(inside)) {
            em.indent();
            em.line(inside == null ? line : inside.end().line(), "break;");
            em.dedent();
        }
        em.line(inside == null ? line : inside.end().line(), "}");
        em.dedent();
        em.line(line, "}");
        if (after != null) {
            after.statements().forEach(this::statement);
        }
        em.dedent();
        em.line(blockEnd(ifStmt), "} while (false);");
    }

    /** Java rejects a statement after one that never completes. */
    private static boolean exits(Stmt stmt) {
        return switch (stmt) {
            case Stmt.Return r -> true;
            case Stmt.ExprStmt e -> e.expr() instanceof Expr.Throw;
            case Stmt.Block block -> !block.statements().isEmpty() && exits(block.statements().getLast());
            case Stmt.If i -> i.elseBranch() != null && exits(i.then()) && exits(i.elseBranch());
            case Stmt.While w -> w.condition() instanceof Expr.Literal l && l.text().equals("true");
            default -> false;
        };
    }

    /** After `if (x !is T) { return }` the names stay narrowed, as Java introduces them. */
    private Map<String, String> narrowedAfter(Stmt.If ifStmt) {
        boolean thenExits = exits(ifStmt.then());
        boolean elseExits = ifStmt.elseBranch() != null && exits(ifStmt.elseBranch());
        if (thenExits && !elseExits) {
            return whenFalse(ifStmt.condition());
        }
        return elseExits && !thenExits ? whenTrue(ifStmt.condition()) : Map.of();
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
        if (Captures.capturedIn(loop.condition(), analysis).isEmpty() && !Lifts.needed(loop.condition())) {
            em.line(line, "while (" + expr(loop.condition()) + ") {");
            narrowed(whenTrue(loop.condition()), () -> block(loop.body()));
        } else {
            // Copies and lifted `try`s must run on every iteration, before the condition.
            em.line(line, "while (true) {");
            em.indent();
            if (Captures.capturedIn(loop.condition(), analysis).isEmpty() && loop.condition() instanceof Expr.Binary b
                    && (b.op().equals("&&") || b.op().equals("||")) && Lifts.needed(b.right())) {
                // Separate checks keep what the left side checks known in the body, as in shortCircuitIf.
                String left = lifted(line, b.left()).code();
                boolean and = b.op().equals("&&");
                em.line(line, "if (" + negated(left) + ") " + (and ? "break;" : "{"));
                if (!and) {
                    em.indent();
                }
                em.line(line, "if (" + negated(lifted(line, b.right()).code()) + ") break;");
                if (!and) {
                    em.dedent();
                    em.line(line, "}");
                }
            } else {
                withCopies(line, loop.condition(), () ->
                        em.line(line, "if (" + negated(lifted(line, loop.condition()).code()) + ") break;"));
            }
            narrowed(whenTrue(loop.condition()), () -> loop.body().statements().forEach(this::statement));
            em.dedent();
        }
        em.line(loop.body().end().line(), "}");
    }

    /** Where a `when` stands: what its branches do with their value. */
    private enum WhenForm { STATEMENT, RESULT, VALUE }

    /**
     * A `when` is a Java switch. As a statement its branches are statements, after `return` they return
     * their last expression, and as a value they yield it; head is what comes before a switch that is
     * a value. Each `is` condition has a branch of its own, so a branch with several is written for each.
     */
    private void emitWhen(int line, Expr.When w, String head, WhenForm form) {
        String[] subject = new String[1];
        withCopies(line, w.subject(), () -> {
            if (w.bindingName() != null) {
                subject[0] = "$e" + ++patternCount;
                em.line(line, "final var " + subject[0] + " = " + lifted(line, w.subject()).code() + ";");
            } else {
                subject[0] = lifted(line, w.subject()).code();
            }
        });
        // The name the branches narrow: a bound name, or a subject that is a name that never changes.
        String narrowedName = w.bindingName() != null ? w.bindingName()
                : withoutParens(w.subject()) instanceof Expr.Name n && !analysis.mutableRefs().contains(n)
                && !analysis.staticNames().contains(n) ? n.name() : null;
        Map<String, String> whole = w.bindingName() != null ? Map.of(w.bindingName(), subject[0]) : Map.of();
        em.line(line, head + "switch (" + subject[0] + ") {");
        em.indent();
        for (Expr.Arm arm : w.arms()) {
            narrowed(whole, () -> emitArm(arm, narrowedName, form));
        }
        em.dedent();
        em.line(w.end().line(), form == WhenForm.VALUE ? "};" : "}");
    }

    private void emitArm(Expr.Arm arm, String narrowedName, WhenForm form) {
        if (arm.conditions().isEmpty()) {
            emitBranch(arm, "default", Map.of(), form);
        } else if (arm.conditions().getFirst().type() == null) {
            String constants = arm.conditions().stream().map(c -> literal(c.constant().text())).collect(Collectors.joining(", "));
            emitBranch(arm, "case " + constants, Map.of(), form);
        } else {
            for (Expr.Cond condition : arm.conditions()) {
                String variable = "$s" + ++patternCount;
                emitBranch(arm, "case " + type(condition.type()) + " " + variable,
                        narrowedName == null ? Map.of() : Map.of(narrowedName, variable), form);
            }
        }
    }

    private void emitBranch(Expr.Arm arm, String label, Map<String, String> narrowing, WhenForm form) {
        int line = arm.pos().line();
        if (form == WhenForm.VALUE && !arm.block() && ((Stmt.ExprStmt) arm.body().getFirst()).expr() instanceof Expr.Throw thrown
                && !Lifts.needed(thrown.value())) {
            em.line(line, label + " -> throw " + narrowed(narrowing, () -> expr(thrown.value())) + ";");
            return;
        }
        if (form == WhenForm.VALUE && !arm.block() && !Lifts.needed(((Stmt.ExprStmt) arm.body().getFirst()).expr())) {
            Expr value = ((Stmt.ExprStmt) arm.body().getFirst()).expr();
            em.line(line, label + " -> " + narrowed(narrowing, () -> expr(value)) + ";");
            return;
        }
        em.line(line, label + " -> {");
        em.indent();
        narrowed(narrowing, () -> {
            List<Stmt> body = form == WhenForm.RESULT ? Whens.returning(arm) : arm.body();
            for (int i = 0; i < body.size(); i++) {
                if (form == WhenForm.VALUE && i == body.size() - 1 && !(((Stmt.ExprStmt) body.get(i)).expr() instanceof Expr.Throw)) {
                    Expr value = ((Stmt.ExprStmt) body.get(i)).expr();
                    em.line(body.get(i).pos().line(), "yield " + lifted(body.get(i).pos().line(), value).code() + ";");
                } else {
                    statement(body.get(i));
                }
            }
        });
        em.dedent();
        em.line(arm.body().isEmpty() ? line : arm.body().getLast().pos().line(), "}");
    }

    private void emitFor(Stmt.For loop) {
        int line = loop.pos().line();
        withCopies(line, loop.iterable(), () ->
                em.line(line, "for (final var " + loop.name() + " : " + lifted(line, loop.iterable()).code() + ") {"));
        block(loop.body());
        em.line(loop.body().end().line(), "}");
    }

    private void block(Stmt.Block block) {
        Map<String, String> outer = smart;
        em.indent();
        block.statements().forEach(this::statement);
        em.dedent();
        smart = outer;
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
        boolean call = (e instanceof Expr.Call || e instanceof Expr.GenericNew || e instanceof Expr.Try
                || e instanceof Expr.Pipe) && code.endsWith(")");
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
        if (!inlinesElvis(expr) || !(expr instanceof Expr.Elvis elvis)) {
            return expr(expr);
        }
        String temp = "$t" + ++tempCount;
        em.line(line, "final var " + temp + " = " + expr(elvis.left()) + ";");
        // Objects.nonNull also compiles when the left side is a primitive.
        return "java.util.Objects.nonNull(" + temp + ") ? " + temp + " : " + expr(elvis.right());
    }

    /**
     * Whether Java widens the number to the type on the left without boxing. Otherwise the overloads
     * of Nulls.or for numbers and for any type both apply, and Java cannot pick one.
     */
    private boolean widens(Expr number, TypeRef left) {
        if (left == null) {
            return true;
        }
        TypeRef type = TypeInference.typeOf(file, number);
        List<String> widening = List.of("Int", "Long", "Double");
        return type != null && widening.contains(type.name()) && widening.indexOf(type.name()) <= widening.indexOf(left.name());
    }

    /** Whether `value` emits a root `?:` as a conditional rather than Nulls.or. */
    private boolean inlinesElvis(Expr expr) {
        return options.inlineElvis() && expr instanceof Expr.Elvis elvis && !Lifts.needed(elvis.right())
                && !Lifts.needed(expr) && !mayMixNumbers(elvis);
    }

    /**
     * `Int? ?: Long?` or `Int? ?: 1.5`: Java unboxes both sides of such a conditional, which fails on a
     * null right side, and widens the result. Nulls.or keeps them as they are. A side whose type the
     * code does not state may be a number.
     */
    private boolean mayMixNumbers(Expr.Elvis elvis) {
        TypeRef left = typeOf(elvis.left());
        TypeRef right = typeOf(elvis.right());
        if (left != null && right != null && left.name().equals(right.name())) {
            return false;
        }
        return !(left != null && !NUMBERS.contains(left.name()) || right != null && !NUMBERS.contains(right.name()));
    }

    private static String converted(String number, String javaType) {
        return javaType.equals("float") && number.matches("-?[\\d.]+") ? number + "F" : "(" + javaType + ") " + number;
    }

    private static String name(TypeRef type) {
        return type == null ? "" : type.name();
    }

    private TypeRef typeOf(Expr expr) {
        Expr e = withoutParens(expr);
        if (e instanceof Expr.Name name && localTypes.containsKey(name.name())) {
            return localTypes.get(name.name());
        }
        return TypeInference.typeOf(file, e);
    }

    /**
     * A number on the right is passed as is, so that Java widens it to the type on the left. After
     * `?.` only when the left side is a temporary: Java could not infer the type of its Nulls.map.
     */
    private String elvis(Expr.Elvis elvis, String left) {
        Expr right = elvis.right();
        boolean number = Lifts.isNumber(right) && widens(right, typeOf(elvis.left()))
                && (!Chains.hasSafeStep(elvis.left()) || left.matches("\\$t\\d+"));
        String narrow = Lifts.isNumber(right) ? NARROW_NUMBERS.get(name(typeOf(elvis.left()))) : null;
        String fallback = number ? expr(right) : narrow != null ? "() -> " + converted(expr(right), narrow)
                : "() -> " + expr(right, true);
        return joined("Nulls.or(" + left, ", ", fallback, startLine(elvis.left()), startLine(right)) + ")";
    }

    /** Copies `var`s that lambdas of this statement capture into fresh finals, then emits it. */
    private void withCopies(int line, Expr expr, Runnable emit) {
        withCopies(line, Captures.capturedIn(expr, analysis), emit);
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
    private String unwrapTry(int statementLine, Expr.Try t) {
        // A `try` on a continuation line runs, and fails, on that line.
        int line = Math.max(statementLine, t.pos().line());
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
                // A call before `?.` may return a primitive, which cannot be compared with null: box it.
                String value = opened > 0 ? "Nulls.typed(" + current + ").value()" : current;
                em.line(line, "final var " + temp + " = " + value + ";");
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
            case Expr.Name n when smart.containsKey(n.name()) -> smart.get(n.name());
            case Expr.Name n -> inLambda && analysis.mutableRefs().contains(n)
                    ? copies.getOrDefault(n.name(), n.name()) : n.name();
            case Expr.Member m -> chain(Chains.flatten(m), inLambda);
            case Expr.Call c when packagePath(c.target()) != null && isTypeName(c.name()) ->
                    "new " + packagePath(c.target()) + "." + c.name() + "(" + args(c.args(), inLambda, c.pos().line()) + ")";
            case Expr.Call c when c.target() != null -> chain(Chains.flatten(c), inLambda);
            case Expr.Call c -> call(c, args(c.args(), inLambda, c.pos().line()));
            case Expr.GenericNew g -> genericNew(g, args(g.args(), inLambda, g.pos().line()));
            case Expr.MethodRef r -> expr(r.target(), inLambda) + "::" + r.name();
            case Expr.NotNull n -> "java.util.Objects.requireNonNull(" + expr(n.target(), inLambda) + ")";
            case Expr.Unary u -> unary(u.op(), expr(u.operand(), inLambda));
            case Expr.Binary b -> {
                String left = expr(b.left(), inLambda);
                String right = narrowed(rightNarrowing(b), () -> expr(b.right(), inLambda));
                yield joined(left, " " + b.op() + " ", right, startLine(b.left()), startLine(b.right()));
            }
            case Expr.Is i -> typeCheck(i, inLambda);
            case Expr.Conditional c -> {
                String condition = expr(c.condition(), inLambda);
                String then = narrowed(whenTrue(c.condition()), () -> expr(c.then(), inLambda));
                String otherwise = narrowed(whenFalse(c.condition()), () -> expr(c.otherwise(), inLambda));
                yield "(" + condition + " ? " + then + " : " + otherwise + ")";
            }
            case Expr.Throw t -> throw new IllegalStateException("throw at " + t.pos() + " cannot be an expression");
            case Expr.Elvis e -> elvis(e, expr(e.left(), inLambda));
            // `(a ?: b)` needs parentheses in Axolotl, but its expansion is a method call in Java.
            case Expr.Paren p when becomesCall(p.inner()) -> expr(p.inner(), inLambda);
            case Expr.Paren p -> "(" + expr(p.inner(), inLambda) + ")";
            case Expr.Attempt a -> {
                String error = a.errorName() != null ? a.errorName() : "$ex" + ++exceptionCount;
                yield "Result.attempt(() -> " + expr(a.body(), true) + ", " + error + " -> " + expr(a.handler(), true) + ")";
            }
            case Expr.Lambda l -> lambda(l);
            case Expr.Pipe p -> pipe(expr(p.source(), inLambda), p.source(), p.stages(), a -> expr(a, inLambda), inLambda);
            case Expr.Try t -> throw new IllegalStateException("try at " + t.pos() + " should have been lifted");
            case Expr.When w -> throw new IllegalStateException("when at " + w.pos() + " should have been lifted");
        };
    }

    /** `x instanceof T $s1` when `x` can be narrowed, which a name that never changes can. */
    private String typeCheck(Expr.Is check, boolean inLambda) {
        String type = type(check.type());
        if (!(check.target() instanceof Expr.Name name) || analysis.mutableRefs().contains(name)
                || analysis.staticNames().contains(name)) {
            String test = expr(check.target(), inLambda) + " instanceof " + type;
            return check.negated() ? "!(" + test + ")" : test;
        }
        String variable = "$s" + ++patternCount;
        patternVariables.put(check, variable);
        String test = expr(name, inLambda) + " instanceof " + type + " " + variable;
        return check.negated() ? "!(" + test + ")" : test;
    }

    /** The names a condition narrows where it is true. */
    private Map<String, String> whenTrue(Expr condition) {
        return switch (condition) {
            case Expr.Is i when !i.negated() && patternVariables.containsKey(i) -> Map.of(((Expr.Name) i.target()).name(), patternVariables.get(i));
            case Expr.Is i when i.negated() -> Map.of();
            case Expr.Paren p -> whenTrue(p.inner());
            case Expr.Unary u when u.op().equals("!") -> whenFalse(u.operand());
            case Expr.Binary b when b.op().equals("&&") -> union(whenTrue(b.left()), whenTrue(b.right()));
            default -> Map.of();
        };
    }

    /** The names a condition narrows where it is false. */
    private Map<String, String> whenFalse(Expr condition) {
        return switch (condition) {
            case Expr.Is i when i.negated() && patternVariables.containsKey(i) -> Map.of(((Expr.Name) i.target()).name(), patternVariables.get(i));
            case Expr.Paren p -> whenFalse(p.inner());
            case Expr.Unary u when u.op().equals("!") -> whenTrue(u.operand());
            case Expr.Binary b when b.op().equals("||") -> union(whenFalse(b.left()), whenFalse(b.right()));
            default -> Map.of();
        };
    }

    private static Map<String, String> union(Map<String, String> a, Map<String, String> b) {
        Map<String, String> result = new HashMap<>(a);
        result.putAll(b);
        return result;
    }

    /** What the left side of `a && b` or `a || b` narrows in `b`. */
    private Map<String, String> rightNarrowing(Expr.Binary b) {
        return b.op().equals("&&") ? whenTrue(b.left()) : b.op().equals("||") ? whenFalse(b.left()) : Map.of();
    }

    private <T> T narrowed(Map<String, String> names, java.util.function.Supplier<T> body) {
        Map<String, String> outer = smart;
        smart = union(outer, names);
        T result = body.get();
        smart = outer;
        return result;
    }

    private void narrowed(Map<String, String> names, Runnable body) {
        narrowed(names, () -> {
            body.run();
            return null;
        });
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

    /** Where a `?.` argument, also on the left of `?:`, cannot be moved into a temporary, Nulls.typed gives it its type. */
    private String argument(Expr arg, boolean inLambda) {
        return switch (arg) {
            case Expr.Paren p -> "(" + argument(p.inner(), inLambda) + ")";
            case Expr.NotNull n -> "java.util.Objects.requireNonNull(" + argument(n.target(), inLambda) + ")";
            // As the receiver of `.value()`, the expansion gets its type without the overloads of the call.
            default -> Chains.hasSafeStep(arg) || arg instanceof Expr.Elvis e && Chains.hasSafeStep(e.left())
                    ? "Nulls.typed(" + expr(arg, inLambda) + ").value()" : expr(arg, inLambda);
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

    /** A `var` from outside stays itself in a lambda of the code, so that javac rejects it, not a stale copy. */
    private String lambda(Expr.Lambda lambda) {
        String params;
        if (analysis.implicitIt().contains(lambda)) {
            params = "it";
        } else if (lambda.params().size() == 1 && lambda.params().getFirst().type() == null) {
            params = lambda.params().getFirst().name();
        } else if (lambda.params().stream().allMatch(p -> p.type() == null || type(p.type()).equals(primitive(p.type())))) {
            // `x: Int` fits Function<Integer, ...> and IntUnaryOperator alike only when Java infers it.
            params = "(" + lambda.params().stream().map(Expr.Param::name).collect(Collectors.joining(", ")) + ")";
        } else {
            params = "(" + lambda.params().stream()
                    .map(p -> p.type() == null ? p.name() : type(p.type(), true) + " " + p.name())
                    .collect(Collectors.joining(", ")) + ")";
        }
        List<Stmt> body = lambda.body();
        Map<String, String> outerCopies = copies;
        copies = Map.of();
        if (body.size() == 1 && body.getFirst() instanceof Stmt.ExprStmt single && !(single.expr() instanceof Expr.Throw)) {
            int bodyLine = startLine(single.expr());
            String code = expr(single.expr());
            copies = outerCopies;
            // A body on its own line keeps that line, for errors, stack traces and breakpoints.
            return bodyLine > lambda.pos().line() ? params + " ->" + Emitter.continued(bodyLine, code) : params + " -> " + code;
        }
        Emitter outer = em;
        String outerReturnType = returnType;
        Map<String, String> outerSmart = smart;
        returnType = null;
        em = new Emitter();
        em.indent();
        body.forEach(this::statement);
        String block = em.embedded();
        em = outer;
        smart = outerSmart;
        copies = outerCopies;
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
            case Expr.When w -> {
                String temp = "$t" + ++tempCount;
                emitWhen(line, w, "final var " + temp + " = ", WhenForm.VALUE);
                yield new Lifted(temp, true);
            }
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
            int at = Math.max(line, startLine(e));
            Lifted operand = i < last && takesTypeFromTarget(e) ? withArgumentsFirst(at, e) : lifted(at, e);
            if (i < last && !operand.pure() || Chains.hasSafeStep(e)) {
                String temp = "$t" + ++tempCount;
                em.line(at, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            }
            result.add(operand);
        }
        return result;
    }

    /**
     * `Ok(x)`, `ArrayList<>()` and `List.of(...)` get their type arguments from where they are passed;
     * in a temporary they would get `Object`. Factories like these are kept where they stand, which
     * would change the order of calls with side effects.
     */
    private boolean takesTypeFromTarget(Expr e) {
        return switch (e) {
            case Expr.Call c when c.target() == null -> c.name().equals("Ok") || c.name().equals("Err");
            case Expr.Call c -> c.target() instanceof Expr.Name n && analysis.staticNames().contains(n)
                    && TARGET_TYPED_FACTORIES.contains(c.name());
            case Expr.GenericNew g -> g.typeArgs().isEmpty();
            case Expr.MethodRef r -> true;
            default -> false;
        };
    }

    /** Evaluates the arguments into temporaries and keeps the call itself inline, where Java infers its type. */
    private Lifted withArgumentsFirst(int line, Expr e) {
        if (e instanceof Expr.MethodRef r) {
            // `make()::run` calls make() where it stands; a type name such as `String::valueOf` evaluates nothing.
            Lifted target = lifted(line, r.target());
            if (target.pure() || isPath(r.target())) {
                return new Lifted(target.code() + "::" + r.name(), true);
            }
            String temp = "$t" + ++tempCount;
            em.line(line, "final var " + temp + " = " + target.code() + ";");
            return new Lifted(temp + "::" + r.name(), true);
        }
        List<Expr> args = switch (e) {
            case Expr.Call c -> c.args();
            case Expr.GenericNew g -> g.args();
            default -> throw new IllegalStateException();
        };
        List<String> codes = new ArrayList<>();
        for (Expr arg : args) {
            Lifted operand = takesTypeFromTarget(arg) ? withArgumentsFirst(line, arg) : lifted(line, arg);
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
            case Expr.MethodRef r -> isPure(r.target()) || isPath(r.target());
            case Expr.Paren p -> isPure(p.inner());
            // Kept inline so that Java can still infer their type arguments from the target.
            case Expr.Call c when c.target() == null && (c.name().equals("Ok") || c.name().equals("Err")) ->
                    c.args().stream().allMatch(this::isPure);
            default -> false;
        };
    }

    /** A class, or a static field reached through classes and packages, such as `System.out`. */
    private boolean isPath(Expr e) {
        return switch (e) {
            case Expr.Name n -> analysis.staticNames().contains(n);
            case Expr.Member m -> !m.safe() && isPath(m.target());
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
        em.line(line, temp + " = " + narrowed(rightNarrowing(b), () -> lifted(line, b.right()).code()) + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }

    /** `a ?: try b` evaluates `b` and its `try` only when `a` is null. */
    private Lifted elvisChain(int line, Expr.Elvis elvis, String javaType) {
        String left = lifted(line, elvis.left()).code();
        if (javaType == null && elvis.right() instanceof Expr.Try t && !Lifts.needed(t.inner())) {
            // A temporary of the type on the left could not hold the value on the right: `xs ?: try load()`
            // with an ArrayList on the left and a List on the right. A conditional lets Java find the type.
            String value = "$t" + ++tempCount;
            em.line(line, "final var " + value + " = " + left + ";");
            String result = "$t" + ++tempCount;
            em.line(line, "final var " + result + " = java.util.Objects.isNull(" + value + ") ? " + expr(t.inner()) + " : null;");
            em.line(line, "if (" + result + " != null && " + result + ".isErr()) return Result.propagate(" + result + ");");
            return new Lifted("java.util.Objects.nonNull(" + value + ") ? " + value
                    + " : Result.unwrap(java.util.Objects.requireNonNull(" + result + "))", false);
        }
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

    /** `java.sql` in `java.sql.Timestamp(1L)`, which creates an object as `Timestamp(1L)` would; else null. */
    private String packagePath(Expr target) {
        if (target instanceof Expr.Name n && analysis.staticNames().contains(n) && Character.isLowerCase(n.name().charAt(0))) {
            return n.name();
        }
        if (target instanceof Expr.Member m && !m.safe() && Character.isLowerCase(m.name().charAt(0))) {
            String path = packagePath(m.target());
            return path == null ? null : path + "." + m.name();
        }
        return null;
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
            String lambda = stage.lambda() == null ? "" : lambda(stage.lambda());
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

    private static String primitive(TypeRef type) {
        String[] primitive = PRIMITIVES.get(type.name());
        return primitive == null || type.nullable() ? null : primitive[0];
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
