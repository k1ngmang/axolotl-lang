package org.axl.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.compiler.parse.SourceParser;
import org.axl.javac.JavacTypes;
import org.eclipse.lsp4j.Position;

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
        this.lines = lines(text);
        this.tokens = new AxolotlLexer(CharStreams.fromString(text)).getAllTokens();
        String className = Translator.className(path.getFileName().toString());
        this.ast = SourceParser.parse(uri, className, text).file();
        String packageName = ast != null && ast.packageName() != null ? ast.packageName() : "";
        this.module = project.moduleOf(path, packageName);
        Path root = module.axlRootOf(path);
        this.sourcePath = (root != null ? root.relativize(path) : path.getFileName()).toString().replace('\\', '/');
        // Types of top-level values that depend on Java code come from javac, only when needed.
        Options options = new Options(false).withJavaTypes(new JavacTypes(module.classpath(), module.javaRoots()));
        this.translation = Translator.translate(sourcePath, uri, text, options);
    }

    /** Lines as the lexer counts them, including the empty line after a final newline. */
    private static List<String> lines(String text) {
        return Arrays.stream(text.split("\n", -1)).map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l).toList();
    }

    List<AxlDiagnostic> diagnostics() {
        return translation.diagnostics();
    }

    /**
     * The token at a 0-based LSP position, or null. Between two tokens, as in `f(x`, the one that
     * starts there wins over the one that ends there.
     */
    Token tokenAt(int line, int character) {
        int column = codePointColumn(line, character);
        Token ending = null;
        for (Token token : tokens) {
            if (token.getLine() - 1 != line || token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int start = token.getCharPositionInLine();
            int end = start + token.getStopIndex() - token.getStartIndex() + 1;
            if (column >= start && column < end) {
                return token;
            }
            if (column == end) {
                ending = token;
            }
        }
        return ending;
    }

    /**
     * The UTF-16 offset of a 0-based code point column of a line. The lexer and the compiler count
     * code points, LSP counts UTF-16 units; they differ after emoji and other non-BMP characters.
     */
    int utf16Column(int line, int codePointColumn) {
        if (line < 0 || line >= lines.size()) {
            return codePointColumn;
        }
        String text = lines.get(line);
        int length = text.codePointCount(0, text.length());
        return codePointColumn <= length
                ? text.offsetByCodePoints(0, codePointColumn)
                : text.length() + codePointColumn - length;
    }

    /** The 0-based code point column of a UTF-16 offset in a line. */
    int codePointColumn(int line, int utf16Column) {
        if (line < 0 || line >= lines.size()) {
            return utf16Column;
        }
        String text = lines.get(line);
        return utf16Column <= text.length()
                ? text.codePointCount(0, utf16Column)
                : text.codePointCount(0, text.length()) + utf16Column - text.length();
    }

    /** The compiler position of a 0-based LSP position. */
    Pos pos(int line, int character) {
        return new Pos(line + 1, codePointColumn(line, character) + 1);
    }

    /** The LSP position of a compiler position. */
    Position position(Pos pos) {
        return new Position(pos.line() - 1, utf16Column(pos.line() - 1, pos.column() - 1));
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
