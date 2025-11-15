package org.axl.compiler.ast;

import java.util.List;

public sealed interface Decl {

    Pos pos();

    /** Where the declared name is, for errors about the name. */
    Pos namePos();

    String name();

    boolean isPrivate();

    /** Exactly one of exprBody and body is set; returnType is null for void. */
    record Function(Pos pos, Pos namePos, boolean isPrivate, String name, List<Expr.Param> params, TypeRef returnType,
                    Expr exprBody, Stmt.Block body) implements Decl {}

    record Value(Pos pos, Pos namePos, boolean isPrivate, String name, TypeRef type, Expr init) implements Decl {}
}
