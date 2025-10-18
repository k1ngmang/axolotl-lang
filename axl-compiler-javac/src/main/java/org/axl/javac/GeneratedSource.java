package org.axl.javac;

import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.GeneratedUnit;

/**
 * A generated .java file together with what is needed to report its errors on the .axl source.
 *
 * @param displayPath how the .axl file is named in diagnostics
 * @param axlLines    the .axl source split into lines
 */
public record GeneratedSource(GeneratedUnit unit, Path javaFile, String displayPath, List<String> axlLines) {

    /** Column of the first non-blank character of an .axl line, 1-based. */
    public int firstColumn(int axlLine) {
        if (axlLine < 1 || axlLine > axlLines.size()) {
            return 1;
        }
        String text = axlLines.get(axlLine - 1);
        int column = 0;
        while (column < text.length() && Character.isWhitespace(text.charAt(column))) {
            column++;
        }
        return column + 1;
    }
}
