package org.axl.compiler;

import java.util.List;
import org.axl.compiler.ast.SourceFile;

/**
 * Translation options.
 *
 * @param inlineElvis expand a `?:` at the root of a statement to a temporary and a conditional
 *                    instead of `Nulls.or` with a lambda
 * @param javaTypes   compiler access for the types of top-level values, or null
 * @param draft       translate even when the type of a top-level value is unknown, using `Object`;
 *                    other files can then be compiled against the draft while types are worked out
 * @param siblings    the other .axl files of the build, parsed: the translator reads their declarations,
 *                    such as the fields of a generic data class a `when` takes apart, or what a function returns
 */
public record Options(boolean inlineElvis, JavaTypes javaTypes, boolean draft, List<SourceFile> siblings) {

    public static final Options DEFAULT = new Options(false);

    public Options(boolean inlineElvis) {
        this(inlineElvis, null, false, List.of());
    }

    public Options withJavaTypes(JavaTypes types) {
        return new Options(inlineElvis, types, false, siblings);
    }

    public Options asDraft() {
        return new Options(inlineElvis, javaTypes, true, siblings);
    }

    public Options withSiblings(List<SourceFile> files) {
        return new Options(inlineElvis, javaTypes, draft, files);
    }
}
