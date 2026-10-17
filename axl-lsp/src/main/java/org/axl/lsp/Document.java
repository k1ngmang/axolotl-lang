package org.axl.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.WritableToken;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.compiler.parse.SourceParser;
import org.axl.javac.JavacClasses;
import org.axl.javac.JavacTypes;
import org.eclipse.lsp4j.Position;

/** An .axl file as the editor has it, with everything derived from its text. */
final class Document {

    final String uri;
    final Path path;
    final String text;
    final List<String> lines;
    // Lines without characters outside the BMP, where code point and UTF-16 columns are the same.
    private final boolean[] bmpOnly;
    final List<? extends Token> tokens;
    /** Null when the text has syntax errors. */
    final SourceFile ast;
    final Translator.Result translation;
    final Module module;
    final String sourcePath;
    private final Options options;
    private GeneratedUnit partial;

    /** The parsed other .axl files of a module, which the translator reads declarations from. */
    interface Siblings {
        List<SourceFile> of(Module module, Path own);
    }

    /** drafts: the directory with the Java of the other .axl files of a module. */
    Document(String uri, String text, ProjectModel project, Function<Module, Path> drafts) {
        this(uri, text, project, drafts, (module, own) -> List.of());
    }

    Document(String uri, String text, ProjectModel project, Function<Module, Path> drafts, Siblings siblings) {
        this.uri = uri;
        this.path = Path.of(URI.create(uri));
        this.text = text;
        this.lines = lines(text);
        this.bmpOnly = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            bmpOnly[i] = lines.get(i).chars().noneMatch(c -> Character.isSurrogate((char) c));
        }
        this.tokens = indexed(new AxolotlLexer(CharStreams.fromString(text)).getAllTokens());
        String className = Translator.className(path.getFileName().toString());
        this.ast = SourceParser.parse(uri, className, text).file();
        this.module = project.moduleOf(path, packageName());
        Path root = module.axlRootOf(path);
        this.sourcePath = (root != null ? root.relativize(path) : path.getFileName()).toString().replace('\\', '/');
        this.options = options(module, drafts.apply(module)).withSiblings(siblings.of(module, path));
        this.translation = Translator.translate(sourcePath, uri, text, options);
    }

    /** Tokens that know their place in the list, so that their neighbours are found without a search. */
    private static List<? extends Token> indexed(List<? extends Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            ((WritableToken) tokens.get(i)).setTokenIndex(i);
        }
        return tokens;
    }

    /** Types of top-level values that depend on Java code or other files come from javac, only when needed. */
    static Options options(Module module, Path drafts) {
        List<Path> sourcepath = new ArrayList<>(List.of(drafts));
        sourcepath.addAll(module.sourcepath());
        return new Options(false).withJavaTypes(new JavacTypes(module.classpath(), sourcepath, module.strictNull()))
                .withClasses(JavacClasses.perQuestion(module.classpath(), module.sourcepath()));
    }

    /**
     * The Java that javac reads for types: the translation, or while it has errors, the translation of
     * the text without the lines that have them. Null when even that fails.
     */
    GeneratedUnit javaUnit() {
        if (translation.unit() != null || ast == null) {
            return translation.unit();
        }
        if (partial == null) {
            partial = partialUnit(sourcePath, uri, text, options, translation.diagnostics());
        }
        return partial;
    }

    /**
     * The Java compiled in place of this file while it does not translate: {@link #javaUnit}, and for
     * a text with syntax errors too, the translation of the text without the lines that have them.
     */
    GeneratedUnit standIn() {
        if (ast != null) {
            return javaUnit();
        }
        if (partial == null) {
            partial = partialUnit(sourcePath, uri, text, options, translation.diagnostics());
        }
        return partial;
    }

    /**
     * The lines with errors become empty, so that the other lines keep their positions. Of syntax
     * errors only the first counts: the parser's later ones may be about lines it misread after it.
     */
    static GeneratedUnit partialUnit(String sourcePath, String displayPath, String text, Options options,
                                     List<AxlDiagnostic> diagnostics) {
        List<String> lines = new ArrayList<>(lines(text));
        for (int attempt = 0; attempt < 3; attempt++) {
            List<AxlDiagnostic> syntax = diagnostics.stream().filter(d -> d.code() == ErrorCode.SYNTAX).limit(1).toList();
            for (AxlDiagnostic d : syntax.isEmpty() ? diagnostics : syntax) {
                if (d.isError() && d.line() >= 1 && d.line() <= lines.size()) {
                    lines.set(d.line() - 1, "");
                }
            }
            Translator.Result result = Translator.translate(sourcePath, displayPath, String.join("\n", lines), options.asDraft());
            if (result.unit() != null) {
                return result.unit();
            }
            diagnostics = result.diagnostics();
        }
        return null;
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
        if (line < 0 || line >= lines.size() || codePointColumn < 0 || bmpOnly[line]) {
            return codePointColumn;
        }
        String text = lines.get(line);
        int length = text.codePointCount(0, text.length());
        return codePointColumn <= length
                ? text.offsetByCodePoints(0, codePointColumn)
                : text.length() + codePointColumn - length;
    }

    /** The 0-based code point column of a UTF-16 offset in a line; a negative offset stays outside the line. */
    int codePointColumn(int line, int utf16Column) {
        if (line < 0 || line >= lines.size() || utf16Column < 0 || bmpOnly[line]) {
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

    /** The line of another version of this text that a line is; -1 for an edited or inserted line. */
    int sameLineIn(Document other, int line) {
        return line >= 0 && line < lines.size() ? linesIn(other)[line] : -1;
    }

    /**
     * For each line, the line of another version of this text that it is, or -1 for an edited or
     * inserted line. The unchanged lines between two edits are matched too, unless there are too
     * many to compare.
     */
    int[] linesIn(Document other) {
        int[] result = new int[lines.size()];
        Arrays.fill(result, -1);
        int leading = sharedLeadingLines(other);
        int trailing = sharedTrailingLines(other, leading);
        for (int i = 0; i < leading; i++) {
            result[i] = i;
        }
        for (int i = 1; i <= trailing; i++) {
            result[lines.size() - i] = other.lines.size() - i;
        }
        matchMiddle(result, lines.subList(leading, lines.size() - trailing),
                other.lines.subList(leading, other.lines.size() - trailing), leading);
        return result;
    }

    private static final long MAX_DIFF_CELLS = 1_000_000;

    /** Matches the lines of a and b by their longest common subsequence. */
    private static void matchMiddle(int[] result, List<String> a, List<String> b, int offset) {
        if (a.isEmpty() || b.isEmpty() || (long) a.size() * b.size() > MAX_DIFF_CELLS) {
            return;
        }
        int[][] common = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        for (int i = 0, j = 0; i < a.size() && j < b.size(); ) {
            if (a.get(i).equals(b.get(j))) {
                result[offset + i++] = offset + j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
    }

    /** The number of lines at the start that are the same in both versions. */
    int sharedLeadingLines(Document other) {
        int count = 0;
        while (count < lines.size() && count < other.lines.size() && lines.get(count).equals(other.lines.get(count))) {
            count++;
        }
        return count;
    }

    private int sharedTrailingLines(Document other, int leading) {
        int max = Math.min(lines.size(), other.lines.size()) - leading;
        int count = 0;
        while (count < max && lines.get(lines.size() - 1 - count).equals(other.lines.get(other.lines.size() - 1 - count))) {
            count++;
        }
        return count;
    }

    String packageName() {
        return ast != null && ast.packageName() != null ? ast.packageName() : "";
    }

    /** Whether the file has top-level values without a written type, which javac types from other files. */
    boolean infersValueTypes() {
        return ast != null && ast.decls().stream().anyMatch(d -> d instanceof Decl.Value value && value.type() == null);
    }

    /** The compiler position just after a token. */
    static Pos end(Token token) {
        String text = token.getText();
        int lastBreak = text.lastIndexOf('\n');
        int lines = (int) text.chars().filter(c -> c == '\n').count();
        int length = text.codePointCount(lastBreak + 1, text.length());
        return lastBreak < 0 ? new Pos(token.getLine(), token.getCharPositionInLine() + 1 + length)
                : new Pos(token.getLine() + lines, length + 1);
    }

    /** The compiler position where a token starts. */
    static Pos start(Token token) {
        return new Pos(token.getLine(), token.getCharPositionInLine() + 1);
    }

    /** When a name is called, its arguments; null when it is not called. */
    List<String> argumentTypes(Token name) {
        return argumentTypes(name, t -> null);
    }

    /**
     * When a name is called, the types of the arguments that are literals or names, null for the
     * others; null when it is not called. nameType: the type of a name argument, or null.
     */
    List<String> argumentTypes(Token name, Function<Token, String> nameType) {
        Token open = next(name);
        if (open == null || open.getType() != AxolotlLexer.LPAREN || open.getLine() != name.getLine()) {
            return null;
        }
        List<String> types = new ArrayList<>();
        List<Token> argument = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens.subList(open.getTokenIndex() + 1, tokens.size())) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();
            if (depth == 0 && (type == AxolotlLexer.COMMA || type == AxolotlLexer.RPAREN)) {
                if (!argument.isEmpty()) {
                    types.add(argumentType(argument, nameType));
                }
                argument.clear();
                if (type == AxolotlLexer.RPAREN) {
                    break;
                }
                continue;
            }
            if (type == AxolotlLexer.LPAREN || type == AxolotlLexer.LBRACE) {
                depth++;
            } else if (type == AxolotlLexer.RPAREN || type == AxolotlLexer.RBRACE) {
                depth--;
            }
            argument.add(token);
        }
        return types;
    }

    private static String argumentType(List<Token> argument, Function<Token, String> nameType) {
        if (argument.size() != 1) {
            return null;
        }
        Token token = argument.getFirst();
        return switch (token.getType()) {
            case AxolotlLexer.IDENTIFIER -> nameType.apply(token);
            case AxolotlLexer.STRING_LITERAL -> "String";
            case AxolotlLexer.INT_LITERAL -> token.getText().toLowerCase(Locale.ROOT).endsWith("l") ? "Long" : "Int";
            case AxolotlLexer.FLOAT_LITERAL -> "Double";
            case AxolotlLexer.TRUE, AxolotlLexer.FALSE -> "Boolean";
            default -> null;
        };
    }

    /**
     * Where a name first appears from a position on, such as the name of a data class field after
     * its `val`; the position itself when it does not.
     */
    Pos nameAfter(Pos from, String name) {
        for (Token token : tokens.subList(firstTokenFrom(from), tokens.size())) {
            if (token.getType() == AxolotlLexer.IDENTIFIER && token.getText().equals(name)) {
                return start(token);
            }
        }
        return from;
    }

    /** The positions of the constants of an enum, whose names the AST keeps without them, from the tokens after its brace. */
    List<Pos> constantPositions(Decl.EnumDecl enumeration) {
        List<Pos> result = new ArrayList<>();
        boolean open = false;
        // Names in the arguments of a constant, `DEFAULT(Format.CSV)`, are not constants.
        int depth = 0;
        for (Token token : tokens.subList(firstTokenFrom(enumeration.namePos()), tokens.size())) {
            if (result.size() == enumeration.constants().size()) {
                break;
            }
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (!open) {
                open = token.getType() == AxolotlLexer.LBRACE;
            } else if (token.getType() == AxolotlLexer.LPAREN) {
                depth++;
            } else if (token.getType() == AxolotlLexer.RPAREN) {
                depth--;
            } else if (token.getType() == AxolotlLexer.IDENTIFIER && depth == 0) {
                result.add(start(token));
            }
        }
        return result;
    }

    /** The index of the first token that starts at or after a position; the number of tokens if none does. */
    int firstTokenFrom(Pos pos) {
        int low = 0;
        int high = tokens.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (Symbols.before(pos, start(tokens.get(middle)))) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** The default-channel token after the given one, or null. */
    Token next(Token token) {
        for (int i = token.getTokenIndex() + 1; i > 0 && i < tokens.size(); i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return tokens.get(i);
            }
        }
        return null;
    }

    /** The default-channel token before the given one, or null. */
    Token previous(Token token) {
        for (int i = token.getTokenIndex() - 1; i >= 0; i--) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return tokens.get(i);
            }
        }
        return null;
    }
}
