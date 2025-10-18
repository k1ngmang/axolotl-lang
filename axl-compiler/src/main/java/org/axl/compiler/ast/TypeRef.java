package org.axl.compiler.ast;

import java.util.List;

public record TypeRef(Pos pos, String name, List<TypeRef> args, boolean nullable) {

    public String simpleName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
