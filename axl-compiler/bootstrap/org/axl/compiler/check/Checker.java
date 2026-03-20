package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
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

@NullMarked public final class Checker {
    public final SourceFile file;
    public final ArrayList<AxlDiagnostic> diagnostics;
    public final Set<Expr.Lambda> implicitIt;
    public final Set<Expr.Name> mutableRefs;
    public final Set<Expr.Name> stableNames;
    public final Set<Expr.Name> staticNames;
    public final HashSet<String> typeNames;
    public final IdentityHashMap<Expr.When, Boolean> whenResults;
    public int valueWhenDepth;
    public int expressionLambdas;
    public int conditionalDepth;
    public final Set<Expr.Throw> allowedThrows;
    public final IdentityHashMap<Expr.Stage, TypeRef> mapResultTypes;
    public Scope scope;
    public Decl.@Nullable Function current;
    public @Nullable Stmt lastInLambda;
    public int lambdaDepth;
    public int safeTailDepth;
    public int attemptDepth;
    public @Nullable TypeRef declaredType;
    public Checker(SourceFile file) {
        this.file = file;
        this.diagnostics = new ArrayList<AxlDiagnostic>();
        this.implicitIt = Collections.newSetFromMap(new IdentityHashMap<Expr.Lambda, Boolean>());
        this.mutableRefs = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.stableNames = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.staticNames = Collections.newSetFromMap(new IdentityHashMap<Expr.Name, Boolean>());
        this.typeNames = new HashSet<String>();
        this.whenResults = new IdentityHashMap<Expr.When, Boolean>();
        this.valueWhenDepth = -1;
        this.expressionLambdas = 0;
        this.conditionalDepth = 0;
        this.allowedThrows = Collections.newSetFromMap(new IdentityHashMap<Expr.Throw, Boolean>());
        this.mapResultTypes = new IdentityHashMap<Expr.Stage, TypeRef>();
        this.scope = new Scope(null, true);
        this.current = null;
        this.lastInLambda = null;
        this.lambdaDepth = 0;
        this.safeTailDepth = 0;
        this.attemptDepth = 0;
        this.declaredType = null;
    }
    public void run() {
        checkFileName();
        checkPackage();
        checkImports();
        declareTopLevel();
        for (final var decl : file.decls()) {
            switch (decl) {
                case Decl.Value $s1 -> {
                    topLevelValue($s1);
                }
                case Decl.Function $s2 -> {
                    function($s2);
                }
                case Decl.ClassDecl $s3 -> {
                    classDecl($s3);
                }
                case Decl.EnumDecl $s4 -> {
                }
                case Decl.DataClass $s5 -> {
                    members($s5.fields(), "field");
                }
                case Decl.Interface $s6 -> {
                    members($s6.members(), "member");
                }
            }
        }
        checkInitOrder();
        for (final var mismatch : Implementations.check(file)) {
            error(mismatch.pos(), ErrorCode.INTERFACE_MEMBER, mismatch.message());
        }
    }
    private void checkFileName() {
        final var name = file.className();
        if (!isJavaIdentifier(name) || RESERVED.contains(name)) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name + "` is not a valid class name");
        } else if (PACKAGE_ROOTS.contains(name)) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name + "` would hide the `" +
                    name +
                    "` package that the generated code uses; rename the file");
        } else if (GENERATED_CODE_CLASSES.contains(name)) {
            error(new Pos(1, 1), ErrorCode.INVALID_FILE_NAME, "file name `" + name +
                    "` would hide the class `" +
                    name +
                    "` that the generated code uses; rename the file");
        }
    }
    private void checkPackage() {
        final var packageName = file.packageName();
        if (packageName == null) {
            error(new Pos(1, 1), ErrorCode.MISSING_PACKAGE,
                    "missing `package` declaration: every .axl file starts with `package name`");
            return;
        }
        for (final var segment : packageName.split("\\.")) {
            if (RESERVED.contains(segment)) {
                error(Nulls.or(file.packagePos(), () -> new Pos(1, 1)), ErrorCode.RESERVED_NAME, "`" + segment +
                        "` is a reserved word in Java and cannot be part of a package name");
            }
        }
    }
    private void checkImports() {
        for (final var imp : file.imports()) {
            if (imp.kind() != Import.Kind.TYPE) {
                scope.names.putIfAbsent(imp.simpleName(), new Symbol(Kind.IMPORTED, imp.pos(), null));
            } else if (hidesGeneratedClass(imp)) {
                error(imp.pos(), ErrorCode.RESERVED_NAME, "`" + imp.simpleName() + "` would hide the class `" +
                        imp.simpleName() +
                        "` that the generated code uses; write `" +
                        imp.name() +
                        "` in full instead");
            }
        }
    }
    private boolean hidesGeneratedClass(Import imp) {
        if (!GENERATED_CODE_CLASSES.contains(imp.simpleName())) {
            return false;
        }
        final var packageName = imp.name().substring(0, Math.max(imp.name().lastIndexOf("."), 0));
        return !GENERATED_CODE_PACKAGES.contains(packageName);
    }
    private void declareTopLevel() {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1) {
                final var existing = scope.names.get($s1.name());
                if (existing != null) {
                    error($s1.namePos(), ErrorCode.DUPLICATE_NAME,
                            "`" + $s1.name() + "` is already declared at line " + existing.pos().line());
                }
                scope.names.put($s1.name(), new Symbol(Kind.TOP_LEVEL, $s1.pos(), null));
            } else if (isTypeDecl(decl)) {
                checkTypeName(decl);
            } else {
                scope.names.putIfAbsent(decl.name(), new Symbol(Kind.TOP_LEVEL, decl.pos(), null));
            }
            checkReserved(decl.namePos(), decl.name());
            if (decl instanceof Decl.Function $s2) {
                checkFunctionName($s2);
            }
            if (decl instanceof Decl.Value $s3) {
                checkValueName($s3);
            }
        }
    }
    private void checkValueName(Decl.Value value) {
        if (value.isPrivate() && fileIsInterface()) {
            error(value.namePos(), ErrorCode.SYNTAX, "a value in the file of an interface cannot be private");
        }
        if (value.name().equals("args") && hasMain()) {
            error(value.namePos(), ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` " +
                    "method, which would read it instead of this value; choose another name");
        }
    }
    private boolean hasMain() {
        return file.decls().stream().anyMatch(d -> d instanceof Decl.Function $s1 && isMain($s1));
    }
    private void checkInitOrder() {
        for (final var problem : InitOrder.check(file)) {
            final var value = problem.value();
            final var position = (problem.later().equals(value.name()) ? "by itself" : "later");
            error(value.namePos(), ErrorCode.INIT_ORDER, "`" + value.name() + "` is computed from `" + problem.later() +
                    "`, which is declared " +
                    position +
                    "; top-level values are set in order, so declare `" +
                    problem.later() +
                    "` first");
        }
        for (final var problem : InitOrder.checkProperties(file)) {
            final var property = problem.property();
            final var position = (problem.later().equals(property.name()) ? "by itself" : "later");
            error(property.namePos(), ErrorCode.INIT_ORDER, "`" + property.name() + "` is computed from `" +
                    problem.later() +
                    "`, which is declared " +
                    position +
                    "; properties are set in order, so declare `" +
                    problem.later() +
                    "` first");
        }
    }
    private void classDecl(Decl.ClassDecl c) {
        scope = new Scope(scope, false);
        for (final var p : c.parameters()) {
            declare(p.namePos(), p.name(), fieldKind(p), p.type());
        }
        for (final var p : c.properties()) {
            if (p.type() == null) {
                final var keyword = (p.mutable() ? "var " : "val ");
                error(p.pos(), ErrorCode.TYPE_REQUIRED, "a property needs a type: `" + keyword + p.name() + ": Type = ...`");
            }
            declare(p.namePos(), p.name(), fieldKind(p), p.type());
        }
        for (final var p : c.properties()) {
            final var init = p.init();
            if (init != null) {
                expr(init);
            }
        }
        for (final var m : c.methods()) {
            function(m);
        }
        pop();
    }
    private Kind fieldKind(Decl.Property p) {
        return (p.mutable() ? Kind.MUTABLE_FIELD : Kind.FIELD);
    }
    private void pop() {
        scope = java.util.Objects.requireNonNull(scope.parent);
    }
    private boolean fileIsInterface() {
        return file.decls().stream().anyMatch(d -> d instanceof Decl.Interface $s1 && $s1.name().equals(file.className()));
    }
    private void checkTypeName(Decl decl) {
        final var name = decl.name();
        if (!Character.isUpperCase(name.charAt(0))) {
            error(decl.namePos(), ErrorCode.SYNTAX, "the name of a type starts with a capital letter, as `" +
                    Character.toUpperCase(name.charAt(0)) +
                    name.substring(1) +
                    "`: `Name(...)` creates an object");
        } else if (name.equals(file.className()) && decl.isPrivate()) {
            error(decl.namePos(), ErrorCode.SYNTAX, "`" + name + "` is the type of this file and cannot be private");
        } else if (!typeNames.add(name)) {
            error(decl.namePos(), ErrorCode.DUPLICATE_NAME, "the type `" + name + "` is already declared in this file");
        }
    }
    private void members(List<Expr.Param> list, String what) {
        final var seen = new HashSet<String>();
        for (final var member : list) {
            if (!seen.add(member.name())) {
                error(member.pos(), ErrorCode.DUPLICATE_NAME, "the " + what + " `" + member.name() + "` is declared twice");
            } else if (OBJECT_METHOD_NAMES.contains(member.name())) {
                error(member.pos(), ErrorCode.RESERVED_NAME, "`" + member.name() + "` is a method of every Java object " +
                        "and cannot be the name of a " +
                        what +
                        "; choose another name");
            }
        }
    }
    private void topLevelValue(Decl.Value value) {
        final var init = value.init();
        if (init instanceof Expr.Try $s1) {
            error($s1.pos(), ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, TRY_NEEDS_RESULT);
            expr($s1.inner());
        } else {
            expr(init);
        }
    }
    private void function(Decl.Function f) {
        current = f;
        scope = new Scope(scope, false);
        for (final var p : f.params()) {
            declare(p.pos(), p.name(), Kind.PARAM, null);
        }
        final var exprBody = f.exprBody();
        final var body = f.body();
        if (exprBody != null) {
            expressionBody(f, exprBody);
        } else if (body != null) {
            statements(body.statements());
        }
        pop();
        current = null;
    }
    private void expressionBody(Decl.Function f, Expr body) {
        final var inner = unparenthesized(body);
        if (inner instanceof Expr.When $s1) {
            whenResults.put($s1, f.returnType() != null);
        }
        if (body instanceof Expr.Try $s2) {
            if (!returnsResult(f)) {
                statementTry($s2);
            } else {
                error($s2.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
                expr($s2.inner());
            }
            return;
        }
        if (f.returnType() == null && !doesSomething(body)) {
            error(body.pos(), ErrorCode.SYNTAX, "a function without a result type runs its body as a " +
                    "statement, where this expression does nothing; to return it, add the type: `-> Type`");
        }
        expr(body);
    }
    private void statements(List<Stmt> list) {
        scope = new Scope(scope, false);
        for (final var stmt : list) {
            statement(stmt);
        }
        pop();
    }
    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local $s1 -> {
                local($s1);
            }
            case Stmt.Assign $s2 -> {
                assign($s2);
            }
            case Stmt.Return $s3 -> {
                returnStatement($s3);
            }
            case Stmt.If $s4 -> {
                expr($s4.condition());
                statements($s4.then().statements());
                final var elseBranch = $s4.elseBranch();
                if (elseBranch != null) {
                    statement(elseBranch);
                }
            }
            case Stmt.While $s5 -> {
                expr($s5.condition());
                statements($s5.body().statements());
            }
            case Stmt.For $s6 -> {
                expr($s6.iterable());
                scope = new Scope(scope, false);
                declare($s6.namePos(), $s6.name(), Kind.LOCAL, null);
                statements($s6.body().statements());
                pop();
            }
            case Stmt.ExprStmt $s7 -> {
                expressionStatement($s7);
            }
            case Stmt.Block $s8 -> {
                statements($s8.statements());
            }
        }
    }
    private void local(Stmt.Local stmt) {
        final var init = unparenthesized(stmt.init());
        if ((init instanceof Expr.Lambda $s1 || isNull(init)) && stmt.type() == null) {
            final var keyword = (stmt.mutable() ? "var" : "val");
            final var what = (init instanceof Expr.Lambda $s2 ? "a lambda" : "`null`");
            error(stmt.pos(), ErrorCode.TYPE_REQUIRED, "`" + keyword + " " + stmt.name() +
                    "` needs a type when it is initialized with " +
                    what +
                    ": `" +
                    keyword +
                    " " +
                    stmt.name() +
                    ": Type = ...`");
        }
        declaredType = stmt.type();
        final var value = stmt.init();
        if (value instanceof Expr.Try $s3) {
            statementTry($s3);
        } else {
            expr(value);
        }
        declaredType = null;
        final var type = stmt.type();
        if (type != null && !type.nullable() && isNull(value)) {
            error(value.pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable(stmt.name(), type));
        }
        final var kind = (stmt.mutable() ? Kind.MUTABLE_LOCAL : Kind.LOCAL);
        declare(stmt.namePos(), stmt.name(), kind, type);
    }
    private void assign(Stmt.Assign stmt) {
        final var target = stmt.target();
        if (target instanceof Expr.Name $s1) {
            final var symbol = scope.lookup($s1.name());
            final var notNull = declaredNotNull(symbol);
            if (symbol != null && symbol.kind() != Kind.MUTABLE_LOCAL && symbol.kind() != Kind.MUTABLE_FIELD) {
                error(stmt.pos(), ErrorCode.ASSIGN_TO_VAL, assignmentProblem($s1.name(), symbol));
            } else if (notNull != null && isNull(stmt.value())) {
                error(stmt.value().pos(), ErrorCode.NULLABLE_DEREFERENCE, notNullable($s1.name(), notNull));
            }
        }
        expr(target);
        expr(stmt.value());
    }
    private @Nullable TypeRef declaredNotNull(@Nullable Symbol symbol) {
        if (symbol == null) {
            return null;
        }
        final var type = symbol.type();
        if (type != null && !type.nullable()) {
            return type;
        }
        return null;
    }
    private void returnStatement(Stmt.Return stmt) {
        if (valueWhenDepth == lambdaDepth) {
            error(stmt.pos(), ErrorCode.SYNTAX, "`return` cannot leave a `when` that gives a value; " +
                    "write the `when` as a statement or as `return when ...`");
        }
        final var value = stmt.value();
        if (value == null) {
            return;
        }
        final var inner = unparenthesized(value);
        if (inner instanceof Expr.When $s1) {
            whenResults.put($s1, true);
        }
        if (!(value instanceof Expr.Try $s2)) {
            expr(value);
        } else if (lambdaDepth > 0 || !returnsResult(current)) {
            statementTry($s2);
        } else {
            error($s2.pos(), ErrorCode.RETURN_TRY, RETURN_TRY);
            expr($s2.inner());
        }
    }
    private void expressionStatement(Stmt.ExprStmt stmt) {
        final var e = stmt.expr();
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.When $s1) {
            whenResults.put($s1, false);
        }
        if (e instanceof Expr.Throw $s2) {
            allowedThrows.add($s2);
        }
        if (e instanceof Expr.Lambda $s3) {
            error($s3.pos(), ErrorCode.SYNTAX, "a lambda on its own does nothing; to run these statements, remove the braces");
        } else if (!doesSomething(e)) {
            final var hint = (stmt == lastInLambda ? "to return it from the lambda, write `return` before it" : "use its value, for example in a `val`, or remove it");
            error(e.pos(), ErrorCode.SYNTAX, "this expression does nothing on its own; " + hint);
        }
        if (e instanceof Expr.Try $s4) {
            statementTry($s4);
        } else {
            expr(e);
        }
    }
    private String assignmentProblem(String name, Symbol symbol) {
        final var kind = symbol.kind();
        if (kind == Kind.PARAM) {
            return "cannot assign to the parameter `" + name + "`; copy it into a `var` first: `var current = " + name + "`";
        }
        if (kind == Kind.TOP_LEVEL) {
            return topLevelProblem(name);
        }
        if (kind == Kind.FIELD) {
            return "cannot assign to the `val` property `" + name + "`; declare it with `var`";
        }
        return "cannot assign to `val` `" + name + "`; declare it with `var`";
    }
    private String topLevelProblem(String name) {
        final var isFunction = file.decls().stream().anyMatch(d -> d instanceof Decl.Function $s1 && $s1.name().equals(name));
        if (isFunction) {
            return "`" + name + "` is a function, not a variable";
        }
        return "cannot assign to the top-level value `" + name + "`; top-level values cannot change";
    }
    private void statementTry(Expr.Try t) {
        checkTryPlace(t.pos(), "`try`");
        expr(t.inner());
    }
    private void checkTryPlace(Pos pos, String what) {
        final var isTry = what.equals("`try`");
        if (conditionalDepth > 0) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what + " cannot be used in an `if` that gives a value; " +
                    "write an `if` statement");
        } else if (valueWhenDepth == lambdaDepth) {
            error(pos, ErrorCode.TRY_IN_EXPRESSION, what + " cannot be used in a `when` that gives a value, since it " +
                    "would return from the function in the middle of the `when`; write the `when` as a statement " +
                    "or as `return when ...`");
        } else if (lambdaDepth > 0) {
            final var message = (isTry ? TRY_IN_LAMBDA : what + " cannot be used in a lambda: it would return from the lambda, not the function");
            error(pos, ErrorCode.TRY_IN_EXPRESSION, message);
        } else if (safeTailDepth > 0) {
            final var message = (isTry ? TRY_AFTER_SAFE_CALL : what + " after `?.` runs only for a non-null value; move it into a separate `val`");
            error(pos, ErrorCode.TRY_IN_EXPRESSION, message);
        } else if (returnsNullableResult()) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " needs a function that returns `Result` without `?`; " +
                    "this one may return null instead");
        } else if (!returnsResult(current)) {
            error(pos, ErrorCode.TRY_OUTSIDE_RESULT_FUNCTION, what + " can only be used in a function that returns `Result`");
        }
    }
    private boolean returnsNullableResult() {
        final var f = current;
        if (f == null) {
            return false;
        }
        final var type = f.returnType();
        return type != null && type.nullable() && isResult(type);
    }
    private void expr(Expr e) {
        switch (e) {
            case Expr.Literal $s1 -> {
            }
            case Expr.Name $s2 -> {
                name($s2);
            }
            case Expr.Member $s3 -> {
                expr($s3.target());
            }
            case Expr.Call $s4 -> {
                call($s4);
            }
            case Expr.GenericNew $s5 -> {
                exprs($s5.args());
            }
            case Expr.MethodRef $s6 -> {
                expr($s6.target());
            }
            case Expr.NotNull $s7 -> {
                expr($s7.target());
            }
            case Expr.Unary $s8 -> {
                expr($s8.operand());
            }
            case Expr.Binary $s9 -> {
                expr($s9.left());
                expr($s9.right());
            }
            case Expr.Elvis $s10 -> {
                expr($s10.left());
                expr($s10.right());
            }
            case Expr.Is $s11 -> {
                expr($s11.target());
            }
            case Expr.Conditional $s12 -> {
                expr($s12.condition());
                conditionalDepth = conditionalDepth + 1;
                expr($s12.then());
                expr($s12.otherwise());
                conditionalDepth = conditionalDepth - 1;
            }
            case Expr.Throw $s13 -> {
                if (!allowedThrows.contains($s13)) {
                    error($s13.pos(), ErrorCode.SYNTAX, "`throw` can stand as a statement or as a branch of a `when`, " +
                            "not inside an expression");
                }
                expr($s13.value());
            }
            case Expr.When $s14 -> {
                whenExpr($s14);
            }
            case Expr.Paren $s15 -> {
                expr($s15.inner());
            }
            case Expr.Try $s16 -> {
                checkTryPlace($s16.pos(), "`try`");
                expr($s16.inner());
            }
            case Expr.Attempt $s17 -> {
                attemptExpr($s17);
            }
            case Expr.Pipe $s18 -> {
                pipeExpr($s18);
            }
            case Expr.Lambda $s19 -> {
                lambdaExpr($s19, false);
            }
        }
    }
    private void exprs(List<Expr> list) {
        for (final var item : list) {
            expr(item);
        }
    }
    private void name(Expr.Name e) {
        final var symbol = scope.lookup(e.name());
        if (symbol == null) {
            staticNames.add(e);
            return;
        }
        if (symbol.kind() == Kind.MUTABLE_LOCAL) {
            mutableRefs.add(e);
        }
        if (symbol.kind() != Kind.IMPORTED && symbol.kind() != Kind.MUTABLE_FIELD) {
            stableNames.add(e);
        }
    }
    private void call(Expr.Call e) {
        final var target = e.target();
        if (target != null) {
            expr(target);
        } else if (localLookup(e.name()) != null) {
            error(e.pos(), ErrorCode.JAVAC, "`" + e.name() + "` is a value, not a function; " +
                    "call its method, such as `" +
                    e.name() +
                    ".apply(...)` or `" +
                    e.name() +
                    ".get()`");
        }
        final var safeTail = e.safe() || (target != null && hasSafeStep(target));
        if (safeTail) {
            safeTailDepth = safeTailDepth + 1;
        }
        exprs(e.args());
        if (safeTail) {
            safeTailDepth = safeTailDepth - 1;
        }
    }
    private void attemptExpr(Expr.Attempt e) {
        lambdaDepth = lambdaDepth + 1;
        attemptDepth = attemptDepth + 1;
        expr(e.body());
        scope = new Scope(scope, false);
        final var errorName = e.errorName();
        final var errorPos = e.errorNamePos();
        if (errorName != null && errorPos != null) {
            declare(errorPos, errorName, Kind.PARAM, null);
        }
        expr(e.handler());
        pop();
        attemptDepth = attemptDepth - 1;
        lambdaDepth = lambdaDepth - 1;
    }
    private void whenExpr(Expr.When w) {
        final var result = whenResults.get(w);
        final var gives = result == null;
        final var returns = result != null && result;
        checkWhenPlace(w, gives);
        expr(w.subject());
        scope = new Scope(scope, false);
        final var bindingName = w.bindingName();
        final var bindingPos = w.bindingPos();
        if (bindingName != null && bindingPos != null) {
            declare(bindingPos, bindingName, Kind.LOCAL, null);
        }
        checkArms(w, gives || returns);
        final var outerDepth = valueWhenDepth;
        if (gives) {
            valueWhenDepth = lambdaDepth;
        }
        for (final var arm : w.arms()) {
            checkArm(arm, gives, returns);
        }
        valueWhenDepth = outerDepth;
        pop();
    }
    private void checkWhenPlace(Expr.When w, boolean gives) {
        if (!gives) {
            return;
        }
        if (current == null) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value must be inside a function");
        } else if (conditionalDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside an `if` that gives a value");
        } else if (expressionLambdas > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside a lambda of one expression; " +
                    "give the lambda statements and write `return when ...`");
        } else if (attemptDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be inside `attempt`; " +
                    "compute it in a `val` first");
        } else if (safeTailDepth > 0) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` that gives a value cannot be an argument after `?.`; " +
                    "compute it in a `val` first");
        }
    }
    private void checkArm(Expr.Arm arm, boolean gives, boolean returns) {
        final var body = (returns ? Whens.returning(arm) : arm.body());
        if (gives && !endsWithValue(arm.body())) {
            error(arm.pos(), ErrorCode.SYNTAX, "each branch of a `when` that gives a value ends with the value");
        }
        if (!arm.block() && !gives && !returns) {
            final var first = arm.body().getFirst();
            if (first instanceof Stmt.ExprStmt $s1 && !doesSomething($s1.expr())) {
                error($s1.pos(), ErrorCode.SYNTAX, "this expression does nothing on its own; " +
                        "use its value, for example in a `val`, or remove it");
            }
        }
        if (gives) {
            valueArm(body);
        } else {
            statements(body);
        }
    }
    private boolean endsWithValue(List<Stmt> body) {
        return !body.isEmpty() && body.getLast() instanceof Stmt.ExprStmt;
    }
    private void valueArm(List<Stmt> body) {
        scope = new Scope(scope, false);
        var i = 0;
        while (i < body.size()) {
            final var stmt = body.get(i);
            if (i == body.size() - 1 && stmt instanceof Stmt.ExprStmt $s1) {
                final var value = $s1.expr();
                if (value instanceof Expr.Throw $s2) {
                    allowedThrows.add($s2);
                }
                expr(value);
            } else {
                statement(stmt);
            }
            i += 1;
        }
        pop();
    }
    private void checkArms(Expr.When w, boolean needsElse) {
        var sawElse = false;
        var types = false;
        var constants = false;
        for (final var arm : w.arms()) {
            if (sawElse) {
                error(arm.pos(), ErrorCode.SYNTAX, "`else` must be the last branch of a `when`");
            }
            if (arm.conditions().isEmpty()) {
                sawElse = true;
            }
            for (final var condition : arm.conditions()) {
                if (condition.type() != null) {
                    types = true;
                } else {
                    constants = true;
                }
            }
        }
        if (types && constants) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` tests either types with `is` or constants, not both");
        } else if (constants && needsElse && !sawElse) {
            error(w.pos(), ErrorCode.SYNTAX, "a `when` over constants that gives a value needs an `else` branch");
        }
    }
    private void pipeExpr(Expr.Pipe p) {
        final var declared = declaredType;
        declaredType = null;
        expr(p.source());
        final var stages = p.stages();
        var i = 0;
        while (i < stages.size()) {
            final var stage = stages.get(i);
            final var isLast = i == stages.size() - 1;
            final var lambda = stage.lambda();
            checkStage(stage, isLast);
            if (stage.name().equals("mapResult") && lambda != null) {
                mapResult(stage, lambda, (isLast ? declared : null));
            }
            exprs(stage.args());
            if (lambda != null) {
                lambdaExpr(lambda, !stage.name().equals("sorted"));
            }
            i += 1;
        }
    }
    private void checkStage(Expr.Stage stage, boolean isLast) {
        final var name = stage.name();
        final var hasLambda = stage.lambda() != null;
        final var noArguments = STAGES_WITHOUT_ARGUMENTS.contains(name);
        final var known = STAGES_WITH_LAMBDA.contains(name) || STAGES_WITH_ARGUMENT.contains(name) ||
                noArguments ||
                name.equals("sorted");
        if (!known) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "unknown pipe stage `" + name + "`; use filter, map, mapResult, peek, " +
                    "sorted, distinct, limit, skip, forEach, anyMatch, allMatch, noneMatch, count or first");
        } else if (STAGES_WITH_LAMBDA.contains(name) && (!hasLambda || !stage.args().isEmpty())) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes a lambda: `|> " + name + " { ... }`");
        } else if (STAGES_WITH_ARGUMENT.contains(name) && (hasLambda || stage.args().size() != 1)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes one argument: `|> " + name + "(n)`");
        } else if (((noArguments || name.equals("sorted")) && !stage.args().isEmpty()) || (noArguments && hasLambda)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` takes no arguments");
        } else if (TERMINAL_STAGES.contains(name) && !isLast) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "`" + name + "` ends a pipe and must be its last stage");
        }
    }
    private void mapResult(Expr.Stage stage, Expr.Lambda lambda, @Nullable TypeRef declared) {
        checkTryPlace(stage.pos(), "`mapResult`");
        final var body = lambda.body();
        if (body.size() != 1 || !(body.getFirst() instanceof Stmt.ExprStmt)) {
            error(stage.pos(), ErrorCode.PIPE_STAGE, "the lambda of `mapResult` must be a single expression");
            return;
        }
        final var element = MapResultTypes.elementType(stage, file, declared);
        if (element == null) {
            error(stage.pos(), ErrorCode.TYPE_REQUIRED, "`mapResult` needs the element type: call a function of this file " +
                    "that returns `Result<T, E>`, or declare the result as `val name: List<T> = ...`");
        } else {
            mapResultTypes.put(stage, element);
        }
    }
    private void lambdaExpr(Expr.Lambda l, boolean takesElement) {
        scope = new Scope(scope, false);
        lambdaDepth = lambdaDepth + 1;
        for (final var p : l.params()) {
            declare(p.pos(), p.name(), Kind.PARAM, null);
        }
        declareIt(l, takesElement);
        final var body = l.body();
        final var single = onlyExpression(body);
        if (single != null) {
            expressionLambdas = expressionLambdas + 1;
            if (single instanceof Expr.Throw $s1) {
                allowedThrows.add($s1);
            }
            expr(single);
            expressionLambdas = expressionLambdas - 1;
        } else {
            final var outerLast = lastInLambda;
            final var outerExpressionLambdas = expressionLambdas;
            final var outerAttempts = attemptDepth;
            final var outerSafeTails = safeTailDepth;
            expressionLambdas = 0;
            attemptDepth = 0;
            safeTailDepth = 0;
            lastInLambda = lastOf(body);
            statements(body);
            lastInLambda = outerLast;
            expressionLambdas = outerExpressionLambdas;
            attemptDepth = outerAttempts;
            safeTailDepth = outerSafeTails;
        }
        lambdaDepth = lambdaDepth - 1;
        pop();
    }
    private void declareIt(Expr.Lambda l, boolean takesElement) {
        final var outerIt = localLookup("it");
        if (l.arrow()) {
            return;
        }
        if (takesElement && outerIt != null) {
            error(l.pos(), ErrorCode.SHADOWING, "this lambda's `it` would shadow the `it` declared at line " +
                    outerIt.pos().line() +
                    "; name the parameter: `{ x -> ... }`");
        } else if (outerIt == null && (takesElement || mentionsIt(l.body()))) {
            implicitIt.add(l);
            scope.names.put("it", new Symbol(Kind.PARAM, l.pos(), null));
        }
    }
    private @Nullable Expr onlyExpression(List<Stmt> body) {
        if (body.size() != 1) {
            return null;
        }
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt $s1 && !($s1.expr() instanceof Expr.Try)) {
            return $s1.expr();
        }
        return null;
    }
    private @Nullable Stmt lastOf(List<Stmt> body) {
        if (body.isEmpty()) {
            return null;
        }
        return body.getLast();
    }
    private @Nullable Symbol localLookup(String name) {
        @Nullable Scope s = scope;
        while (s != null && !s.fileScope) {
            final var symbol = s.names.get(name);
            if (symbol != null && symbol.kind() != Kind.FIELD && symbol.kind() != Kind.MUTABLE_FIELD) {
                return symbol;
            }
            s = s.parent;
        }
        return null;
    }
    private void declare(Pos pos, String name, Kind kind, @Nullable TypeRef type) {
        checkReserved(pos, name);
        @Nullable Scope s = scope;
        while (s != null && !s.fileScope) {
            final var existing = s.names.get(name);
            if (existing == null) {
                s = s.parent;
            } else {
                if (s == scope) {
                    error(pos, ErrorCode.DUPLICATE_NAME, "`" + name + "` is already declared at line " + existing.pos().line());
                } else {
                    error(pos, ErrorCode.SHADOWING, "`" + name + "` shadows the `" + name + "` declared at line " +
                            existing.pos().line() +
                            "; choose another name");
                }
                s = null;
            }
        }
        scope.names.put(name, new Symbol(kind, pos, type));
    }
    private void checkFunctionName(Decl.Function f) {
        final var signature = f.name() + "/" + f.params().size();
        if (f.name().equals("Ok") || f.name().equals("Err")) {
            error(f.namePos(), ErrorCode.RESERVED_NAME, "`" + f.name() + "(...)` makes a `Result`, so a function of " +
                    "this name would never be called; choose another name");
        } else if (OBJECT_METHODS.contains(signature)) {
            error(f.namePos(), ErrorCode.RESERVED_NAME, "every Java object has a method `" + f.name() +
                    "` with as many parameters, which a function cannot replace; choose another name");
        }
    }
    private void checkReserved(Pos pos, String name) {
        if (RESERVED.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` is a reserved word in Java and cannot be used as a name");
        } else if (PACKAGE_ROOTS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the `" + name +
                    "` package that the generated code uses; choose another name");
        } else if (GENERATED_CODE_RECEIVERS.contains(name)) {
            error(pos, ErrorCode.RESERVED_NAME, "`" + name + "` would hide the class `" + name +
                    "` that the generated code uses; choose another name");
        } else if (name.equals("args") && inMain()) {
            error(pos, ErrorCode.RESERVED_NAME, "`args` is the parameter of the generated `main` method; choose another name");
        }
    }
    private boolean inMain() {
        final var f = current;
        return f != null && isMain(f);
    }
    private void error(Pos pos, ErrorCode code, String message) {
        diagnostics.add(AxlDiagnostic.error(file.path(), pos.line(), pos.column(), code, message));
    }
    public static final String TRY_IN_LAMBDA =
            "`try` cannot be used in a lambda or `attempt`: it would not return from the function; move it into a `val` first, " +
            "or use `|> mapResult` for a collection";
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
            "var", "yield", "record", "sealed", "permits", "true", "false", "null",
            "package", "import", "if", "else", "while", "return", "try");
    private static final Set<String> GENERATED_CODE_CLASSES = Set.of("Nulls", "Result", "Ok", "Err", "System", "Nullable",
            "NullMarked", "String", "Integer", "Long", "Double", "Boolean", "Object", "Exception");
    private static final Set<String> OBJECT_METHODS = Set.of("toString/0", "hashCode/0", "equals/1", "getClass/0",
            "notify/0", "notifyAll/0", "wait/0", "wait/1", "wait/2", "clone/0", "finalize/0");
    private static final Set<String> OBJECT_METHOD_NAMES = Set.of("toString", "hashCode", "getClass", "clone", "finalize",
            "notify", "notifyAll", "wait");
    private static final Set<String> GENERATED_CODE_PACKAGES = Set.of("axl.lang", "java.lang", "org.jspecify.annotations");
    private static final Set<String> GENERATED_CODE_RECEIVERS = Set.of("System", "Nulls", "Result");
    private static final Set<String> PACKAGE_ROOTS = Set.of("java", "axl");
    public enum Kind { LOCAL, MUTABLE_LOCAL, PARAM, TOP_LEVEL, IMPORTED, FIELD, MUTABLE_FIELD }
    public record Symbol(Kind kind, Pos pos, @Nullable TypeRef type) {}
    public static final class Scope {
        public final @Nullable Scope parent;
        public final boolean fileScope;
        public final HashMap<String, Symbol> names;
        public Scope(@Nullable Scope parent, boolean fileScope) {
            this.parent = parent;
            this.fileScope = fileScope;
            this.names = new HashMap<String, Symbol>();
        }
        public @Nullable Symbol lookup(String name) {
            @Nullable Scope s = this;
            while (s != null) {
                final var symbol = s.names.get(name);
                if (symbol != null) {
                    return symbol;
                }
                s = s.parent;
            }
            return null;
        }
    }
    public static Analysis check(SourceFile file, List<AxlDiagnostic> diagnostics) {
        final var checker = new Checker(file);
        checker.run();
        diagnostics.addAll(checker.diagnostics);
        return new Analysis(checker.mutableRefs, checker.implicitIt, checker.stableNames, checker.staticNames,
                checker.mapResultTypes);
    }
    private static boolean isTypeDecl(Decl decl) {
        return decl instanceof Decl.DataClass $s1 || decl instanceof Decl.Interface $s2 || decl instanceof Decl.ClassDecl $s3 || decl instanceof Decl.EnumDecl $s4;
    }
    private static boolean isMain(Decl.Function f) {
        return f.name().equals("main") && f.params().isEmpty() && f.returnType() == null && !f.isPrivate();
    }
    private static boolean isNull(Expr e) {
        final var inner = unparenthesized(e);
        return inner instanceof Expr.Literal $s1 && $s1.text().equals("null");
    }
    private static String notNullable(String name, TypeRef type) {
        return "`" + name + "` is declared as `" + type.name() + "`, which does not allow null; declare it as `" + type.name() + "?`";
    }
    private static Expr unparenthesized(Expr e) {
        return (e instanceof Expr.Paren $s1 ? unparenthesized($s1.inner()) : e);
    }
    private static boolean doesSomething(Expr e) {
        switch (e) {
            case Expr.Paren $s1 -> {
                return doesSomething($s1.inner());
            }
            case Expr.Binary $s2 -> {
                return ($s2.op().equals("&&") || $s2.op().equals("||")) && doesSomething($s2.right());
            }
            case Expr.Elvis $s3 -> {
                return doesSomething($s3.right());
            }
            case Expr.Member $s4 -> {
                return true;
            }
            case Expr.Call $s5 -> {
                return true;
            }
            case Expr.GenericNew $s6 -> {
                return true;
            }
            case Expr.Try $s7 -> {
                return true;
            }
            case Expr.Pipe $s8 -> {
                return true;
            }
            case Expr.Throw $s9 -> {
                return true;
            }
            case Expr.When $s10 -> {
                return true;
            }
            case Expr.Attempt $s11 -> {
                return true;
            }
            case Expr.NotNull $s12 -> {
                return true;
            }
            case Expr.Lambda $s13 -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean returnsResult(Decl.@Nullable Function f) {
        if (f == null) {
            return false;
        }
        final var type = f.returnType();
        return type != null && !type.nullable() && isResult(type);
    }
    private static boolean isResult(TypeRef type) {
        return type.name().equals("Result") || type.name().equals("axl.lang.Result");
    }
    private static boolean hasSafeStep(Expr e) {
        switch (e) {
            case Expr.Member $s1 -> {
                return $s1.safe() || hasSafeStep($s1.target());
            }
            case Expr.Call $s2 -> {
                final var target = $s2.target();
                return $s2.safe() || (target != null && hasSafeStep(target));
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean mentionsIt(List<Stmt> list) {
        for (final var stmt : list) {
            if (ItFinder.usesIt(stmt)) {
                return true;
            }
        }
        return false;
    }
    private static boolean isJavaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        var i = 0;
        while (i < name.length()) {
            if (!Character.isJavaIdentifierPart(name.charAt(i)) || name.charAt(i) == 36) {
                return false;
            }
            i += 1;
        }
        return true;
    }
}
