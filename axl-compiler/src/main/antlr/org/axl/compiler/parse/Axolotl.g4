grammar Axolotl;

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
        if (!startsUpper(_input.LT(1)) || _input.LT(2).getType() != LT) {
            return false;
        }
        int depth = 0;
        for (int i = 2; ; i++) {
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

// `data class Point(val x: Int, val y: Int) : Shape`; `data` is a word only here.
dataClassDecl
    : PRIVATE? {_input.LT(1).getText().equals("data")}? IDENTIFIER CLASS identifier
      LPAREN (field (COMMA field)*)? RPAREN (COLON type (COMMA type)*)?
    ;

field
    : VAL identifier COLON type
    ;

enumDecl
    : PRIVATE? ENUM identifier LBRACE (identifier (COMMA identifier)* COMMA?)? RBRACE
    ;

// A class with state: constructor parameters and properties are fields, functions are methods.
classDecl
    : PRIVATE? CLASS identifier (LPAREN (classParam (COMMA classParam)*)? RPAREN)? (COLON type (COMMA type)*)?
      (LBRACE classMember* RBRACE)?
    ;

classParam
    : (VAL | VAR) identifier COLON type
    ;

classMember
    : PRIVATE? (VAL | VAR) identifier (COLON type)? ASSIGN expression SEMI?   # propertyMember
    | functionDecl                                                            # methodMember
    ;

// `sealed interface Shape { val name: String }`
interfaceDecl
    : PRIVATE? SEALED? INTERFACE identifier (COLON type (COMMA type)*)? (LBRACE field* RBRACE)?
    ;

functionDecl
    : PRIVATE? FN identifier LPAREN (param (COMMA param)*)? RPAREN (ARROW type)? functionBody
    ;

functionBody
    : ASSIGN expression SEMI?
    | block
    ;

param
    : identifier COLON type
    ;

valDecl
    : PRIVATE? VAL identifier (COLON type)? ASSIGN expression
    ;

varDecl
    : VAR identifier (COLON type)? ASSIGN expression
    ;

block
    : LBRACE statement* RBRACE
    ;

statement
    : valDecl SEMI?                                         # valStatement
    | varDecl SEMI?                                         # varStatement
    | RETURN ({sameLine()}? expression)? SEMI?              # returnStatement
    | ifStatement                                           # ifStatementAlt
    | WHILE LPAREN expression RPAREN block                  # whileStatement
    | FOR LPAREN identifier {_input.LT(1).getText().equals("in")}? IDENTIFIER expression RPAREN block   # forStatement
    | postfixExpression assignOp expression SEMI?           # assignStatement
    | expression SEMI?                                      # expressionStatement
    | SEMI                                                  # emptyStatement
    ;

ifStatement
    : IF LPAREN expression RPAREN block (ELSE (ifStatement | block))?
    ;

assignOp
    : ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN
    ;

expression
    : disjunction (PIPE pipeStage)*
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
    : elvis ({sameLine()}? BANG? IS qualifiedName)?
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
    | ATTEMPT expression ELSE (identifier ARROW)? expression                # attemptExpression
    | THROW prefixExpression                                                # throwExpression
    | postfixExpression                                                     # postfixAlt
    ;

postfixExpression
    : primary postfixSuffix*
    ;

postfixSuffix
    : {sameLine()}? LPAREN arguments? RPAREN                # callSuffix
    | (DOT | SAFE_DOT) identifier                           # memberSuffix
    | COLONCOLON identifier                                 # methodRefSuffix
    | BANGBANG                                              # notNullSuffix
    ;

arguments
    : expression (COMMA expression)*
    ;

primary
    : {genericConstructor()}? identifier typeArgs LPAREN arguments? RPAREN   # genericConstructor
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

whenArm
    : (whenCondition (COMMA whenCondition)* | ELSE) ARROW (block | expression) SEMI?
    ;

whenCondition
    : IS qualifiedName
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
    : qualifiedName (LT type (COMMA type)* GT)? QUESTION?
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
