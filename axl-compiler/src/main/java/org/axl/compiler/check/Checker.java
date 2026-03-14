package org.axl.compiler.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

/**
 * Name and placement rules that need no types: scopes, shadowing, where `try` may appear,
 * mandatory type annotations. Also records what the generator needs to know about names.
 */
public final class Checker {

    public static final String TRY_IN_LAMBDA =
            "`try` cannot be used in a lambda or `attempt`: it would not return from the function; move it into a `val` first, "
                    + "or use `|> mapResult` for a collection";
    public static final String TRY_AFTER_SAFE_CALL =
            "`try` after `?.` runs only for a non-null value; move `try` into a separate `val`";
    public static final String RETURN_TRY =
            "`return try e` makes no sense: `try` unwraps the value, but the function returns `Result`; write `return e`";
    public static final String TRY_NEEDS_RESULT = "`try` can only be used in a function that returns `Result`";

    private static final Set<String> STAGES_WITH_LAMBDA = Set.of("filter", "map", "peek", "mapResult", "forEach",
            "anyMatch", "allMatch", "noneMatch");
    private static final Set<String> STAGES_WITH_ARGUMENT = Set.of("limit", "skip");
    private static final Set<String> STAGES_WITHOUT_ARGUMENTS = Set.of("distinct", "count", "first");
    public static final Set<String> TERMINAL_STAGES = Set.of("forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");

    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "enum", "extends", "final", "finally", "float", "for",
            "goto", "implements", "instanceof", "int", "interface", "long", "native", "new", "protected",
            "public", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "void", "volatile", "_",
            // Restricted identifiers, and Axolotl keywords that only file names could still hit.
            "var", "yield", "record", "sealed", "permits", "true", "false", "null",
            "package", "import", "if", "else", "while", "return", "try");

    // Classes the generated code names: a file of the same name would hide them.
    private static final Set<String> GENERATED_CODE_CLASSES = Set.of("Nulls", "Result", "Ok", "Err", "System", "Nullable",
            "NullMarked", "String", "Integer", "Long", "Double", "Boolean", "Object", "Exception");

    // Methods of java.lang.Object by name and parameter count, which the static method of a function would clash with.
    private static final Set<String> OBJECT_METHODS = Set.of("toString/0", "hashCode/0", "equals/1", "getClass/0",
            "notify/0", "notifyAll/0", "wait/0", "wait/1", "wait/2", "clone/0", "finalize/0");

    // Methods that a record or an interface member cannot be named after.
    private static final Set<String> OBJECT_METHOD_NAMES = Set.of("toString", "hashCode", "getClass", "clone", "finalize",
            "notify", "notifyAll", "wait");

    // Where the classes above come from; importing them from there changes nothing.
    private static final Set<String> GENERATED_CODE_PACKAGES = Set.of("axl.lang", "java.lang", "org.jspecify.annotations");

    // Classes the generated code names in expressions, where a value of the same name would take their place.
    private static final Set<String> GENERATED_CODE_RECEIVERS = Set.of("System", "Nulls", "Result");

    // Names that would hide the `java` and `axl` packages the generated code qualifies names with.
    private static final Set<String> PACKAGE_ROOTS = Set.of("java", "axl");

    private enum Kind { LOCAL, MUTABLE_LOCAL, PARAM, TOP_LEVEL, IMPORTED }

    /** type is the written type of a local, or null. */
    private record Symbol(Kind kind, Pos pos, TypeRef type) {

        Symbol(Kind kind, Pos pos) {
            this(kind, pos, null);
        }
    }

    private static final class Scope {
        final Scope parent;
        final boolean fileScope;
        final Map<String, Symbol> names = new HashMap<>();

        Scope(Scope parent, boolean fileScope) {
            this.parent = parent;
            this.fileScope = fileScope;
        }

        Symbol lookup(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Symbol symbol = s.names.get(name);
                if (symbol != null) {
                    return symbol;
                }
            }
            return null;
        }
    }

    private final SourceFile file;
    private final List<AxlDiagnostic> diagnostics = new ArrayList<>();
    private final Set<Expr.Lambda> implicitIt = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Expr.Name> mutableRefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Expr.Name> stableNames = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> typeNames = new HashSet<>();
    // How each `when` is used; one that is not here gives a value.
    private final Map<Expr.When, Boolean> whenResults = new IdentityHashMap<>();
    // Lambda depth inside the innermost `when` that gives a value, or -1.
    private int valueWhenDepth = -1;
    private int expressionLambdas;
    private final Set<Expr.Name> staticNames = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Expr.Stage, TypeRef> mapResultTypes = new IdentityHashMap<>();
    private Scope scope;
    private Decl.Function function;
    private Stmt lastInLambda;
    private int lambdaDepth;
    private int safeTailDepth;
    // The written type of the `val` whose initializer is being checked, for `mapResult`.
    private TypeRef declaredType;

    private Checker(SourceFile file) {
        this.file = file;
    }

    public static Analysis check(SourceFile file, List<AxlDiagnostic> diagnostics) {
        Checker checker = new Checker(file);
        checker.run();
        diagnostics.addAll(checker.diagnostics);
        return new Analysis(checker.mutableRefs, checker.implicitIt, checker.stableNames, checker.staticNames,
                checker.mapResultTypes);
    }

    private void run() {
        if (file.packageName() == null) {
            error(new Pos(1, 1), ErrorCode.MISSING_PACKAGE, "missing `package` declaration: every .axl file starts with `package name`");
        }
        if (!isJavaIdentifier(file.className()) || RESERVED.contains(file.className())) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME,
                    "file name `" + file.className() + "` is not a valid class name");
        } else if (PACKAGE_ROOTS.contains(file.className())) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + file.className() + "` would hide the `"
                    + file.className() + "` package that the generated code uses; rename the file");
        } else if (GENERATED_CODE_CLASSES.contains(file.className())) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + file.className()
                    + "` would hide the class `" + file.className() + "` that the generated code uses; rename the file");
        }
        for (String segment : file.packageName() == null ? new String[0] : file.packageName().split("\\.")) {
            if (RESERVED.contains(segment)) {
                error(file.packagePos(), ErrorCode.RESERVED_NAME, "`" + segment
                        + "` is a reserved word in Java and cannot be part of a package name");
            }
        }
        scope = new Scope(null, true);
        for (Import imp : file.imports()) {
            if (imp.kind() != Import.Kind.TYPE) {
                scope.names.putIfAbsent(imp.simpleName(), new Symbol(Kind.IMPORTED, imp.pos()));
            } else if (GENERATED_CODE_CLASSES.contains(imp.simpleName()) && !GENERATED_CODE_PACKAGES.contains(
                    imp.name().substring(0, Math.max(imp.name().lastIndexOf('.'), 0)))) {
                error(imp.pos(), ErrorCode.RESERVED_NAME, "`" + imp.simpleName() + "` would hide the class `"
                        + imp.simpleName() + "` that the generated code uses; write `" + imp.name() + "` in full instead");
            }
        }
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value) {
                Symbol existing = scope.names.get(value.name());
                if (existing != null) {
                    error(value.namePos(), ErrorCode.DUPLICATE_NAME,
                            "`" + value.name() + "` is already declared at line " + existing.pos().line());
                }
                scope.names.put(value.name(), new Symbol(Kind.TOP_LEVEL, value.pos()));
            } else if (decl instanceof Decl.DataClass || decl instanceof Decl.Interface) {
                checkTypeName(decl);
            } else {
                scope.names.putIfAbsent(decl.name(), new Symbol(Kind.TOP_LEVEL, decl.pos()));
            }
            checkReserved(decl.namePos(), decl.name());
            if (decl instanceof Decl.Function fn) {
                checkFunctionName(fn);
            }
            if (decl instanceof Decl.Value value && value.isPrivate() && fileIsInterface()) {
                error(value.namePos(), ErrorCode.SYNTAX, "a value in the file of an interface cannot be private");
            }
            if (decl instanceof Decl.Value value && value.name().equals("args")
                    && file.decls().stream().anyMatch(d -> d instanceof Decl.Function fn && isMain(fn))) {
                error(value.namePos(), ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` "
                        + "method, which would read it instead of this value; choose another name");
            }
        }
        for (Decl decl : file.decls()) {
            switch (decl) {
                case Decl.Value value -> topLevelValue(value);
                case Decl.Function fn -> function(fn);
                case Decl.DataClass data -> members(data.fields(), "field");
                case Decl.Interface iface -> members(iface.members(), "member");
            }
        }
        for (InitOrder.Problem problem : InitOrder.check(file)) {
            Decl.Value value = problem.value();
            error(value.namePos(), ErrorCode.INIT_ORDER, "`" + value.name() + "` is computed from `" + problem.later()
                    + "`, which is declared " + (problem.later().equals(value.name()) ? "by itself" : "later")
                    + "; top-level values are set in order, so declare `" + problem.later() + "` first");
        }
    }

    private boolean fileIsInterface() {
        return file.decls().stream().anyMatch(d -> d instanceof Decl.Interface && d.name().equals(file.className()));
    }

    private void checkTypeName(Decl decl) {
        if (!Character.isUpperCase(decl.name().charAt(0))) {
            error(decl.namePos(), ErrorCode.SYNTAX, "the name of a type starts with a capital letter, as `"
                    + Character.toUpperCase(decl.name().charAt(0)) + decl.name().substring(1) + "`: "
                    + "`Name(...)` creates an object");
        } else if (decl.name().equals(file.className()) && decl.isPrivate()) {
            error(decl.namePos(), ErrorCode.SYNTAX, "`" + decl.name() + "` is the type of this file and cannot be private");
        } else if (!typeNames.add(decl.name())) {
            error(decl.namePos(), ErrorCode.DUPLICATE_NAME, "the type `" + decl.name() + "` is already declared in this file");
        }
    }

    private void members(List<Expr.Param> members, String what) {
        Set<String> seen = new HashSet<>();
        for (Expr.Param member : members) {
            if (!seen.add(member.name())) {
                error(member.pos(), ErrorCode.DUPLICATE_NAME, "the " + what + " `" + member.name() + "` is declared twice");
            } else if (OBJECT_METHOD_NAMES.contains(member.name())) {
                error(member.pos(), ErrorCode.RESERVED_NAME, "`" + member.name() + "` is a method of every Java object "
                        + "and cannot be the name of a " + what + "; choose another name");
            }
        }
    }

    private void topLevelValue(Decl.Value value) {
        if (value.init() instanceof Expr.Try t) {
            error(t.pos(), ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, TRY_NEEDS_RESULT);
            expr(t.inner());
        } else {
            expr(value.init());
        }
    }

    private void function(Decl.Function fn) {
        function = fn;
        scope = new Scope(scope, false);
        for (Expr.Param p : fn.params()) {
            declare(p.pos(), p.name(), Kind.PARAM);
        }
        if (fn.exprBody() != null) {
            if (unparenthesized(fn.exprBody()) instanceof Expr.When w) {
                whenResults.put(w, fn.returnType() != null);
            }
            if (fn.exprBody() instanceof Expr.Try t) {
                if (!returnsResult(fn)) {
                    statementTry(t);
                } else {
                    error(t.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
                    expr(t.inner());
                }
            } else {
                if (fn.returnType() == null && !doesSomething(fn.exprBody())) {
                    error(fn.exprBody().pos(), ErrorCode.SYNTAX, "a function without a result type runs its body as a "
                            + "statement, where this expression does nothing; to return it, add the type: `-> Type`");
                }
                expr(fn.exprBody());
            }
        } else {
            statements(fn.body().statements());
        }
        scope = scope.parent;
        function = null;
    }

    private void statements(List<Stmt> statements) {
        scope = new Scope(scope, false);
        for (Stmt stmt : statements) {
            statement(stmt);
        }
        scope = scope.parent;
    }

    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local local -> {
                Expr init = unparenthesized(local.init());
                boolean needsType = init instanceof Expr.Lambda || init instanceof Expr.Literal l && l.text().equals("null");
                if (needsType && local.type() == null) {
                    String keyword = local.mutable() ? "var" : "val";
                    error(local.pos(), ErrorCode.TYPE_REQUIRED, "`" + keyword + " " + local.name()
                            + "` needs a type when it is initialized with " + (init instanceof Expr.Lambda
                            ? "a lambda" : "`null`") + ": `" + keyword + " " + local.name() + ": Type = ...`");
                }
                declaredType = local.type();
                if (local.init() instanceof Expr.Try t) {
                    statementTry(t);
                } else {
                    expr(local.init());
                }
                declaredType = null;
                if (local.type() != null && !local.type().nullable() && isNull(local.init())) {
                    error(local.init().pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable(local.name(), local.type()));
                }
                declare(local.namePos(), local.name(), local.mutable() ? Kind.MUTABLE_LOCAL : Kind.LOCAL, local.type());
            }
            case Stmt.Assign assign -> {
                if (assign.target() instanceof Expr.Name name) {
                    Symbol symbol = scope.lookup(name.name());
                    if (symbol != null && symbol.kind != Kind.MUTABLE_LOCAL) {
                        error(assign.pos(), ErrorCode.ASSIGN_TO_VAL, assignmentProblem(name.name(), symbol));
                    } else if (symbol != null && symbol.type() != null && !symbol.type().nullable() && isNull(assign.value())) {
                        // NullAway does not check what is assigned to locals.
                        error(assign.value().pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable(name.name(), symbol.type()));
                    }
                }
                expr(assign.target());
                expr(assign.value());
            }
            case Stmt.Return ret -> {
                if (valueWhenDepth == lambdaDepth) {
                    error(ret.pos(), ErrorCode.SYNTAX, "`return` cannot leave a `when` that gives a value; "
                            + "write the `when` as a statement or as `return when ...`");
                }
                if (unparenthesized(ret.value()) instanceof Expr.When w) {
                    whenResults.put(w, true);
                }
                if (ret.value() instanceof Expr.Try t && (lambdaDepth > 0 || !returnsResult(function))) {
                    statementTry(t);
                } else if (ret.value() instanceof Expr.Try t) {
                    error(t.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
                    expr(t.inner());
                } else if (ret.value() != null) {
                    expr(ret.value());
                }
            }
            case Stmt.If ifStmt -> {
                expr(ifStmt.condition());
                statements(ifStmt.then().statements());
                if (ifStmt.elseBranch() != null) {
                    statement(ifStmt.elseBranch());
                }
            }
            case Stmt.While loop -> {
                expr(loop.condition());
                statements(loop.body().statements());
            }
            case Stmt.For loop -> {
                expr(loop.iterable());
                scope = new Scope(scope, false);
                declare(loop.namePos(), loop.name(), Kind.LOCAL, null);
                statements(loop.body().statements());
                scope = scope.parent;
            }
            case Stmt.ExprStmt exprStmt -> {
                if (unparenthesized(exprStmt.expr()) instanceof Expr.When w) {
                    whenResults.put(w, false);
                }
                if (exprStmt.expr() instanceof Expr.Lambda lambda) {
                    error(lambda.pos(), ErrorCode.SYNTAX, "a lambda on its own does nothing; "
                            + "to run these statements, remove the braces");
                } else if (!doesSomething(exprStmt.expr())) {
                    error(exprStmt.expr().pos(), ErrorCode.SYNTAX, "this expression does nothing on its own; "
                            + (stmt == lastInLambda ? "to return it from the lambda, write `return` before it"
                                    : "use its value, for example in a `val`, or remove it"));
                }
                if (exprStmt.expr() instanceof Expr.Try t) {
                    statementTry(t);
                } else {
                    expr(exprStmt.expr());
                }
            }
            case Stmt.Block block -> statements(block.statements());
        }
    }

    private String assignmentProblem(String name, Symbol symbol) {
        return switch (symbol.kind) {
            case PARAM -> "cannot assign to the parameter `" + name + "`; copy it into a `var` first: `var current = " + name + "`";
            case TOP_LEVEL -> file.decls().stream().anyMatch(d -> d instanceof Decl.Function && d.name().equals(name))
                    ? "`" + name + "` is a function, not a variable"
                    : "cannot assign to the top-level value `" + name + "`; top-level values cannot change";
            default -> "cannot assign to `val` `" + name + "`; declare it with `var`";
        };
    }

    private static boolean isNull(Expr e) {
        return unparenthesized(e) instanceof Expr.Literal l && l.text().equals("null");
    }

    private static String notNullable(String name, TypeRef type) {
        return "`" + name + "` is declared as `" + type.name() + "`, which does not allow null; declare it as `"
                + type.name() + "?`";
    }

    private static Expr unparenthesized(Expr e) {
        return e instanceof Expr.Paren p ? unparenthesized(p.inner()) : e;
    }

    /** A `try` in one of the two allowed statement positions. */
    private void statementTry(Expr.Try t) {
        checkTryPlace(t.pos(), "`try`");
        expr(t.inner());
    }

    /** `try` and `mapResult` return from the function, so they need one that returns `Result`. */
    private void checkTryPlace(Pos pos, String what) {
        if (valueWhenDepth == lambdaDepth) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what + " cannot be used in a `when` that gives a value, since it "
                    + "would return from the function in the middle of the `when`; write the `when` as a statement "
                    + "or as `return when ...`");
        } else if (lambdaDepth > 0) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what.equals("`try`") ? TRY_IN_LAMBDA
                    : what + " cannot be used in a lambda: it would return from the lambda, not the function");
        } else if (safeTailDepth > 0) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what.equals("`try`") ? TRY_AFTER_SAFE_CALL
                    : what + " after `?.` runs only for a non-null value; move it into a separate `val`");
        } else if (function != null && function.returnType() != null && function.returnType().nullable()
                && isResult(function.returnType())) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " needs a function that returns `Result` without `?`; "
                    + "this one may return null instead");
        } else if (!returnsResult(function)) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " can only be used in a function that returns `Result`");
        }
    }

    /** Whether an expression can stand as a statement: it calls something or creates an object. */
    private static boolean doesSomething(Expr expr) {
        return switch (expr) {
            case Expr.Paren p -> doesSomething(p.inner());
            case Expr.Binary b -> (b.op().equals("&&") || b.op().equals("||")) && doesSomething(b.right());
            // `s.le` while a name is being typed: javac reports it, so that the rest of the file still translates.
            case Expr.Member m -> true;
            case Expr.Call c -> true;
            case Expr.GenericNew g -> true;
            case Expr.Try t -> true;
            case Expr.Pipe p -> true;
            case Expr.Elvis e -> doesSomething(e.right());
            case Expr.When w -> true;
            case Expr.Attempt a -> true;
            case Expr.NotNull n -> true;
            case Expr.Lambda l -> true;
            default -> false;
        };
    }

    private static boolean returnsResult(Decl.Function fn) {
        return fn != null && fn.returnType() != null && !fn.returnType().nullable() && isResult(fn.returnType());
    }

    private static boolean isResult(TypeRef type) {
        return type.name().equals("Result") || type.name().equals("axl.lang.Result");
    }

    private void expr(Expr expr) {
        switch (expr) {
            case Expr.Literal l -> { }
            case Expr.Name name -> {
                Symbol symbol = scope.lookup(name.name());
                if (symbol != null && symbol.kind == Kind.MUTABLE_LOCAL) {
                    mutableRefs.add(name);
                }
                if (symbol != null && symbol.kind != Kind.IMPORTED) {
                    stableNames.add(name);
                }
                if (symbol == null) {
                    staticNames.add(name);
                }
            }
            case Expr.Member m -> {
                expr(m.target());
            }
            case Expr.Call call -> {
                if (call.target() != null) {
                    expr(call.target());
                } else if (localLookup(call.name()) != null) {
                    error(call.pos(), ErrorCode.JAVAC, "`" + call.name() + "` is a value, not a function; "
                            + "call its method, such as `" + call.name() + ".apply(...)` or `" + call.name() + ".get()`");
                }
                // Arguments after a `?.` are evaluated only when the receiver is not null.
                boolean safeTail = call.safe() || call.target() != null && hasSafeStep(call.target());
                if (safeTail) {
                    safeTailDepth++;
                }
                call.args().forEach(this::expr);
                if (safeTail) {
                    safeTailDepth--;
                }
            }
            case Expr.GenericNew g -> g.args().forEach(this::expr);
            case Expr.MethodRef r -> expr(r.target());
            case Expr.NotNull n -> expr(n.target());
            case Expr.Unary u -> expr(u.operand());
            case Expr.Binary b -> {
                expr(b.left());
                expr(b.right());
            }
            case Expr.Elvis e -> {
                expr(e.left());
                expr(e.right());
            }
            case Expr.Is i -> expr(i.target());
            case Expr.When w -> when(w);
            case Expr.Paren p -> expr(p.inner());
            case Expr.Try t -> {
                checkTryPlace(t.pos(), "`try`");
                expr(t.inner());
            }
            // The body and the handler of `attempt` run inside lambdas.
            case Expr.Attempt a -> {
                lambdaDepth++;
                expr(a.body());
                scope = new Scope(scope, false);
                if (a.errorName() != null) {
                    declare(a.errorNamePos(), a.errorName(), Kind.PARAM);
                }
                expr(a.handler());
                scope = scope.parent;
                lambdaDepth--;
            }
            case Expr.Pipe pipe -> pipe(pipe);
            case Expr.Lambda lambda -> lambda(lambda, false);
        }
    }

    /** The arms are checked as the statements they become in the places where a `when` stands. */
    private void when(Expr.When w) {
        Boolean result = whenResults.get(w);
        boolean gives = result == null;
        if (gives && function == null) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value must be inside a function");
        } else if (gives && expressionLambdas > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside a lambda of one expression; "
                    + "give the lambda statements and write `return when ...`");
        }
        expr(w.subject());
        scope = new Scope(scope, false);
        if (w.bindingName() != null) {
            declare(w.bindingPos(), w.bindingName(), Kind.LOCAL);
        }
        checkArms(w, result == null || result);
        int outerDepth = valueWhenDepth;
        if (gives) {
            valueWhenDepth = lambdaDepth;
        }
        for (Expr.Arm arm : w.arms()) {
            List<Stmt> body = result != null && result ? Whens.returning(arm) : arm.body();
            if (gives && (arm.body().isEmpty() || !(arm.body().getLast() instanceof Stmt.ExprStmt))) {
                error(arm.pos(), ErrorCode.SYNTAX, "each branch of a `when` that gives a value ends with the value");
            }
            if (!arm.block() && result != null && !result && !doesSomething(((Stmt.ExprStmt) arm.body().getFirst()).expr())) {
                error(arm.body().getFirst().pos(), ErrorCode.SYNTAX, "this expression does nothing on its own; "
                        + "use its value, for example in a `val`, or remove it");
            }
            if (result != null) {
                statements(body);
            } else {
                scope = new Scope(scope, false);
                for (int i = 0; i < body.size(); i++) {
                    if (i == body.size() - 1 && body.get(i) instanceof Stmt.ExprStmt value) {
                        expr(value.expr());
                    } else {
                        statement(body.get(i));
                    }
                }
                scope = scope.parent;
            }
        }
        valueWhenDepth = outerDepth;
        scope = scope.parent;
    }

    private void checkArms(Expr.When w, boolean needsElse) {
        boolean sawElse = false;
        boolean types = false;
        boolean constants = false;
        for (Expr.Arm arm : w.arms()) {
            if (sawElse) {
                error(arm.pos(), ErrorCode.SYNTAX, "`else` must be the last branch of a `when`");
            }
            if (arm.conditions().isEmpty()) {
                sawElse = true;
            }
            for (Expr.Cond condition : arm.conditions()) {
                types |= condition.type() != null;
                constants |= condition.type() == null;
            }
        }
        if (types && constants) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` tests either types with `is` or constants, not both");
        } else if (constants && needsElse && !sawElse) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` over constants that gives a value needs an `else` branch");
        }
    }

    private void pipe(Expr.Pipe pipe) {
        TypeRef declared = declaredType;
        declaredType = null;
        expr(pipe.source());
        List<Expr.Stage> stages = pipe.stages();
        for (int i = 0; i < stages.size(); i++) {
            Expr.Stage stage = stages.get(i);
            String name = stage.name();
            boolean hasLambda = stage.lambda() != null;
            if (!STAGES_WITH_LAMBDA.contains(name) && !STAGES_WITH_ARGUMENT.contains(name)
                    && !STAGES_WITHOUT_ARGUMENTS.contains(name) && !name.equals("sorted")) {
                error(stage.pos(), ErrorCode.PIPE_STAGE, "unknown pipe stage `" + name + "`; use filter, map, mapResult, peek, "
                        + "sorted, distinct, limit, skip, forEach, anyMatch, allMatch, noneMatch, count or first");
            } else if (STAGES_WITH_LAMBDA.contains(name) && (!hasLambda || !stage.args().isEmpty())) {
                error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes a lambda: `|> " + name + " { ... }`");
            } else if (STAGES_WITH_ARGUMENT.contains(name) && (hasLambda || stage.args().size() != 1)) {
                error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes one argument: `|> " + name + "(n)`");
            } else if ((STAGES_WITHOUT_ARGUMENTS.contains(name) || name.equals("sorted")) && !stage.args().isEmpty()
                    || STAGES_WITHOUT_ARGUMENTS.contains(name) && hasLambda) {
                error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes no arguments");
            } else if (TERMINAL_STAGES.contains(name) && i < stages.size() - 1) {
                error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` ends a pipe and must be its last stage");
            }
            if (name.equals("mapResult") && hasLambda) {
                mapResult(stage, i == stages.size() - 1 ? declared : null);
            }
            stage.args().forEach(this::expr);
            if (hasLambda) {
                // Every stage but `sorted` passes one element, so a lambda without parameters takes `it`.
                lambda(stage.lambda(), !name.equals("sorted"));
            }
        }
    }

    /** The loop of `mapResult` needs the element type of the list it builds. */
    private void mapResult(Expr.Stage stage, TypeRef declared) {
        checkTryPlace(stage.pos(), "`mapResult`");
        if (stage.lambda().body().size() != 1 || !(stage.lambda().body().getFirst() instanceof Stmt.ExprStmt)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "the lambda of `mapResult` must be a single expression");
            return;
        }
        TypeRef element = MapResultTypes.elementType(stage, file, declared);
        if (element == null) {
            error(stage.pos(), ErrorCode.TYPE_REQUIRED, "`mapResult` needs the element type: call a function of this file "
                    + "that returns `Result<T, E>`, or declare the result as `val name: List<T> = ...`");
        } else {
            mapResultTypes.put(stage, element);
        }
    }

    private static boolean hasSafeStep(Expr expr) {
        return switch (expr) {
            case Expr.Member m -> m.safe() || hasSafeStep(m.target());
            case Expr.Call c -> c.safe() || c.target() != null && hasSafeStep(c.target());
            default -> false;
        };
    }

    private void lambda(Expr.Lambda lambda, boolean takesElement) {
        scope = new Scope(scope, false);
        lambdaDepth++;
        for (Expr.Param p : lambda.params()) {
            declare(p.pos(), p.name(), Kind.PARAM);
        }
        Symbol outerIt = localLookup("it");
        if (!lambda.arrow() && takesElement && outerIt != null) {
            error(lambda.pos(), ErrorCode.SHADOWING, "this lambda's `it` would shadow the `it` declared at line "
                    + outerIt.pos().line() + "; name the parameter: `{ x -> ... }`");
        } else if (!lambda.arrow() && outerIt == null && (takesElement || mentionsIt(lambda.body()))) {
            implicitIt.add(lambda);
            scope.names.put("it", new Symbol(Kind.PARAM, lambda.pos()));
        }
        if (lambda.body().size() == 1 && lambda.body().getFirst() instanceof Stmt.ExprStmt single
                && !(single.expr() instanceof Expr.Try)) {
            // A lambda of one expression returns its value, so the expression need not do anything.
            expressionLambdas++;
            expr(single.expr());
            expressionLambdas--;
        } else {
            Stmt outerLast = lastInLambda;
            int outerExpressionLambdas = expressionLambdas;
            expressionLambdas = 0;
            lastInLambda = lambda.body().isEmpty() ? null : lambda.body().getLast();
            statements(lambda.body());
            lastInLambda = outerLast;
            expressionLambdas = outerExpressionLambdas;
        }
        lambdaDepth--;
        scope = scope.parent;
    }

    private static boolean isMain(Decl.Function fn) {
        return fn.name().equals("main") && fn.params().isEmpty() && fn.returnType() == null && !fn.isPrivate();
    }

    /** A local, parameter or lambda parameter; file-level names do not count. */
    private Symbol localLookup(String name) {
        for (Scope s = scope; s != null && !s.fileScope; s = s.parent) {
            Symbol symbol = s.names.get(name);
            if (symbol != null) {
                return symbol;
            }
        }
        return null;
    }

    private void declare(Pos pos, String name, Kind kind) {
        declare(pos, name, kind, null);
    }

    private void declare(Pos pos, String name, Kind kind, TypeRef type) {
        checkReserved(pos, name);
        for (Scope s = scope; s != null && !s.fileScope; s = s.parent) {
            Symbol existing = s.names.get(name);
            if (existing == null) {
                continue;
            }
            if (s == scope) {
                error(pos, ErrorCode.DUPLICATE_NAME, "`" + name + "` is already declared at line " + existing.pos().line());
            } else {
                error(pos, ErrorCode.SHADOWING, "`" + name + "` shadows the `" + name + "` declared at line "
                        + existing.pos().line() + "; choose another name");
            }
            break;
        }
        scope.names.put(name, new Symbol(kind, pos, type));
    }

    private void checkFunctionName(Decl.Function fn) {
        String signature = fn.name() + "/" + fn.params().size();
        if (fn.name().equals("Ok") || fn.name().equals("Err")) {
            error(fn.namePos(), ErrorCode.RESERVED_NAME, "`" + fn.name() + "(...)` makes a `Result`, so a function of "
                    + "this name would never be called; choose another name");
        } else if (OBJECT_METHODS.contains(signature)) {
            error(fn.namePos(), ErrorCode.RESERVED_NAME, "every Java object has a method `" + fn.name()
                    + "` with as many parameters, which a function cannot replace; choose another name");
        }
    }

    private void checkReserved(Pos pos, String name) {
        if (RESERVED.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` is a reserved word in Java and cannot be used as a name");
        } else if (PACKAGE_ROOTS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the `" + name
                    + "` package that the generated code uses; choose another name");
        } else if (GENERATED_CODE_RECEIVERS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the class `" + name
                    + "` that the generated code uses; choose another name");
        } else if (name.equals("args") && function != null && isMain(function)) {
            error(pos, ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` method; choose another name");
        }
    }

    private static boolean mentionsIt(List<Stmt> statements) {
        return statements.stream().anyMatch(ItFinder::usesIt);
    }

    private void error(Pos pos, ErrorCode code, String message) {
        diagnostics.add(AxlDiagnostic.error(file.path(), pos.line(), pos.column(), code, message));
    }

    private static boolean isJavaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().allMatch(Character::isJavaIdentifierPart) && name.indexOf('$') < 0;
    }
}
