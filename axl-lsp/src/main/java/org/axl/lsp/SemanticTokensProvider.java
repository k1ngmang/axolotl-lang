package org.axl.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;

/** Token kinds from the lexer, refined for names by what follows or precedes them. */
final class SemanticTokensProvider {

    static final List<String> TYPES = List.of("keyword", "function", "variable", "parameter", "type", "string",
            "number", "comment", "operator", "property", "namespace", "enumMember", "decorator", "method", "typeParameter");
    static final SemanticTokensLegend LEGEND = new SemanticTokensLegend(TYPES, List.of());

    private static final Set<Integer> KEYWORDS = Set.of(AxolotlLexer.PACKAGE, AxolotlLexer.IMPORT, AxolotlLexer.FN,
            AxolotlLexer.VAL, AxolotlLexer.VAR, AxolotlLexer.PRIVATE, AxolotlLexer.RETURN, AxolotlLexer.BREAK,
            AxolotlLexer.CONTINUE, AxolotlLexer.IF,
            AxolotlLexer.ELSE, AxolotlLexer.WHILE, AxolotlLexer.FOR, AxolotlLexer.WHEN, AxolotlLexer.IS,
            AxolotlLexer.CLASS, AxolotlLexer.ENUM, AxolotlLexer.INTERFACE, AxolotlLexer.SEALED, AxolotlLexer.THROW,
            AxolotlLexer.TRY, AxolotlLexer.ATTEMPT, AxolotlLexer.TRUE, AxolotlLexer.FALSE, AxolotlLexer.NULL);
    private static final Set<Integer> OPERATORS = Set.of(AxolotlLexer.SAFE_DOT, AxolotlLexer.ELVIS, AxolotlLexer.ARROW, AxolotlLexer.PIPE,
            AxolotlLexer.BANGBANG);

    /** The enums of a file: where their constants are declared, and the constants of each by the enum's name. */
    private record Enums(Set<Pos> declared, Map<String, List<String>> constants) {

        static Enums of(Document document) {
            Set<Pos> declared = new HashSet<>();
            Map<String, List<String>> constants = new HashMap<>();
            if (document.ast != null) {
                for (Decl decl : document.ast.decls()) {
                    if (decl instanceof Decl.EnumDecl enumeration) {
                        declared.addAll(document.constantPositions(enumeration));
                        constants.put(enumeration.name(), enumeration.constants());
                    }
                }
            }
            return new Enums(declared, constants);
        }

        /** A constant where it is declared, or after the name of its enum and a dot: `Color.RED`. */
        boolean isConstant(List<? extends Token> tokens, int index, Token previous) {
            if (declared.contains(Document.start(tokens.get(index)))) {
                return true;
            }
            if (previous == null || previous.getType() != AxolotlLexer.DOT) {
                return false;
            }
            int owner = previousIndex(tokens, previousIndex(tokens, index));
            List<String> names = owner < 0 ? null : constants.get(tokens.get(owner).getText());
            return names != null && names.contains(tokens.get(index).getText());
        }
    }

    /** The parameter and type parameter tokens of a file, and the names it declares for values, which are not packages. */
    private record Names(Set<Integer> parameters, Set<String> declared, Set<Integer> interfaceMethods, Set<Integer> typeParameters) {

        static Names of(List<? extends Token> tokens) {
            Set<Integer> parameters = ParameterTokens.of(tokens);
            Set<String> declared = new HashSet<>();
            parameters.forEach(i -> declared.add(tokens.get(i).getText()));
            for (int i = 0; i < tokens.size(); i++) {
                int previous = type(tokens, previousIndex(tokens, i));
                boolean named = previous == AxolotlLexer.VAL || previous == AxolotlLexer.VAR || previous == AxolotlLexer.FN
                        || previous == AxolotlLexer.LPAREN && type(tokens, previousIndex(tokens, previousIndex(tokens, i))) == AxolotlLexer.FOR;
                if (tokens.get(i).getType() == AxolotlLexer.IDENTIFIER && named) {
                    declared.add(tokens.get(i).getText());
                }
            }
            return new Names(parameters, declared, interfaceMethods(tokens), ParameterTokens.typeParameters(tokens));
        }

        /** The names of the methods declared inside the braces of an interface. */
        private static Set<Integer> interfaceMethods(List<? extends Token> tokens) {
            Set<Integer> result = new HashSet<>();
            List<Boolean> braces = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                int kind = tokens.get(i).getType();
                if (kind == AxolotlLexer.LBRACE) {
                    braces.add(opensInterface(tokens, i));
                } else if (kind == AxolotlLexer.RBRACE && !braces.isEmpty()) {
                    braces.removeLast();
                } else if (kind == AxolotlLexer.IDENTIFIER && type(tokens, previousIndex(tokens, i)) == AxolotlLexer.FN
                        && !braces.isEmpty() && braces.getLast()) {
                    result.add(i);
                }
            }
            return result;
        }

