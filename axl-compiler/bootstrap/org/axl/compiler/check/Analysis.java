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
        Map<Expr.Stage, TypeRef> mapResultTypes) {
}
