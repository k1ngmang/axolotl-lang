package org.axl.compiler.check;

import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Stmt;

/** How the arms of a `when` read in the places where it stands for the value of a function. */
public final class Whens {

    private Whens() {}

    /** The arm's statements with its value, the last expression, returned: `return when (x) {...}`. */
    public static List<Stmt> returning(Expr.Arm arm) {
        List<Stmt> statements = new ArrayList<>(arm.body());
        if (!statements.isEmpty() && statements.getLast() instanceof Stmt.ExprStmt last && !(last.expr() instanceof Expr.Throw)) {
            statements.set(statements.size() - 1, new Stmt.Return(last.pos(), last.expr()));
        }
        return statements;
    }
}
