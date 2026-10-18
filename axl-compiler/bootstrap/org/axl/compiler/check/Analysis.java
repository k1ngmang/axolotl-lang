package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.TypeRef;

@NullMarked public record Analysis(
        Set<Expr.Name> mutableRefs,
        Set<Expr.Lambda> implicitIt,
        Set<Expr.Name> stableNames,
        Set<Expr.Name> staticNames,
        Set<Expr.Name> fieldNames,
        Map<Expr.Stage, TypeRef> mapResultTypes,
        Map<Expr.Name, String> superOwners) {
    public Analysis {
        Nulls.param(mutableRefs, "mutableRefs");
        Nulls.param(implicitIt, "implicitIt");
        Nulls.param(stableNames, "stableNames");
        Nulls.param(staticNames, "staticNames");
        Nulls.param(fieldNames, "fieldNames");
        Nulls.param(mapResultTypes, "mapResultTypes");
        Nulls.param(superOwners, "superOwners");
    }
}
