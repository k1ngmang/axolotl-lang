package org.axl.compiler.check;

import java.util.List;
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
        // With overloads of the same arity the called one is not known here.
        List<Decl.Function> candidates = file.decls().stream()
                .filter(d -> d instanceof Decl.Function fn && fn.name().equals(call.name()) && fn.params().size() == call.args().size())
                .map(Decl.Function.class::cast).toList();
        if (candidates.size() != 1) {
            return null;
        }
        TypeRef result = candidates.getFirst().returnType();
        return result != null && RESULTS.contains(result.name()) && result.args().size() == 2 ? result.args().getFirst() : null;
    }
}
