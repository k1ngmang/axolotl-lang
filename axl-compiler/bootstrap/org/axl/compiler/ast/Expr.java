package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.math.BigInteger;
import java.util.List;

@NullMarked public sealed interface Expr {
    Pos pos();
    public record Literal(Pos pos, String text) implements Expr {}
    public record Name(Pos pos, String name) implements Expr {}
    public record Member(Pos pos, Expr target, String name, boolean safe) implements Expr {}
    public record ClassLiteral(Pos pos, Expr target) implements Expr {}
    public static @Nullable String pathName(Expr e) {
        if (e instanceof Name e$1) {
            return e$1.name();
        }
        if (e instanceof Member e$2 && !e$2.safe()) {
            final var target = pathName(e$2.target());
            return (target == null ? null : target + "." + e$2.name());
        }
        return null;
    }
    public static @Nullable BigInteger integerValue(Expr e) {
        if (e instanceof Paren e$1) {
            return integerValue(e$1.inner());
        }
        if (e instanceof Unary e$2 && e$2.op().equals("-")) {
            return Nulls.map(integerValue(e$2.operand()), $n1 -> $n1.negate());
        }
        if (!(e instanceof Literal e$3)) {
            return null;
        }
        final var text = e$3.text().replace("_", "").replaceFirst("[lL]$", "");
        if (text.matches("0[xX][0-9a-fA-F]+")) {
            return new BigInteger(text.substring(2), 16);
        }
        return (text.matches("[0-9]+") ? new BigInteger(text) : null);
    }
    public record Call(Pos pos, @Nullable Expr target, String name, List<Expr> args, boolean safe) implements Expr {}
    public record GenericNew(Pos pos, String type, List<TypeRef> typeArgs, List<Expr> args) implements Expr {}
    public record MethodRef(Pos pos, Expr target, String name) implements Expr {}
    public record NotNull(Pos pos, Expr target) implements Expr {}
    public record Unary(Pos pos, String op, Expr operand) implements Expr {}
    public record Binary(Pos pos, String op, Expr left, Expr right) implements Expr {}
    public record Is(Pos pos, Expr target, TypeRef type, boolean negated) implements Expr {}
    public record When(
            Pos pos,
            @Nullable String bindingName,
            @Nullable Pos bindingPos,
            Expr subject,
            List<Arm> arms,
            Pos end) implements Expr {}
    public record Arm(Pos pos, List<Cond> conditions, List<Stmt> body, boolean block) {}
    public record Cond(Pos pos, @Nullable TypeRef type, @Nullable Literal constant) {}
    public record Conditional(Pos pos, Expr condition, Expr then, Expr otherwise) implements Expr {}
    public record Throw(Pos pos, Expr value) implements Expr {}
    public record Elvis(Pos pos, Expr left, Expr right) implements Expr {}
    public record Paren(Pos pos, Expr inner) implements Expr {}
    public record Try(Pos pos, Expr inner) implements Expr {}
    public record Attempt(
            Pos pos,
            Expr body,
            @Nullable String errorName,
            @Nullable Pos errorNamePos,
            @Nullable TypeRef errorType,
            Expr handler) implements Expr {}
    public record Lambda(
            Pos pos,
            List<Param> params,
            boolean arrow,
            List<Stmt> body,
            Pos end) implements Expr {}
    public record Param(Pos pos, String name, @Nullable TypeRef type, List<Decl.Annotation> annotations) {}
    public record Pipe(Pos pos, Expr source, List<Stage> stages) implements Expr {}
    public record Stage(Pos pos, String name, List<Expr> args, @Nullable Lambda lambda) {}
}
