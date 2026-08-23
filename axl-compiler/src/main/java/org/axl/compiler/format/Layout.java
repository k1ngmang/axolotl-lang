package org.axl.compiler.format;

import static org.axl.compiler.parse.AxolotlLexer.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.compiler.parse.AxolotlParser;
import org.axl.compiler.parse.SourceParser;

/**
 * Lays out the tokens of a file that parses: spaces within a line, indentation and blank lines, and
 * the order of the imports. Which line every token is on stays as the author wrote it, since a line
 * break can end a statement; only blank lines are added or removed.
 */
final class Layout {

    private static final int INDENT = 4;
    private static final int TAB = 4;

    private static final Map<String, Boolean> MERGES = new ConcurrentHashMap<>();

    /** The code and comment tokens, without whitespace. */
    private final List<Token> tokens = new ArrayList<>();
    /** The whitespace before each token, and at the end after the last one. */
    private final List<String> gaps = new ArrayList<>();
    private final Map<Integer, ParserRuleContext> parents = new HashMap<>();
    /** Token indexes of the first tokens of declarations, statements, members, `when` branches and enum constants. */
    private final Set<Integer> itemStarts = new HashSet<>();
    private final AxolotlParser.CompilationUnitContext tree;
    private final Map<Integer, Integer> positions = new HashMap<>();

    /** The logical lines: the position of the first token of each, and the line of each token. */
    private final List<Integer> lineStarts = new ArrayList<>();
    private int[] lineOf;
    private int[] indent;
    private final Map<Integer, Integer> blankLines = new HashMap<>();

    private Layout(SourceParser.Syntax syntax) {
        tree = syntax.tree();
        StringBuilder gap = new StringBuilder();
        for (Token t : syntax.tokens()) {
            if (t.getType() == WS) {
                gap.append(t.getText());
            } else if (t.getType() == Token.EOF) {
                break;
            } else {
                positions.put(t.getTokenIndex(), tokens.size());
                tokens.add(t);
                gaps.add(gap.toString());
                gap.setLength(0);
            }
        }
        gaps.add(gap.toString());
        collect(tree);
    }

    /** source has `\n` line ends and no byte order mark, and parses without errors. */
    static String format(String source) {
        return new Layout(SourceParser.syntax(source)).render();
    }

    private void collect(ParseTree node) {
        if (node instanceof TerminalNode terminal) {
            if (terminal.getParent() instanceof ParserRuleContext parent) {
                parents.put(terminal.getSymbol().getTokenIndex(), parent);
            }
            return;
        }
        ParserRuleContext ctx = (ParserRuleContext) node;
        switch (ctx) {
            case AxolotlParser.CompilationUnitContext unit -> {
                if (unit.packageDecl() != null) {
                    item(unit.packageDecl());
                }
                unit.importDecl().forEach(this::item);
                unit.topLevel().forEach(this::item);
            }
            case AxolotlParser.BlockContext block -> block.statement().forEach(this::item);
            case AxolotlParser.LambdaContext lambda -> lambda.statement().forEach(this::item);
            case AxolotlParser.ClassDeclContext decl -> decl.classMember().forEach(this::item);
            case AxolotlParser.DataClassDeclContext decl -> decl.functionDecl().forEach(this::item);
            case AxolotlParser.InterfaceDeclContext decl -> {
                decl.field().forEach(this::item);
                decl.interfaceMethod().forEach(this::item);
            }
            case AxolotlParser.EnumDeclContext decl -> decl.enumConstant().forEach(this::item);
            case AxolotlParser.WhenExpressionContext when -> when.whenArm().forEach(this::item);
            case AxolotlParser.AnnotationValueContext value -> value.annotationValue().forEach(this::item);
            default -> { }
        }
        // `@Test` on a line of its own: the declaration after it starts a line like the annotation.
        if (ctx.getChildCount() > 1 && ctx.getChild(0) instanceof AxolotlParser.AnnotationContext) {
            for (int i = 0; i < ctx.getChildCount(); i++) {
                ParseTree child = ctx.getChild(i);
                if (child instanceof AxolotlParser.AnnotationContext annotation) {
                    item(annotation);
                } else {
                    itemStarts.add(child instanceof TerminalNode t ? t.getSymbol().getTokenIndex()
                            : ((ParserRuleContext) child).getStart().getTokenIndex());
                    break;
                }
            }
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            collect(ctx.getChild(i));
        }
    }

