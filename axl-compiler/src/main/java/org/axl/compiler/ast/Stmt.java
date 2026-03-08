package org.axl.compiler.ast;

import java.util.List;

public sealed interface Stmt {

    Pos pos();

    /** `val` or `var`; type is null when it is left to javac. */
    record Local(Pos pos, Pos namePos, String name, TypeRef type, Expr init, boolean mutable) implements Stmt {}

    record Assign(Pos pos, Expr target, String op, Expr value) implements Stmt {}

    record Return(Pos pos, Expr value) implements Stmt {}

    /** elseBranch is null, a Block or another If. */
    record If(Pos pos, Expr condition, Block then, Stmt elseBranch) implements Stmt {}

    record While(Pos pos, Expr condition, Block body) implements Stmt {}

    /** `for (name in iterable) { ... }`. */
    record For(Pos pos, Pos namePos, String name, Expr iterable, Block body) implements Stmt {}

    record ExprStmt(Pos pos, Expr expr) implements Stmt {}

    record Block(Pos pos, List<Stmt> statements, Pos end) implements Stmt {}
}
