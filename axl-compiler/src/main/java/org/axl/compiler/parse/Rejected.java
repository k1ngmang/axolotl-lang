package org.axl.compiler.parse;

import org.axl.compiler.ast.Pos;

/** A construct the grammar accepts but the language does not, reported as a syntax error. */
final class Rejected extends RuntimeException {
    final Pos pos;

    Rejected(Pos pos, String message) {
        super(message, null, false, false);
        this.pos = pos;
    }
}
