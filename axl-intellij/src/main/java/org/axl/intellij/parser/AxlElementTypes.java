package org.axl.intellij.parser;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import org.axl.intellij.AxlLanguage;

/** Composite nodes of the Axolotl PSI tree. */
public final class AxlElementTypes {

    public static final IFileElementType FILE = new IFileElementType(AxlLanguage.INSTANCE);

    public static final IElementType PACKAGE = type("PACKAGE");
    public static final IElementType IMPORT = type("IMPORT");
    public static final IElementType QUALIFIED_NAME = type("QUALIFIED_NAME");
    public static final IElementType FUNCTION = type("FUNCTION");
    public static final IElementType CONSTRUCTOR = type("CONSTRUCTOR");
    /** `private constructor` or `@Deprecated constructor` between a class's name and its parameters. */
    public static final IElementType PRIMARY_CONSTRUCTOR = type("PRIMARY_CONSTRUCTOR");
    public static final IElementType PARAMETER_LIST = type("PARAMETER_LIST");
    public static final IElementType PARAMETER = type("PARAMETER");
    public static final IElementType TYPE_PARAMETER = type("TYPE_PARAMETER");
    public static final IElementType TYPE = type("TYPE");
    public static final IElementType VARIABLE = type("VARIABLE");
    public static final IElementType BLOCK = type("BLOCK");
    public static final IElementType RETURN = type("RETURN");
    public static final IElementType IF = type("IF");
    public static final IElementType WHILE = type("WHILE");
    public static final IElementType USE = type("USE");
    public static final IElementType JUMP = type("JUMP");
    public static final IElementType ASSIGNMENT = type("ASSIGNMENT");
    public static final IElementType EXPRESSION_STATEMENT = type("EXPRESSION_STATEMENT");
    public static final IElementType LAMBDA = type("LAMBDA");
    public static final IElementType LAMBDA_PARAMETER = type("LAMBDA_PARAMETER");
    public static final IElementType ARGUMENTS = type("ARGUMENTS");
    public static final IElementType TYPE_ARGUMENTS = type("TYPE_ARGUMENTS");
    public static final IElementType REFERENCE = type("REFERENCE");
    public static final IElementType CALL = type("CALL");
    public static final IElementType GENERIC_NEW = type("GENERIC_NEW");
    public static final IElementType METHOD_REFERENCE = type("METHOD_REFERENCE");
    public static final IElementType NOT_NULL = type("NOT_NULL");
    public static final IElementType BINARY = type("BINARY");
    public static final IElementType ELVIS = type("ELVIS");
    public static final IElementType UNARY = type("UNARY");
    public static final IElementType PARENTHESIZED = type("PARENTHESIZED");
    public static final IElementType LITERAL = type("LITERAL");
    public static final IElementType TRY = type("TRY");
    public static final IElementType ATTEMPT = type("ATTEMPT");
    public static final IElementType PIPE = type("PIPE");
    public static final IElementType PIPE_STAGE = type("PIPE_STAGE");
    public static final IElementType FOR = type("FOR");
    public static final IElementType LOOP_VARIABLE = type("LOOP_VARIABLE");
    public static final IElementType DATA_CLASS = type("DATA_CLASS");
    public static final IElementType INTERFACE = type("INTERFACE");
    public static final IElementType CLASS = type("CLASS");
    public static final IElementType ENUM = type("ENUM");
    public static final IElementType ENUM_CONSTANT = type("ENUM_CONSTANT");
    public static final IElementType THROW = type("THROW");
    public static final IElementType CONDITIONAL = type("CONDITIONAL");
    public static final IElementType FIELD = type("FIELD");
    public static final IElementType IS_CHECK = type("IS_CHECK");
    public static final IElementType WHEN = type("WHEN");
    public static final IElementType WHEN_ARM = type("WHEN_ARM");
    public static final IElementType ANNOTATION = type("ANNOTATION");
    public static final IElementType ANNOTATION_ARRAY = type("ANNOTATION_ARRAY");
    public static final IElementType CLASS_LITERAL = type("CLASS_LITERAL");

    private AxlElementTypes() {}

    private static IElementType type(String name) {
        return new IElementType(name, AxlLanguage.INSTANCE);
    }
}
