package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public record TypeRef(Pos pos, String name, List<TypeRef> args, boolean nullable) {
    public TypeRef {
        Nulls.param(pos, "pos");
        Nulls.param(name, "name");
        Nulls.param(args, "args");
    }
    public static final String ANY = "*";
}
