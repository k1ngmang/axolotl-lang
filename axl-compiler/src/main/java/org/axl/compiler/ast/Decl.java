package org.axl.compiler.ast;

import java.util.List;

public sealed interface Decl {

    Pos pos();

    String name();

    boolean isPrivate();

    /** Exactly one of exprBody and body is set; returnType is null for void. */
    record Function(Pos pos, boolean isPrivate, String name, List<Expr.Param> params, TypeRef returnType,
                    Expr exprBody, Stmt.Block body) implements Decl {}

    record Value(Pos pos, boolean isPrivate, String name, TypeRef type, Expr init) implements Decl {}
}
