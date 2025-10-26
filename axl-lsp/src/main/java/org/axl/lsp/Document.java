package org.axl.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.compiler.parse.SourceParser;

/** An .axl file as the editor has it, with everything derived from its text. */
final class Document {

    final String uri;
    final Path path;
    final String text;
    final List<String> lines;
    final List<? extends Token> tokens;
    /** Null when the text has syntax errors. */
    final SourceFile ast;
    final Translator.Result translation;
    final Module module;
    final String sourcePath;

    Document(String uri, String text, ProjectModel project) {
        this.uri = uri;
        this.path = Path.of(URI.create(uri));
        this.text = text;
        this.lines = text.lines().toList();
        this.tokens = new AxolotlLexer(CharStreams.fromString(text)).getAllTokens();
        String className = Translator.className(path.getFileName().toString());
        this.ast = SourceParser.parse(uri, className, text).file();
        String packageName = ast != null && ast.packageName() != null ? ast.packageName() : "";
        this.module = project.moduleOf(path, packageName);
        Path root = module.axlRootOf(path);
        this.sourcePath = (root != null ? root.relativize(path) : path.getFileName()).toString().replace('\\', '/');
        this.translation = Translator.translate(sourcePath, uri, text);
    }

    List<AxlDiagnostic> diagnostics() {
        return translation.diagnostics();
    }

    /** The token at a 0-based LSP position, or null. */
    Token tokenAt(int line, int character) {
        for (Token token : tokens) {
            int start = token.getCharPositionInLine();
            int end = start + token.getText().length();
            if (token.getLine() - 1 == line && character >= start && character <= end && token.getChannel() == Token.DEFAULT_CHANNEL) {
                return token;
            }
        }
        return null;
    }

    /** The default-channel token before the given one, or null. */
    Token previous(Token token) {
        Token previous = null;
        for (Token t : tokens) {
            if (t == token) {
                return previous;
            }
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                previous = t;
            }
        }
        return null;
    }
}
