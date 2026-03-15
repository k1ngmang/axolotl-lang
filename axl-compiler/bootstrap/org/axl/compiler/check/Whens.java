package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.Stmt.Return;

@NullMarked public final class Whens {
    private Whens() {}
    public static List<Stmt> returning(Expr.Arm arm) {
        final var statements = new ArrayList<Stmt>(arm.body());
        if (statements.isEmpty()) {
            return statements;
        }
        final var last = statements.getLast();
        if (last instanceof Stmt.ExprStmt $s1 && !($s1.expr() instanceof Expr.Throw)) {
            statements.set(statements.size() - 1, new Return($s1.pos(), $s1.expr()));
        }
        return statements;
    }
}
