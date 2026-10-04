package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public sealed interface Stmt {
    Pos pos();
    public record Local(
            Pos pos,
            Pos namePos,
            String name,
            @Nullable TypeRef type,
            Expr init,
            boolean mutable) implements Stmt {}
    public record Assign(Pos pos, Expr target, String op, Expr value) implements Stmt {}
    public record Return(Pos pos, @Nullable Expr value) implements Stmt {}
    public record Use(
            Pos pos,
            Pos namePos,
            String name,
            @Nullable TypeRef type,
            Expr resource,
            Block body,
            @Nullable String errorName,
            @Nullable Pos errorNamePos,
            @Nullable TypeRef errorType,
            @Nullable Expr handler) implements Stmt {}
    public record Jump(Pos pos, boolean isBreak) implements Stmt {}
    public record AttemptExit(
            Pos pos,
            Expr body,
            @Nullable String errorName,
            @Nullable Pos errorNamePos,
            @Nullable TypeRef errorType,
            Stmt exit) implements Stmt {}
    public record If(Pos pos, Expr condition, Block then, @Nullable Stmt elseBranch) implements Stmt {}
    public record While(Pos pos, Expr condition, Block body) implements Stmt {}
    public record For(Pos pos, Pos namePos, String name, Expr iterable, Block body) implements Stmt {}
    public record Count(
            Pos pos,
            Pos namePos,
            String name,
            Expr from,
            String kind,
            Expr to,
            @Nullable Expr step,
            Block body) implements Stmt {}
    public record ExprStmt(Pos pos, Expr expr) implements Stmt {}
    public record Block(Pos pos, List<Stmt> statements, Pos end) implements Stmt {}
}
