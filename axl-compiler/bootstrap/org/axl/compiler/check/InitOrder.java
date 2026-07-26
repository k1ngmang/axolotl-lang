package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class InitOrder {
    public final SourceFile file;
    public final HashSet<String> visited;
    public Decl.@Nullable ClassDecl owner;
    public Map<String, String> classes;
    public Map<String, String> elements;
    public InitOrder(SourceFile file) {
        this.file = file;
        this.visited = new HashSet<String>();
        this.owner = null;
        this.classes = new HashMap<String, String>();
        this.elements = new HashMap<String, String>();
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
                if (!isThis(expr$5.target())) {
                    names(expr$5.target(), hidden, out);
                }
            }
            case Expr.NotNull expr$6 -> {
                names(expr$6.target(), hidden, out);
            }
            case Expr.ClassLiteral expr$7 -> {
            }
            case Expr.Unary expr$8 -> {
                names(expr$8.operand(), hidden, out);
            }
            case Expr.Binary expr$9 -> {
                names(expr$9.left(), hidden, out);
                names(expr$9.right(), hidden, out);
                if (expr$9.op().equals("+")) {
                    toStringOf(expr$9.left(), hidden, out);
                    toStringOf(expr$9.right(), hidden, out);
                }
            }
            case Expr.Elvis expr$10 -> {
                names(expr$10.left(), hidden, out);
                names(expr$10.right(), hidden, out);
            }
            case Expr.Is expr$11 -> {
                names(expr$11.target(), hidden, out);
            }
            case Expr.Conditional expr$12 -> {
                names(expr$12.condition(), hidden, out);
                names(expr$12.then(), hidden, out);
                names(expr$12.otherwise(), hidden, out);
            }
            case Expr.Throw expr$13 -> {
                names(expr$13.value(), hidden, out);
            }
            case Expr.When expr$14 -> {
                whenNames(expr$14, hidden, out);
            }
            case Expr.Paren expr$15 -> {
                names(expr$15.inner(), hidden, out);
            }
            case Expr.Try expr$16 -> {
                names(expr$16.inner(), hidden, out);
            }
            case Expr.Attempt expr$17 -> {
                names(expr$17.body(), hidden, out);
                final var errorName = expr$17.errorName();
                names(expr$17.handler(), (errorName == null ? hidden : with(hidden, Set.of(errorName))), out);
            }
            case Expr.Pipe expr$18 -> {
                pipeNames(expr$18, hidden, out);
            }
            case Expr.Lambda expr$19 -> {
            }
            case Expr.Literal expr$20 -> {
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
            final var receiver = classOf(target, hidden);
            if (receiver != null) {
                method(receiver, call.name(), out);
            }
            final var returned = returnedLambda(target);
            if (returned != null) {
                lambdaBody(returned, Set.of(), out, null);
            }
        }
        arguments(call.args(), hidden, out);
    }
    private void toStringOf(Expr e, Set<String> hidden, Set<String> out) {
        final var c = classOf(e, hidden);
        if (c != null) {
            method(c, "toString", out);
        }
    }
    private Decl.@Nullable ClassDecl classOf(Expr e, Set<String> hidden) {
        final var created = constructed(e);
        if (created != null) {
            return created;
        }
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.Name inner$1) {
            final var local = classes.get(inner$1.name());
            if (local != null) {
                return classNamed(local);
            }
            return (hidden.contains(inner$1.name()) ? null : valueClass(inner$1.name()));
        }
        if (inner instanceof Expr.Call inner$2) {
            final var target = inner$2.target();
            if (target == null) {
                return resultClass(inner$2.name());
            }
            final var element = (ELEMENT_GETTERS.contains(inner$2.name()) ? elementOf(target, hidden) : null);
            return (element == null ? null : classNamed(element));
        }
        return null;
    }
    private Decl.@Nullable ClassDecl resultClass(String function) {
        for (final var decl : file.decls()) {
            final var result = (decl instanceof Decl.Function decl$1 && decl$1.name().equals(function) ? decl$1.returnType() : null);
            if (result != null) {
                return classNamed(result.name());
            }
        }
        return null;
    }
    private Decl.@Nullable ClassDecl valueClass(String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name)) {
                final var type = decl$1.type();
                return (type != null ? classNamed(type.name()) : constructed(decl$1.init()));
            }
        }
        return null;
    }
    private @Nullable String elementOf(Expr e, Set<String> hidden) {
        final var inner = unparenthesized(e);
        if (inner instanceof Expr.Name inner$1) {
            final var local = elements.get(inner$1.name());
            if (local != null || hidden.contains(inner$1.name())) {
                return local;
            }
            return valueElement(inner$1.name());
        }
        if (inner instanceof Expr.Call inner$2 && inner$2.target() != null && inner$2.name().equals("of") && !inner$2.args().isEmpty()) {
            final var first = classOf(inner$2.args().get(0), hidden);
            return (first == null ? null : first.name());
        }
        return null;
    }
    private @Nullable String valueElement(String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name)) {
                final var type = decl$1.type();
                final var init = decl$1.init();
                return (type != null ? elementName(type) : Nulls.box((unparenthesized(init) instanceof Expr.Name ? null : elementOf(init, Set.of()))));
            }
        }
        return null;
    }
    private void track(String name, @Nullable String type, @Nullable String element) {
        if (type == null) {
            classes.remove(name);
        } else {
            classes.put(name, type);
        }
        if (element == null) {
            elements.remove(name);
        } else {
            elements.put(name, element);
        }
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
        var element = elementOf(pipe.source(), hidden);
        for (final var stage : pipe.stages()) {
            for (final var arg : stage.args()) {
                names(arg, hidden, out);
            }
            final var lambda = stage.lambda();
            if (lambda != null) {
                lambdaBody(lambda, hidden, out, element);
            }
            if (!SAME_ELEMENTS.contains(stage.name())) {
                element = null;
            }
        }
    }
    private void arguments(List<Expr> args, Set<String> hidden, Set<String> out) {
        for (final var arg : args) {
            final var inner = unparenthesized(arg);
            if (inner instanceof Expr.Lambda inner$1) {
                lambdaBody(inner$1, hidden, out, null);
            } else {
                names(arg, hidden, out);
                if (inner instanceof Expr.MethodRef inner$2) {
                    calledRef(inner$2, hidden, out);
                }
            }
        }
    }
    private void calledRef(Expr.MethodRef ref, Set<String> hidden, Set<String> out) {
        if (isThisClass(ref.target()) || isThis(ref.target())) {
            function(ref.name(), out);
            return;
        }
        final var c = classOf(ref.target(), hidden);
        if (c != null) {
            method(c, ref.name(), out);
        }
    }
    private void read(String name, Set<String> hidden, Set<String> out) {
        if (hidden.contains(name)) {
            return;
        }
        out.add(name);
        final var c = owner;
        if (c != null) {
            for (final var p : c.properties()) {
                final var init = p.init();
                if (p.name().equals(name) && init != null && visited.add("property " + c.name() + "." + name)) {
                    calledValue(unparenthesized(init), out);
                }
            }
        }
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name) && visited.add("val " + name)) {
                calledValue(unparenthesized(decl$1.init()), out);
            }
        }
    }
    private void calledValue(Expr init, Set<String> out) {
        final var outerClasses = classes;
        final var outerElements = elements;
        classes = new HashMap<String, String>();
        elements = new HashMap<String, String>();
        if (init instanceof Expr.Lambda init$1) {
            lambdaBody(init$1, Set.of(), out, null);
        } else if (init instanceof Expr.MethodRef init$2) {
            calledRef(init$2, Set.of(), out);
        }
        classes = outerClasses;
        elements = outerElements;
    }
    private void lambdaBody(Expr.Lambda lambda, Set<String> hidden, Set<String> out, @Nullable String element) {
        final var outerClasses = classes;
        final var outerElements = elements;
        classes = new HashMap<String, String>(classes);
        elements = new HashMap<String, String>(elements);
        final var scope = new HashSet<String>(hidden);
        for (final var param : lambda.params()) {
            scope.add(param.name());
            final var type = param.type();
            track(param.name(), (type == null ? element : Nulls.box(type.name())), (type == null ? null : elementName(type)));
        }
        if (lambda.params().isEmpty()) {
            scope.add("it");
            track("it", element, null);
        }
        statements(lambda.body(), scope, out);
        classes = outerClasses;
        elements = outerElements;
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
        final var outerClasses = classes;
        final var outerElements = elements;
        classes = new HashMap<String, String>();
        elements = new HashMap<String, String>();
        final var scope = new HashSet<String>();
        for (final var param : f.params()) {
            scope.add(param.name());
            final var type = param.type();
            if (type != null) {
                track(param.name(), type.name(), elementName(type));
            }
        }
        final var exprBody = f.exprBody();
        final var block = f.body();
        if (exprBody != null) {
            names(exprBody, scope, out);
        } else if (block != null) {
            statements(block.statements(), scope, out);
        }
        classes = outerClasses;
        elements = outerElements;
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
                    lambdaBody(init$2, hidden, out, null);
                } else {
                    names(stmt$1.init(), hidden, out);
                    if (init instanceof Expr.MethodRef init$3) {
                        calledRef(init$3, hidden, out);
                    }
                }
                final var type = stmt$1.type();
                if (type != null) {
                    track(stmt$1.name(), type.name(), elementName(type));
                } else {
                    final var c = classOf(init, hidden);
                    track(stmt$1.name(), (c == null ? null : c.name()), elementOf(init, hidden));
                }
            }
            case Stmt.Assign stmt$4 -> {
                names(stmt$4.target(), hidden, out);
                names(stmt$4.value(), hidden, out);
            }
            case Stmt.Return stmt$5 -> {
                final var value = stmt$5.value();
                if (value != null) {
                    names(value, hidden, out);
                }
            }
            case Stmt.Jump stmt$6 -> {
            }
            case Stmt.Use stmt$7 -> {
                names(stmt$7.resource(), hidden, out);
                statement(stmt$7.body(), with(hidden, Set.of(stmt$7.name())), out);
                final var handler = stmt$7.handler();
                final var errorName = stmt$7.errorName();
                if (handler != null) {
                    names(handler, (errorName == null ? hidden : with(hidden, Set.of(errorName))), out);
                }
            }
            case Stmt.If stmt$8 -> {
                names(stmt$8.condition(), hidden, out);
                statement(stmt$8.then(), hidden, out);
                final var otherwise = stmt$8.elseBranch();
                if (otherwise != null) {
                    statement(otherwise, hidden, out);
                }
            }
            case Stmt.While stmt$9 -> {
                names(stmt$9.condition(), hidden, out);
                statement(stmt$9.body(), hidden, out);
            }
            case Stmt.For stmt$10 -> {
                names(stmt$10.iterable(), hidden, out);
                track(stmt$10.name(), elementOf(stmt$10.iterable(), hidden), null);
                statement(stmt$10.body(), with(hidden, Set.of(stmt$10.name())), out);
            }
            case Stmt.ExprStmt stmt$11 -> {
                names(stmt$11.expr(), hidden, out);
            }
            case Stmt.Block stmt$12 -> {
                statements(stmt$12.statements(), hidden, out);
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
    private static final Set<String> ELEMENT_GETTERS = Set.of("get", "getFirst", "getLast", "first", "last");
    private static final Set<String> SAME_ELEMENTS = Set.of("filter", "sorted", "distinct", "peek", "limit", "skip", "takeWhile", "dropWhile");
    private static @Nullable String elementName(TypeRef type) {
        return (type.args().isEmpty() ? null : type.args().get(type.args().size() - 1).name());
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
