package org.axl.compiler.check;

import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.TypeRef;

/**
 * Facts about names the generator relies on.
 *
 * @param implicitIt     lambdas without parameters whose body uses `it`
 * @param mutableRefs    references to `var` locals
 * @param stableNames    references to locals, parameters and top-level values of the file, which no
 *                       call can change while an expression is evaluated
 * @param mapResultTypes element type of the list each `mapResult` stage builds
 */
public record Analysis(Set<Expr.Name> mutableRefs, Set<Expr.Lambda> implicitIt, Set<Expr.Name> stableNames,
                       Map<Expr.Stage, TypeRef> mapResultTypes) {}
