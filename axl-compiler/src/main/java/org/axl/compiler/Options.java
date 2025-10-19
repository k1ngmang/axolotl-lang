package org.axl.compiler;

/**
 * Translation options.
 *
 * @param inlineElvis expand a `?:` at the root of a statement to a temporary and a conditional
 *                    instead of `Nulls.or` with a lambda
 */
public record Options(boolean inlineElvis) {

    public static final Options DEFAULT = new Options(false);
}
