package org.axl.compiler.ast;

import java.util.List;

/** packageName is null when the file has no package clause. */
public record SourceFile(String path, String className, Pos packagePos, String packageName,
                         List<Import> imports, List<Decl> decls) {}
