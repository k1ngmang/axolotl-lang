package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;

/** The type of what stands before a dot, such as `a.b().c` in `a.b().c.d`. */
final class Receivers {

    /** A name or a call in a receiver; arguments as {@link Document#argumentTypes} gives them, null for a name. */
    record Step(String name, List<String> arguments) {}

    /** statics: the receiver is a class, whose static members follow the dot. */
    record Receiver(TypeMirror type, boolean statics) {}

    private Receivers() {}

    /** The step of a string literal, which has no name. */
    static final String STRING = "\"\"";

    /**
     * The names and calls before a dot or a `::`, first to last; null when it is another expression.
     * nameType: the type of a name that is an argument, or null; see {@link Document#argumentTypes}.
     */
    static List<Step> steps(Document document, Token dot, Function<Token, String> nameType) {
        List<Step> steps = new ArrayList<>();
        Token token = dot;
        while (token != null && (token.getType() == AxolotlLexer.DOT || token.getType() == AxolotlLexer.SAFE_DOT
                || token == dot && token.getType() == AxolotlLexer.COLONCOLON)) {
            token = document.previous(token);
            if (token != null && token.getType() == AxolotlLexer.STRING_LITERAL) {
                steps.addFirst(new Step(STRING, null));
                return steps;
            }
            if (token != null && token.getType() == AxolotlLexer.BANGBANG) {
                token = document.previous(token);
            }
            List<String> arguments = null;
            if (token != null && token.getType() == AxolotlLexer.RPAREN) {
                Token open = openingParenthesis(document, token);
                token = open == null ? null : document.previous(open);
                arguments = token == null ? null : document.argumentTypes(token, nameType);
                if (arguments == null) {
                    return null;
                }
            }
            if (token == null || token.getType() != AxolotlLexer.IDENTIFIER) {
                return null;
            }
            steps.addFirst(new Step(token.getText(), arguments));
            Token name = token;
            token = document.previous(token);
            if (token != null && token.getLine() < name.getLine()) {
                // A dot at the end of the line above is being typed there: `label.` over `Integer.`.
                break;
            }
        }
        return steps;
    }

    private static Token openingParenthesis(Document document, Token close) {
        int depth = 0;
        for (Token token = close; token != null; token = document.previous(token)) {
            if (token.getType() == AxolotlLexer.RPAREN || token.getType() == AxolotlLexer.RBRACE) {
                depth++;
            } else if (token.getType() == AxolotlLexer.LPAREN || token.getType() == AxolotlLexer.LBRACE) {
                depth--;
            }
            if (depth == 0) {
                return token.getType() == AxolotlLexer.LPAREN ? token : null;
            }
        }
        return null;
    }

    /** The type of the steps of a receiver at pos of a document; a null type when it is unknown. */
    static Receiver typeOf(JavaModel java, SourceFile ast, Pos pos, List<Step> receiver) {
        Receiver start = first(java, ast, pos, receiver);
        TypeMirror type = start.type();
        boolean statics = start.statics();
        int used = statics ? qualifiedLength(java, ast, receiver) : 1;
        for (Step step : receiver.subList(Math.min(used, receiver.size()), receiver.size())) {
            TypeElement nested = statics && step.arguments() == null
                    ? java.nestedType(JavaModel.elementOf(type), step.name()) : null;
            if (nested != null) {
                type = nested.asType();
                continue;
            }
            type = java.memberType(ast, type, step.name(), step.arguments());
            statics = false;
        }
        return new Receiver(type, statics);
    }

    /**
     * The first step: a value, the result of a function, `this` in a class, an object a constructor
     * call creates, or a class, maybe written with its package: `java.util.List`.
     */
    private static Receiver first(JavaModel java, SourceFile ast, Pos pos, List<Step> receiver) {
        Step first = receiver.getFirst();
        if (first.name().equals(STRING)) {
            return new Receiver(java.type("java.lang.String").asType(), false);
        }
        Symbols.Symbol symbol = Symbols.lookup(ast, pos, first.name());
        if (symbol != null && symbol.kind() != Symbols.Kind.TYPE && symbol.kind() != Symbols.Kind.DECLARED_TYPE) {
            boolean value = symbol.kind() == Symbols.Kind.VALUE || symbol.kind() == Symbols.Kind.IMPORTED_VALUE;
            boolean member = first.arguments() != null || value && symbol.type() == null;
            return new Receiver(member ? memberOfOwner(java, ast, symbol, first) : valueType(java, ast, symbol), false);
        }
        if (first.name().equals("this") && symbol == null) {
            String cls = Symbols.enclosingClass(ast, pos);
            TypeElement type = cls == null ? null : java.resolveType(ast, cls);
            return new Receiver(type == null ? null : type.asType(), false);
        }
        // `super.read()` in a class that extends a Java class: the methods of that class.
        if (first.name().equals("super") && symbol == null
                && Symbols.enclosingDecl(ast, pos) instanceof Decl.ClassDecl classDecl && classDecl.superArgs() != null) {
            TypeElement type = java.resolveType(ast, classDecl.supertypes().getFirst().name());
            return new Receiver(type == null ? null : type.asType(), false);
        }
        if (first.arguments() != null) {
            TypeElement constructed = java.resolveType(ast, first.name());
            return new Receiver(constructed == null ? null : constructed.asType(), false);
        }
        return new Receiver(staticType(java, ast, receiver), true);
    }

    private static TypeMirror staticType(JavaModel java, SourceFile ast, List<Step> receiver) {
        int length = qualifiedLength(java, ast, receiver);
        if (length == 0) {
            return null;
        }
        TypeElement cls = java.resolveType(ast, qualifiedName(receiver, length));
        return cls == null ? null : cls.asType();
    }

    /** The number of leading steps that name a class, such as 3 for `java.util.List.of`; 0 if none do. */
    private static int qualifiedLength(JavaModel java, SourceFile ast, List<Step> receiver) {
        for (int used = 1; used <= receiver.size() && receiver.get(used - 1).arguments() == null; used++) {
            if (java.resolveType(ast, qualifiedName(receiver, used)) != null) {
                return used;
            }
        }
        return 0;
    }

    private static String qualifiedName(List<Step> receiver, int length) {
        return receiver.subList(0, length).stream().map(Step::name).collect(Collectors.joining("."));
    }

    /** The type of a local, a parameter or a value with a written type. */
    private static TypeMirror valueType(JavaModel java, SourceFile ast, Symbols.Symbol symbol) {
        return symbol.type() != null ? java.typeOf(ast, symbol.type()) : java.typeOf(ast, symbol);
    }

    /** The type of a top-level value or of a function's result, of this file or imported. */
    private static TypeMirror memberOfOwner(JavaModel java, SourceFile ast, Symbols.Symbol symbol, Step step) {
        String owner = ast.className();
        if (symbol.imported() != null) {
            owner = symbol.imported().name().substring(0, symbol.imported().name().lastIndexOf('.'));
        }
        TypeElement type = java.resolveType(ast, owner);
        return type == null ? null : java.memberType(ast, type.asType(), step.name(), step.arguments());
    }
}
