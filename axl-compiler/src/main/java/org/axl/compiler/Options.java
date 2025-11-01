package org.axl.compiler;

/**
 * Translation options.
 *
 * @param inlineElvis expand a `?:` at the root of a statement to a temporary and a conditional
 *                    instead of `Nulls.or` with a lambda
 * @param javaTypes   compiler access for the types of top-level values, or null
 * @param draft       translate even when the type of a top-level value is unknown, using `Object`;
 *                    other files can then be compiled against the draft while types are worked out
 */
public record Options(boolean inlineElvis, JavaTypes javaTypes, boolean draft) {

    public static final Options DEFAULT = new Options(false);

    public Options(boolean inlineElvis) {
        this(inlineElvis, null, false);
    }

    public Options withJavaTypes(JavaTypes types) {
        return new Options(inlineElvis, types, false);
    }

    public Options asDraft() {
        return new Options(inlineElvis, javaTypes, true);
    }
}
