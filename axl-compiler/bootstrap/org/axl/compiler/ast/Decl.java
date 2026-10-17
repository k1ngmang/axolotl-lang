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
    public record Annotation(Pos pos, String name, List<AnnotationArg> args) {
        public Annotation {
            Nulls.param(pos, "pos");
            Nulls.param(name, "name");
            Nulls.param(args, "args");
        }
    }
    public record AnnotationArg(@Nullable String name, @Nullable Expr value, List<AnnotationArg> elements) {
        public AnnotationArg {
            Nulls.param(elements, "elements");
        }
    }
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
            List<TypeParam> typeParams) implements Decl {
        public Function {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(params, "params");
            Nulls.param(annotations, "annotations");
            Nulls.param(typeParams, "typeParams");
        }
    }
    public record TypeParam(Pos pos, String name, @Nullable TypeRef bound) {
        public TypeParam {
            Nulls.param(pos, "pos");
            Nulls.param(name, "name");
        }
    }
    public record Value(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            Expr init,
            List<Annotation> annotations) implements Decl {
        public Value {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(init, "init");
            Nulls.param(annotations, "annotations");
        }
    }
    public record DataClass(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<Expr.Param> fields,
            List<TypeRef> supertypes,
            List<Annotation> annotations,
            List<TypeParam> typeParams,
            List<Function> methods) implements Decl {
        public DataClass {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(fields, "fields");
            Nulls.param(supertypes, "supertypes");
            Nulls.param(annotations, "annotations");
            Nulls.param(typeParams, "typeParams");
            Nulls.param(methods, "methods");
        }
    }
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
            List<TypeParam> typeParams) implements Decl {
        public Interface {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(members, "members");
            Nulls.param(methods, "methods");
            Nulls.param(supertypes, "supertypes");
            Nulls.param(annotations, "annotations");
            Nulls.param(typeParams, "typeParams");
        }
    }
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
            @Nullable List<Expr> superArgs,
            List<Constructor> constructors,
            @Nullable Pos constructorPos,
            boolean privateConstructor,
            List<Annotation> constructorAnnotations) implements Decl {
        public ClassDecl {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(parameters, "parameters");
            Nulls.param(properties, "properties");
            Nulls.param(methods, "methods");
            Nulls.param(supertypes, "supertypes");
            Nulls.param(annotations, "annotations");
            Nulls.param(typeParams, "typeParams");
            Nulls.param(constructors, "constructors");
            Nulls.param(constructorAnnotations, "constructorAnnotations");
        }
    }
    public record Constructor(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            List<Expr.Param> params,
            List<Expr> args,
            Pos thisPos,
            List<Annotation> annotations) {
        public Constructor {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(params, "params");
            Nulls.param(args, "args");
            Nulls.param(thisPos, "thisPos");
            Nulls.param(annotations, "annotations");
        }
    }
    public record EnumDecl(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            List<String> constants,
            List<Annotation> annotations,
            List<Expr.Param> fields,
            List<List<Expr>> arguments,
            List<TypeRef> supertypes,
            List<Function> methods) implements Decl {
        public EnumDecl {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(constants, "constants");
            Nulls.param(annotations, "annotations");
            Nulls.param(fields, "fields");
            Nulls.param(arguments, "arguments");
            Nulls.param(supertypes, "supertypes");
            Nulls.param(methods, "methods");
        }
    }
    public record Property(
            Pos pos,
            Pos namePos,
            boolean isPrivate,
            String name,
            @Nullable TypeRef type,
            @Nullable Expr init,
            boolean mutable,
            List<Annotation> annotations) {
        public Property {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(annotations, "annotations");
        }
    }
}
