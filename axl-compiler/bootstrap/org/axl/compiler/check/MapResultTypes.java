package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class MapResultTypes {
    private MapResultTypes() {}
    private static final Set<String> lists = Set.of("List", "java.util.List");
    private static final Set<String> results = Set.of("Result", "axl.lang.Result");
    public static @Nullable TypeRef elementType(Expr.Stage stage, SourceFile file, List<Decl.Function> methods, @Nullable TypeRef declared) {
        if (declared != null && lists.contains(declared.name()) && declared.args().size() == 1) {
            return declared.args().getFirst();
        }
        final var call = calledFunction(stage);
        if (call == null) {
            return null;
        }
        final var candidates = functionsLike(file, methods, call);
        if (candidates.size() != 1) {
            return null;
        }
        final var result = candidates.getFirst().returnType();
        if (result != null && results.contains(result.name()) && result.args().size() == 2) {
            return result.args().getFirst();
        }
        return null;
    }
    private static Expr.@Nullable Call calledFunction(Expr.Stage stage) {
        final var first = java.util.Objects.requireNonNull(stage.lambda()).body().getFirst();
        if (!(first instanceof Stmt.ExprStmt first$1)) {
            return null;
        }
        final var expr = first$1.expr();
        if (!(expr instanceof Expr.Call expr$2) || expr$2.target() != null) {
            return null;
        }
        return expr$2;
    }
    private static List<Decl.Function> functionsLike(SourceFile file, List<Decl.Function> methods, Expr.Call call) {
        final var found = new ArrayList<Decl.Function>();
        for (final var m : methods) {
            if (m.name().equals(call.name())) {
                found.add(m);
            }
        }
        if (!found.isEmpty()) {
            return found.stream().filter(m -> m.params().size() == call.args().size()).toList();
        }
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1 && decl$1.name().equals(call.name()) && decl$1.params().size() == call.args().size()) {
                found.add(decl$1);
            }
        }
        return found;
    }
}
