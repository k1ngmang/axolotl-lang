package org.axl.intellij.actions;

import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.JavaRecursiveElementVisitor;
import java.util.List;
import java.util.Map;

/**
 * Converts the subset of Java that maps one to one: a class with only static methods and fields,
 * whose bodies use locals, `if`, `while`, `return` and expression statements.
 */
final class JavaToAxolotl {

    /** Thrown for code outside the subset. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message);
        }
    }

    private static final Map<String, String> PRIMITIVES = Map.of("int", "Int", "long", "Long", "boolean", "Boolean",
            "double", "Double", "void", "");
    private static final Map<String, String> BOXED = Map.of("java.lang.Integer", "Int?", "java.lang.Long", "Long?",
            "java.lang.Boolean", "Boolean?", "java.lang.Double", "Double?");

    private final StringBuilder out = new StringBuilder();

    static String convert(PsiJavaFile file) {
        PsiClass[] classes = file.getClasses();
        if (classes.length != 1) {
            throw new Unsupported("the file must contain exactly one class");
        }
        return new JavaToAxolotl().file(file, classes[0]);
    }

    static boolean isConvertible(PsiJavaFile file) {
        try {
            convert(file);
            return true;
        } catch (Unsupported e) {
            return false;
        }
    }

    private String file(PsiJavaFile file, PsiClass cls) {
        if (cls.isInterface() || cls.isEnum() || cls.isRecord() || cls.getInnerClasses().length > 0
                || cls.getExtendsListTypes().length > 0 || cls.getImplementsListTypes().length > 0) {
            throw new Unsupported("only plain classes with static members are converted");
        }
        out.append("package ").append(file.getPackageName()).append("\n");
        if (file.getImportList() != null) {
            for (PsiImportStatement imp : file.getImportList().getImportStatements()) {
                if (imp.isOnDemand()) {
                    throw new Unsupported("on-demand imports have no Axolotl form");
                }
                out.append("import ").append(imp.getQualifiedName()).append("\n");
            }
            for (PsiImportStaticStatement imp : file.getImportList().getImportStaticStatements()) {
                if (imp.isOnDemand() || imp.resolveTargetClass() == null) {
                    throw new Unsupported("on-demand static imports have no Axolotl form");
                }
                String keyword = imp.resolve() instanceof PsiMethod ? "fn" : "val";
                out.append("import ").append(keyword).append(" ").append(imp.resolveTargetClass().getQualifiedName())
                        .append(".").append(imp.getReferenceName()).append("\n");
            }
        }
        for (var field : cls.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC) || !field.hasModifierProperty(PsiModifier.FINAL)
                    || field.getInitializer() == null) {
                throw new Unsupported("only static final fields are converted");
            }
            out.append("\n").append(visibility(field.hasModifierProperty(PsiModifier.PRIVATE))).append("val ")
                    .append(field.getName()).append(": ").append(type(field.getType())).append(" = ")
                    .append(expression(field.getInitializer())).append("\n");
        }
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor() && method.getParameterList().isEmpty()) {
                continue;
            }
            if (!method.hasModifierProperty(PsiModifier.STATIC) || method.getBody() == null) {
                throw new Unsupported("only static methods are converted");
            }
            function(method);
        }
        return out.toString();
    }

    private void function(PsiMethod method) {
        boolean main = method.getName().equals("main") && method.getParameterList().getParametersCount() == 1
                && method.getParameterList().getParameters()[0].getType().getCanonicalText().equals("java.lang.String[]");
        out.append("\n").append(visibility(method.hasModifierProperty(PsiModifier.PRIVATE))).append("fn ")
                .append(method.getName()).append("(");
        if (!main) {
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                out.append(i == 0 ? "" : ", ").append(params[i].getName()).append(": ").append(type(params[i].getType()));
            }
        }
        out.append(")");
        String returns = type(method.getReturnType());
        if (!returns.isEmpty()) {
            out.append(" -> ").append(returns);
        }
        out.append(" ");
        block(method.getBody(), 0);
        out.append("\n");
    }

    private void block(PsiCodeBlock block, int depth) {
        out.append("{\n");
        for (PsiStatement statement : block.getStatements()) {
            statement(statement, depth + 1);
        }
        out.append("    ".repeat(depth)).append("}");
    }

    private void statement(PsiStatement statement, int depth) {
        String indent = "    ".repeat(depth);
        switch (statement) {
            case PsiDeclarationStatement declaration -> {
                for (PsiElement element : declaration.getDeclaredElements()) {
                    if (!(element instanceof PsiLocalVariable local) || local.getInitializer() == null) {
                        throw new Unsupported("locals need an initializer");
                    }
                    String keyword = local.hasModifierProperty(PsiModifier.FINAL) || !isReassigned(local) ? "val " : "var ";
                    out.append(indent).append(keyword).append(local.getName()).append(": ").append(type(local.getType()))
                            .append(" = ").append(expression(local.getInitializer())).append("\n");
                }
            }
            case PsiReturnStatement ret -> out.append(indent).append("return")
                    .append(ret.getReturnValue() == null ? "" : " " + expression(ret.getReturnValue())).append("\n");
            case PsiExpressionStatement expr when expr.getExpression() instanceof PsiAssignmentExpression
                    || expr.getExpression() instanceof PsiMethodCallExpression ->
                    out.append(indent).append(expression(expr.getExpression())).append("\n");
            case PsiIfStatement ifStatement -> {
                out.append(indent);
                ifChain(ifStatement, depth);
                out.append("\n");
            }
            case PsiWhileStatement loop -> {
                out.append(indent).append("while (").append(expression(loop.getCondition())).append(") ");
                block(body(loop.getBody()), depth);
                out.append("\n");
            }
            default -> throw new Unsupported("unsupported statement: " + statement.getText());
        }
    }

    private void ifChain(PsiIfStatement ifStatement, int depth) {
        out.append("if (").append(expression(ifStatement.getCondition())).append(") ");
        block(body(ifStatement.getThenBranch()), depth);
        PsiStatement elseBranch = ifStatement.getElseBranch();
        if (elseBranch instanceof PsiIfStatement elseIf) {
            out.append(" else ");
            ifChain(elseIf, depth);
        } else if (elseBranch != null) {
            out.append(" else ");
            block(body(elseBranch), depth);
        }
    }

    private static PsiCodeBlock body(PsiStatement statement) {
        if (statement instanceof PsiBlockStatement block) {
            return block.getCodeBlock();
        }
        throw new Unsupported("branches and loop bodies must be blocks");
    }

    private static boolean isReassigned(PsiLocalVariable local) {
        boolean[] assigned = {false};
        PsiElement scope = local.getParent().getParent();
        scope.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                if (expression.getLExpression() instanceof PsiReferenceExpression ref && ref.isReferenceTo(local)) {
                    assigned[0] = true;
                }
                super.visitAssignmentExpression(expression);
            }
        });
        return assigned[0];
    }

    /** Expressions that read the same in both languages, with `new` and `System.out.println` translated. */
    private static String expression(PsiExpression expression) {
        return switch (expression) {
            case null -> "";
            case PsiLiteralExpression literal -> literal.getText();
            case PsiParenthesizedExpression paren -> "(" + expression(paren.getExpression()) + ")";
            case PsiReferenceExpression ref -> ref.getQualifierExpression() == null ? ref.getReferenceName()
                    : expression(ref.getQualifierExpression()) + "." + ref.getReferenceName();
            case PsiMethodCallExpression call when call.getMethodExpression().getText().equals("System.out.println") ->
                    "println" + arguments(call.getArgumentList());
            case PsiMethodCallExpression call -> expression(call.getMethodExpression()) + arguments(call.getArgumentList());
            case PsiNewExpression created when created.getClassReference() != null && created.getArgumentList() != null
                    && created.getAnonymousClass() == null -> created.getClassReference().getText() + arguments(created.getArgumentList());
            case PsiPolyadicExpression poly -> {
                StringBuilder sb = new StringBuilder(expression(poly.getOperands()[0]));
                for (PsiExpression operand : java.util.Arrays.copyOfRange(poly.getOperands(), 1, poly.getOperands().length)) {
                    sb.append(" ").append(poly.getTokenBeforeOperand(operand).getText()).append(" ").append(expression(operand));
                }
                yield sb.toString();
            }
            case PsiPrefixExpression prefix when List.of("!", "-", "+").contains(prefix.getOperationSign().getText()) ->
                    prefix.getOperationSign().getText() + expression(prefix.getOperand());
            case PsiAssignmentExpression assignment -> expression(assignment.getLExpression()) + " "
                    + assignment.getOperationSign().getText() + " " + expression(assignment.getRExpression());
            default -> throw new Unsupported("unsupported expression: " + expression.getText());
        };
    }

    private static String arguments(PsiExpressionList list) {
        return java.util.Arrays.stream(list.getExpressions()).map(JavaToAxolotl::expression)
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    private static String type(PsiType type) {
        if (type == null) {
            return "";
        }
        if (type instanceof PsiPrimitiveType primitive) {
            String mapped = PRIMITIVES.get(primitive.getCanonicalText());
            if (mapped == null) {
                throw new Unsupported("unsupported primitive type: " + primitive.getCanonicalText());
            }
            return mapped;
        }
        String boxed = BOXED.get(type.getCanonicalText());
        if (boxed != null) {
            return boxed;
        }
        if (type instanceof com.intellij.psi.PsiArrayType) {
            throw new Unsupported("arrays have no Axolotl form");
        }
        return type.getPresentableText();
    }

    private static String visibility(boolean isPrivate) {
        return isPrivate ? "private " : "";
    }
}
