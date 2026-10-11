package org.axl.compiler.gen;
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
import org.axl.compiler.check.TypeInference;

@NullMarked public final class Reads {
    public final Set<String> names = new HashSet<String>();
    public final Set<String> inLambdas = new HashSet<String>();
    public final Set<String> members = new HashSet<String>();
    public final Set<String> assigned = new HashSet<String>();
    public int lambdas = 0;
    public boolean expansions = false;
    public Decl.@Nullable ClassDecl memberOf = null;
    public @Nullable SourceFile file = null;
    public List<SourceFile> siblings = List.of();
    public @Nullable Decl owner = null;
    public Map<String, TypeRef> ownerTypes = Map.of();
    public final HashMap<String, @Nullable TypeRef> locals = new HashMap<String, @Nullable TypeRef>();
    public void expr(Expr e) {
        Nulls.param(e, "e");
        switch (e) {
            case Expr.Literal e$1 -> {
            }
            case Expr.Name e$2 -> {
                if (lambdas > 0) {
                    inLambdas.add(e$2.name());
                } else {
                    names.add(e$2.name());
                }
            }
            case Expr.Member e$3 -> {
                if (readsMember(e$3)) {
                    members.add(e$3.name());
                }
                expanded(e$3.safe(), () -> expr(e$3.target()));
            }
            case Expr.Call e$4 -> {
                expanded(e$4.safe(), () -> {
                    final var target = e$4.target();
                    if (target != null) {
                        expr(target);
                    }
                    exprs(e$4.args());
                });
            }
            case Expr.GenericNew e$5 -> {
                exprs(e$5.args());
            }
            case Expr.MethodRef e$6 -> {
                expr(e$6.target());
            }
            case Expr.ClassLiteral e$7 -> {
            }
            case Expr.NotNull e$8 -> {
                expr(e$8.target());
            }
            case Expr.Unary e$9 -> {
                expr(e$9.operand());
            }
            case Expr.Binary e$10 -> {
                expr(e$10.left());
                expr(e$10.right());
            }
            case Expr.Elvis e$11 -> {
                expanded(true, () -> {
                    expr(e$11.left());
                    expr(e$11.right());
                });
            }
            case Expr.Is e$12 -> {
                expr(e$12.target());
            }
            case Expr.Conditional e$13 -> {
                expr(e$13.condition());
                expr(e$13.then());
                expr(e$13.otherwise());
            }
            case Expr.Throw e$14 -> {
                expr(e$14.value());
            }
            case Expr.When e$15 -> {
                expr(e$15.subject());
                for (final var arm : e$15.arms()) {
                    statements(arm.body());
                }
            }
            case Expr.Paren e$16 -> {
                expr(e$16.inner());
            }
            case Expr.Try e$17 -> {
                expr(e$17.inner());
            }
            case Expr.Attempt e$18 -> {
                expanded(true, () -> {
                    expr(e$18.body());
                    expr(e$18.handler());
                });
            }
            case Expr.Lambda e$19 -> {
                for (final var p : e$19.params()) {
                    locals.put(p.name(), p.type());
                }
                lambdas += 1;
                statements(e$19.body());
                lambdas -= 1;
            }
            case Expr.Pipe e$20 -> {
                expr(e$20.source());
                for (final var stage : e$20.stages()) {
                    exprs(stage.args());
                    final var lambda = stage.lambda();
                    if (lambda != null) {
                        expr(lambda);
                    }
                }
            }
        }
    }
    private boolean readsMember(Expr.Member e) {
        final var c = memberOf;
        final var inFile = file;
        if (c == null || inFile == null) {
            return true;
        }
        final var target = e.target();
        if (target instanceof Expr.Name target$1 && target$1.name().equals("this")) {
            return c.equals(owner);
        }
        if (target instanceof Expr.Name target$2 && locals.containsKey(target$2.name()) && locals.get(target$2.name()) == null) {
            return true;
        }
        final var type = new TypeInference(inFile, knownTypes(), siblings).of(target);
        return type == null || type.name().substring(type.name().lastIndexOf('.') + 1).equals(c.name());
    }
    private Map<String, TypeRef> knownTypes() {
        final var known = new HashMap<String, TypeRef>(ownerTypes);
        for (final var local : locals.entrySet()) {
            final var type = local.getValue();
            if (type != null) {
                known.put(local.getKey(), type);
            } else {
                known.remove(local.getKey());
            }
        }
        return known;
    }
    private void expanded(boolean lambda, Runnable visit) {
        final var counts = lambda && expansions;
        if (counts) {
            lambdas += 1;
        }
        visit.run();
        if (counts) {
            lambdas -= 1;
        }
    }
    public void exprs(List<Expr> list) {
        Nulls.param(list, "list");
        for (final var e : list) {
            expr(e);
        }
    }
    public void statements(List<Stmt> list) {
        Nulls.param(list, "list");
        for (final var s : list) {
            statement(s);
        }
    }
    public void statement(Stmt s) {
        Nulls.param(s, "s");
        switch (s) {
            case Stmt.Local s$1 -> {
                expr(s$1.init());
                locals.put(s$1.name(), Nulls.or(s$1.type(), () -> localType(s$1.init())));
            }
            case Stmt.Assign s$2 -> {
                final var target = s$2.target();
                if (target instanceof Expr.Name target$3) {
                    assigned.add(target$3.name());
                }
                expr(target);
                expr(s$2.value());
            }
            case Stmt.Return s$4 -> {
                final var value = s$4.value();
                if (value != null) {
                    expr(value);
                }
            }
            case Stmt.Jump s$5 -> {
            }
            case Stmt.AttemptExit s$6 -> {
                expanded(true, () -> expr(s$6.body()));
                statement(s$6.exit());
            }
            case Stmt.Use s$7 -> {
                expr(s$7.resource());
                locals.put(s$7.name(), Nulls.or(s$7.type(), () -> localType(s$7.resource())));
                statement(s$7.body());
                final var handler = s$7.handler();
                if (handler != null) {
                    expr(handler);
                }
            }
            case Stmt.If s$8 -> {
                expr(s$8.condition());
                statement(s$8.then());
                final var otherwise = s$8.elseBranch();
                if (otherwise != null) {
                    statement(otherwise);
                }
            }
            case Stmt.While s$9 -> {
                expr(s$9.condition());
                statement(s$9.body());
            }
            case Stmt.For s$10 -> {
                expr(s$10.iterable());
                locals.put(s$10.name(), null);
                statement(s$10.body());
            }
            case Stmt.Count s$11 -> {
                locals.put(s$11.name(), null);
                expr(s$11.from());
                expr(s$11.to());
                final var step = s$11.step();
                if (step != null) {
                    expr(step);
                }
                statement(s$11.body());
            }
            case Stmt.ExprStmt s$12 -> {
                expr(s$12.expr());
            }
            case Stmt.Block s$13 -> {
                statements(s$13.statements());
            }
        }
    }
    private @Nullable TypeRef localType(Expr init) {
        final var inFile = file;
        if (memberOf == null || inFile == null) {
            return null;
        }
        return new TypeInference(inFile, knownTypes(), siblings).of(init);
    }
    public void function(Decl.Function f) {
        Nulls.param(f, "f");
        locals.clear();
        for (final var p : f.params()) {
            locals.put(p.name(), p.type());
        }
        final var exprBody = f.exprBody();
        if (exprBody != null) {
            expr(exprBody);
        }
        final var body = f.body();
        if (body != null) {
            statement(body);
        }
    }
    public void decl(Decl d) {
        Nulls.param(d, "d");
        locals.clear();
        owner = d;
        final var inFile = file;
        if (memberOf != null && inFile != null) {
            ownerTypes = ownerTypesOf(d, inFile, siblings);
        }
        switch (d) {
            case Decl.Function d$1 -> {
                function(d$1);
            }
            case Decl.Value d$2 -> {
                expr(d$2.init());
            }
            case Decl.DataClass d$3 -> {
                for (final var m : d$3.methods()) {
                    function(m);
                }
            }
            case Decl.Interface d$4 -> {
                for (final var m : d$4.methods()) {
                    function(m);
                }
            }
            case Decl.EnumDecl d$5 -> {
                for (final var args : d$5.arguments()) {
                    exprs(args);
                }
            }
            case Decl.ClassDecl d$6 -> {
                final var superArgs = d$6.superArgs();
                if (superArgs != null) {
                    exprs(superArgs);
                }
                for (final var p : d$6.properties()) {
                    final var init = p.init();
                    if (init != null) {
                        expr(init);
                    }
                }
                for (final var k : d$6.constructors()) {
                    exprs(k.args());
                }
                for (final var m : d$6.methods()) {
                    function(m);
                }
            }
        }
    }
    private static Map<String, TypeRef> ownerTypesOf(Decl d, SourceFile file, List<SourceFile> siblings) {
        final var types = new HashMap<String, TypeRef>();
        if (d instanceof Decl.ClassDecl d$1) {
            types.putAll(TypeInference.propertyTypes(file, siblings, d$1));
        } else if (d instanceof Decl.DataClass d$2) {
            for (final var f : d$2.fields()) {
                final var type = f.type();
                if (type != null) {
                    types.put(f.name(), type);
                }
            }
        }
        return types;
    }
    public static Set<String> constructorOnly(Decl.ClassDecl c, SourceFile file, List<SourceFile> siblings, Set<String> required) {
        Nulls.param(c, "c");
        Nulls.param(file, "file");
        Nulls.param(siblings, "siblings");
        Nulls.param(required, "required");
        final var result = new HashSet<String>();
        for (final var p : c.parameters()) {
            if (p.isPrivate() && !p.mutable() && p.annotations().isEmpty() && !required.contains(p.name())) {
                result.add(p.name());
            }
        }
        if (result.isEmpty()) {
            return result;
        }
        final var inMethods = new Reads();
        for (final var m : c.methods()) {
            inMethods.function(m);
        }
        result.removeAll(inMethods.names);
        result.removeAll(inMethods.inLambdas);
        for (final var k : c.constructors()) {
            final var inConstructor = new Reads();
            inConstructor.exprs(k.args());
            for (final var p : k.params()) {
                inConstructor.names.remove(p.name());
                inConstructor.inLambdas.remove(p.name());
            }
            result.removeAll(inConstructor.names);
            result.removeAll(inConstructor.inLambdas);
        }
        final var inProperties = new Reads();
        for (final var p : c.properties()) {
            final var init = p.init();
            if (init != null) {
                inProperties.expr(init);
            }
        }
        final var superArgs = c.superArgs();
        if (superArgs != null) {
            inProperties.exprs(superArgs);
        }
        result.removeAll(inProperties.inLambdas);
        result.retainAll(inProperties.names);
        if (result.isEmpty()) {
            return result;
        }
        final var inBuild = new Reads();
        inBuild.memberOf = c;
        inBuild.siblings = with(file, siblings);
        for (final var f : inBuild.siblings) {
            inBuild.file = f;
            for (final var d : f.decls()) {
                inBuild.decl(d);
            }
        }
        result.removeAll(inBuild.members);
        return result;
    }
    private static List<SourceFile> with(SourceFile file, List<SourceFile> siblings) {
        final var files = new ArrayList<SourceFile>(siblings);
        files.add(file);
        return files;
    }
}
