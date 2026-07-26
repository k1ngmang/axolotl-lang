package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public sealed interface Decl {
    Pos pos();
    Pos namePos();
    String name();
    boolean isPrivate();
    List<Annotation> annotations();
    public record Annotation(Pos pos, String name, List<AnnotationArg> args) {}
    public record AnnotationArg(@Nullable String name, @Nullable Expr value, List<AnnotationArg> elements) {}
    public record Function(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Expr.Param> params,
            @Nullable TypeRef returnType,
            @Nullable Expr exprBody,
            Stmt.@Nullable Block body,
            List<Annotation> annotations,
            List<TypeParam> typeParams) implements Decl {}
    public record TypeParam(Pos pos, String name, @Nullable TypeRef bound) {}
    public record Value(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            Expr init,
            List<Annotation> annotations) implements Decl {}
    public record DataClass(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Expr.Param> fields,
            List<TypeRef> supertypes,
            List<Annotation> annotations,
            List<TypeParam> typeParams,
            List<Function> methods) implements Decl {}
    public record Interface(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            boolean isSealed,
            List<Expr.Param> members,
            List<Function> methods,
            List<TypeRef> supertypes,
            List<Annotation> annotations,
            List<TypeParam> typeParams) implements Decl {}
    public record ClassDecl(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Property> parameters,
            List<Property> properties,
            List<Function> methods,
            List<TypeRef> supertypes,
            List<Annotation> annotations,
            List<TypeParam> typeParams,
            @Nullable List<Expr> superArgs) implements Decl {}
    public record EnumDecl(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<String> constants,
            List<Annotation> annotations,
            List<Expr.Param> fields,
            List<List<Expr>> arguments) implements Decl {}
    public record Property(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            @Nullable Expr init,
            boolean mutable,
            List<Annotation> annotations) {}
}
