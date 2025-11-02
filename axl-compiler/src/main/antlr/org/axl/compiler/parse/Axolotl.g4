grammar Axolotl;

@parser::members {
    // Newlines are hidden, so a call or a binary +/- must start on the line of
    // the previous token; otherwise `f()` followed by `(x)` would become `f()(x)`.
    private boolean sameLine() {
        Token prev = _input.LT(-1);
        Token next = _input.LT(1);
        return prev == null || prev.getLine() == next.getLine();
    }

    private boolean upperCase() {
        String text = _input.LT(1).getText();
        return !text.isEmpty() && Character.isUpperCase(text.charAt(0));
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
    | SEMI
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
    : elvis ((LT | LE | GT | GE) elvis)*
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
    : {upperCase()}? identifier typeArgs LPAREN arguments? RPAREN   # genericConstructor
    | literal                                                       # literalPrimary
    | identifier                                                    # namePrimary
    | LPAREN expression RPAREN                                      # parenPrimary
    | lambda                                                        # lambdaPrimary
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
TRY     : 'try';
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
