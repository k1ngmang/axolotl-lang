package org.axl.compiler.parse;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.ast.SourceFile;

public final class SourceParser {

    /** file is null when the source has syntax errors. */
    public record Result(SourceFile file, List<AxlDiagnostic> diagnostics) {}

    private SourceParser() {}

    public static Result parse(String path, String className, String source) {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        var listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                // ANTLR recovery tends to report the same broken line several times.
                boolean seen = diagnostics.stream().anyMatch(d -> d.line() == line);
                if (!seen) {
                    diagnostics.add(AxlDiagnostic.error(path, line, charPositionInLine + 1, ErrorCode.SYNTAX,
                            msg.replace("IDENTIFIER", "name")));
                }
            }
        };
        var lexer = new AxolotlLexer(CharStreams.fromString(source, path));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        var tokens = new CommonTokenStream(lexer);
        var parser = new AxolotlParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        var tree = parser.compilationUnit();
        if (!diagnostics.isEmpty()) {
            return new Result(null, diagnostics);
        }
        for (Token token : tokens.getTokens()) {
            if (token.getType() == AxolotlLexer.IDENTIFIER && token.getText().indexOf('$') >= 0) {
                diagnostics.add(AxlDiagnostic.error(path, token.getLine(), token.getCharPositionInLine() + 1,
                        ErrorCode.DOLLAR_IN_NAME, "`$` is not allowed in names: `" + token.getText() + "`"));
            }
        }
        try {
            return new Result(new AstBuilder().build(path, className, tree), diagnostics);
        } catch (AstBuilder.Rejected e) {
            diagnostics.add(AxlDiagnostic.error(path, e.pos.line(), e.pos.column(), ErrorCode.SYNTAX, e.getMessage()));
            return new Result(null, diagnostics);
        }
    }
}
