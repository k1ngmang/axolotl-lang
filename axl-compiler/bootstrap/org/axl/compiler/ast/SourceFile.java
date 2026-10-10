package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public record SourceFile(
        String path,
        String className,
        @Nullable Pos packagePos,
        @Nullable String packageName,
        List<Import> imports,
        List<Decl> decls) {
    public SourceFile {
        Nulls.param(path, "path");
        Nulls.param(className, "className");
        Nulls.param(imports, "imports");
        Nulls.param(decls, "decls");
    }
}
