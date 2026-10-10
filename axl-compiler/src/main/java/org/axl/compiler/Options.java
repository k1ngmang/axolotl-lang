package org.axl.compiler;

import java.util.List;
import org.axl.compiler.ast.SourceFile;

/**
 * Translation options.
 *
 * @param inlineElvis expand a `?:` at the root of a statement to a temporary and a conditional
 *                    instead of `Nulls.or` with a lambda
 * @param paramChecks check the non-null parameters of the functions and constructors Java code can call
 *                    on entry, so that a null from Java fails there and names the parameter
 * @param javaTypes   compiler access for the types of top-level values, or null
 * @param draft       translate even when the type of a top-level value is unknown, using `Object`;
 *                    other files can then be compiled against the draft while types are worked out
 * @param siblings    the other .axl files of the build, parsed: the translator reads their declarations,
 *                    such as the fields of a generic data class a `when` takes apart, or what a function returns
 * @param classes     the classes of the JDK, the classpath and Java sources, such as the type parameters of a
 *                    generic sealed type from a jar that an `is` narrows
 */
public record Options(boolean inlineElvis, boolean paramChecks, JavaTypes javaTypes, boolean draft, List<SourceFile> siblings,
                      JavaClasses classes) {

    public static final Options DEFAULT = new Options(false);

    public Options(boolean inlineElvis) {
        this(inlineElvis, true);
    }

    public Options(boolean inlineElvis, boolean paramChecks) {
        this(inlineElvis, paramChecks, null, false, List.of(), ReflectedClasses.JDK);
    }

    public Options withJavaTypes(JavaTypes types) {
        return new Options(inlineElvis, paramChecks, types, false, siblings, classes);
    }

    public Options asDraft() {
        return new Options(inlineElvis, paramChecks, javaTypes, true, siblings, classes);
    }

    public Options withSiblings(List<SourceFile> files) {
        return new Options(inlineElvis, paramChecks, javaTypes, draft, files, classes);
    }

    public Options withClasses(JavaClasses readable) {
        return new Options(inlineElvis, paramChecks, javaTypes, draft, siblings, readable);
    }
}
