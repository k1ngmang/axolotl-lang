grammar Axolotl;

tokens { BLOCK_START }

@parser::members {
    // Newlines are hidden, so a call or a binary +/- must start on the line of
    // the previous token; otherwise `f()` followed by `(x)` would become `f()(x)`.
    private boolean sameLine() {
        Token prev = _input.LT(-1);
        Token next = _input.LT(1);
        return prev == null || prev.getLine() == next.getLine();
    }

    /**
     * `Box<String>(x)` builds an object; `LOW < v, v > (1)` compares. Type names start with an upper
     * case letter, so the arguments between `<` and `>` must look like types.
     */
    private boolean genericConstructor() {
        // `java.util.HashMap<String, Int>()` names the class by its package.
        int start = 1;
        while (_input.LT(start).getType() == IDENTIFIER && _input.LT(start + 1).getType() == DOT) {
            start += 2;
        }
        if (!startsUpper(_input.LT(start)) || _input.LT(start + 1).getType() != LT) {
            return false;
        }
        int depth = 0;
        for (int i = start + 1; ; i++) {
            Token t = _input.LT(i);
            switch (t.getType()) {
                case LT -> depth++;
                case GT -> {
                    if (--depth == 0) {
                        return _input.LT(i + 1).getType() == LPAREN;
                    }
                }
                case IDENTIFIER -> {
                    if (_input.LT(i + 1).getType() != DOT && !startsUpper(t)) {
                        return false;
                    }
                }
                case DOT, COMMA, QUESTION -> { }
                // `HashMap<Class<*>, String>()`: a `*` stands alone as an argument of a nested type.
                case STAR -> {
                    int before = _input.LT(i - 1).getType();
                    int after = _input.LT(i + 1).getType();
                    if (depth < 2 || (before != LT && before != COMMA) || (after != GT && after != COMMA)) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
    }

    // `if (c) a else b` gives a value; `if (c) { ... }` is a statement. Telling them apart by the
    // token after the condition keeps the parser from reading a whole `if` to find out.
    private boolean ifExpressionAhead() {
        if (_input.LT(1).getType() != IF || _input.LT(2).getType() != LPAREN) {
            return false;
        }
        int depth = 0;
        for (int i = 2; ; i++) {
            Token t = _input.LT(i);
            switch (t.getType()) {
                case LPAREN -> depth++;
                case RPAREN -> {
                    if (--depth == 0) {
                        return _input.LT(i + 1).getType() != LBRACE;
                    }
                }
                case Token.EOF -> {
                    return false;
                }
                default -> { }
            }
        }
    }

    // `class Parser private constructor(...)`: annotations or `private`, then `constructor(`. After a class without
    // parameters, an annotation or `private` may start the next declaration instead, so this looks past them.
    private boolean primaryConstructorAhead() {
        int i = 1;
        while (_input.LT(i).getType() == AT) {
            i++;
            while (_input.LT(i + 1).getType() == DOT) {
                i += 2;
            }
            i++;
            if (_input.LT(i).getType() == LPAREN) {
                int depth = 0;
                do {
                    int type = _input.LT(i).getType();
                    if (type == Token.EOF) {
                        return false;
                    }
                    depth += type == LPAREN ? 1 : type == RPAREN ? -1 : 0;
                    i++;
                } while (depth > 0);
            }
        }
        if (_input.LT(i).getType() == PRIVATE) {
            i++;
        }
        return i > 1 && _input.LT(i).getText().equals("constructor") && _input.LT(i + 1).getType() == LPAREN;
    }

    private static boolean startsUpper(Token t) {
        return t.getType() == IDENTIFIER && Character.isUpperCase(t.getText().charAt(0));
    }
}

compilationUnit
    : packageDecl? importDecl* topLevel* EOF
    ;

packageDecl
    : PACKAGE qualifiedName SEMI?
    ;

importDecl
    : IMPORT kind=(FN | VAL)? qualifiedName SEMI?
    ;

topLevel
    : functionDecl
    | valDecl SEMI?
    | dataClassDecl
    | classDecl
    | enumDecl
    | interfaceDecl
    | SEMI
    ;

// `@Name(x = 1, "y")` before a declaration, passed to Java as it is; `{ a, b }` is an array element.
annotation
    : AT qualifiedName (LPAREN (annotationArg (COMMA annotationArg)*)? RPAREN)?
    ;

annotationArg
    : (identifier ASSIGN)? annotationValue
    ;

annotationValue
    : LBRACE (annotationValue (COMMA annotationValue)*)? RBRACE
    | expression
    ;

// `data class Point(val x: Int, val y: Int) : Shape`; `data` is a word only here.
// A body holds methods only, for the interfaces the data class implements, such as `Comparable`.
dataClassDecl
    : annotation* PRIVATE? {_input.LT(1).getText().equals("data")}? IDENTIFIER CLASS identifier typeParams?
      LPAREN (field (COMMA field)*)? RPAREN (COLON type (COMMA type)*)? (LBRACE functionDecl* RBRACE)?
    ;
// `<T>` or `<K, V : Comparable<V>>`: the type parameters of a type or a function.
typeParams
    : LT typeParam (COMMA typeParam)* GT
    ;
typeParam
    : identifier (COLON type)?
    ;

field
    : annotation* VAL identifier COLON type
    ;

// `enum Planet(val mass: Double) { MERCURY(3.3e23), EARTH(5.97e24) }`: fields, given by each constant.
// Methods follow the constants, after an optional `;`, as in a data class body; the supertypes are interfaces.
enumDecl
    : annotation* PRIVATE? ENUM identifier (LPAREN field (COMMA field)* RPAREN)? (COLON type (COMMA type)*)?
      LBRACE (enumConstant (COMMA enumConstant)* COMMA?)? SEMI? functionDecl* RBRACE
    ;
enumConstant
    : identifier (LPAREN arguments? RPAREN)?
    ;

// A class with state: constructor parameters and properties are fields, functions are methods.
// `: Thread()` extends the Java class Thread, passing the arguments to its constructor; `: Runnable` implements.
classDecl
    : annotation* PRIVATE? CLASS identifier typeParams? (primaryConstructor? LPAREN (classParam (COMMA classParam)*)? RPAREN)?
      (COLON type (LPAREN arguments? RPAREN)? (COMMA type)*)?
      (LBRACE classMember* RBRACE)?
    ;

// `class Parser private constructor(...)` or `class Builder @Deprecated constructor()`: the constructor the parameters
// make is private or annotated. Without either, the keyword is left out.
primaryConstructor
    : {primaryConstructorAhead()}? annotation* PRIVATE? IDENTIFIER
    ;

// `private val` keeps the value inside the class: Java's private field, without the public accessor.
classParam
    : annotation* PRIVATE? (VAL | VAR) identifier COLON type
    ;

// `constructor(opt: String?) = this(opt, null)`: another constructor, which passes its values on to the parameters;
// `constructor` is a word only here.
classMember
    : annotation* PRIVATE? (VAL | VAR) identifier (COLON type)? ASSIGN expression SEMI?   # propertyMember
    | annotation* PRIVATE? {_input.LT(1).getText().equals("constructor")}? IDENTIFIER
      LPAREN (param (COMMA param)*)? RPAREN functionBody                      # constructorMember
    | functionDecl                                                            # methodMember
    ;

// `sealed interface Shape { val name: String }`
interfaceDecl
    : annotation* PRIVATE? SEALED? INTERFACE identifier typeParams? (COLON type (COMMA type)*)? (LBRACE (field | interfaceMethod)* RBRACE)?
    ;
interfaceMethod
    : annotation* FN identifier typeParams? LPAREN (param (COMMA param)*)? RPAREN (ARROW type)? SEMI?
    ;

functionDecl
    : annotation* PRIVATE? FN identifier typeParams? LPAREN (param (COMMA param)*)? RPAREN (ARROW type)? functionBody
    ;

functionBody
    : ASSIGN expression SEMI?
    | block
    ;

param
    : annotation* identifier COLON type
    ;

valDecl
    : annotation* PRIVATE? VAL identifier (COLON type)? ASSIGN expression
    ;

varDecl
    : VAR identifier (COLON type)? ASSIGN expression
    ;

// The source parser marks the braces of an `if` statement as BLOCK_START, so that they are never
// tried as a lambda or an expression: for nested ifs that took exponential time.
block
    : (LBRACE | BLOCK_START) statement* RBRACE
    ;

statement
    : valDecl SEMI?                                         # valStatement
    | varDecl SEMI?                                         # varStatement
    | RETURN ({sameLine()}? expression)? SEMI?              # returnStatement
    | {_input.LT(1).getText().equals("use")}? IDENTIFIER LPAREN (VAL identifier (COLON type)? ASSIGN)? expression RPAREN block
      (ELSE handlerParam? expression)?                      # useStatement
    | BREAK SEMI?                                           # breakStatement
    | CONTINUE SEMI?                                        # continueStatement
    | ifStatement                                           # ifStatementAlt
    | WHILE LPAREN expression RPAREN block                  # whileStatement
    | FOR LPAREN identifier {_input.LT(1).getText().equals("in")}? IDENTIFIER expression countKind expression countStep? RPAREN block   # countStatement
    | FOR LPAREN identifier {_input.LT(1).getText().equals("in")}? IDENTIFIER expression RPAREN block   # forStatement
    | ATTEMPT expression ELSE handlerParam? attemptExit SEMI?   # attemptExitStatement
    | postfixExpression assignOp expression SEMI?           # assignStatement
    | expression SEMI?                                      # expressionStatement
    | SEMI                                                  # emptyStatement
    ;

// `for (i in 0 until n)`, `for (i in 1..n)` and `for (i in n downTo 0)` count without a range object; `until`, `downTo`
// and `step` are words only here.
countKind
    : {_input.LT(1).getText().equals("until") || _input.LT(1).getText().equals("downTo")}? IDENTIFIER
    | DOT DOT
    ;
countStep
    : {_input.LT(1).getText().equals("step")}? IDENTIFIER expression
    ;

// `e ->` or `e: IOException ->` before the handler of `attempt` and `use`: the exception, any `Exception` or one of
// that class; the others are thrown on.
handlerParam
    : identifier (COLON type)? ARROW
    ;

// The handler of an `attempt` on a line of its own may leave the loop or the function.
attemptExit
    : BREAK
    | CONTINUE
    | RETURN ({sameLine()}? expression)?
    ;

ifStatement
    : IF LPAREN expression RPAREN block (ELSE (ifStatement | block))?
    ;

assignOp
    : ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN
    ;

// `xs |> first ?: fallback`: the fallback belongs to the whole pipe, whose `first` may be null.
expression
    : disjunction (PIPE pipeStage)* (ELVIS additive)?
    ;

// `xs |> filter { it > 0 } |> limit(10)`
pipeStage
    : identifier ({sameLine()}? LPAREN arguments? RPAREN)? ({sameLine()}? lambda)?
    ;

disjunction
    : conjunction (OROR conjunction)*
    ;

conjunction
    : equality (ANDAND equality)*
    ;

equality
    : comparison ((EQ | NEQ) comparison)*
    ;

comparison
    : isCheck ((LT | LE | GT | GE) isCheck)*
    ;

// `x is Circle` and `x !is Circle`; a val or parameter is known as a Circle where the check holds.
isCheck
    : elvis ({sameLine()}? BANG? IS qualifiedName starArgs?)?
    ;

// `is List<*>` is `is List`: a type test checks the class only.
starArgs
    : LT STAR (COMMA STAR)* GT
    ;

elvis
    : additive (ELVIS additive)*
    ;

additive
    : multiplicative ({sameLine()}? (PLUS | MINUS) multiplicative)*
    ;

multiplicative
    : prefixExpression ((STAR | SLASH | PERCENT) prefixExpression)*
    ;

prefixExpression
    : (BANG | BANGBANG | MINUS | PLUS) prefixExpression                     # unaryExpression
    | TRY prefixExpression                                                  # tryExpression
    | ATTEMPT expression ELSE handlerParam? expression                      # attemptExpression
    | THROW prefixExpression                                                # throwExpression
    | postfixExpression                                                     # postfixAlt
    ;

postfixExpression
    : primary postfixSuffix*
    ;

postfixSuffix
    : {sameLine()}? LPAREN arguments? RPAREN                # callSuffix
    | (DOT | SAFE_DOT) identifier                           # memberSuffix
    | DOT CLASS                                             # classSuffix
    | COLONCOLON identifier                                 # methodRefSuffix
    | BANGBANG                                              # notNullSuffix
    ;

arguments
    : expression (COMMA expression)*
    ;

primary
    : {genericConstructor()}? qualifiedName typeArgs LPAREN arguments? RPAREN   # genericConstructor
    | literal                                                       # literalPrimary
    | identifier                                                    # namePrimary
    | LPAREN expression RPAREN                                      # parenPrimary
    | lambda                                                        # lambdaPrimary
    | whenExpression                                                # whenPrimary
    | {ifExpressionAhead()}? IF LPAREN expression RPAREN expression ELSE expression   # ifPrimary
    ;

// `when (x) { is Circle -> x.radius(); 1, 2 -> "few"; else -> { ... } }`
whenExpression
    : WHEN LPAREN (VAL identifier ASSIGN)? expression RPAREN LBRACE whenArm* RBRACE
    ;

// After `->` a block or a value; a jump, a `return` or an assignment, statements elsewhere, also stand in a `when` statement.
whenArm
    : (whenCondition (COMMA whenCondition)* | ELSE) ARROW
      (block | BREAK | CONTINUE | RETURN ({sameLine()}? expression)? | postfixExpression assignOp expression | expression) SEMI?
    ;

whenCondition
    : IS qualifiedName starArgs?
    | MINUS? literal
    | qualifiedName
    ;

typeArgs
    : LT (type (COMMA type)*)? GT
    ;

lambda
    : LBRACE ((lambdaParam (COMMA lambdaParam)*)? ARROW)? statement* RBRACE
    ;

lambdaParam
    : identifier (COLON type)?
    ;

literal
    : INT_LITERAL
    | FLOAT_LITERAL
    | STRING_LITERAL
    | CHAR_LITERAL
    | TRUE
    | FALSE
    | NULL
    ;

type
    : qualifiedName (LT typeArg (COMMA typeArg)* GT)? QUESTION?
    ;

// `Class<*>` is Java's `Class<?>`: a type argument that is not known.
typeArg
    : type
    | STAR
    ;

qualifiedName
    : identifier (DOT identifier)*
    ;

identifier
    : IDENTIFIER
    ;

PACKAGE : 'package';
IMPORT  : 'import';
FN      : 'fn';
VAL     : 'val';
VAR     : 'var';
PRIVATE : 'private';
RETURN  : 'return';
BREAK   : 'break';
CONTINUE : 'continue';
IF      : 'if';
ELSE    : 'else';
WHILE   : 'while';
IS        : 'is';
WHEN      : 'when';
CLASS     : 'class';
ENUM      : 'enum';
INTERFACE : 'interface';
SEALED    : 'sealed';
FOR     : 'for';
TRY     : 'try';
THROW   : 'throw';
ATTEMPT : 'attempt';
TRUE    : 'true';
FALSE   : 'false';
NULL    : 'null';

ARROW          : '->';
PIPE           : '|>';
SAFE_DOT       : '?.';
ELVIS          : '?:';
COLONCOLON     : '::';
QUESTION       : '?';
DOT            : '.';
COMMA          : ',';
COLON          : ':';
SEMI           : ';';
LPAREN         : '(';
RPAREN         : ')';
LBRACE         : '{';
RBRACE         : '}';
EQ             : '==';
NEQ            : '!=';
LE             : '<=';
GE             : '>=';
LT             : '<';
GT             : '>';
ANDAND         : '&&';
OROR           : '||';
PLUS_ASSIGN    : '+=';
MINUS_ASSIGN   : '-=';
STAR_ASSIGN    : '*=';
SLASH_ASSIGN   : '/=';
PERCENT_ASSIGN : '%=';
ASSIGN         : '=';
AT             : '@';
BANGBANG       : '!!';
BANG           : '!';
PLUS           : '+';
MINUS          : '-';
STAR           : '*';
SLASH          : '/';
PERCENT        : '%';

FLOAT_LITERAL
    : DIGITS '.' DIGITS EXPONENT? [fFdD]?
    | DIGITS EXPONENT [fFdD]?
    | DIGITS [fFdD]
    ;

INT_LITERAL
    : '0' [xX] [0-9a-fA-F] [0-9a-fA-F_]* [lL]?
    | DIGITS [lL]?
    ;

STRING_LITERAL : '"' (~["\\\r\n] | '\\' ~[\r\n])* '"';
CHAR_LITERAL   : '\'' (~['\\\r\n] | '\\' ~[\r\n])+ '\'';

// `$` is accepted here so the translator can report it with a clear message.
IDENTIFIER : [\p{L}_$] [\p{L}\p{N}_$]*;

WS            : [ \t\r\n\f]+ -> channel(HIDDEN);
LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN);
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN);

UNKNOWN_CHAR : .;

fragment DIGITS   : [0-9] [0-9_]*;
fragment EXPONENT : [eE] [+-]? DIGITS;
