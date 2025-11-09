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
public record GeneratedSource(GeneratedUnit unit, Path javaFile, String displayPath, List<String> axlLines) {}
