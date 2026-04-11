package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;

@NullMarked public final class InitOrder {
    public final SourceFile file;
    public final HashSet<String> visited;
    public Decl.@Nullable ClassDecl owner;
    public InitOrder(SourceFile file) {
        this.file = file;
        this.visited = new HashSet<String>();
        this.owner = null;
    }
    public List<Problem> problems() {
        final var values = new ArrayList<Decl.Value>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1) {
                values.add(decl$1);
            }
        }
        final var problems = new ArrayList<Problem>();
        var i = 0;
        while (i < values.size()) {
            final var read = new HashSet<String>();
            visited.clear();
            names(values.get(i).init(), Set.of(), read);
            final var later = firstRead(values.subList(i, values.size()), read);
            if (later != null) {
                problems.add(new Problem(values.get(i), later));
            }
            i += 1;
        }
        return problems;
    }
    public List<PropertyProblem> propertyProblems() {
        final var problems = new ArrayList<PropertyProblem>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.ClassDecl decl$1) {
                classProblems(decl$1, problems);
            }
        }
        return problems;
    }
    private void classProblems(Decl.ClassDecl c, List<PropertyProblem> problems) {
        final var properties = c.properties();
        var i = 0;
        while (i < properties.size()) {
            final var init = properties.get(i).init();
            if (init != null) {
                final var read = new HashSet<String>();
                visited.clear();
                owner = c;
                names(init, Set.of(), read);
                owner = null;
                final var later = firstProperty(properties.subList(i, properties.size()), read);
                if (later != null) {
                    problems.add(new PropertyProblem(properties.get(i), later));
                } else if (read.contains("this") && i + 1 < properties.size()) {
                    problems.add(new PropertyProblem(properties.get(i), "this"));
                }
            }
            i += 1;
        }
    }
    private @Nullable String firstProperty(List<Decl.Property> properties, Set<String> read) {
        for (final var p : properties) {
            if (read.contains(p.name())) {
                return p.name();
            }
        }
        return null;
    }
    private @Nullable String firstRead(List<Decl.Value> values, Set<String> read) {
        for (final var value : values) {
            if (read.contains(value.name())) {
                return value.name();
            }
        }
        return null;
    }
    private void names(Expr expr, Set<String> hidden, Set<String> out) {
        switch (expr) {
            case Expr.Name expr$1 -> {
                read(expr$1.name(), hidden, out);
            }
            case Expr.Call expr$2 -> {
                callNames(expr$2, hidden, out);
            }
            case Expr.Member expr$3 -> {
                if (isThisClass(expr$3.target()) || isThis(expr$3.target())) {
                    read(expr$3.name(), Set.of(), out);
                } else {
                    names(expr$3.target(), hidden, out);
                }
            }
            case Expr.GenericNew expr$4 -> {
                arguments(expr$4.args(), hidden, out);
            }
            case Expr.MethodRef expr$5 -> {
                if (isThis(expr$5.target())) {
                    function(expr$5.name(), out);
                } else {
                    names(expr$5.target(), hidden, out);
                }
            }
            case Expr.NotNull expr$6 -> {
                names(expr$6.target(), hidden, out);
            }
            case Expr.Unary expr$7 -> {
                names(expr$7.operand(), hidden, out);
            }
            case Expr.Binary expr$8 -> {
                names(expr$8.left(), hidden, out);
                names(expr$8.right(), hidden, out);
            }
            case Expr.Elvis expr$9 -> {
                names(expr$9.left(), hidden, out);
                names(expr$9.right(), hidden, out);
            }
            case Expr.Is expr$10 -> {
                names(expr$10.target(), hidden, out);
            }
            case Expr.Conditional expr$11 -> {
                names(expr$11.condition(), hidden, out);
                names(expr$11.then(), hidden, out);
                names(expr$11.otherwise(), hidden, out);
            }
            case Expr.Throw expr$12 -> {
                names(expr$12.value(), hidden, out);
            }
            case Expr.When expr$13 -> {
                whenNames(expr$13, hidden, out);
            }
            case Expr.Paren expr$14 -> {
                names(expr$14.inner(), hidden, out);
            }
            case Expr.Try expr$15 -> {
                names(expr$15.inner(), hidden, out);
            }
            case Expr.Attempt expr$16 -> {
                names(expr$16.body(), hidden, out);
                final var errorName = expr$16.errorName();
                names(expr$16.handler(), (errorName == null ? hidden : with(hidden, Set.of(errorName))), out);
            }
            case Expr.Pipe expr$17 -> {
                pipeNames(expr$17, hidden, out);
            }
            case Expr.Lambda expr$18 -> {
            }
            case Expr.Literal expr$19 -> {
            }
        }
    }
    private void callNames(Expr.Call call, Set<String> hidden, Set<String> out) {
        final var target = call.target();
        if (target == null) {
            if (!hidden.contains(call.name())) {
                function(call.name(), out);
            }
        } else if (isThisClass(target) || isThis(target)) {
            function(call.name(), out);
        } else {
            names(target, hidden, out);
            final var created = constructed(target);
            if (created != null) {
                method(created, call.name(), out);
            }
            final var returned = returnedLambda(target);
            if (returned != null) {
                lambdaBody(returned, Set.of(), out);
            }
        }
        arguments(call.args(), hidden, out);
    }
    private Decl.@Nullable ClassDecl constructed(Expr e) {
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.Call inner$1 && inner$1.target() == null) {
            return classNamed(inner$1.name());
        }
        return null;
    }
    private Expr.@Nullable Lambda returnedLambda(Expr e) {
        final var inner = unparenthesized(e);
        if (!(inner instanceof Expr.Call inner$1) || inner$1.target() != null) {
            return null;
        }
        final var c = owner;
        final var functions = new ArrayList<Decl.Function>();
        if (c != null) {
            functions.addAll(c.methods());
        }
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$2) {
                functions.add(decl$2);
            }
        }
        for (final var f : functions) {
            final var body = f.exprBody();
            if (f.name().equals(inner$1.name()) && body != null && unparenthesized(body) instanceof Expr.Lambda) {
                return lambdaOf(body);
            }
        }
        return null;
    }
    private Decl.@Nullable ClassDecl classNamed(String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.ClassDecl decl$1 && decl$1.name().equals(name)) {
                return decl$1;
            }
        }
        return null;
    }
    private void constructor(Decl.ClassDecl c, Set<String> out) {
        if (!visited.add("new " + c.name())) {
            return;
        }
        final var outer = owner;
        owner = c;
        for (final var p : c.properties()) {
            final var init = p.init();
            if (init != null) {
                names(init, Set.of(), out);
            }
        }
        owner = outer;
    }
    private void method(Decl.ClassDecl c, String name, Set<String> out) {
        final var outer = owner;
        owner = c;
        for (final var m : c.methods()) {
            if (m.name().equals(name) && visited.add("method " + c.name() + "." + name)) {
                body(m, out);
            }
        }
        owner = outer;
    }
    private void whenNames(Expr.When w, Set<String> hidden, Set<String> out) {
        names(w.subject(), hidden, out);
        final var binding = w.bindingName();
        final var inArms = (binding == null ? hidden : with(hidden, Set.of(binding)));
        for (final var arm : w.arms()) {
            statements(arm.body(), inArms, out);
        }
    }
    private void pipeNames(Expr.Pipe pipe, Set<String> hidden, Set<String> out) {
        names(pipe.source(), hidden, out);
        for (final var stage : pipe.stages()) {
            for (final var arg : stage.args()) {
                names(arg, hidden, out);
            }
            final var lambda = stage.lambda();
            if (lambda != null) {
                lambdaBody(lambda, hidden, out);
            }
        }
    }
    private void arguments(List<Expr> args, Set<String> hidden, Set<String> out) {
        for (final var arg : args) {
            final var inner = unparenthesized(arg);
            if (inner instanceof Expr.Lambda inner$1) {
                lambdaBody(inner$1, hidden, out);
            } else if (inner instanceof Expr.MethodRef inner$2 && (isThisClass(inner$2.target()) || isThis(inner$2.target()))) {
                function(inner$2.name(), out);
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
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name) && visited.add("val " + name)) {
                final var init = unparenthesized(decl$1.init());
                if (init instanceof Expr.Lambda init$2) {
                    lambdaBody(init$2, Set.of(), out);
                } else if (init instanceof Expr.MethodRef init$3 && isThisClass(init$3.target())) {
                    function(init$3.name(), out);
                }
            }
        }
    }
    private void lambdaBody(Expr.Lambda lambda, Set<String> hidden, Set<String> out) {
        final var scope = new HashSet<String>(hidden);
        for (final var param : lambda.params()) {
            scope.add(param.name());
        }
        if (lambda.params().isEmpty()) {
            scope.add("it");
        }
        statements(lambda.body(), scope, out);
    }
    private void function(String name, Set<String> out) {
        final var c = owner;
        if (c != null && hasMethod(c, name)) {
            method(c, name, out);
            return;
        }
        final var created = classNamed(name);
        if (created != null) {
            constructor(created, out);
            return;
        }
        if (!visited.add("fn " + name)) {
            return;
        }
        final var outer = owner;
        owner = null;
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1 && decl$1.name().equals(name)) {
                body(decl$1, out);
            }
        }
        owner = outer;
    }
    private void body(Decl.Function f, Set<String> out) {
        final var scope = new HashSet<String>();
        for (final var param : f.params()) {
            scope.add(param.name());
        }
        final var exprBody = f.exprBody();
        final var block = f.body();
        if (exprBody != null) {
            names(exprBody, scope, out);
        } else if (block != null) {
            statements(block.statements(), scope, out);
        }
    }
    private boolean isThisClass(Expr target) {
        final var text = dotted(target);
        if (text == null) {
            return false;
        }
        final var packageName = java.util.Objects.requireNonNull(file.packageName());
        final var qualified = (packageName.isEmpty() ? file.className() : packageName + "." + file.className());
        return text.equals(file.className()) || text.equals(qualified);
    }
    private void statements(List<Stmt> statements, Set<String> hidden, Set<String> out) {
        final var scope = new HashSet<String>(hidden);
        for (final var stmt : statements) {
            statement(stmt, scope, out);
            if (stmt instanceof Stmt.Local stmt$1) {
                scope.add(stmt$1.name());
            }
        }
    }
    private void statement(Stmt stmt, Set<String> hidden, Set<String> out) {
        switch (stmt) {
            case Stmt.Local stmt$1 -> {
                final var init = unparenthesized(stmt$1.init());
                if (init instanceof Expr.Lambda init$2) {
                    lambdaBody(init$2, hidden, out);
                } else {
                    names(stmt$1.init(), hidden, out);
                }
            }
            case Stmt.Assign stmt$3 -> {
                names(stmt$3.target(), hidden, out);
                names(stmt$3.value(), hidden, out);
            }
            case Stmt.Return stmt$4 -> {
                final var value = stmt$4.value();
                if (value != null) {
                    names(value, hidden, out);
                }
            }
            case Stmt.If stmt$5 -> {
                names(stmt$5.condition(), hidden, out);
                statement(stmt$5.then(), hidden, out);
                final var otherwise = stmt$5.elseBranch();
                if (otherwise != null) {
                    statement(otherwise, hidden, out);
                }
            }
            case Stmt.While stmt$6 -> {
                names(stmt$6.condition(), hidden, out);
                statement(stmt$6.body(), hidden, out);
            }
            case Stmt.For stmt$7 -> {
                names(stmt$7.iterable(), hidden, out);
                statement(stmt$7.body(), with(hidden, Set.of(stmt$7.name())), out);
            }
            case Stmt.ExprStmt stmt$8 -> {
                names(stmt$8.expr(), hidden, out);
            }
            case Stmt.Block stmt$9 -> {
                statements(stmt$9.statements(), hidden, out);
            }
        }
    }
    public record Problem(Decl.Value value, String later) {}
    public record PropertyProblem(Decl.Property property, String later) {}
    public static List<Problem> check(SourceFile file) {
        return new InitOrder(file).problems();
    }
    public static List<PropertyProblem> checkProperties(SourceFile file) {
        return new InitOrder(file).propertyProblems();
    }
    private static boolean hasMethod(Decl.ClassDecl c, String name) {
        return c.methods().stream().anyMatch(m -> m.name().equals(name));
    }
    private static Expr.@Nullable Lambda lambdaOf(Expr expr) {
        final var inner = unparenthesized(expr);
        return (inner instanceof Expr.Lambda inner$1 ? inner$1 : null);
    }
    private static boolean isThis(Expr expr) {
        return expr instanceof Expr.Name expr$1 && expr$1.name().equals("this");
    }
    private static Expr unparenthesized(Expr expr) {
        return (expr instanceof Expr.Paren expr$1 ? expr$1.inner() : expr);
    }
    private static @Nullable String dotted(Expr expr) {
        if (expr instanceof Expr.Name expr$1) {
            return expr$1.name();
        }
        if (expr instanceof Expr.Member expr$2 && !expr$2.safe()) {
            final var target = dotted(expr$2.target());
            return (target == null ? null : target + "." + expr$2.name());
        }
        return null;
    }
    private static Set<String> with(Set<String> names, Set<String> more) {
        final var result = new HashSet<String>(names);
        result.addAll(more);
        return result;
    }
}
