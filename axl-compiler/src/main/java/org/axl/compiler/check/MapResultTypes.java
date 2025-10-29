package org.axl.compiler.check;

import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

/**
 * Element type of the list `|> mapResult { ... }` builds, found without type inference: from the
 * written type of the `val` it initializes, or from the `Result<T, E>` of a function of the file
 * that the lambda calls.
 */
public final class MapResultTypes {

    private static final Set<String> LISTS = Set.of("List", "java.util.List");
    private static final Set<String> RESULTS = Set.of("Result", "axl.lang.Result");

    private MapResultTypes() {}

    public static TypeRef elementType(Expr.Stage stage, SourceFile file, TypeRef declared) {
        if (declared != null && LISTS.contains(declared.name()) && declared.args().size() == 1) {
            return declared.args().getFirst();
        }
        if (!(stage.lambda().body().getFirst() instanceof Stmt.ExprStmt body) || !(body.expr() instanceof Expr.Call call)
                || call.target() != null) {
            return null;
        }
        for (Decl decl : file.decls()) {
            if (decl instanceof Decl.Function fn && fn.name().equals(call.name()) && fn.params().size() == call.args().size()
                    && fn.returnType() != null && RESULTS.contains(fn.returnType().name()) && fn.returnType().args().size() == 2) {
                return fn.returnType().args().getFirst();
            }
        }
        return null;
    }
}
