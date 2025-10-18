package org.axl.compiler.ast;

/** A 1-based line and column in an .axl file. */
public record Pos(int line, int column) {
    @Override
    public String toString() {
        return line + ":" + column;
    }
}
