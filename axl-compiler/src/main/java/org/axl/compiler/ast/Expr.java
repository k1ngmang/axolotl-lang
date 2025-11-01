package org.axl.compiler.ast;

import java.util.List;

public sealed interface Expr {

    Pos pos();

    record Literal(Pos pos, String text) implements Expr {}

    record Name(Pos pos, String name) implements Expr {}

    /** `target.name` or `target?.name`. */
    record Member(Pos pos, Expr target, String name, boolean safe) implements Expr {}

    /** `name(args)` when target is null, otherwise `target.name(args)` or `target?.name(args)`. */
    record Call(Pos pos, Expr target, String name, List<Expr> args, boolean safe) implements Expr {}

    /** `Name<T>(args)` or `Name<>(args)`. */
    record GenericNew(Pos pos, String type, List<TypeRef> typeArgs, List<Expr> args) implements Expr {}

    record MethodRef(Pos pos, Expr target, String name) implements Expr {}

    /** `target!!`: fails with NullPointerException when the value is null. */
    record NotNull(Pos pos, Expr target) implements Expr {}

    record Unary(Pos pos, String op, Expr operand) implements Expr {}

    record Binary(Pos pos, String op, Expr left, Expr right) implements Expr {}

    record Elvis(Pos pos, Expr left, Expr right) implements Expr {}

    record Paren(Pos pos, Expr inner) implements Expr {}

    record Try(Pos pos, Expr inner) implements Expr {}

    /** `attempt body else handler`; errorName is null when the handler names no parameter. */
    record Attempt(Pos pos, Expr body, String errorName, Expr handler) implements Expr {}

    /** arrow is true when the parameter list is written out, even if empty: `{ -> f() }`. */
    record Lambda(Pos pos, List<Param> params, boolean arrow, List<Stmt> body, Pos end) implements Expr {}

    record Param(Pos pos, String name, TypeRef type) {}

    /** `source |> stage |> stage`. */
    record Pipe(Pos pos, Expr source, List<Stage> stages) implements Expr {}

    /** A pipe stage: a name with optional arguments and an optional trailing lambda. */
    record Stage(Pos pos, String name, List<Expr> args, Lambda lambda) {}
}
