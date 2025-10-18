package org.axl.compiler.ast;

public record Import(Pos pos, Kind kind, String name) {

    public enum Kind { TYPE, FN, VAL }

    public String simpleName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