    private void item(ParserRuleContext ctx) {
        itemStarts.add(ctx.getStart().getTokenIndex());
    }

    private String render() {
        if (tokens.isEmpty()) {
            return "";
        }
        lineOf = new int[tokens.size()];
        for (int k = 0; k < tokens.size(); k++) {
            if (k == 0 || newlines(k) > 0) {
                lineStarts.add(k);
            }
            lineOf[k] = lineStarts.size() - 1;
        }
        indent = new int[lineStarts.size()];
        indentCode();
        indentComments();
        separateTopLevel();
        List<Line> lines = emit();
        sortImports(lines);
        StringBuilder out = new StringBuilder();
        for (Line line : lines) {
            out.append(line.text).append('\n');
        }
        return out.toString();
    }

    private static final class Frame {
        final int openerLine;
        /** The line whose indentation the brackets' content is one indentation deeper than, and the closing bracket's. */
        final int baseLine;
        final boolean paren;
        /** A `(` that ends its line: what follows starts on the next lines, one indentation deeper. */
        final boolean hanging;
        /** The line where the latest element in the brackets starts. */
        int itemLine = -1;

        Frame(int openerLine, int baseLine, boolean paren, boolean hanging) {
            this.openerLine = openerLine;
            this.baseLine = baseLine;
            this.paren = paren;
            this.hanging = hanging;
        }
    }

