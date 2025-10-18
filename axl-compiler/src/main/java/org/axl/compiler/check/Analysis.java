package org.axl.compiler.check;

import java.util.Set;
import org.axl.compiler.ast.Expr;

/**
 * Facts about names the generator relies on.
 *
 * @param implicitIt lambdas without parameters whose body uses `it`
 * @param mutableRefs references to `var` locals
 */
public record Analysis(Set<Expr.Lambda> implicitIt, Set<Expr.Name> mutableRefs) {}
