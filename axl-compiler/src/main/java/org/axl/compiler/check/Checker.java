package org.axl.compiler.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

/**
 * Name and placement rules that need no types: scopes, shadowing, where `try` may appear,
 * mandatory type annotations. Also records what the generator needs to know about names.
 */
public final class Checker {

    public static final String TRY_IN_EXPRESSION =
            "`try` is only allowed as `val x = try e` or as a statement; move `try` into a separate `val`";
    public static final String RETURN_TRY =
            "`return try e` makes no sense: `try` unwraps the value, but the function returns `Result`; write `return e`";
    public static final String TRY_NEEDS_RESULT = "`try` can only be used in a function that returns `Result`";

    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "enum", "extends", "final", "finally", "float", "for",
            "goto", "implements", "instanceof", "int", "interface", "long", "native", "new", "protected",
            "public", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "void", "volatile", "_");

    private enum Kind { LOCAL, MUTABLE_LOCAL, PARAM, TOP_LEVEL }

    private record Symbol(Kind kind, Pos pos) {}

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
    private Scope scope;
    private Decl.Function function;
    private int lambdaDepth;

    private Checker(SourceFile file) {
        this.file = file;
    }

    public static Analysis check(SourceFile file, List<AxlDiagnostic> diagnostics) {
        Checker checker = new Checker(file);
        checker.run();
        diagnostics.addAll(checker.diagnostics);
        return new Analysis(checker.implicitIt, checker.mutableRefs);
    }

    private void run() {
        if (file.packageName() == null) {
            error(new Pos(1, 1), ErrorCode.MISSING_PACKAGE, "missing `package` declaration: every .axl file starts with `package name`");
        }
        if (!isJavaIdentifier(file.className()) || RESERVED.contains(file.className())) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME,
                    "file name `" + file.className() + "` is not a valid class name");
        }
        scope = new Scope(null, true);
        for (Import imp : file.imports()) {
            if (imp.kind() != Import.Kind.TYPE) {
                scope.names.putIfAbsent(imp.simpleName(), new Symbol(Kind.TOP_LEVEL, imp.pos()));
            }
        }
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Value value) {
                Symbol existing = scope.names.get(value.name());
                if (existing != null) {
                    error(value.pos(), ErrorCode.DUPLICATE_NAME,
                            "`" + value.name() + "` is already declared at line " + existing.pos().line());
                }
                scope.names.put(value.name(), new Symbol(Kind.TOP_LEVEL, value.pos()));
            } else {
                scope.names.putIfAbsent(decl.name(), new Symbol(Kind.TOP_LEVEL, decl.pos()));
            }
            checkReserved(decl.pos(), decl.name());
        }
        for (Decl decl : file.decls()) {
            switch (decl) {
                case Decl.Value value -> topLevelValue(value);
                case Decl.Function fn -> function(fn);
            }
        }
    }

    private void topLevelValue(Decl.Value value) {
        if (value.type() == null) {
            error(value.pos(), ErrorCode.TYPE_REQUIRED, "top-level `val " + value.name() + "` needs a type: `val "
                    + value.name() + ": Type = ...`");
        }
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
            if (fn.exprBody() instanceof Expr.Try t) {
                if (fn.returnType() == null) {
                    statementTry(t);
                } else {
                    error(t.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
                    expr(t.inner());
                }
            } else {
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
                boolean needsType = local.init() instanceof Expr.Lambda
                        || local.init() instanceof Expr.Literal l && l.text().equals("null");
                if (needsType && local.type() == null) {
                    String keyword = local.mutable() ? "var" : "val";
                    error(local.pos(), ErrorCode.TYPE_REQUIRED, "`" + keyword + " " + local.name()
                            + "` needs a type when it is initialized with " + (local.init() instanceof Expr.Lambda
                            ? "a lambda" : "`null`") + ": `" + keyword + " " + local.name() + ": Type = ...`");
                }
                if (local.init() instanceof Expr.Try t) {
                    statementTry(t);
                } else {
                    expr(local.init());
                }
                declare(local.pos(), local.name(), local.mutable() ? Kind.MUTABLE_LOCAL : Kind.LOCAL);
            }
            case Stmt.Assign assign -> {
                if (assign.target() instanceof Expr.Name name) {
                    Symbol symbol = scope.lookup(name.name());
                    if (symbol != null && symbol.kind != Kind.MUTABLE_LOCAL) {
                        String what = symbol.kind == Kind.PARAM ? "parameter" : "`val`";
                        error(assign.pos(), ErrorCode.ASSIGN_TO_VAL, "cannot assign to " + what + " `" + name.name()
                                + "`; declare it with `var`");
                    }
                }
                expr(assign.target());
                expr(assign.value());
            }
            case Stmt.Return ret -> {
                if (ret.value() instanceof Expr.Try t) {
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
            case Stmt.ExprStmt exprStmt -> {
                if (exprStmt.expr() instanceof Expr.Try t) {
                    statementTry(t);
                } else {
                    expr(exprStmt.expr());
                }
            }
            case Stmt.Block block -> statements(block.statements());
        }
    }

    /** A `try` in one of the two allowed statement positions. */
    private void statementTry(Expr.Try t) {
        if (lambdaDepth > 0) {
            error(t.pos(), ErrorCode.TRY_IN_EXPRESSION, TRY_IN_EXPRESSION);
        } else if (!returnsResult(function)) {
            error(t.pos(), ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, TRY_NEEDS_RESULT);
        }
        expr(t.inner());
    }

    private static boolean returnsResult(Decl.Function fn) {
        return fn != null && fn.returnType() != null && !fn.returnType().nullable()
                && (fn.returnType().name().equals("Result") || fn.returnType().name().equals("axl.lang.Result"));
    }

    private void expr(Expr expr) {
        switch (expr) {
            case Expr.Literal l -> { }
            case Expr.Name name -> {
                Symbol symbol = scope.lookup(name.name());
                if (symbol != null && symbol.kind == Kind.MUTABLE_LOCAL) {
                    mutableRefs.add(name);
                }
            }
            case Expr.Member m -> expr(m.target());
            case Expr.Call call -> {
                if (call.target() != null) {
                    expr(call.target());
                }
                call.args().forEach(this::expr);
            }
            case Expr.GenericNew g -> g.args().forEach(this::expr);
            case Expr.MethodRef r -> expr(r.target());
            case Expr.Unary u -> expr(u.operand());
            case Expr.Binary b -> {
                expr(b.left());
                expr(b.right());
            }
            case Expr.Elvis e -> {
                expr(e.left());
                expr(e.right());
            }
            case Expr.Paren p -> expr(p.inner());
            case Expr.Try t -> {
                error(t.pos(), ErrorCode.TRY_IN_EXPRESSION, TRY_IN_EXPRESSION);
                expr(t.inner());
            }
            case Expr.Attempt a -> {
                expr(a.body());
                scope = new Scope(scope, false);
                if (a.errorName() != null) {
                    declare(a.pos(), a.errorName(), Kind.PARAM);
                }
                expr(a.handler());
                scope = scope.parent;
            }
            case Expr.Lambda lambda -> lambda(lambda);
        }
    }

    private void lambda(Expr.Lambda lambda) {
        scope = new Scope(scope, false);
        lambdaDepth++;
        for (Expr.Param p : lambda.params()) {
            declare(p.pos(), p.name(), Kind.PARAM);
        }
        if (!lambda.arrow() && scope.lookup("it") == null && mentionsIt(lambda.body())) {
            implicitIt.add(lambda);
            scope.names.put("it", new Symbol(Kind.PARAM, lambda.pos()));
        }
        statements(lambda.body());
        lambdaDepth--;
        scope = scope.parent;
    }

    private void declare(Pos pos, String name, Kind kind) {
        checkReserved(pos, name);
        for (Scope s = scope; s != null && !s.fileScope; s = s.parent) {
            Symbol existing = s.names.get(name);
            if (existing == null) {
                continue;
            }
            if (s == scope) {
                error(pos, ErrorCode.DUPLICATE_NAME, "`" + name + "` is already declared at line " + existing.pos().line());
            } else {
                error(pos, ErrorCode.SHADOWING, "`" + name + "` shadows the local variable declared at line "
                        + existing.pos().line() + "; choose another name");
            }
            break;
        }
        scope.names.put(name, new Symbol(kind, pos));
    }

    private void checkReserved(Pos pos, String name) {
        if (RESERVED.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` is a reserved word in Java and cannot be used as a name");
        }
    }

    private static boolean mentionsIt(List<Stmt> statements) {
        return statements.stream().anyMatch(new ItFinder()::in);
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