        /** Whether the header before a brace is `interface Name : Supertypes`, which holds nothing but names and type punctuation. */
        private static boolean opensInterface(List<? extends Token> tokens, int brace) {
            Set<Integer> inHeader = Set.of(AxolotlLexer.IDENTIFIER, AxolotlLexer.DOT, AxolotlLexer.COMMA, AxolotlLexer.COLON,
                    AxolotlLexer.LT, AxolotlLexer.GT, AxolotlLexer.QUESTION, AxolotlLexer.STAR);
            for (int i = previousIndex(tokens, brace); i >= 0; i = previousIndex(tokens, i)) {
                int kind = tokens.get(i).getType();
                if (kind == AxolotlLexer.INTERFACE) {
                    return true;
                }
                if (!inHeader.contains(kind)) {
                    return false;
                }
            }
            return false;
        }
    }

    private SemanticTokensProvider() {}

    static SemanticTokens tokens(Document document) {
        List<Integer> data = new ArrayList<>();
        List<? extends Token> tokens = document.tokens;
        Enums enums = Enums.of(document);
        Names names = Names.of(tokens);
        int lastLine = 0;
        int lastStart = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int type = classify(tokens, i, enums, names);
            if (type < 0) {
                continue;
            }
            // Multi-line block comments are sent one line at a time.
            String[] parts = token.getText().split("\r?\n", -1);
            for (int p = 0; p < parts.length; p++) {
                int line = token.getLine() - 1 + p;
                int start = p == 0 ? document.utf16Column(line, token.getCharPositionInLine()) : 0;
                if (parts[p].isEmpty()) {
                    continue;
                }
                data.add(line - lastLine);
                data.add(line == lastLine ? start - lastStart : start);
                data.add(parts[p].length());
                data.add(type);
                data.add(0);
                lastLine = line;
                lastStart = start;
            }
        }
        return new SemanticTokens(data);
    }

    private static int classify(List<? extends Token> tokens, int index, Enums enums, Names names) {
        Token token = tokens.get(index);
        int kind = token.getType();
        if (KEYWORDS.contains(kind)) {
            return TYPES.indexOf("keyword");
        }
        if (OPERATORS.contains(kind)) {
            return TYPES.indexOf("operator");
        }
        return switch (kind) {
            case AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL -> TYPES.indexOf("string");
            case AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL -> TYPES.indexOf("number");
            case AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT -> TYPES.indexOf("comment");
            case AxolotlLexer.AT -> TYPES.indexOf("decorator");
            case AxolotlLexer.IDENTIFIER -> TYPES.indexOf(name(tokens, index, enums, names));
            default -> -1;
        };
    }

    private static String name(List<? extends Token> tokens, int index, Enums enums, Names names) {
        Token next = neighbour(tokens, index, 1);
        Token previous = neighbour(tokens, index, -1);
        if (isContextualKeyword(tokens, index, next, previous)) {
            return "keyword";
        }
        if (isAnnotationName(tokens, index)) {
            return "decorator";
        }
        if (names.typeParameters().contains(index)) {
            return "typeParameter";
        }
        if (enums.isConstant(tokens, index, previous)) {
            return "enumMember";
        }
        if (names.parameters().contains(index)) {
            return "parameter";
        }
        String text = tokens.get(index).getText();
        boolean upperCase = Character.isUpperCase(text.charAt(0));
        // A name in capitals, such as `MAX_SIZE`, is a constant and not a type.
        boolean type = upperCase && !text.matches("\\p{Lu}[\\p{Lu}\\p{N}_]+");
        if (names.interfaceMethods().contains(index)) {
            return "method";
        }
        String qualified = inDeclaredName(tokens, index, next);
        if (qualified != null) {
            return qualified;
        }
        if (next != null && next.getType() == AxolotlLexer.LPAREN) {
            return upperCase ? "type" : "function";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.FN || previous.getType() == AxolotlLexer.COLONCOLON)) {
            return "function";
        }
        if (previous != null && previous.getType() == AxolotlLexer.ELSE && next != null && next.getType() == AxolotlLexer.ARROW) {
            return "parameter";
        }
        if (!upperCase && isPackageSegment(tokens, index, names.declared())) {
            return "namespace";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT)) {
            return type && next != null && next.getType() == AxolotlLexer.DOT ? "type" : "property";
        }
        return type ? "type" : "variable";
    }

    /**
     * A segment before the class of a qualified name in an expression, as `java` and `util` in
     * `java.util.List.of(1)`: of lower-case segments that a capitalized one follows, the first not a
     * name the file declares.
     */
    private static boolean isPackageSegment(List<? extends Token> tokens, int index, Set<String> declared) {
        int first = index;
        while (type(tokens, previousIndex(tokens, first)) == AxolotlLexer.DOT
                && type(tokens, previousIndex(tokens, previousIndex(tokens, first))) == AxolotlLexer.IDENTIFIER) {
            first = previousIndex(tokens, previousIndex(tokens, first));
        }
        if (declared.contains(tokens.get(first).getText())) {
            return false;
        }
        for (int i = first; type(tokens, i) == AxolotlLexer.IDENTIFIER; i = nextIndex(tokens, nextIndex(tokens, i))) {
            boolean lower = Character.isLowerCase(tokens.get(i).getText().charAt(0));
            if (i > index && !lower) {
                return true;
            }
            if (!lower || type(tokens, nextIndex(tokens, i)) != AxolotlLexer.DOT) {
                return false;
            }
        }
        return false;
    }

    /** A segment of the name after `@`, as `Test` in `@Test` and both of `@org.Marker`, colored with the `@`. */
    private static boolean isAnnotationName(List<? extends Token> tokens, int index) {
        int first = index;
        while (type(tokens, previousIndex(tokens, first)) == AxolotlLexer.DOT
                && type(tokens, previousIndex(tokens, previousIndex(tokens, first))) == AxolotlLexer.IDENTIFIER) {
            first = previousIndex(tokens, previousIndex(tokens, first));
        }
        return type(tokens, previousIndex(tokens, first)) == AxolotlLexer.AT;
    }

    /** `data` before `class`, `in` in `for (x in xs)` and `use` before `(val`; all are ordinary names elsewhere. */
    private static boolean isContextualKeyword(List<? extends Token> tokens, int index, Token next, Token previous) {
        String text = tokens.get(index).getText();
        if (text.equals("data")) {
            return next != null && next.getType() == AxolotlLexer.CLASS;
        }
        if (text.equals("use")) {
            int open = nextIndex(tokens, index);
            return type(tokens, open) == AxolotlLexer.LPAREN && type(tokens, nextIndex(tokens, open)) == AxolotlLexer.VAL;
        }
        if (text.equals("in")) {
            int loopVariable = previousIndex(tokens, index);
            return previous != null && previous.getType() == AxolotlLexer.IDENTIFIER
                    && type(tokens, previousIndex(tokens, loopVariable)) == AxolotlLexer.LPAREN
                    && type(tokens, previousIndex(tokens, previousIndex(tokens, loopVariable))) == AxolotlLexer.FOR;
        }
        return false;
    }

    /**
     * The kind of a segment of the qualified name in a package or import declaration or a written
     * type, where the segments before the last are packages; null for other names.
     */
    private static String inDeclaredName(List<? extends Token> tokens, int index, Token next) {
        int before = previousIndex(tokens, index);
        while (type(tokens, before) == AxolotlLexer.DOT && type(tokens, previousIndex(tokens, before)) == AxolotlLexer.IDENTIFIER) {
            before = previousIndex(tokens, previousIndex(tokens, before));
        }
        int keyword = type(tokens, before);
        int beforeKeyword = type(tokens, previousIndex(tokens, before));
        boolean importMember = (keyword == AxolotlLexer.FN || keyword == AxolotlLexer.VAL) && beforeKeyword == AxolotlLexer.IMPORT;
        boolean written = keyword == AxolotlLexer.COLON || keyword == AxolotlLexer.ARROW && beforeKeyword == AxolotlLexer.RPAREN;
        if (!importMember && !written && keyword != AxolotlLexer.IMPORT && keyword != AxolotlLexer.PACKAGE) {
            return null;
        }
        boolean last = next == null || next.getType() != AxolotlLexer.DOT;
        if (!last || keyword == AxolotlLexer.PACKAGE) {
            return "namespace";
        }
        if (importMember) {
            return keyword == AxolotlLexer.FN ? "function" : "variable";
        }
        return "type";
    }

    private static Token neighbour(List<? extends Token> tokens, int index, int step) {
        for (int i = index + step; i >= 0 && i < tokens.size(); i += step) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return tokens.get(i);
            }
        }
        return null;
    }

    /** The index of the default-channel token after an index, or -1. */
    private static int nextIndex(List<? extends Token> tokens, int index) {
        for (int i = index + 1; index >= 0 && i < tokens.size(); i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the default-channel token before an index, or -1. */
    private static int previousIndex(List<? extends Token> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return i;
            }
        }
        return -1;
    }

    private static int type(List<? extends Token> tokens, int index) {
        return index < 0 ? Token.INVALID_TYPE : tokens.get(index).getType();
    }
}
