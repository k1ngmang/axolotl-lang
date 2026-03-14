package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public sealed interface Decl {
    Pos pos();
    Pos namePos();
    String name();
    boolean isPrivate();
    public record Function(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Expr.Param> params,
            @Nullable TypeRef returnType,
            @Nullable Expr exprBody,
            Stmt.@Nullable Block body) implements Decl {}
    public record Value(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            Expr init) implements Decl {}
    public record DataClass(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Expr.Param> fields,
            List<TypeRef> supertypes) implements Decl {}
    public record Interface(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            boolean isSealed,
            List<Expr.Param> members,
            List<TypeRef> supertypes) implements Decl {}
    public record ClassDecl(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Property> parameters,
            List<Property> properties,
            List<Function> methods,
            List<TypeRef> supertypes) implements Decl {}
    public record Property(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            @Nullable Expr init,
            boolean mutable) {}
}
