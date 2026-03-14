package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public record Pos(int line, int column) {
}