    /**
     * A line that starts a statement, a declaration or an element of brackets broken after `(` is indented
     * by the brackets around it, and a line that starts with `)` or `}` like the line of its opening bracket.
     * Any other line goes on with the one before: it keeps its indentation relative to the line where its
     * statement or element starts, the layout the author chose for a long expression, but at least one
     * indentation more.
     */
    private void indentCode() {
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(-1, -1, false, false));
        for (int line = 0; line < lineStarts.size(); line++) {
            int first = firstCode(line);
            if (first < 0) {
                continue;
            }
            Frame frame = frames.peek();
            int type = tokens.get(first).getType();
            if ((type == RPAREN || type == RBRACE) && frame.openerLine >= 0) {
                indent[line] = indent[frame.baseLine];
            } else if (startsElement(first, frame)) {
                indent[line] = frame.openerLine < 0 ? 0 : indent[frame.baseLine] + INDENT;
            } else {
                int anchor = Math.max(frame.itemLine, frame.openerLine);
                if (anchor < 0) {
                    indent[line] = 0;
                } else {
                    int relative = originalIndent(line) - originalIndent(anchor);
                    // `} else {` written as `}` and `else {` on two lines.
                    indent[line] = indent[anchor] + (type == ELSE && relative <= 0 ? 0 : Math.max(relative, INDENT));
                }
            }
            for (int k = first; k < tokens.size() && lineOf[k] == line; k++) {
                if (comment(k)) {
                    continue;
                }
                if (startsElement(k, frames.peek())) {
                    frames.peek().itemLine = line;
                }
                int t = tokens.get(k).getType();
                if (t == LPAREN || t == LBRACE) {
                    Frame outer = frames.peek();
                    int base = body(k) && outer.itemLine >= 0 ? outer.itemLine : line;
                    frames.push(new Frame(line, base, t == LPAREN, lastCodeOfLine(k)));
                } else if ((t == RPAREN || t == RBRACE) && frames.size() > 1) {
                    frames.pop();
                }
            }
        }
    }

    /**
     * The `{` of a function, a class or a statement such as `if`, whose header may go on over several lines:
     * the body and the `}` are indented by the line where the declaration or statement starts. A lambda's
     * and a `when`'s are indented by the line of the `{`.
     */
    private boolean body(int k) {
        ParserRuleContext parent = parents.get(tokens.get(k).getTokenIndex());
        return tokens.get(k).getType() == LBRACE && !(parent instanceof AxolotlParser.LambdaContext
                || parent instanceof AxolotlParser.WhenExpressionContext || parent instanceof AxolotlParser.AnnotationValueContext);
    }

    private boolean startsElement(int k, Frame frame) {
        if (!frame.paren) {
            return itemStarts.contains(tokens.get(k).getTokenIndex());
        }
        if (!frame.hanging) {
            return false;
        }
        int previous = previousCode(k);
        if (previous < 0) {
            return false;
        }
        Token before = tokens.get(previous);
        return before.getType() == LPAREN || before.getType() == COMMA && !inType(before);
    }

    /** A comment on a line of its own is indented like the code after it, or inside the brackets that code closes. */
    private void indentComments() {
        int next = -1;
        for (int line = lineStarts.size() - 1; line >= 0; line--) {
            int first = firstCode(line);
            if (first >= 0) {
                next = line;
                continue;
            }
            if (next < 0) {
                indent[line] = 0;
            } else {
                int type = tokens.get(firstCode(next)).getType();
                indent[line] = indent[next] + (type == RPAREN || type == RBRACE ? INDENT : 0);
            }
        }
    }

    private record Unit(ParserRuleContext ctx, int start, int stop) {

        boolean isImport() {
            return ctx instanceof AxolotlParser.ImportDeclContext;
        }

        boolean isPackage() {
            return ctx instanceof AxolotlParser.PackageDeclContext;
        }
    }

    private List<Unit> units() {
        List<Unit> units = new ArrayList<>();
        if (tree.packageDecl() != null) {
            units.add(unit(tree.packageDecl()));
        }
        tree.importDecl().forEach(i -> units.add(unit(i)));
        for (AxolotlParser.TopLevelContext top : tree.topLevel()) {
            // A `;` alone between declarations is not one.
            if (!(top.getChildCount() == 1 && top.SEMI() != null)) {
                units.add(unit(top));
            }
        }
        return units;
    }

    private Unit unit(ParserRuleContext ctx) {
        return new Unit(ctx, positions.get(ctx.getStart().getTokenIndex()), positions.get(ctx.getStop().getTokenIndex()));
    }

    /**
     * The imports are one group, right after the package, and one blank line separates the package,
     * the imports and each declaration. Declarations of one line each may also stand together, as a group.
     */
    private void separateTopLevel() {
        List<Unit> units = units();
        for (int i = 1; i < units.size(); i++) {
            Unit before = units.get(i - 1);
            Unit after = units.get(i);
            if (tokens.get(before.stop).getLine() == tokens.get(after.start).getLine()) {
                continue;
            }
            if (before.isImport() && after.isImport()) {
                for (int k = before.stop + 1; k <= after.start; k++) {
                    if (newlines(k) > 0) {
                        blankLines.put(k, 0);
                    }
                }
                continue;
            }
            boolean declarations = !before.isImport() && !before.isPackage() && !after.isImport();
            if (declarations && oneLine(before) && oneLine(after)) {
                continue;
            }
            boolean blank = false;
            int firstBreak = -1;
            for (int k = before.stop + 1; k <= after.start; k++) {
                blank |= newlines(k) > 1;
                if (firstBreak < 0 && newlines(k) > 0) {
                    firstBreak = k;
                }
            }
            if (!blank && firstBreak >= 0) {
                blankLines.put(firstBreak, 1);
            }
        }
    }

    private boolean oneLine(Unit unit) {
        return tokens.get(unit.start).getLine() == tokens.get(unit.stop).getLine();
    }

    /** An output line; first is the position of its first token, or -1 for a blank line. */
    private record Line(String text, int first) {}

    private List<Line> emit() {
        List<Line> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int first = 0;
        for (int k = 0; k < tokens.size(); k++) {
            if (k == 0 || newlines(k) > 0) {
                if (k > 0) {
                    lines.add(new Line(text.toString(), first));
                    text.setLength(0);
                    int blanks = blankLines.getOrDefault(k, Math.min(newlines(k) - 1, 1));
                    for (int b = 0; b < blanks; b++) {
                        lines.add(new Line("", -1));
                    }
                }
                first = k;
                text.append(" ".repeat(indent[lineOf[k]]));
            } else {
                text.append(space(k - 1, k));
            }
            text.append(tokenText(k));
        }
        lines.add(new Line(text.toString(), first));
        return lines;
    }

    private String tokenText(int k) {
        Token t = tokens.get(k);
        if (t.getType() == LINE_COMMENT) {
            return t.getText().stripTrailing();
        }
        if (t.getType() != BLOCK_COMMENT) {
            return t.getText();
        }
        String[] parts = t.getText().split("\n", -1);
        // The lines of a comment that starts its line move with it.
        boolean startsLine = k == 0 || newlines(k) > 0;
        int shift = startsLine ? indent[lineOf[k]] - originalIndent(lineOf[k]) : 0;
        StringBuilder text = new StringBuilder(parts[0].stripTrailing());
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].stripTrailing();
            String body = part.stripLeading();
            int width = body.isEmpty() ? 0 : Math.max(0, width(part.substring(0, part.length() - body.length())) + shift);
            text.append('\n').append(" ".repeat(width)).append(body);
        }
        return text.toString();
    }

    /** The spaces between two tokens on one line. */
    private String space(int a, int b) {
        Token x = tokens.get(a);
        Token y = tokens.get(b);
        if (comment(a) || comment(b)) {
            // The space around comments is the author's, such as comments aligned in a column.
            return y.getType() == LINE_COMMENT && gaps.get(b).isEmpty() ? " " : gaps.get(b);
        }
        int spaces = spaces(x, y);
        return spaces == 0 && merges(x, y) ? " " : spaces == 0 ? "" : " ";
    }

    private int spaces(Token x, Token y) {
        int tx = x.getType();
        int ty = y.getType();
        ParserRuleContext px = parents.get(x.getTokenIndex());
        ParserRuleContext py = parents.get(y.getTokenIndex());
        if (ty == COMMA || ty == SEMI && tx != ARROW && tx != LBRACE) {
            return 0;
        }
        if (tx == COMMA || tx == SEMI) {
            return 1;
        }
        if (tx == LPAREN || ty == RPAREN) {
            return 0;
        }
        if (tx == DOT || tx == SAFE_DOT || tx == COLONCOLON || ty == DOT || ty == SAFE_DOT || ty == COLONCOLON) {
            return 0;
        }
        if (tx == AT) {
            return 0;
        }
        if (tx == LBRACE && ty == RBRACE) {
            return 0;
        }
        if (tx == LBRACE || ty == LBRACE || ty == RBRACE) {
            return 1;
        }
        if (ty == BANGBANG && py instanceof AxolotlParser.NotNullSuffixContext) {
            return 0;
        }
        if ((tx == BANG || tx == BANGBANG || tx == MINUS || tx == PLUS) && px instanceof AxolotlParser.UnaryExpressionContext
                || tx == MINUS && px instanceof AxolotlParser.WhenConditionContext
                || tx == BANG && px instanceof AxolotlParser.IsCheckContext) {
            return 0;
        }
        if (ty == LPAREN) {
            return tightParen(py) ? 0 : 1;
        }
        if ((ty == LT || ty == GT) && inType(y) || tx == LT && inType(x) || ty == QUESTION) {
            return 0;
        }
        if (ty == COLON) {
            return spacedColon(py) ? 1 : 0;
        }
        return 1;
    }

    /** The `(` of a call or of a declaration's parameters, which follows the name directly. */
    private static boolean tightParen(ParserRuleContext parent) {
        return parent instanceof AxolotlParser.CallSuffixContext || parent instanceof AxolotlParser.FunctionDeclContext
                || parent instanceof AxolotlParser.InterfaceMethodContext || parent instanceof AxolotlParser.ClassDeclContext
                || parent instanceof AxolotlParser.DataClassDeclContext || parent instanceof AxolotlParser.EnumDeclContext
                || parent instanceof AxolotlParser.EnumConstantContext || parent instanceof AxolotlParser.AnnotationContext
                || parent instanceof AxolotlParser.GenericConstructorContext || parent instanceof AxolotlParser.PipeStageContext;
    }

    /** `class A : B` and `<T : Comparable<T>>` against `x: Int`. */
    private static boolean spacedColon(ParserRuleContext parent) {
        return parent instanceof AxolotlParser.TypeParamContext || parent instanceof AxolotlParser.ClassDeclContext
                || parent instanceof AxolotlParser.DataClassDeclContext || parent instanceof AxolotlParser.InterfaceDeclContext;
    }

    /** `<`, `>` or `,` of type arguments or type parameters, not a comparison or a list of values. */
    private boolean inType(Token t) {
        ParserRuleContext parent = parents.get(t.getTokenIndex());
        return parent instanceof AxolotlParser.TypeArgsContext || parent instanceof AxolotlParser.TypeParamsContext
                || parent instanceof AxolotlParser.TypeContext;
    }

    /** Whether two tokens written together would read as other tokens, as `!` `!` does as `!!`. */
    private static boolean merges(Token x, Token y) {
        if ((x.getType() == PLUS || x.getType() == MINUS) && x.getType() == y.getType()) {
            return true;
        }
        // Names, numbers and literals end where a symbol starts; the symbols are few, and so are their pairs.
        if (!symbol(x) || !symbol(y)) {
            return false;
        }
        return MERGES.computeIfAbsent(x.getText() + "\u0000" + y.getText(), key -> {
            var lexer = new AxolotlLexer(CharStreams.fromString(x.getText() + y.getText()));
            lexer.removeErrorListeners();
            List<? extends Token> joined = lexer.getAllTokens();
            return joined.size() != 2 || joined.get(0).getType() != x.getType() || joined.get(1).getType() != y.getType();
        });
    }

    private static boolean symbol(Token t) {
        return t.getText().chars().noneMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '"' || c == '\'');
    }

    /**
     * Sorts the imports by name, as one group, each with the comment lines above it. Imports that share
     * a line with other code are left in their order.
     */
    private void sortImports(List<Line> lines) {
        List<AxolotlParser.ImportDeclContext> imports = tree.importDecl();
        if (imports.size() < 2) {
            return;
        }
        List<Unit> units = imports.stream().map(this::unit).toList();
        for (Unit u : units) {
            int next = nextCode(u.stop);
            boolean ownLine = (u.start == 0 || newlines(u.start) > 0) && (next < 0 || tokens.get(next).getLine() > tokens.get(u.stop).getLine());
            if (!ownLine) {
                return;
            }
        }
        // Where each import's lines begin: after the line of the import before it.
        List<Integer> starts = new ArrayList<>();
        starts.add(units.getFirst().start);
        for (int i = 1; i < units.size(); i++) {
            int k = units.get(i - 1).stop + 1;
            while (newlines(k) == 0) {
                k++;
            }
            starts.add(k);
        }
        int last = units.getLast().stop;
        int from = -1;
        int to = -1;
        for (int i = 0; i < lines.size(); i++) {
            int first = lines.get(i).first;
            if (first >= starts.getFirst() && first <= last) {
                from = from < 0 ? i : from;
                to = i;
            }
        }
        List<List<Line>> groups = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            groups.add(new ArrayList<>());
        }
        for (int i = from; i <= to; i++) {
            Line line = lines.get(i);
            int unit = 0;
            while (unit + 1 < starts.size() && line.first >= starts.get(unit + 1)) {
                unit++;
            }
            groups.get(unit).add(line);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> imports.get(i).qualifiedName().getText()));
        List<Line> sorted = new ArrayList<>();
        order.forEach(i -> sorted.addAll(groups.get(i)));
        for (int i = from; i <= to; i++) {
            lines.set(i, sorted.get(i - from));
        }
    }

    private int newlines(int k) {
        String gap = gaps.get(k);
        int count = 0;
        for (int i = 0; i < gap.length(); i++) {
            if (gap.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** The width of the whitespace before the line's first token in the source. */
    private int originalIndent(int line) {
        String gap = gaps.get(lineStarts.get(line));
        return width(gap.substring(gap.lastIndexOf('\n') + 1));
    }

    private static int width(String whitespace) {
        int width = 0;
        for (int i = 0; i < whitespace.length(); i++) {
            width = whitespace.charAt(i) == '\t' ? (width / TAB + 1) * TAB : width + 1;
        }
        return width;
    }

    private boolean comment(int k) {
        int type = tokens.get(k).getType();
        return type == LINE_COMMENT || type == BLOCK_COMMENT;
    }

    private int firstCode(int line) {
        for (int k = lineStarts.get(line); k < tokens.size() && lineOf[k] == line; k++) {
            if (!comment(k)) {
                return k;
            }
        }
        return -1;
    }

    private boolean lastCodeOfLine(int k) {
        int next = nextCode(k);
        return next < 0 || lineOf[next] != lineOf[k];
    }

    private int nextCode(int k) {
        for (int i = k + 1; i < tokens.size(); i++) {
            if (!comment(i)) {
                return i;
            }
        }
        return -1;
    }

    private int previousCode(int k) {
        for (int i = k - 1; i >= 0; i--) {
            if (!comment(i)) {
                return i;
            }
        }
        return -1;
    }
}
