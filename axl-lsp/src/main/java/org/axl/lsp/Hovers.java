package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.type.TypeMirror;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

/** The declaration or inferred type of a name, and the Java generated from the line. */
final class Hovers {

    private final Workspace workspace;

    Hovers(Workspace workspace) {
        this.workspace = workspace;
    }

    /** While the text does not parse, an earlier version answers for the lines that are unchanged. */
    Hover hover(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document document = workspace.parsed(uri);
        if (current == null || document == null) {
            return null;
        }
        line = current.sameLineIn(document, line);
        if (line < 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Token token = document.tokenAt(line, character);
        if (token != null && token.getType() == AxolotlLexer.IDENTIFIER && !isMember(document, token)) {
            Symbols.Symbol symbol = Symbols.lookup(document.ast, document.pos(line, character), token.getText());
            if (symbol != null) {
                parts.add("```axl\n" + describe(document, symbol) + "\n```");
            }
        }
        String java = generatedJava(document.translation.unit(), line + 1);
        if (!java.isEmpty()) {
            parts.add("Generated Java:\n```java\n" + java + "\n```");
        }
        return parts.isEmpty() ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, String.join("\n\n", parts)));
    }

    private static boolean isMember(Document document, Token token) {
        Token previous = document.previous(token);
        return previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT);
    }

    private String describe(Document document, Symbols.Symbol symbol) {
        return switch (symbol.kind()) {
            case FUNCTION -> signature(symbol.function());
            case VALUE -> "val " + symbol.name() + ": " + typeText(symbol.type());
            case IMPORTED_FUNCTION -> "import fn " + symbol.imported().name();
            case IMPORTED_VALUE -> "import val " + symbol.imported().name();
            case TYPE -> "import " + symbol.imported().name();
            case PARAMETER, LOCAL, VARIABLE -> {
                String keyword = symbol.kind() == Symbols.Kind.VARIABLE ? "var " : symbol.kind() == Symbols.Kind.LOCAL ? "val " : "";
                yield keyword + symbol.name() + ": " + localType(document, symbol);
            }
        };
    }

    private String localType(Document document, Symbols.Symbol symbol) {
        if (symbol.type() != null) {
            return typeText(symbol.type());
        }
        Decl.Function fn = symbol.function();
        TypeMirror type = workspace.javaModel(document.module).localType(document.ast, fn.name(), fn.params().size(), symbol.name(),
                symbol.pos().line());
        return type == null ? "?" : JavaModel.simple(type.toString());
    }

    static String signature(Decl.Function fn) {
        String params = fn.params().stream().map(p -> p.name() + ": " + typeText(p.type())).collect(Collectors.joining(", "));
        String result = fn.returnType() == null ? "" : " -> " + typeText(fn.returnType());
        return (fn.isPrivate() ? "private " : "") + "fn " + fn.name() + "(" + params + ")" + result;
    }

    static String typeText(TypeRef type) {
        String args = type.args().isEmpty() ? ""
                : type.args().stream().map(Hovers::typeText).collect(Collectors.joining(", ", "<", ">"));
        return type.name() + args + (type.nullable() ? "?" : "");
    }

    /** All Java lines generated from one .axl line, without their indentation. */
    static String generatedJava(GeneratedUnit unit, int axlLine) {
        if (unit == null) {
            return "";
        }
        String[] lines = unit.javaSource().split("\n");
        List<String> result = new ArrayList<>();
        for (int javaLine = 1; javaLine <= lines.length; javaLine++) {
            if (unit.lineMap().axlLine(javaLine) == axlLine) {
                result.add(lines[javaLine - 1].strip());
            }
        }
        return String.join("\n", result);
    }
}
